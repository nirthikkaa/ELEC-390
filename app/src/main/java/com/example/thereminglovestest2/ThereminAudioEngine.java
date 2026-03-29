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
 * Sprint 2: Added PcmListener interface so RecordingManager can tap the raw PCM stream.
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
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_WRITE_SAMPLES = 1024;
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
    private static final float VISUALIZER_SCALE = (AUDIO_WRITE_SAMPLES - 1f) / (VISUALIZER_SAMPLE_COUNT - 1f);

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

    // Sprint 2: PCM tap for recording. Listener receives each filled buffer from the audio thread.
    // Volatile so the recording start/stop from the UI thread is immediately visible to audio thread.
    private volatile PcmListener pcmListener;

    // Sprint 3: Effects pipeline fields.
    // All buffers are pre-allocated here — no allocation inside fillBuffer().
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

    // Sprint 3: Scale lock — snaps the smoothed frequency to the nearest note in the chosen scale.
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

    // Sprint 3: Reference to the drum engine — held here so effects and drums share the same owner.
    // volatile: written from background init thread, read from audio thread.
    private volatile DrumEngine drumEngine;

    private float phase;
    private float vibratoPhase;
    private float smoothFreqHz = 880f;
    private float smoothVolumeLinear;
    private float lastFreqHz = 880f;
    private float lastVolumeLinear;

    // --- Sprint 2: PCM tap interface ---
    // Implemented by RecordingManager. Called from the audio thread on every buffer fill (~2048
    // samples at 48kHz = ~43ms per call). Implementations must be fast and non-blocking.
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
        toneType = AppSettings.normalizeToneType(requestedToneType);
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

            short[] buffer = new short[AUDIO_WRITE_SAMPLES];
            while (running) {
                fillBuffer(buffer);
                // Sprint 3: Mix drum and bass PCM into buffer before the PCM tap so
                // recordings capture both the theremin and the drum engine output.
                DrumEngine drum = drumEngine;
                if (drum != null) drum.mixInto(buffer, buffer.length);
                updateVisualizer(buffer);
                // Sprint 2: PCM tap — capture local reference to avoid race on volatile field.
                // The listener (RecordingManager) must be non-blocking; this runs on the audio thread.
                PcmListener l = pcmListener;
                if (l != null) l.onPcmSamples(buffer, buffer.length);
                int written;
                try { written = track.write(buffer, 0, buffer.length); } catch (Exception ignored) { written = AudioTrack.ERROR; }
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
        // 500 ms timeout instead of 300 ms: the audio thread now creates the AudioTrack
        // internally, which can take up to ~200 ms. We wait long enough that the thread
        // finishes initialization and exits cleanly, so the track reference is valid below.
        if (t != null) try { t.join(500); } catch (InterruptedException ignored) {}
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
    }

    public void shutdown() { stop(); }

    private AudioTrack createAndStartTrack() {
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
        int bufferBytes = Math.max(min, Math.max(MIN_STREAM_BUFFER_BYTES, AUDIO_WRITE_SAMPLES * 2));
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
        short[] silence = new short[AUDIO_WRITE_SAMPLES];
        t.write(silence, 0, silence.length);
        t.write(silence, 0, silence.length);

        return t;
    }

    // Fill one PCM block. Each sample uses the latest smoothed pitch and volume, not the raw UI
    // target values, which avoids clicks and sudden jumps.
    // Sprint 3: effects (reverb, delay, distortion) and scale lock are applied per-sample here.
    private void fillBuffer(short[] buffer) {
        // toneType is always normalized by setToneType(); no need to normalize again here.
        String tone = toneType;

        // Capture all volatile fields into local finals before the loop.
        // Volatile reads carry a JVM memory barrier — reading them 2048 times per buffer prevents
        // the JIT from hoisting the checks out of the loop. Capturing once per buffer is safe:
        // effects and scale changes only need to take effect at the next buffer boundary (~43ms).
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
            // Sprint 3: snap smoothed frequency to the nearest scale note before synthesis.
            freq = snapToScale(freq, scale);
            float volume = updateVolume();
            float s = sample(tone, phase, volume) * volume * OUTPUT_GAIN * mg;
            // Sprint 3: run the effects chain with hoisted locals — no volatile reads in loop.
            if (doReverb)     s = applyReverb(s, rMix);
            if (doDelay)      s = applyDelay(s, dFb, dMix);
            if (doDistortion) s = applyDistortion(s, dGain);
            s = clamp(s, -1f, 1f);
            buffer[i] = (short) (s * Short.MAX_VALUE);
            advancePhase(freq);
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

    // Tone recipes. Each tone is designed to be perceptibly distinct from the others.
    // Math.sin() is cheap on modern JIT; the audio thread runs at THREAD_PRIORITY_AUDIO.
    private float sample(String tone, float phase, float volume) {
        switch (tone) {
            case AppSettings.TONE_TRIANGLE:
                // Triangle core plus boosted odd upper partials — reedier than pure triangle,
                // clearly brighter than sine but less harsh than square or saw.
                return ((float) (2.0 / Math.PI) * (float) Math.asin(Math.sin(phase))
                        + 0.25f * (float) Math.sin(phase * 3f)
                        - 0.10f * (float) Math.sin(phase * 5f)
                        + 0.04f * (float) Math.sin(phase * 7f)) * 0.72f;

            case AppSettings.TONE_SAW:
                // Direct analog sawtooth oscillator — linear ramp +1 → −1 each cycle.
                // Completely different waveform shape from all sine-based tones.
                // tanh drive (1.20) warms it and tames Nyquist aliasing at high pitches.
                return saturate(1f - phase / (float) Math.PI, 1.20f);

            case AppSettings.TONE_SQUARE:
                // Hard square wave — harsh and buzzy, most distinctive of all tones
                return saturate(
                    0.65f * (Math.sin(phase) >= 0f ? 1f : -1f)
                    + 0.22f * (float) Math.sin(phase),
                    1.10f);

            case AppSettings.TONE_PULSE:
                // Direct 25% duty-cycle pulse — high for first quarter, low for three quarters.
                // Zero-mean amplitudes (0.75/−0.25) match the correct Fourier DC balance.
                // Nasal, oboe-like quality from the hard asymmetry; sine blend softens clicks.
                return saturate((phase < (float) (Math.PI * 0.5) ? 0.75f : -0.25f)
                        + 0.15f * (float) Math.sin(phase), 1.0f);

            case AppSettings.TONE_ORGAN:
                // Full-wave rectified sine base — folds every cycle into a double-frequency ripple,
                // naturally emphasising even harmonics like a Hammond drawbar organ.
                // Hard-driven into tanh adds back odd harmonics as the characteristic "chiff".
                return saturate(
                    (Math.abs((float) Math.sin(phase)) * 2f - 1f)
                    + 0.45f * (float) Math.sin(phase),
                    1.35f);

            case AppSettings.TONE_STRING:
                // FM synthesis: carrier:modulator 1:1, index 2.5 — bowed string spectrum.
                // sin(x + 2.5·sin(x)) distributes energy into harmonics via Bessel coefficients;
                // sounds completely unlike additive sine — complex, reedy, bowed character.
                return (float) Math.sin(phase + 2.5f * (float) Math.sin(phase)) * 0.88f;

            case AppSettings.TONE_BELL:
                // Chowning FM bell: carrier:modulator 1:2.756, index 3.0.
                // Non-integer modulator ratio creates inharmonic sidebands — authentic bell ring.
                return (float) Math.sin(phase + 3.0f * (float) Math.sin(phase * 2.756f)) * 0.88f;

            case AppSettings.TONE_PAD:
                // Chorus pad: sawtooth main voice + detuned triangle voice.
                // Two fundamentally different base waveforms beating against each other
                // create a warmer, richer chorus texture than all-sine detuning.
                return saturate(
                    0.38f * (1f - phase / (float) Math.PI)
                    + 0.38f * ((float) (2.0 / Math.PI) * (float) Math.asin(Math.sin(phase * 1.007f)))
                    + 0.14f * (float) Math.sin(phase * 0.993f)
                    + 0.18f * (float) Math.sin(phase * 2.014f),
                    0.88f + 0.12f * volume);

            case AppSettings.TONE_VIOLIN:
                // Bowed string: Helmholtz motion spectrum — strong 2nd and odd harmonics,
                // driven through tanh to get the edgy "nail" quality of a bowed string.
                // Clearly brighter and more aggressive than Theremin; different ratio from String.
                return saturate(
                    (float) Math.sin(phase)
                    + 0.50f * (float) Math.sin(phase * 2f)
                    + 0.35f * (float) Math.sin(phase * 3f)
                    + 0.18f * (float) Math.sin(phase * 4f)
                    + 0.10f * (float) Math.sin(phase * 5f),
                    1.30f) * 0.52f;

            case AppSettings.TONE_GUITAR:
                // Acoustic guitar: 1:2 FM synthesis — modulator at double the carrier frequency.
                // sin(p + M·sin(2p)) gives sideband energy at 3rd, 5th, odd partials; bright
                // and plucky, completely different from String (1:1 FM at index 2.5).
                return (float) Math.sin(phase + 1.20f * (float) Math.sin(phase * 2f)) * 0.88f;

            case AppSettings.TONE_FLUTE:
                // Breathy flute: near-pure tone with very low-index PM at a near-2x ratio.
                // index 0.15 keeps it almost sinusoidal but adds a soft shimmer that reads as
                // "breath". 1.99 (not exactly 2) creates a slow micro-detuning for air texture.
                return (float) Math.sin(phase + 0.15f * (float) Math.sin(phase * 1.99f)) * 0.92f;

            case AppSettings.TONE_TRUMPET:
                // Bright brass: dense harmonic stack hard-driven into tanh saturation.
                // The resulting clipped-dense waveform captures the "brassy" buzz of brass instruments.
                // Much richer and harsher than Theremin or Organ; distinct from Square (no odd-only).
                return saturate(
                    (float) Math.sin(phase)
                    + 0.75f * (float) Math.sin(phase * 2f)
                    + 0.55f * (float) Math.sin(phase * 3f)
                    + 0.32f * (float) Math.sin(phase * 4f)
                    + 0.18f * (float) Math.sin(phase * 5f)
                    + 0.08f * (float) Math.sin(phase * 6f),
                    2.00f) * 0.40f;

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

    // Downsample the latest audio block so the UI can draw a light-weight waveform preview.
    private void updateVisualizer(short[] buffer) {
        synchronized (visualizerLock) {
            for (int i = 0; i < visualizerSamples.length; i++) {
                int source = Math.min(AUDIO_WRITE_SAMPLES - 1, Math.round(i * VISUALIZER_SCALE));
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
    // Sprint 3: Effects pipeline — all methods must be non-blocking, allocation-free.
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
     * Sprint 3: Scale lock. Snaps freqHz to the nearest in-scale frequency.
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
    // Sprint 3: Public setters for effects and scale lock (called from UI thread).
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
