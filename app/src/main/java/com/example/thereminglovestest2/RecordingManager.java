package com.example.thereminglovestest2;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Recording engine. Captures PCM samples from ThereminAudioEngine via the PcmListener tap.
 *
 *   LOSSLESS → raw PCM written as WAV (.wav) — no codec, always works, truly lossless
 *   HIGH     → AAC-LC 192 kbps, 24 kHz stereo (.m4a)
 *   MEDIUM   → AAC-LC 128 kbps, 16 kHz stereo (.m4a)
 *   LOW      → AAC-LC  64 kbps,  8 kHz stereo (.m4a)
 *
 * Thread safety: startRecording / stopRecording are called on the main thread.
 * onPcmSamples is called on the audio thread. A ReentrantLock protects the pipeline
 * so that finalization in stopRecording never races with a concurrent onPcmSamples call.
 */
public class RecordingManager implements ThereminAudioEngine.PcmListener {

    private static final long MIN_FREE_BYTES = 10 * 1024 * 1024; // 10 MB guard

    // ── Public interface ──────────────────────────────────────────────────────

    public interface RecordingListener {
        void onRecordingStarted();
        void onRecordingStopped(String filePath, long durationMs);
        void onRecordingError(String error);
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final Context       context;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());
    private final ReentrantLock codecLock   = new ReentrantLock();

    private volatile RecordingListener listener;
    private volatile boolean           recording;
    private volatile long              startTimeMs;

    // WAV pipeline (LOSSLESS path, guarded by codecLock)
    private boolean          useWav;
    private RandomAccessFile wavOutput;
    private long             wavDataBytes;

    // MediaCodec pipeline (AAC path, guarded by codecLock)
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private int        muxerTrackIndex  = -1;
    private boolean    muxerStarted     = false;
    private long       presentationTimeUs = 0;
    private String     activeFilePath;

    // Per-recording parameters (set in startRecording, read on audio thread)
    private int activeSampleRate;
    private int activeChannels;
    private int activeDownsample;

    // Pre-allocated scratch buffer — avoids heap allocation on the audio thread
    private final byte[] pcmScratch = new byte[65536];

    // ── Constructor ───────────────────────────────────────────────────────────

