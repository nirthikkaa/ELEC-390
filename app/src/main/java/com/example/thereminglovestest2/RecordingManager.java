package com.example.thereminglovestest2;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Sprint 2: Recording engine. Captures PCM samples from ThereminAudioEngine via the PcmListener
 * tap and writes them to a 16-bit mono 48 kHz WAV file in app-private storage.
 *
 * Owned by Nirthika (implementation). UI wiring (start/stop triggers, setAudioEngine calls) is
 * done by Ayan in MainActivity.
 *
 * Thread safety: startRecording/stopRecording are called on the main thread. onPcmSamples is
 * called on the audio thread. The `recording` flag is volatile so the audio thread sees state
 * changes immediately without needing synchronization for the common path.
 */
public class RecordingManager implements ThereminAudioEngine.PcmListener {

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final long MIN_FREE_BYTES = 10 * 1024 * 1024; // 10 MB guard

    public interface RecordingListener {
        void onRecordingStarted();
        void onRecordingStopped(String filePath, long durationMs);
        void onRecordingError(String error);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile RecordingListener listener;
    private volatile boolean recording;
    private volatile FileOutputStream outputStream;
    private volatile long startTimeMs;
    private volatile long totalSamplesWritten;
    private volatile File currentFile;

    public RecordingManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Hook this manager into the active audio engine's PCM tap.
     * Ayan calls this from MainActivity whenever the active engine changes
     * (foreground engine on resume, background service engine on pause).
     * Pass null to disconnect from any engine.
     */
    public void setAudioEngine(ThereminAudioEngine engine) {
        if (engine != null) engine.setPcmListener(this);
    }

    public void setListener(RecordingListener listener) {
        this.listener = listener;
    }

    public boolean isRecording() { return recording; }

    /** Call from the main thread to begin capture. */
    public void startRecording() {
        if (recording) return;

        File dir = getRecordingsDir();
        StatFs stat = new StatFs(dir.getPath());
        if (stat.getAvailableBytes() < MIN_FREE_BYTES) {
            notifyError("Not enough storage space to start recording");
            return;
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        currentFile = new File(dir, "recording_" + timestamp + ".wav");

        try {
            outputStream = new FileOutputStream(currentFile);
            // Reserve 44 bytes for the WAV header; we write the real values after stopRecording().
            outputStream.write(new byte[44]);
        } catch (IOException e) {
            notifyError("Could not create recording file: " + e.getMessage());
            currentFile = null;
            return;
        }

        totalSamplesWritten = 0;
        startTimeMs = System.currentTimeMillis();
        recording = true;
        mainHandler.post(() -> { if (listener != null) listener.onRecordingStarted(); });
    }

    /** Call from the main thread to end capture and finalize the WAV file. */
    public void stopRecording() {
        if (!recording) return;
        recording = false;

        // Capture locals before the audio thread can see recording==false and stop writing.
        FileOutputStream stream = outputStream;
        outputStream = null;
        File file = currentFile;
        long samples = totalSamplesWritten;
        long durationMs = System.currentTimeMillis() - startTimeMs;

        if (stream == null || file == null) {
            notifyError("Recording state error — no output stream");
            return;
        }

        try {
            stream.flush();
            stream.close();
            writeWavHeader(file, samples);
        } catch (IOException e) {
            notifyError("Failed to finalize recording: " + e.getMessage());
            return;
        }

        if (!file.exists() || file.length() <= 44) {
            notifyError("Recording failed — output file is empty");
            return;
        }

        final String path = file.getAbsolutePath();
        final long dur = durationMs;
        mainHandler.post(() -> { if (listener != null) listener.onRecordingStopped(path, dur); });
    }

    /**
     * Called on the audio thread for every filled PCM buffer (~43 ms at 48 kHz).
     * Must be non-blocking. Drops the block silently on write failure rather than crashing.
     */
    @Override
    public void onPcmSamples(short[] samples, int count) {
        if (!recording) return;
        FileOutputStream stream = outputStream;
        if (stream == null) return;
        byte[] bytes = shortsToBytes(samples, count);
        try {
            stream.write(bytes);
            totalSamplesWritten += count;
        } catch (IOException ignored) {
            // Drop the block; WAV will be truncated but the app won't crash.
        }
    }

    /** Call in Activity.onDestroy() to ensure any in-progress recording is cleanly finalized. */
    public void release() {
        if (recording) stopRecording();
    }

    // --- WAV header helpers ---

    private void writeWavHeader(File file, long totalSamples) throws IOException {
        long dataBytes = totalSamples * CHANNELS * (BITS_PER_SAMPLE / 8);
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            raf.write(buildWavHeader(dataBytes));
        }
    }

    private byte[] buildWavHeader(long dataBytes) {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
        ByteBuffer buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        // RIFF chunk descriptor
        buf.put(new byte[]{'R', 'I', 'F', 'F'});
        buf.putInt((int) (36 + dataBytes));
        buf.put(new byte[]{'W', 'A', 'V', 'E'});
        // fmt sub-chunk
        buf.put(new byte[]{'f', 'm', 't', ' '});
        buf.putInt(16);
        buf.putShort((short) 1);            // PCM
        buf.putShort((short) CHANNELS);
        buf.putInt(SAMPLE_RATE);
        buf.putInt(byteRate);
        buf.putShort((short) blockAlign);
        buf.putShort((short) BITS_PER_SAMPLE);
        // data sub-chunk
        buf.put(new byte[]{'d', 'a', 't', 'a'});
        buf.putInt((int) dataBytes);
        return buf.array();
    }

    private byte[] shortsToBytes(short[] shorts, int count) {
        byte[] bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            bytes[i * 2]     = (byte) (shorts[i] & 0xFF);
            bytes[i * 2 + 1] = (byte) ((shorts[i] >> 8) & 0xFF);
        }
        return bytes;
    }

    private File getRecordingsDir() {
        File dir = new File(context.getFilesDir(), "recordings");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void notifyError(String msg) {
        mainHandler.post(() -> { if (listener != null) listener.onRecordingError(msg); });
    }
}
