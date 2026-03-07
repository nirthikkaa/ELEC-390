package com.example.thereminglovestest2;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Arrays;

/**
 * Small self-contained audio engine for the Play screen.
 *
 * Why this exists:
 * - MainActivity should focus on UI and BLE state, not low-level PCM generation.
 * - The visualizer needs access to the real waveform that was just played.
 * - We want one place to tweak the instrument sound without hunting through the activity.
 */
public final class ThereminAudioEngine {

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final float TWO_PI = (float) (Math.PI * 2.0);

    // Keep the output a little conservative so it stays smooth on phone speakers.
    private static final float OUTPUT_GAIN = 0.22f;
    private static final int VISUALIZER_SAMPLE_COUNT = 180;
    private static final int AUDIO_WRITE_SAMPLES = 2048;
    private static final int MIN_STREAM_BUFFER_BYTES = 16384;

    // Gentle smoothing makes the gloves feel more like an instrument and less like a slider.
    private static final float FREQ_SMOOTHING = 0.0030f;
    private static final float ATTACK_SMOOTHING = 0.0046f;
    private static final float RELEASE_SMOOTHING = 0.0018f;

    // A theremin usually has a light vocal wobble, so we fake a subtle one here.
    private static final float VIBRATO_RATE_HZ = 5.2f;
    private static final float MIN_VIBRATO_DEPTH = 0.0020f;
    private static final float MAX_VIBRATO_DEPTH = 0.0065f;

    private final Object visualizerLock = new Object();
    private final float[] visualizerSamples = new float[VISUALIZER_SAMPLE_COUNT];

    private AudioTrack track;
    private Thread audioThread;
    private volatile boolean running = false;

    private volatile float targetFreqHz = 880.0f;
    private volatile float targetVolumeLinear = 0.0f;
    private volatile String toneType = AppSettings.TONE_SINE;

    private float phase = 0f;
    private float vibratoPhase = 0f;
    private float smoothFreqHz = 880f;
    private float smoothVolumeLinear = 0f;
    private float lastFreqHz = 880f;
    private float lastVolumeLinear = 0f;

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

    public boolean isRunning() {
        return running;
    }

    public void setTargets(float freqHz, float volumeLinear) {
        targetFreqHz = clamp(freqHz, 20.0f, 20000.0f);
        targetVolumeLinear = clamp(volumeLinear, 0.0f, 1.0f);
    }

    public void setToneType(String requestedToneType) {
        toneType = AppSettings.normalizeToneType(requestedToneType);
    }

    public VisualizerSnapshot getVisualizerSnapshot() {
        synchronized (visualizerLock) {
            return new VisualizerSnapshot(
                    Arrays.copyOf(visualizerSamples, visualizerSamples.length),
                    lastFreqHz,
                    lastVolumeLinear
            );
        }
    }

    public void start() {
        if (running) return;

        int minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
        int bufferSize = Math.max(minBufferSize * 2, MIN_STREAM_BUFFER_BYTES);

        track = new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_MASK,
                ENCODING,
                bufferSize,
                AudioTrack.MODE_STREAM
        );

        smoothFreqHz = clamp(targetFreqHz, 20.0f, 20000.0f);
        smoothVolumeLinear = 0f;
        lastFreqHz = smoothFreqHz;
        lastVolumeLinear = 0f;
        running = true;
        track.play();

