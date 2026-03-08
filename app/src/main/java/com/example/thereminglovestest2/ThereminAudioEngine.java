package com.example.thereminglovestest2;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import java.util.Arrays;

public final class ThereminAudioEngine {

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private static final int AUDIO_WRITE_SAMPLES = 2048;
    private static final int MIN_STREAM_BUFFER_BYTES = 16384;
    private static final int VISUALIZER_SAMPLE_COUNT = 180;

    private static final float TWO_PI = (float) (Math.PI * 2.0);
    private static final float OUTPUT_GAIN = 0.22f;
    private static final float FREQ_SMOOTHING = 0.0030f;
    private static final float ATTACK_SMOOTHING = 0.0046f;
    private static final float RELEASE_SMOOTHING = 0.0018f;
    private static final float VIBRATO_RATE_HZ = 5.2f;
    private static final float MIN_VIBRATO_DEPTH = 0.0020f;
    private static final float MAX_VIBRATO_DEPTH = 0.0065f;

    private final Object visualizerLock = new Object();
    private final float[] visualizerSamples = new float[VISUALIZER_SAMPLE_COUNT];

    private AudioTrack track;
    private Thread audioThread;
    private volatile boolean running;
    private volatile float targetFreqHz = 880f;
    private volatile float targetVolumeLinear;
    private volatile String toneType = AppSettings.TONE_SINE;

    private float phase;
    private float vibratoPhase;
    private float smoothFreqHz = 880f;
    private float smoothVolumeLinear;
    private float lastFreqHz = 880f;
    private float lastVolumeLinear;

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

    public void setTargets(float freqHz, float volumeLinear) {
        targetFreqHz = clamp(freqHz, 20f, 20000f);
        targetVolumeLinear = clamp(volumeLinear, 0f, 1f);
    }

    public void setToneType(String requestedToneType) {
        toneType = AppSettings.normalizeToneType(requestedToneType);
    }

    public VisualizerSnapshot getVisualizerSnapshot() {
        synchronized (visualizerLock) {
            return new VisualizerSnapshot(Arrays.copyOf(visualizerSamples, visualizerSamples.length), lastFreqHz, lastVolumeLinear);
        }
    }

    public void start() {
        if (running) return;
        int min = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
        track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE, CHANNEL_MASK, ENCODING,
                Math.max(min * 2, MIN_STREAM_BUFFER_BYTES), AudioTrack.MODE_STREAM);
        smoothFreqHz = clamp(targetFreqHz, 20f, 20000f);
        smoothVolumeLinear = lastVolumeLinear = 0f;
        lastFreqHz = smoothFreqHz;
        running = true;
        track.play();

        audioThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            short[] buffer = new short[AUDIO_WRITE_SAMPLES];
            while (running) {
                fillBuffer(buffer);
                updateVisualizer(buffer);
                try { track.write(buffer, 0, buffer.length); } catch (Exception ignored) {}
            }
        }, "ThereminAudioThread");
        audioThread.start();
    }

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

    private void fillBuffer(short[] buffer) {
        String tone = AppSettings.normalizeToneType(toneType);
        for (int i = 0; i < buffer.length; i++) {
            float freq = updateFrequency();
            float volume = updateVolume();
            float sample = clamp(sample(tone, phase, volume) * volume * OUTPUT_GAIN, -1f, 1f);
            buffer[i] = (short) (sample * Short.MAX_VALUE);
            advancePhase(freq);
        }
    }

    private float updateFrequency() {
        smoothFreqHz += (clamp(targetFreqHz, 20f, 20000f) - smoothFreqHz) * FREQ_SMOOTHING;
        float mix = clamp((smoothVolumeLinear - 0.03f) / 0.35f, 0f, 1f);
        float depth = MIN_VIBRATO_DEPTH + (MAX_VIBRATO_DEPTH - MIN_VIBRATO_DEPTH) * mix;
        vibratoPhase += (TWO_PI * VIBRATO_RATE_HZ) / SAMPLE_RATE;
        if (vibratoPhase >= TWO_PI) vibratoPhase -= TWO_PI;
        return smoothFreqHz * (1f + (float) Math.sin(vibratoPhase) * depth);
    }

    private float updateVolume() {
        float target = clamp(targetVolumeLinear, 0f, 1f);
        smoothVolumeLinear += (target - smoothVolumeLinear) * (target > smoothVolumeLinear ? ATTACK_SMOOTHING : RELEASE_SMOOTHING);
        return smoothVolumeLinear;
    }

    private void advancePhase(float freq) {
        phase += (TWO_PI * freq) / SAMPLE_RATE;
        if (phase >= TWO_PI) phase -= TWO_PI;
        else if (phase < 0f) phase += TWO_PI;
    }

    private float sample(String tone, float phase, float volume) {
        switch (tone) {
            case AppSettings.TONE_TRIANGLE:
                return saturate((float) (2.0 / Math.PI * Math.asin(Math.sin(phase))) + 0.08f * (float) Math.sin(phase * 2f), 0.95f + 0.10f * volume);
            case AppSettings.TONE_SAW:
                return saturate(0.80f * (((2f * phase) / TWO_PI) - 1f) + 0.18f * (float) Math.sin(phase), 0.92f + 0.12f * volume);
            case AppSettings.TONE_SQUARE:
                return saturate(0.55f * (Math.sin(phase) >= 0f ? 1f : -1f) + 0.24f * (float) Math.sin(phase), 0.90f + 0.08f * volume);
            default:
                float raw = (float) Math.sin(phase)
                        + 0.22f * (float) Math.sin(phase * 2f + 0.10f)
                        + 0.10f * (float) Math.sin(phase * 3f + 0.24f)
                        + 0.04f * (float) Math.sin(phase * 4f + 0.38f);
                float blend = 0.72f + 0.28f * clamp(volume, 0f, 1f);
                return (float) Math.tanh((float) Math.sin(phase) + blend * (raw - (float) Math.sin(phase)) * 1.12f);
        }
    }

    private float saturate(float value, float gain) {
        return (float) Math.tanh(value * gain);
    }

    private void updateVisualizer(short[] buffer) {
        synchronized (visualizerLock) {
            for (int i = 0; i < visualizerSamples.length; i++) {
                int source = Math.min(buffer.length - 1,
                        Math.round(i * (buffer.length - 1f) / (visualizerSamples.length - 1f)));
                visualizerSamples[i] = buffer[source] / (float) Short.MAX_VALUE;
            }
            lastFreqHz = smoothFreqHz;
            lastVolumeLinear = smoothVolumeLinear;
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
