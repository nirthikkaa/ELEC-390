package com.example.thereminglovestest2;

/**
 * File guide:
 * Low-level audio engine.
 *
 * This class owns the AudioTrack, generates the theremin waveform in a worker thread, smooths
 * sudden jumps in pitch and volume, and exposes a downsampled copy of the waveform for the UI
 * visualizer. The rest of the app only tells it the latest target frequency, target volume, and
 * tone type.
 *
 * RecordingManager taps the mixed mono render buffer before it is duplicated into stereo for
 * playback.
 */

import android.media.AudioFormat;
import android.media.AudioAttributes;
import android.media.AudioTrack;
import android.os.Build;

import java.util.Arrays;

public final class ThereminAudioEngine {
    // --- Audio format and synth timing constants ---
    // These values shape the feel of the instrument more than the UI does. Small changes here can
    // make the theremin feel smoother, harsher, more responsive, or more stable.

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int OUTPUT_CHANNELS = 2;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_WRITE_FRAMES = 1024;
    private static final int MIN_STREAM_BUFFER_BYTES = 4096;
    private static final int VISUALIZER_SAMPLE_COUNT = 180;

    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float OUTPUT_GAIN = 0.14f;
    private static final float FREQ_SMOOTHING = 0.0030f;
    private static final float ATTACK_SMOOTHING = 0.0046f;
    private static final float RELEASE_SMOOTHING = 0.0018f;
    private static final float VIBRATO_RATE_HZ = 4.2f;
    private static final float MIN_VIBRATO_DEPTH = 0.0003f;
    private static final float MAX_VIBRATO_DEPTH = 0.0014f;
    private static final float VISUALIZER_SCALE = (AUDIO_WRITE_FRAMES - 1f) / (VISUALIZER_SAMPLE_COUNT - 1f);
    private static final float DRUM_HIT_RATE_RATIO = 0.0125f;
    private static final float DRUM_MIN_HIT_RATE_HZ = 0.75f;
    private static final float DRUM_MAX_HIT_RATE_HZ = 12.0f;
    private static final float DRUM_BODY_DECAY = 0.9991f;
    private static final float DRUM_NOISE_DECAY = 0.9935f;
    private static final float REAL_DRUM_BODY_DECAY = 0.9968f;
    private static final float REAL_DRUM_NOISE_DECAY = 0.9805f;

    // The visualizer reads a copy of the latest waveform while the audio thread keeps writing new
    // samples, so this lock protects that tiny shared buffer.
    private final Object visualizerLock = new Object();
    private final float[] visualizerSamples = new float[VISUALIZER_SAMPLE_COUNT];

    // volatile so stop() can safely read the value assigned by the audio thread after join().
    private volatile AudioTrack track;
    private Thread audioThread;
    private volatile boolean running;
    private volatile float targetFreqHz = 880f;
    private volatile float targetVolumeLinear;
    private volatile String toneType = AppSettings.TONE_SINE;

    // PCM tap for recording. The listener receives the mixed mono render buffer before it is
    // duplicated into stereo for playback. Volatile keeps UI-thread start/stop visible to audio.
    private volatile PcmListener pcmListener;

    // Effects pipeline state. All delay/reverb buffers are pre-allocated here so fillBuffer()
    // stays allocation-free on the audio thread.
    private volatile boolean reverbEnabled = false;
    private volatile float reverbMix = 0.3f;
    // Power-of-2 length so the wrap-around can use bitwise AND instead of integer division.
    // 8192 samples at 48 kHz = ~170 ms — slightly longer reverb tail than the previous 4800/100ms.
    private static final int COMB_MASK = 8191; // 8192 - 1
    private final float[] combBuffer = new float[8192];
    private int combIdx = 0;

    private volatile boolean delayEnabled = false;
    private volatile float delayMix = 0.4f;
    private volatile float delayFeedback = 0.35f;
    // 32768 samples at 48 kHz = ~682 ms delay line (was 24000/500ms).
    private static final int DELAY_MASK = 32767; // 32768 - 1
    private final float[] delayBuffer = new float[32768];
    private int delayIdx = 0;

    private volatile boolean distortionEnabled = false;
    private volatile float distortionGain = 2.5f;
    private volatile float mixGain = 0.85f;

