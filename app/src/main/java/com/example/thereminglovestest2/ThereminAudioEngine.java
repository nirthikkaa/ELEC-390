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

    private AudioTrack track;
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
    private final float[] combBuffer = new float[4800]; // ~100ms at 48kHz
    private int combIdx = 0;

    private volatile boolean delayEnabled = false;
    private volatile float delayMix = 0.4f;
    private volatile float delayFeedback = 0.35f;
    private final float[] delayBuffer = new float[24000]; // ~500ms at 48kHz
    private int delayIdx = 0;

    private volatile boolean distortionEnabled = false;
    private volatile float distortionGain = 2.5f;
    private volatile float mixGain = 0.85f;

    // Sprint 3: Scale lock — snaps the smoothed frequency to the nearest note in the chosen scale.
    private volatile String activeScale = "CHROMATIC";
    private static final int[] SCALE_MAJOR      = {0, 2, 4, 5, 7, 9, 11};
    private static final int[] SCALE_MINOR      = {0, 2, 3, 5, 7, 8, 10};
    private static final int[] SCALE_PENTATONIC = {0, 2, 4, 7, 9};

    // Sprint 3: Reference to the drum engine — held here so effects and drums share the same owner.
    private DrumEngine drumEngine;

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
        // Clear effect delay lines so stale large values don't cause instant clipping
        Arrays.fill(combBuffer, 0f);
        Arrays.fill(delayBuffer, 0f);
        combIdx  = 0;
        delayIdx = 0;
        track = createAndStartTrack();
        smoothFreqHz = clamp(targetFreqHz, 20f, 20000f);
        smoothVolumeLinear = lastVolumeLinear = 0f;
        lastFreqHz = smoothFreqHz;
        running = true;

        audioThread = new Thread(() -> {
            // Android treats audio threads specially, so we raise priority to reduce glitches.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
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
        if (t != null) try { t.join(300); } catch (InterruptedException ignored) {}
        if (track != null) {
            try { track.pause(); } catch (Exception ignored) {}
            try { track.flush(); } catch (Exception ignored) {}
            try { track.release(); } catch (Exception ignored) {}
            track = null;
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
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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
        return t;
    }

    // Fill one PCM block. Each sample uses the latest smoothed pitch and volume, not the raw UI
    // target values, which avoids clicks and sudden jumps.
    // Sprint 3: effects (reverb, delay, distortion) and scale lock are applied per-sample here.
    private void fillBuffer(short[] buffer) {
        // toneType is always normalized by setToneType(); no need to normalize again here.
        String tone = toneType;
        for (int i = 0; i < buffer.length; i++) {
            float freq = updateFrequency();
            // Sprint 3: snap smoothed frequency to the nearest scale note before synthesis.
            freq = snapToScale(freq);
            float volume = updateVolume();
            float s = sample(tone, phase, volume) * volume * OUTPUT_GAIN * mixGain;
            // Sprint 3: run the effects chain, then hard-clip to valid PCM range.
            s = applyEffects(s);
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

    /** Route the sample through whichever effects are enabled, in series. */
    private float applyEffects(float x) {
        if (reverbEnabled)     x = applyReverb(x);
        if (delayEnabled)      x = applyDelay(x);
        if (distortionEnabled) x = applyDistortion(x);
        return x;
    }

    /**
     * Schroeder comb-filter reverb (~100 ms).
     * Stores (input + damped feedback) but outputs the pure delay tap — this is the
     * correct implementation. The previous version output the summed value which caused
     * the buffer to grow unboundedly under sustained input and fill with clipping-level
     * values that never decayed. Stored values are clamped to prevent any residual blowup.
     */
    private float applyReverb(float x) {
        float delayed = combBuffer[combIdx];
        // Store accumulated signal (clamped so buffer can never overflow)
        combBuffer[combIdx] = clamp(x + delayed * 0.6f, -1f, 1f);
        combIdx = (combIdx + 1) % combBuffer.length;
        // Output the pure delay tap (from before this sample was added)
        return x * (1f - reverbMix) + delayed * reverbMix;
    }

    /**
     * Feedback delay line (~500 ms). Stored values and output are clamped so a loud
     * transient cannot fill the ring buffer and sustain clipping indefinitely.
     */
    private float applyDelay(float x) {
        float delayed = delayBuffer[delayIdx];
        delayBuffer[delayIdx] = clamp(x + delayed * delayFeedback, -1f, 1f);
        delayIdx = (delayIdx + 1) % delayBuffer.length;
        // Wet/dry blend — output stays at the same level as the input, not additive.
        return x * (1f - delayMix) + delayed * delayMix;
    }

    /**
     * Soft-clip distortion via tanh. At gain=1 the output is identical to the input.
     * Higher gain values drive the signal into saturation.
     */
    private float applyDistortion(float x) {
        if (distortionGain <= 1f) return x;
        return (float) (Math.tanh(x * distortionGain) / Math.tanh(distortionGain));
    }

    /**
     * Sprint 3: Scale lock. Snaps freqHz to the nearest MIDI note that belongs to
     * the active scale. Returns the input unchanged when scale is CHROMATIC.
     * Called from the audio thread — uses only stack variables, no allocation.
     */
    private float snapToScale(float freqHz) {
        if ("CHROMATIC".equals(activeScale)) return freqHz;
        if (freqHz <= 0f) return freqHz;
        // Convert Hz → fractional MIDI note number.
        double midi = 69.0 + 12.0 * Math.log(freqHz / 440.0) / Math.log(2.0);
        int rounded  = (int) Math.round(midi);
        int[] offsets = getScaleOffsets(activeScale);
        int octave    = Math.floorDiv(rounded, 12);
        int semitone  = rounded - octave * 12; // always 0–11
        int nearest   = semitone;
        int minDist   = Integer.MAX_VALUE;
        for (int offset : offsets) {
            int d = Math.abs(semitone - offset);
            if (d < minDist) { minDist = d; nearest = offset; }
            // Check wrap-around distance (e.g. semitone=0, offset=11 → d=1)
            int d2 = 12 - d;
            if (d2 < minDist) {
                minDist = d2;
                nearest = (semitone < offset) ? offset - 12 : offset + 12;
            }
        }
        int snappedMidi = octave * 12 + nearest;
        return (float) (440.0 * Math.pow(2.0, (snappedMidi - 69.0) / 12.0));
    }

    private int[] getScaleOffsets(String scale) {
        switch (scale) {
            case "MAJOR":      return SCALE_MAJOR;
            case "MINOR":      return SCALE_MINOR;
            case "PENTATONIC": return SCALE_PENTATONIC;
            default:           return new int[]{0,1,2,3,4,5,6,7,8,9,10,11};
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