    public RecordingManager(Context context) {
        this.context = context.getApplicationContext();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setAudioEngine(ThereminAudioEngine engine) {
        if (engine != null) engine.setPcmListener(this);
    }

    public void setListener(RecordingListener l) { this.listener = l; }

    public boolean isRecording() { return recording; }

    /** Called on the main thread to begin capture. */
    public void startRecording() {
        if (recording) return;

        File dir = getRecordingsDir();
        try {
            if (new StatFs(dir.getPath()).getAvailableBytes() < MIN_FREE_BYTES) {
                notifyError("Not enough storage space to start recording");
                return;
            }
        } catch (Exception ignored) {
            // StatFs can throw on some devices; treat as non-fatal and proceed
        }

        String quality = SettingsStore.getAudioCompression(context);
        int bitrate = 0;
        switch (quality) {
            case AppSettings.COMPRESSION_LOSSLESS:
                activeSampleRate = 48000; activeChannels = 2; activeDownsample = 1;
                useWav = true;
                break;
            case AppSettings.COMPRESSION_MEDIUM:
                // 48 kHz, no downsampling — aliasing from naive sample-dropping was catastrophic.
                activeSampleRate = 48000; activeChannels = 2; activeDownsample = 1;
                useWav = false; bitrate = 192_000;
                break;
            case AppSettings.COMPRESSION_LOW:
                activeSampleRate = 48000; activeChannels = 2; activeDownsample = 1;
                useWav = false; bitrate = 128_000;
                break;
            default: // HIGH
                activeSampleRate = 48000; activeChannels = 2; activeDownsample = 1;
                useWav = false; bitrate = 320_000;
                break;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File file = new File(dir, "recording_" + timestamp + (useWav ? ".wav" : ".m4a"));
        activeFilePath = file.getAbsolutePath();

        if (useWav) {
            try {
                setupWav(file);
            } catch (Exception e) {
                notifyError("Could not start recording: " + e.getMessage());
                cleanupWav();
                return;
            }
        } else {
            try {
                setupCodec(file, bitrate);
            } catch (Exception e) {
                notifyError("Could not start encoder: " + e.getMessage());
                cleanupCodec();
                return;
            }
        }

        presentationTimeUs = 0;
        startTimeMs = System.currentTimeMillis();
        recording   = true;
        mainHandler.post(() -> { if (listener != null) listener.onRecordingStarted(); });
    }

    /** Called on the main thread to stop capture and finalize the file. */
    public void stopRecording() {
        if (!recording) return;
        recording = false;
        long durationMs = System.currentTimeMillis() - startTimeMs;

        codecLock.lock();
        try {
            if (useWav) finalizeWav();
            else        finalizeEncoder();
        } finally {
            codecLock.unlock();
        }

        File file = new File(activeFilePath);
        if (!file.exists() || file.length() == 0) {
            notifyError("Recording failed — output file is empty");
            return;
        }

        final String path = activeFilePath;
        mainHandler.post(() -> { if (listener != null) listener.onRecordingStopped(path, durationMs); });
    }

    /** Called in Activity.onDestroy() to ensure clean finalization. */
    public void release() {
        if (recording) stopRecording();
    }

    // ── PcmListener (audio thread) ────────────────────────────────────────────

    @Override
    public void onPcmSamples(short[] samples, int count) {
        if (!recording) return;
        if (!codecLock.tryLock()) return; // finalization in progress — drop this buffer
        try {
            if (!recording) return;
            if (useWav) {
                if (wavOutput != null) writePcmToWav(samples, count);
            } else {
                if (encoder == null) return;
                feedEncoder(samples, count);
                drainEncoder(false);
            }
        } catch (Exception e) {
            // Error on the audio thread — stop recording and clean up on the main thread
            recording = false;
            final String msg = e.getMessage();
            mainHandler.post(() -> {
                if (useWav) cleanupWav(); else cleanupCodec();
                if (listener != null) listener.onRecordingError("Recording error: " + msg);
            });
        } finally {
            codecLock.unlock();
        }
    }

    // ── WAV pipeline ──────────────────────────────────────────────────────────

    private void setupWav(File outputFile) throws IOException {
        wavOutput    = new RandomAccessFile(outputFile, "rw");
        wavDataBytes = 0;
        writeWavHeader(); // write placeholder header (sizes filled in on finalize)
    }

    /** Write (or overwrite) the 44-byte WAV header at position 0. */
    private void writeWavHeader() throws IOException {
        int byteRate   = activeSampleRate * activeChannels * 2;
        int blockAlign = activeChannels * 2;
        long riffSize  = 36 + wavDataBytes;
        wavOutput.seek(0);
        wavOutput.write(new byte[]{'R','I','F','F'});
        wavOutput.write(intLE((int) riffSize));
        wavOutput.write(new byte[]{'W','A','V','E'});
        wavOutput.write(new byte[]{'f','m','t',' '});
        wavOutput.write(intLE(16));               // PCM fmt chunk size
        wavOutput.write(shortLE(1));              // AudioFormat = PCM
        wavOutput.write(shortLE(activeChannels));
        wavOutput.write(intLE(activeSampleRate));
        wavOutput.write(intLE(byteRate));
        wavOutput.write(shortLE(blockAlign));
        wavOutput.write(shortLE(16));             // BitsPerSample
        wavOutput.write(new byte[]{'d','a','t','a'});
        wavOutput.write(intLE((int) wavDataBytes));
    }

    /** Called on the audio thread. Writes downsampled stereo PCM directly to the WAV file. */
    private void writePcmToWav(short[] samples, int count) throws IOException {
        int outCount  = count / activeDownsample;
        int byteCount = outCount * activeChannels * 2;
        int idx = 0;
        for (int i = 0; i < outCount; i++) {
            short s = samples[i * activeDownsample];
            pcmScratch[idx++] = (byte)(s & 0xFF);
            pcmScratch[idx++] = (byte)((s >> 8) & 0xFF);
            pcmScratch[idx++] = (byte)(s & 0xFF);   // R = L (stereo)
            pcmScratch[idx++] = (byte)((s >> 8) & 0xFF);
        }
        wavOutput.write(pcmScratch, 0, byteCount);
        wavDataBytes += byteCount;
    }

    /** Called on the main thread (inside codecLock) to finalize the WAV header and close. */
    private void finalizeWav() {
        if (wavOutput == null) return;
        try { writeWavHeader(); } catch (Exception ignored) {}
        try { wavOutput.close(); } catch (Exception ignored) {}
        wavOutput = null;
    }

    private void cleanupWav() {
        try { if (wavOutput != null) wavOutput.close(); } catch (Exception ignored) {}
        wavOutput    = null;
        wavDataBytes = 0;
    }

    // ── AAC / MediaCodec pipeline ─────────────────────────────────────────────

    private void setupCodec(File outputFile, int bitrate) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC, activeSampleRate, activeChannels);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536);

        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        muxer           = new MediaMuxer(outputFile.getAbsolutePath(),
                                         MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        muxerStarted    = false;
        muxerTrackIndex = -1;
    }