    // Scale lock snaps the smoothed frequency to the nearest note in the chosen scale.
    private volatile String activeScale = "CHROMATIC";
    private static final int[] SCALE_MAJOR      = {0, 2, 4, 5, 7, 9, 11};
    private static final int[] SCALE_MINOR      = {0, 2, 3, 5, 7, 8, 10};
    private static final int[] SCALE_PENTATONIC = {0, 2, 4, 7, 9};
    // Pre-allocated chromatic offsets avoid a heap allocation in getScaleOffsets() default case.
    private static final int[] SCALE_CHROMATIC  = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};

    // Precomputed MIDI note frequencies indexed by MIDI number (0–127).
    // Built once at class load time so snapToScale() never calls Math.pow() in the audio thread.
    private static final float[] MIDI_FREQ_HZ = new float[128];
    static {
        for (int m = 0; m < 128; m++) {
            MIDI_FREQ_HZ[m] = (float) (440.0 * Math.pow(2.0, (m - 69.0) / 12.0));
        }
    }

    // Per-scale sorted frequency table rebuilt only when the active scale changes (not per-sample).
    private float[] scaleFreqTable = null;
    private String  scaleFreqTableBuiltFor = null;

    // Snap result cache: consecutive samples at the same note skip the binary search entirely.
    // With FREQ_SMOOTHING=0.003f the frequency shifts at most ~3 Hz between samples, so the
    // same note is output for hundreds of samples in a row — cache hit rate is typically >99%.
    private float snapCacheIn  = Float.NaN;
    private float snapCacheOut = Float.NaN;

    // Optional DrumEngine mixed into the theremin output so beats and theremin share one audio path.
    // Volatile because it can be attached from another thread after engine construction.
    private volatile DrumEngine drumEngine;

    private float phase;
    private float vibratoPhase;
    private float smoothFreqHz = 880f;
    private float smoothVolumeLinear;
    private float lastFreqHz = 880f;
    private float lastVolumeLinear;
    // Shared pulse-tone state for the Helicopter and Drum presets. Only one tone is active at once,
    // so both presets can safely reuse the same hit/envelope state without extra allocation.
    private float drumHitPhase;
    private float drumBodyPhase;
    private float drumBodyEnv;
    private float drumNoiseEnv;
    private int drumNoiseState = 0x2468ACE1;

    // --- PCM tap interface ---
    // Implemented by RecordingManager. Called from the audio thread on every buffer fill
    // (~1024 samples at 48kHz = ~21 ms per call). Implementations must be fast and non-blocking.
    public interface PcmListener {
        void onPcmSamples(short[] samples, int count);
    }

    public void setPcmListener(PcmListener listener) {
        pcmListener = listener;
    }

    // --- Existing API (unchanged) ---

    public static final class VisualizerSnapshot {
        public final float[] samples;
        public final float freqHz;
        public final float volumeLinear;

        VisualizerSnapshot(float[] samples, float freqHz, float volumeLinear) {
            this.samples = samples;
            this.freqHz = freqHz;
            this.volumeLinear = volumeLinear;
        }
    }

    public boolean isRunning() { return running; }

    // Screens and the background service keep updating targets; the engine glides toward them.

    public void setTargets(float freqHz, float volumeLinear) {
        targetFreqHz = clamp(freqHz, 20f, 20000f);
        targetVolumeLinear = clamp(volumeLinear, 0f, 1f);
    }

    public void setToneType(String requestedToneType) {
        String normalized = AppSettings.normalizeToneType(requestedToneType);
        if (!normalized.equals(toneType)) resetPulseToneState();
        toneType = normalized;
    }

    // The visualizer never reads the live audio buffer directly. Instead it gets a safe copy.
    public VisualizerSnapshot getVisualizerSnapshot() {
        synchronized (visualizerLock) {
            return new VisualizerSnapshot(Arrays.copyOf(visualizerSamples, visualizerSamples.length), lastFreqHz, lastVolumeLinear);
        }
    }

    // Start the streaming synth thread and prime the smoothing state from the latest targets.
    public void start() {
        if (running) return;
        // Clear effect delay lines so stale large values don't cause instant clipping.
        Arrays.fill(combBuffer, 0f);
        Arrays.fill(delayBuffer, 0f);
        combIdx  = 0;
        delayIdx = 0;
        resetPulseToneState();
        // Prime smoothing state before the thread starts so the first buffer is correct.
        smoothFreqHz = clamp(targetFreqHz, 20f, 20000f);
        smoothVolumeLinear = lastVolumeLinear = 0f;
        lastFreqHz = smoothFreqHz;
        // Mark running before starting the thread so isRunning() returns true immediately.
        // Callers (MainActivity, background service) check isRunning() right after start().
        running = true;

        audioThread = new Thread(() -> {
            // Raise thread priority first, then create the AudioTrack.
            // With PERFORMANCE_MODE_LOW_LATENCY + USAGE_GAME the hardware fast-path setup
            // can take 50–200 ms on some devices. Doing this here keeps the main thread
            // free so the Play screen appears instantly rather than freezing during the
            // button tap that calls start().
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            track = createAndStartTrack();

            // Keep synthesis/mixing mono so the recording tap, visualizer, and DrumEngine
            // integration can continue to operate on a single shared PCM buffer.
            short[] monoBuffer = new short[AUDIO_WRITE_FRAMES];
            // Interleave into stereo only at the AudioTrack boundary.
            short[] stereoBuffer = new short[AUDIO_WRITE_FRAMES * OUTPUT_CHANNELS];
            while (running) {
                fillBuffer(monoBuffer);
                // Mix drum and bass PCM into the mono buffer before the recording tap so
                // recordings capture both the theremin and the drum engine output.
                DrumEngine drum = drumEngine;
                if (drum != null) drum.mixInto(monoBuffer, monoBuffer.length);
                updateVisualizer(monoBuffer);
                // PCM tap — capture a local reference to avoid racing the volatile field mid-buffer.
                // The listener (RecordingManager) must be non-blocking; this runs on the audio thread.
                PcmListener l = pcmListener;
                if (l != null) l.onPcmSamples(monoBuffer, monoBuffer.length);
                copyMonoToStereo(monoBuffer, stereoBuffer);
                int written;
                try { written = track.write(stereoBuffer, 0, stereoBuffer.length); } catch (Exception ignored) { written = AudioTrack.ERROR; }
                if (written == AudioTrack.ERROR_DEAD_OBJECT) {
                    // AudioTrack was torn down (e.g. audio output device changed). Recreate it and
                    // resume rather than silently stopping.
                    try { track.release(); } catch (Exception ignored) {}
                    track = createAndStartTrack();
                }
            }
        }, "ThereminAudioThread");
        audioThread.start();
    }

    // Stop playback, release AudioTrack resources, and clear the visualizer back to silence.
    public void stop() {
        running = false;
        Thread t = audioThread;
        audioThread = null;
        AudioTrack trackToPause = track;
        if (trackToPause != null) {
            try { trackToPause.pause(); } catch (Exception ignored) {}
            try { trackToPause.flush(); } catch (Exception ignored) {}
        }
        // The AudioTrack is created on the worker thread and can take ~200 ms on some devices.
        // Pause/flush first so an in-flight write returns quickly, then wait for a clean exit.
        if (t != null) {
            t.interrupt();
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        // Read track into a local variable and null the field first to prevent double-release
        // if stop() is called again while cleanup is in progress.
        AudioTrack trackToRelease = track;
        track = null;
        if (trackToRelease != null) {
            try { trackToRelease.pause(); } catch (Exception ignored) {}
            try { trackToRelease.flush(); } catch (Exception ignored) {}
            try { trackToRelease.release(); } catch (Exception ignored) {}
        }
        synchronized (visualizerLock) {
            Arrays.fill(visualizerSamples, 0f);
            lastVolumeLinear = 0f;
        }
        resetPulseToneState();
    }

    public void shutdown() { stop(); }

    private AudioTrack createAndStartTrack() {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
        // AudioTrack buffers are sized in bytes, so multiply frames by channels and 16-bit depth.
        int bufferBytes = Math.max(min, Math.max(MIN_STREAM_BUFFER_BYTES, AUDIO_WRITE_FRAMES * OUTPUT_CHANNELS * 2));
        AudioTrack t;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            // USAGE_GAME + CONTENT_TYPE_SONIFICATION signal to the Android audio
                            // stack that this is a low-latency real-time use case. On most devices
                            // these attributes are required for PERFORMANCE_MODE_LOW_LATENCY to
                            // engage the hardware fast path (bypassing the software mixer).
                            // USAGE_MEDIA routes through the software mixer on some OEMs, which
                            // adds 10–30 ms of extra buffering.
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(ENCODING)
                            .setChannelMask(CHANNEL_MASK)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
            }
            t = builder.build();
        } else {
            t = new AudioTrack(android.media.AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNEL_MASK, ENCODING,
                    bufferBytes, AudioTrack.MODE_STREAM);
        }
        t.play();

        // Pre-warm the AudioTrack pipeline by writing two silent buffers immediately after play().
        // The driver's internal queue is empty at this point; the first real audio buffer would
        // otherwise stall waiting for the driver to prime itself, adding ~40–80 ms of perceived
        // latency on the very first note. Filling with silence lets the driver finish its startup
        // bookkeeping before any real audio arrives, so the first note plays without delay.
        short[] silence = new short[AUDIO_WRITE_FRAMES * OUTPUT_CHANNELS];
        t.write(silence, 0, silence.length);
        t.write(silence, 0, silence.length);

        return t;
    }

    // Fill one PCM block. Each sample uses the latest smoothed pitch and volume, not the raw UI
    // target values, which avoids clicks and sudden jumps. Scale lock and effects are applied
    // inside this loop.
    private void fillBuffer(short[] buffer) {
        // toneType is always normalized by setToneType(); no need to normalize again here.
        String tone = toneType;

        // Capture all volatile fields into local finals before the loop.
        // Volatile reads carry a JVM memory barrier — reading them 1024 times per buffer prevents
        // the JIT from hoisting the checks out of the loop. Capturing once per buffer is safe:
        // effects and scale changes only need to take effect at the next buffer boundary (~21 ms).
        final boolean doReverb     = reverbEnabled;
        final boolean doDelay      = delayEnabled;
        final boolean doDistortion = distortionEnabled;
        final float   rMix  = reverbMix;
        final float   dFb   = delayFeedback;
        final float   dMix  = delayMix;
        final float   dGain = distortionGain;
        final float   mg    = mixGain;
        final String  scale = activeScale;

        for (int i = 0; i < buffer.length; i++) {
            float freq = updateFrequency();
            // Snap the smoothed frequency to the active scale before synthesis.
            freq = snapToScale(freq, scale);
            float volume = updateVolume();
            float s = sample(tone, phase, freq, volume) * volume * OUTPUT_GAIN * mg;
            // Run the effects chain from hoisted locals so the hot loop does not perform volatile reads.
            if (doReverb)     s = applyReverb(s, rMix);
            if (doDelay)      s = applyDelay(s, dFb, dMix);
            if (doDistortion) s = applyDistortion(s, dGain);
            s = clamp(s, -1f, 1f);
            buffer[i] = (short) (s * Short.MAX_VALUE);
            advancePhase(freq);
        }
    }

    // Duplicate each mono frame into left/right so every instrument reaches the output bus
    // as stereo without changing the existing synth and recording code paths.
    private static void copyMonoToStereo(short[] monoBuffer, short[] stereoBuffer) {
        for (int i = 0, j = 0; i < monoBuffer.length; i++, j += OUTPUT_CHANNELS) {
            short sample = monoBuffer[i];
            stereoBuffer[j] = sample;
            stereoBuffer[j + 1] = sample;
        }
    }

    // Pitch smoothing plus a gentle vibrato that grows a bit with louder playing.
    private float updateFrequency() {
        smoothFreqHz += (targetFreqHz - smoothFreqHz) * FREQ_SMOOTHING; // targetFreqHz clamped in setTargets()
        float mix = clamp((smoothVolumeLinear - 0.03f) / 0.35f, 0f, 1f);
        float depth = MIN_VIBRATO_DEPTH + (MAX_VIBRATO_DEPTH - MIN_VIBRATO_DEPTH) * mix;
        vibratoPhase += (TWO_PI * VIBRATO_RATE_HZ) / SAMPLE_RATE;
        if (vibratoPhase >= TWO_PI) vibratoPhase -= TWO_PI;
        return smoothFreqHz * (1f + (float) Math.sin(vibratoPhase) * depth);
    }

    // Separate attack and release make the theremin fade in quickly but relax out a bit more
    // gently, which sounds more natural than one symmetric smoothing value.
    private float updateVolume() {
        float target = targetVolumeLinear; // clamped in setTargets()
        smoothVolumeLinear += (target - smoothVolumeLinear) * (target > smoothVolumeLinear ? ATTACK_SMOOTHING : RELEASE_SMOOTHING);
        return smoothVolumeLinear;
    }

    private void advancePhase(float freq) {
        phase += (TWO_PI * freq) / SAMPLE_RATE;
        if (phase >= TWO_PI) phase -= TWO_PI;
    }

    // Tone recipes. The non-default voices are biased toward harmonic spectra, controlled
    // brightness, and mild symmetric saturation so they stay musical across wide pitch glides.
    // Math.sin() is cheap on modern JIT; the audio thread runs at THREAD_PRIORITY_AUDIO.
    private float sample(String tone, float phase, float freqHz, float volume) {
        float motion = 0.5f + 0.5f * (float) Math.sin(vibratoPhase * 0.60f);
        switch (tone) {
            case AppSettings.TONE_AIR_PAD:
                // Soft detuned sine pad with a low spectral centroid. The slow beating keeps
                // sustained notes alive without pushing them into noise.
                return saturate(
                        0.44f * (float) Math.sin(phase)
                                + 0.28f * (float) Math.sin(phase * 1.002f + 0.14f * motion)
                                + 0.24f * (float) Math.sin(phase * 0.998f - 0.14f * motion)
                                + 0.14f * (float) Math.sin(phase * 2f)
                                + 0.05f * (float) Math.sin(phase * 3f),
                        0.72f + 0.08f * volume) * 0.78f;

            case AppSettings.TONE_CELLO:
                // Bowed low-string preset: emphasize the first two partials and add just enough
                // 1:1 FM motion to suggest bow pressure while keeping the note center stable.
                return saturate(
                        0.84f * (float) Math.sin(phase + (0.10f + 0.06f * motion) * (float) Math.sin(phase))
                                + 0.32f * (float) Math.sin(phase * 2f)
                                + 0.15f * (float) Math.sin(phase * 3f)
                                + 0.07f * (float) Math.sin(phase * 4f),
                        0.96f + 0.06f * volume) * 0.68f;

            case AppSettings.TONE_SWEET_LEAD:
                // Harmonic 2:1 FM lead with a strong fundamental. The FM index stays modest so
                // bends and portamento feel vocal rather than metallic.
                return saturate(
                        0.90f * (float) Math.sin(phase + (0.20f + 0.18f * volume) * (float) Math.sin(phase * 2f))
                                + 0.20f * (float) Math.sin(phase * 2f)
                                + 0.08f * (float) Math.sin(phase * 3f)
                                + 0.04f * (float) Math.sin(phase * 4f),
                        0.98f + 0.10f * volume) * 0.68f;

            case AppSettings.TONE_CHOIR:
                // Soft vocal pad: harmonic stack stays low-order, while shallow PM adds motion
                // without roughness.
                return saturate(
                        0.92f * (float) Math.sin(phase + (0.10f + 0.06f * motion) * (float) Math.sin(phase * 2f))
                                + 0.24f * (float) Math.sin(phase * 2f)
                                + 0.11f * (float) Math.sin(phase * 3f)
                                + 0.05f * (float) Math.sin(phase * 4f),
                        0.82f) * 0.72f;

            case AppSettings.TONE_VOWEL_O:
                // Rounded "ooh" vowel: strong fundamental/second harmonic anchor keeps pitch
                // clear while restrained PM adds expressiveness.
                return saturate(
                        0.96f * (float) Math.sin(phase + 0.12f * (float) Math.sin(phase * 2f))
                                + 0.20f * (float) Math.sin(phase * 2f)
                                + 0.07f * (float) Math.sin(phase * 3f),
                        0.80f) * 0.80f;

            case AppSettings.TONE_CLARINET:
                // Clarinet-inspired closed-pipe spectrum: odd harmonics dominate, which keeps
                // the tone warm and cheerful without the sandpaper edge of a raw square wave.
                return ((float) Math.sin(phase)
                        + 0.36f * (float) Math.sin(phase * 3f)
                        + 0.19f * (float) Math.sin(phase * 5f)
                        + 0.10f * (float) Math.sin(phase * 7f)
                        + 0.05f * (float) Math.sin(phase * 9f)) * 0.62f;

            case AppSettings.TONE_HELICOPTER:
                // Legacy "drum" tone renamed to Helicopter: pitch still controls rotor chop rate.
                return sampleHelicopterTone(freqHz, volume);

            case AppSettings.TONE_DRUM:
                // Hidden drum-kit tone: keep frequency-to-hit-rate mapping, but give each hit
                // a shorter envelope and a punchier transient than Helicopter.
                return sampleDrumTone(freqHz, volume);

            case AppSettings.TONE_OBOE:
                // Reed tone with fuller upper partials than clarinet, but keep the spectrum
                // compact so glides still sound lyrical.
                return saturate(
                        0.82f * (float) Math.sin(phase)
                                + 0.30f * (float) Math.sin(phase * 2f)
                                + 0.16f * (float) Math.sin(phase * 3f)
                                + 0.09f * (float) Math.sin(phase * 4f),
                        1.05f) * 0.60f;

            case AppSettings.TONE_LEAD:
                // Use harmonic 2:1 FM for brightness, then a small additive stack to keep the
                // pitch center obvious. This is brighter than pad/choir but less raspy.
                return saturate(
                        0.86f * (float) Math.sin(phase + (0.34f + 0.28f * volume) * (float) Math.sin(phase * 2f + 0.10f * motion))
                                + 0.22f * (float) Math.sin(phase * 2f)
                                + 0.10f * (float) Math.sin(phase * 3f),
                        1.05f + 0.10f * volume) * 0.62f;

            case AppSettings.TONE_TRIANGLE:
                // Triangle core plus boosted odd upper partials — reedier than pure triangle,
                // clearly brighter than sine but less harsh than square or saw.
                return ((float) (2.0 / Math.PI) * (float) Math.asin(Math.sin(phase))
                        + 0.25f * (float) Math.sin(phase * 3f)
                        - 0.10f * (float) Math.sin(phase * 5f)
                        + 0.04f * (float) Math.sin(phase * 7f)) * 0.72f;

            case AppSettings.TONE_SAW:
                // Additive saw-style spectrum with the first six harmonics only. That preserves
                // brightness but avoids the buzzy edge of the naive ramp waveform.
                return saturate(
                        0.72f * (float) Math.sin(phase)
                                + 0.36f * (float) Math.sin(phase * 2f)
                                + 0.23f * (float) Math.sin(phase * 3f)
                                + 0.16f * (float) Math.sin(phase * 4f)
                                + 0.10f * (float) Math.sin(phase * 5f)
                                + 0.06f * (float) Math.sin(phase * 6f),
                        0.92f + 0.08f * volume) * 0.46f;

            case AppSettings.TONE_SQUARE:
                // Odd-harmonic square approximation keeps the familiar hollow character, but
                // without the abrasive edge of a hard-clipped switching waveform.
                return saturate(
                        0.86f * (float) Math.sin(phase)
                                + 0.28f * (float) Math.sin(phase * 3f)
                                + 0.15f * (float) Math.sin(phase * 5f)
                                + 0.08f * (float) Math.sin(phase * 7f)
                                + 0.04f * (float) Math.sin(phase * 9f),
                        0.96f) * 0.66f;

            case AppSettings.TONE_PULSE:
                // Narrow-pulse flavor via even-harmonic emphasis, but keep it fully harmonic
                // and lightly saturated so bends stay singable.
                return saturate(
                        0.80f * (float) Math.sin(phase)
                                + 0.28f * (float) Math.sin(phase * 2f)
                                + 0.16f * (float) Math.sin(phase * 4f)
                                + 0.08f * (float) Math.sin(phase * 6f),
                        0.92f) * 0.62f;

            case AppSettings.TONE_ORGAN:
                // Drawbar-like harmonic recipe: strong 2nd and 3rd partials, very mild drive.
                return saturate(
                        0.72f * (float) Math.sin(phase)
                                + 0.46f * (float) Math.sin(phase * 2f)
                                + 0.22f * (float) Math.sin(phase * 3f)
                                + 0.14f * (float) Math.sin(phase * 4f)
                                + 0.07f * (float) Math.sin(phase * 5f),
                        1.00f) * 0.56f;

            case AppSettings.TONE_STRING:
                // Harmonic 1:1 FM plus low-order partials gives motion and sheen, but the
                // fundamental stays clear enough for slow theremin melodies.
                return saturate(
                        0.76f * (float) Math.sin(phase + (0.95f + 0.35f * motion) * (float) Math.sin(phase))
                                + 0.18f * (float) Math.sin(phase * 2f)
                                + 0.10f * (float) Math.sin(phase * 3f),
                        0.98f + 0.08f * volume) * 0.66f;

            case AppSettings.TONE_BELL:
                // Keep the bell's shimmer, but anchor it with a clear fundamental so the played
                // note still reads melodically.
                return saturate(
                        0.58f * (float) Math.sin(phase)
                                + 0.36f * (float) Math.sin(phase + 1.85f * (float) Math.sin(phase * 2.756f))
                                + 0.10f * (float) Math.sin(phase * 2f),
                        0.92f) * 0.76f;

            case AppSettings.TONE_PAD:
                // Slow-beating harmonic pad: detuned sine stack keeps it wide and warm without
                // the fizz of a raw saw.
                return saturate(
                        0.42f * (float) Math.sin(phase)
                                + 0.30f * (float) Math.sin(phase * 1.003f + 0.10f * motion)
                                + 0.24f * (float) Math.sin(phase * 0.997f - 0.10f * motion)
                                + 0.18f * (float) Math.sin(phase * 2f)
                                + 0.08f * (float) Math.sin(phase * 3f),
                        0.78f + 0.10f * volume) * 0.74f;

            case AppSettings.TONE_VIOLIN:
                // Violin stays bright, but move the color with shallow PM instead of heavy
                // clipping so the tone remains lyrical.
                return saturate(
                        0.78f * (float) Math.sin(phase + (0.16f + 0.08f * motion) * (float) Math.sin(phase * 3f))
                                + 0.28f * (float) Math.sin(phase * 2f)
                                + 0.16f * (float) Math.sin(phase * 3f)
                                + 0.08f * (float) Math.sin(phase * 4f),
                        1.04f + 0.08f * volume) * 0.62f;

            case AppSettings.TONE_GUITAR:
                // Harmonic 1:2 FM gives a plucked-string brightness, but keep the index modest so
                // sustained notes do not turn metallic.
                return saturate(
                        0.82f * (float) Math.sin(phase + 0.52f * (float) Math.sin(phase * 2f))
                                + 0.18f * (float) Math.sin(phase * 2f)
                                + 0.08f * (float) Math.sin(phase * 3f),
                        0.92f) * 0.74f;

            case AppSettings.TONE_FLUTE:
                // Near-pure flute with a tiny harmonic shimmer. Keep the spectral centroid low
                // so it reads as soft and melodic.
                return (0.92f * (float) Math.sin(phase + 0.10f * (float) Math.sin(phase * 2f + 0.08f * motion))
                        + 0.04f * (float) Math.sin(phase * 2f)) * 0.90f;

            case AppSettings.TONE_TRUMPET:
                // Brass wants brightness, but keep it on harmonic partials and use moderate
                // symmetric saturation so it stays singable instead of strident.
                return saturate(
                        0.80f * (float) Math.sin(phase)
                                + (0.40f + 0.08f * volume) * (float) Math.sin(phase * 2f)
                                + (0.22f + 0.06f * volume) * (float) Math.sin(phase * 3f)
                                + 0.10f * (float) Math.sin(phase * 4f),
                        1.22f) * 0.54f;

            case AppSettings.TONE_THEREMIN:
                // Authentic heterodyne theremin (RCA Theremin / Moog character):
                // Strong 2nd partial gives the characteristic warm-cello/vocal quality.
                // Partials: 1st (1.0), 2nd (0.35), 3rd (0.18), 4th (0.07), 5th (0.03).
                // Normalised by ~0.60 so combined peak is near unity before OUTPUT_GAIN.
                return ((float) Math.sin(phase)
                        + 0.35f * (float) Math.sin(phase * 2f)
                        + 0.18f * (float) Math.sin(phase * 3f)
                        + 0.07f * (float) Math.sin(phase * 4f)
                        + 0.03f * (float) Math.sin(phase * 5f)) * 0.60f;

            default:
                // Fallback for any unimplemented legacy tone strings — clean sine.
                return (float) Math.sin(phase) * 0.93f;
        }
    }

    private float saturate(float value, float gain) {
        return (float) Math.tanh(value * gain);
    }

    private float sampleHelicopterTone(float freqHz, float volume) {
        float hitRateHz = advancePulseTone(freqHz);

        // Tie the rotor body pitch loosely to the hit rate so faster notes feel tighter without
        // losing the low-end thump that made the old "drum" tone sound like helicopter blades.
        float bodyPitchHz = clamp(42f + hitRateHz * 6f, 42f, 110f);
        float sweptPitchHz = bodyPitchHz * (1f + 1.6f * drumBodyEnv);
        drumBodyPhase += (TWO_PI * sweptPitchHz) / SAMPLE_RATE;
        if (drumBodyPhase >= TWO_PI) drumBodyPhase -= TWO_PI;

        float body = ((float) Math.sin(drumBodyPhase)
                + 0.24f * (float) Math.sin(drumBodyPhase * 2f)
                + 0.10f * (float) Math.sin(drumBodyPhase * 3f)) * drumBodyEnv;
        float noise = nextDrumNoise() * drumNoiseEnv * (0.20f + 0.12f * volume);

        drumBodyEnv *= DRUM_BODY_DECAY;
        drumNoiseEnv *= DRUM_NOISE_DECAY;

        return saturate(body + noise, 1.35f) * 0.92f;
    }

    private float sampleDrumTone(float freqHz, float volume) {
        float hitRateHz = advancePulseTone(freqHz);

        // Keep the real drum body lower and shorter so each pulse reads as a discrete hit.
        float bodyPitchHz = clamp(54f + hitRateHz * 3.5f, 54f, 96f);
        float sweptPitchHz = bodyPitchHz * (0.88f + 2.4f * drumBodyEnv * drumBodyEnv);
        drumBodyPhase += (TWO_PI * sweptPitchHz) / SAMPLE_RATE;
        if (drumBodyPhase >= TWO_PI) drumBodyPhase -= TWO_PI;

        float punchEnv = drumBodyEnv * drumBodyEnv;
        float body = ((float) Math.sin(drumBodyPhase)
                + 0.18f * (float) Math.sin(drumBodyPhase * 2f + 0.25f)
                + 0.08f * (float) Math.sin(drumBodyPhase * 3f)) * punchEnv;
        float click = nextDrumNoise() * drumNoiseEnv * (0.32f + 0.06f * volume);
        float beater = (float) Math.sin(drumBodyPhase * 0.5f + 0.70f) * drumNoiseEnv * 0.08f;

        drumBodyEnv *= REAL_DRUM_BODY_DECAY;
        drumNoiseEnv *= REAL_DRUM_NOISE_DECAY;

        return saturate(body * 1.06f + click + beater, 1.55f) * 0.90f;
    }

    private float advancePulseTone(float freqHz) {
        float hitRateHz = clamp(freqHz * DRUM_HIT_RATE_RATIO, DRUM_MIN_HIT_RATE_HZ, DRUM_MAX_HIT_RATE_HZ);
        if (drumBodyEnv <= 0.0001f && drumNoiseEnv <= 0.0001f && drumHitPhase == 0f) {
            // Prime the first hit immediately when the tone starts instead of waiting a full cycle.
            drumBodyEnv = 1f;
            drumNoiseEnv = 1f;
        }
        drumHitPhase += hitRateHz / SAMPLE_RATE;
        if (drumHitPhase >= 1f) {
            drumHitPhase -= (float) Math.floor(drumHitPhase);
            drumBodyEnv = 1f;
            drumNoiseEnv = 1f;
        }
        return hitRateHz;
    }

    private void resetPulseToneState() {
        drumHitPhase = 0f;
        drumBodyPhase = 0f;
        drumBodyEnv = 0f;
        drumNoiseEnv = 0f;
    }

    private float nextDrumNoise() {
        // Small deterministic PRNG for the audio thread so the drum attack can include a clicky transient.
        drumNoiseState = drumNoiseState * 1664525 + 1013904223;
        return (((drumNoiseState >>> 8) & 0x00FFFFFF) / 8388607.5f) - 1f;
    }

    // Downsample the mono render buffer so the UI waveform matches the pre-stereo synth signal.
    private void updateVisualizer(short[] buffer) {
        synchronized (visualizerLock) {
            for (int i = 0; i < visualizerSamples.length; i++) {
                int source = Math.min(AUDIO_WRITE_FRAMES - 1, Math.round(i * VISUALIZER_SCALE));
                visualizerSamples[i] = buffer[source] / (float) Short.MAX_VALUE;
            }
            lastFreqHz = smoothFreqHz;
            lastVolumeLinear = smoothVolumeLinear;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // -------------------------------------------------------------------------
    // Effects pipeline helpers. These stay allocation-free because they run per sample.
    // -------------------------------------------------------------------------

    /**
     * Schroeder comb-filter reverb (~170 ms at 48kHz with 8192-sample buffer).
     * Stores (input + damped feedback) but outputs the pure delay tap — this is the
     * correct implementation. Stored values are clamped to prevent runaway buildup.
     * Takes mix as a parameter (hoisted from volatile field in fillBuffer) to avoid
     * a per-sample volatile read + memory barrier inside the inner loop.
     * Uses bitwise AND instead of modulo for the ring-buffer wrap (no integer division).
     */
    private float applyReverb(float x, float mix) {
        float delayed = combBuffer[combIdx];
        // Store accumulated signal (clamped so buffer can never overflow)
        combBuffer[combIdx] = clamp(x + delayed * 0.6f, -1f, 1f);
        combIdx = (combIdx + 1) & COMB_MASK; // bitwise AND: no division, same result
        // Output the pure delay tap (from before this sample was added)
        return x * (1f - mix) + delayed * mix;
    }

    /**
     * Feedback delay line (~682 ms at 48kHz with 32768-sample buffer).
     * Stored values and output are clamped so loud transients cannot fill the ring
     * buffer with clipping-level values that sustain indefinitely.
     * Takes feedback and mix as parameters (hoisted from volatile fields in fillBuffer).
     * Uses bitwise AND for ring-buffer wrap.
     */
    private float applyDelay(float x, float feedback, float mix) {
        float delayed = delayBuffer[delayIdx];
        delayBuffer[delayIdx] = clamp(x + delayed * feedback, -1f, 1f);
        delayIdx = (delayIdx + 1) & DELAY_MASK; // bitwise AND: no division, same result
        // Wet/dry blend — output stays at the same level as the input, not additive.
        return x * (1f - mix) + delayed * mix;
    }

    /**
     * Soft-clip distortion via tanh. At gain=1 the output is identical to the input.
     * Higher gain values drive the signal into saturation.
     * Takes gain as a parameter (hoisted from volatile field in fillBuffer).
     */
    private float applyDistortion(float x, float gain) {
        if (gain <= 1f) return x;
        return (float) (Math.tanh(x * gain) / Math.tanh(gain));
    }

    /**
     * Scale lock. Snaps freqHz to the nearest in-scale frequency.
     * Takes 'scale' as a parameter (hoisted from the volatile field in fillBuffer)
     * so there are no volatile reads inside the per-sample loop.
     *
     * Performance design:
     *  1. CHROMATIC fast-path: returns immediately, no work done.
     *  2. Cache: consecutive samples that map to the same note (very common with
     *     FREQ_SMOOTHING=0.003f) skip the binary search entirely.
     *  3. Precomputed table: when a search is needed, it runs over MIDI_FREQ_HZ[] —
     *     a sorted float[] built once at class load. No Math.log/Math.pow per sample.
     *  4. Binary search: O(log N) comparisons (≤6 for pentatonic, ≤7 for chromatic).
     *
     * Called from the audio thread — no allocation, no transcendental math in hot path.
     */
    private float snapToScale(float freqHz, String scale) {
        if ("CHROMATIC".equals(scale)) return freqHz;
        if (freqHz <= 0f) return freqHz;

        // Cache hit: if the frequency hasn't crossed a note boundary since last call,
        // return the cached snap result without doing any search.
        if (Math.abs(freqHz - snapCacheIn) < 0.5f) return snapCacheOut;

        // Rebuild the scale frequency table if the scale changed (at most once per scale switch).
        if (!scale.equals(scaleFreqTableBuiltFor)) {
            scaleFreqTable = buildScaleFreqTable(scale);
            scaleFreqTableBuiltFor = scale;
        }

        float result = binarySearchNearest(scaleFreqTable, freqHz);
        snapCacheIn  = freqHz;
        snapCacheOut = result;
        return result;
    }

    /**
     * Builds a sorted float[] of all in-scale note frequencies across 10 octaves (MIDI 0–127).
     * Called at most once per scale switch — allocation here is acceptable.
     */
    private float[] buildScaleFreqTable(String scale) {
        int[] offsets = getScaleOffsets(scale);
        float[] table = new float[offsets.length * 11]; // 11 octaves × offsets
        int idx = 0;
        for (int oct = 0; oct < 11; oct++) {
            for (int off : offsets) {
                int midi = oct * 12 + off;
                if (midi < 128) table[idx++] = MIDI_FREQ_HZ[midi];
            }
        }
        return java.util.Arrays.copyOf(table, idx); // sorted: ascending by octave then offset
    }

    /**
     * Binary search for the value in a sorted float[] that is closest to target.
     * O(log N) — no transcendental math, no allocation.
     */
    private static float binarySearchNearest(float[] table, float target) {
        int lo = 0, hi = table.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (table[mid] < target) lo = mid + 1;
            else hi = mid;
        }
        // lo is the first index where table[lo] >= target; check lo-1 for closer match
        if (lo > 0 && Math.abs(table[lo - 1] - target) < Math.abs(table[lo] - target)) {
            return table[lo - 1];
        }
        return table[lo];
    }

    /**
     * Returns the semitone offsets (0–11) that belong to the given scale.
     * All return values are pre-allocated static fields — no heap allocation.
     * Used only by buildScaleFreqTable(), not in the per-sample hot path.
     */
    private int[] getScaleOffsets(String scale) {
        switch (scale) {
            case "MAJOR":      return SCALE_MAJOR;
            case "MINOR":      return SCALE_MINOR;
            case "PENTATONIC": return SCALE_PENTATONIC;
            // Use static field instead of `new int[]{}` to avoid a heap allocation
            // if this method is ever accidentally called from the audio thread.
            default:           return SCALE_CHROMATIC;
        }
    }

    // -------------------------------------------------------------------------
    // Public setters for effects and scale lock. These are called from the UI and service threads.
    // -------------------------------------------------------------------------

    public void setReverbEnabled(boolean on)     { reverbEnabled = on; }
    public void setReverbMix(float mix)          { reverbMix = clamp(mix, 0f, 1f); }
    public void setDelayEnabled(boolean on)      { delayEnabled = on; }
    public void setDelayFeedback(float fb)       { delayFeedback = clamp(fb, 0f, 0.9f); }
    public void setDelayMix(float mix)           { delayMix = clamp(mix, 0f, 1f); }
    public void setDistortionEnabled(boolean on) { distortionEnabled = on; }
    public void setDistortionGain(float gain)    { distortionGain = clamp(gain, 1f, 10f); }
    public void setActiveScale(String scale)     { activeScale = (scale != null) ? scale : "CHROMATIC"; }
    public String getActiveScale()               { return activeScale; }
    public void setDrumEngine(DrumEngine drum)   { this.drumEngine = drum; }
    public DrumEngine getDrumEngine()            { return drumEngine; }
    public void setMixGain(float gain)           { mixGain = Math.max(0f, Math.min(1.1f, gain)); }
}