        audioThread = new Thread(() -> {
            // Give the synth thread a little more breathing room.
            // This helps a lot once the app is no longer in the foreground.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            short[] buffer = new short[AUDIO_WRITE_SAMPLES];

            while (running) {
                for (int i = 0; i < buffer.length; i++) {
                    float liveFreq = updateSmoothedFrequency();
                    float liveVolume = updateSmoothedVolume();
                    float wave = buildToneSample(phase, liveVolume, toneType);
                    float sample = clamp(wave * liveVolume * OUTPUT_GAIN, -1.0f, 1.0f);

                    buffer[i] = (short) (sample * Short.MAX_VALUE);
                    advancePhase(liveFreq);
                }

                updateVisualizerPreview(buffer, smoothFreqHz, smoothVolumeLinear);

                if (track != null) {
                    try {
                        track.write(buffer, 0, buffer.length);
                    } catch (Exception ignored) {
                    }
                }
            }
        }, "ThereminAudioThread");
        audioThread.start();
    }

    public void stop() {
        running = false;

        if (audioThread != null) {
            try {
                audioThread.join(300);
            } catch (InterruptedException ignored) {
            }
            audioThread = null;
        }

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

    public void shutdown() {
        stop();
    }

    private float updateSmoothedFrequency() {
        float targetFreq = clamp(targetFreqHz, 20.0f, 20000.0f);
        smoothFreqHz += (targetFreq - smoothFreqHz) * FREQ_SMOOTHING;

        float vibratoMix = clamp((smoothVolumeLinear - 0.03f) / 0.35f, 0f, 1f);
        float vibratoDepth = MIN_VIBRATO_DEPTH
                + (MAX_VIBRATO_DEPTH - MIN_VIBRATO_DEPTH) * vibratoMix;

        vibratoPhase += (TWO_PI * VIBRATO_RATE_HZ) / SAMPLE_RATE;
        if (vibratoPhase >= TWO_PI) vibratoPhase -= TWO_PI;

        float vibrato = (float) Math.sin(vibratoPhase) * vibratoDepth;
        return smoothFreqHz * (1.0f + vibrato);
    }

    private float updateSmoothedVolume() {
        float targetVol = clamp(targetVolumeLinear, 0.0f, 1.0f);
        float smoothing = targetVol > smoothVolumeLinear ? ATTACK_SMOOTHING : RELEASE_SMOOTHING;
        smoothVolumeLinear += (targetVol - smoothVolumeLinear) * smoothing;
        return smoothVolumeLinear;
    }

    private void advancePhase(float liveFreq) {
        phase += (TWO_PI * liveFreq) / SAMPLE_RATE;
        if (phase >= TWO_PI) phase -= TWO_PI;
        if (phase < 0f) phase += TWO_PI;
    }

    private float buildToneSample(float phase, float volumeLinear, String toneType) {
        String tone = AppSettings.normalizeToneType(toneType);

        if (AppSettings.TONE_TRIANGLE.equals(tone)) {
            return buildTriangleThereminSample(phase, volumeLinear);
        }
        if (AppSettings.TONE_SAW.equals(tone)) {
            return buildSawThereminSample(phase, volumeLinear);
        }
        if (AppSettings.TONE_SQUARE.equals(tone)) {
            return buildSquareThereminSample(phase, volumeLinear);
        }
        return buildSingingThereminSample(phase, volumeLinear);
    }

    private float buildSingingThereminSample(float phase, float volumeLinear) {
        float fundamental = (float) Math.sin(phase);
        float second = 0.22f * (float) Math.sin(phase * 2.0f + 0.10f);
        float third = 0.10f * (float) Math.sin(phase * 3.0f + 0.24f);
        float fourth = 0.04f * (float) Math.sin(phase * 4.0f + 0.38f);

        float harmonicBlend = 0.72f + 0.28f * clamp(volumeLinear, 0f, 1f);
        float raw = fundamental + harmonicBlend * (second + third + fourth);

        // Soft saturation rounds the tone a bit so it feels less like a test signal.
        return (float) Math.tanh(raw * 1.12f);
    }

    private float buildTriangleThereminSample(float phase, float volumeLinear) {
        float triangle = (float) (2.0 / Math.PI * Math.asin(Math.sin(phase)));
        float air = 0.08f * (float) Math.sin(phase * 2.0f);
        return (float) Math.tanh((triangle + air) * (0.95f + 0.10f * volumeLinear));
    }

    private float buildSawThereminSample(float phase, float volumeLinear) {
        float cycle = phase / TWO_PI;
        float saw = (2.0f * cycle) - 1.0f;
        float soften = 0.18f * (float) Math.sin(phase);
        return (float) Math.tanh((0.80f * saw + soften) * (0.92f + 0.12f * volumeLinear));
    }

    private float buildSquareThereminSample(float phase, float volumeLinear) {
        float square = Math.sin(phase) >= 0.0f ? 1.0f : -1.0f;
        float soften = 0.24f * (float) Math.sin(phase);
        return (float) Math.tanh((0.55f * square + soften) * (0.90f + 0.08f * volumeLinear));
    }

    private void updateVisualizerPreview(short[] buffer, float freqHz, float volumeLinear) {
        synchronized (visualizerLock) {
            for (int i = 0; i < visualizerSamples.length; i++) {
                int sourceIndex = Math.min(
                        buffer.length - 1,
                        Math.round(i * (buffer.length - 1f) / (visualizerSamples.length - 1f))
                );
                visualizerSamples[i] = buffer[sourceIndex] / (float) Short.MAX_VALUE;
            }
            lastFreqHz = freqHz;
            lastVolumeLinear = volumeLinear;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