    private void feedEncoder(short[] mono, int count) {
        int outCount  = count / activeDownsample;
        int byteCount = outCount * activeChannels * 2;

        int idx = 0;
        for (int i = 0; i < outCount; i++) {
            short s  = mono[i * activeDownsample];
            byte  lo = (byte)(s & 0xFF);
            byte  hi = (byte)((s >> 8) & 0xFF);
            pcmScratch[idx++] = lo;
            pcmScratch[idx++] = hi;
            pcmScratch[idx++] = lo; // R = L
            pcmScratch[idx++] = hi;
        }

        int inputIndex = encoder.dequeueInputBuffer(0);
        if (inputIndex < 0) return;

        ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
        if (inputBuffer == null) return;

        inputBuffer.clear();
        int toCopy = Math.min(byteCount, inputBuffer.remaining());
        inputBuffer.put(pcmScratch, 0, toCopy);
        encoder.queueInputBuffer(inputIndex, 0, toCopy, presentationTimeUs, 0);
        presentationTimeUs += (long) outCount * 1_000_000L / activeSampleRate;
    }

    private void drainEncoder(boolean endOfStream) {
        MediaCodec.BufferInfo info     = new MediaCodec.BufferInfo();
        long                  timeout  = endOfStream ? 100_000L : 0L;
        while (true) {
            int outIdx = encoder.dequeueOutputBuffer(info, timeout);
            if      (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER)      break;
            else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) startMuxerIfReady();
            else if (outIdx >= 0) {
                writeEncoderOutput(outIdx, info);
                encoder.releaseOutputBuffer(outIdx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }

    private void finalizeEncoder() {
        if (encoder == null) return;
        try {
            int inputIdx = encoder.dequeueInputBuffer(100_000);
            if (inputIdx >= 0) {
                encoder.queueInputBuffer(inputIdx, 0, 0, presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
            MediaCodec.BufferInfo info   = new MediaCodec.BufferInfo();
            boolean               sawEos = false;
            while (!sawEos) {
                int outIdx = encoder.dequeueOutputBuffer(info, 100_000);
                if      (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER)      break;
                else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) startMuxerIfReady();
                else if (outIdx >= 0) {
                    writeEncoderOutput(outIdx, info);
                    encoder.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) sawEos = true;
                }
            }
        } catch (Exception ignored) {}
        cleanupCodec();
    }

    private void cleanupCodec() {
        try { if (muxer   != null) { if (muxerStarted) muxer.stop(); muxer.release(); }  } catch (Exception ignored) {}
        try { if (encoder != null) { encoder.stop(); encoder.release(); }                } catch (Exception ignored) {}
        encoder         = null;
        muxer           = null;
        muxerStarted    = false;
        muxerTrackIndex = -1;
    }

    private void startMuxerIfReady() {
        if (muxer != null && !muxerStarted) {
            muxerTrackIndex = muxer.addTrack(encoder.getOutputFormat());
            muxer.start();
            muxerStarted = true;
        }
    }

    private void writeEncoderOutput(int outputIndex, MediaCodec.BufferInfo info) {
        if (!muxerStarted || info.size <= 0) return;
        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) return;
        ByteBuffer buf = encoder.getOutputBuffer(outputIndex);
        if (buf == null) return;
        buf.position(info.offset);
        buf.limit(info.offset + info.size);
        muxer.writeSampleData(muxerTrackIndex, buf, info);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private File getRecordingsDir() {
        File dir = new File(context.getFilesDir(), "recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void notifyError(String msg) {
        mainHandler.post(() -> { if (listener != null) listener.onRecordingError(msg); });
    }

    private static byte[] intLE(int v) {
        return new byte[]{(byte) v, (byte)(v >> 8), (byte)(v >> 16), (byte)(v >> 24)};
    }

    private static byte[] shortLE(int v) {
        return new byte[]{(byte) v, (byte)(v >> 8)};
    }
}
