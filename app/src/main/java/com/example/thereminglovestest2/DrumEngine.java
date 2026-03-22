package com.example.thereminglovestest2;

import android.content.Context;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sprint 3: PCM-based drum and bass backing engine.
 *
 * All sounds are synthesised at construction time as float[] PCM arrays at 48 kHz.
 * The scheduler thread triggers voices by writing into a lock-free voice pool using
 * AtomicIntegerArray for position tracking (volatile-store happens-before guarantee
 * from the JMM ensures the audio thread sees the correct sound index).
 *
 * The audio thread calls mixInto() which adds active voices sample-by-sample into
 * ThereminAudioEngine's short[] buffer. Because mixing happens inside fillBuffer(),
 * before the PCM tap fires, drum and bass audio is captured in recordings.
 *
 * No SoundPool, no Android Context dependency (Context parameter kept for backwards
 * compatibility but is unused).
 */
public class DrumEngine {

    private static final int SAMPLE_RATE = 48000;
    private static final int MAX_VOICES  = 16;

    // Sound indices
    private static final int SND_KICK    = 0;
    private static final int SND_SNARE   = 1;
    private static final int SND_HIHAT_C = 2;
    private static final int SND_HIHAT_O = 3;
    private static final int SND_CRASH   = 4;
    private static final int SND_CLAP    = 5;
    private static final int SND_BASS_E2 = 6;  // E2  ~82 Hz
    private static final int SND_BASS_A2 = 7;  // A2 ~110 Hz
    private static final int SND_BASS_D3 = 8;  // D3 ~147 Hz
    private static final int SND_BASS_G2 = 9;  // G2  ~98 Hz
    private static final int NUM_SOUNDS  = 10;

    private final float[][] sounds = new float[NUM_SOUNDS][];

    // Lock-free voice pool.
    // Scheduler writes: voiceSound.set(i, snd) then voicePos.set(i, 0).
    // Audio thread reads: if voicePos.get(i) >= 0, read voiceSound.get(i) to mix.
    // The volatile write to voicePos happens-after the write to voiceSound (program order),
    // and the audio-thread volatile read of voicePos happens-before reading voiceSound, so
    // the JMM happens-before chain guarantees the audio thread sees the correct sound index.
    private final AtomicIntegerArray voiceSound = new AtomicIntegerArray(MAX_VOICES);
    private final AtomicIntegerArray voicePos   = new AtomicIntegerArray(MAX_VOICES);

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?>       tickFuture;

    private volatile boolean enabled     = false;
    private volatile boolean bassEnabled = false;
    private volatile int     bpm         = 120;
    private volatile int     patternIdx  = 0;
    private int              step        = 0; // only touched on scheduler thread

    /** Timestamp (System.currentTimeMillis) of the most recent bass note trigger. Used by UI for pulse animation. */
    private final AtomicLong lastBassHitMs = new AtomicLong(0L);

    // ── Patterns ──────────────────────────────────────────────────────────────
    // DRUM_PATTERNS[patternIdx][soundRow][step]
    // Rows: 0=kick, 1=snare, 2=closed HH, 3=open HH, 4=crash, 5=clap
    private static final boolean[][][] DRUM_PATTERNS = {
        { // Pattern 0: Rock
            {true, false,false,false,false,false,false,false, true, false,false,false,false,false,false,false},
            {false,false,false,false,true, false,false,false, false,false,false,false,true, false,false,false},
            {true, false,true, false,true, false,true, false, true, false,true, false,true, false,true, false},
            {false,false,false,false,false,false,false,false, false,false,false,false,false,false,false,false},
            {false,false,false,false,false,false,false,false, false,false,false,false,false,false,false,false},
            {false,false,false,false,false,false,false,false, false,false,false,false,false,false,false,false},
        },
        { // Pattern 1: Funk
            {true, false,false,true, false,false,false,false, false,false,true, false,false,false,false,false},
            {false,false,false,false,true, false,false,false, false,false,false,false,true, false,false,false},
            {true, true, true, true, true, true, true, true,  true, true, true, true, true, true, true, true },
            {false,false,false,false,false,false,false,true,  false,false,false,false,false,false,false,false},
            {false,false,false,false,false,false,false,false, false,false,false,false,false,false,false,false},
            {false,false,false,false,false,false,false,false, false,false,false,false,false,false,false,false},
        },
        { // Pattern 2: Electronic (four-on-the-floor)
            {true, false,false,false,true, false,false,false, true, false,false,false,true, false,false,false},
            {false,false,false,false,true, false,false,false, false,false,false,false,true, false,false,false},
            {true, false,true, false,true, false,true, false, true, false,true, false,true, false,true, false},
            {false,false,false,false,false,false,false,false, false,false,false,false,false,false,false,false},
            {true, false,false,false,false,false,false,false, false,false,false,false,false,false,false,false},
            {false,false,false,false,false,false,false,false, false,false,false,false,false,false,false,true },
        },
    };

    // BASS_PATTERNS[patternIdx][step]: bass sound index offset from SND_BASS_E2, or -1 = silent
    private static final int[][] BASS_PATTERNS = {
        // Rock: E2 on beats 1 and 3
        {0,-1,-1,-1,-1,-1,-1,-1, 0,-1,-1,-1,-1,-1,-1,-1},
        // Funk: A2 on 1, 1-and-a, 3-and
        {1,-1,-1,1,-1,-1,-1,-1, -1,-1,1,-1,-1,-1,-1,-1},
        // Electronic: D3 on every beat
        {2,-1,-1,-1,2,-1,-1,-1, 2,-1,-1,-1,2,-1,-1,-1},
    };

    // ── Constructor ───────────────────────────────────────────────────────────

    /** Context is unused but kept for call-site compatibility. */
    public DrumEngine(Context context) { this(); }

    public DrumEngine() {
        for (int i = 0; i < MAX_VOICES; i++) voicePos.set(i, -1);
        synthesizeSounds();
    }

    // ── Sound synthesis ───────────────────────────────────────────────────────

    private void synthesizeSounds() {
        sounds[SND_KICK]    = synthesizeKick();
        sounds[SND_SNARE]   = synthesizeSnare();
        sounds[SND_HIHAT_C] = synthesizeHihatClosed();
        sounds[SND_HIHAT_O] = synthesizeHihatOpen();
        sounds[SND_CRASH]   = synthesizeCrash();
        sounds[SND_CLAP]    = synthesizeClap();
        sounds[SND_BASS_E2] = synthesizeBass(82.41f);
        sounds[SND_BASS_A2] = synthesizeBass(110.0f);
        sounds[SND_BASS_D3] = synthesizeBass(146.83f);
        sounds[SND_BASS_G2] = synthesizeBass(98.0f);
    }

    /** Kick: sine sweep 110 Hz → 45 Hz over 250 ms with exponential amplitude decay. */
    private float[] synthesizeKick() {
        int len = (int)(SAMPLE_RATE * 0.35f);
        float[] pcm = new float[len];
        float phase = 0f;
        for (int i = 0; i < len; i++) {
            float t    = (float) i / SAMPLE_RATE;
            float env  = (float) Math.exp(-t * 10.0);
            float freq = 45f + 65f * (float) Math.exp(-t * 22.0);
            pcm[i] = env * (float) Math.sin(phase) * 0.92f;
            phase += (float) (2.0 * Math.PI * freq / SAMPLE_RATE);
            if (phase >= (float)(2.0 * Math.PI)) phase -= (float)(2.0 * Math.PI);
        }
        return pcm;
    }

    /** Snare: pitched body (185 Hz) + noise, 180 ms decay. */
    private float[] synthesizeSnare() {
        int len = (int)(SAMPLE_RATE * 0.20f);
        float[] pcm = new float[len];
        Random rng = new Random(42L);
        float phase = 0f;
        for (int i = 0; i < len; i++) {
            float t       = (float) i / SAMPLE_RATE;
            float envBody  = (float) Math.exp(-t * 18.0);
            float envNoise = (float) Math.exp(-t * 22.0);
            float body     = envBody  * (float) Math.sin(phase) * 0.30f;
            float noise    = envNoise * (rng.nextFloat() * 2f - 1f) * 0.72f;
            pcm[i] = (body + noise) * 0.85f;
            phase += (float) (2.0 * Math.PI * 185.0 / SAMPLE_RATE);
            if (phase >= (float)(2.0 * Math.PI)) phase -= (float)(2.0 * Math.PI);
        }
        return pcm;
    }

    /** Closed hi-hat: high-pass filtered noise, 60 ms decay. */
    private float[] synthesizeHihatClosed() {
        int len = (int)(SAMPLE_RATE * 0.07f);
        float[] pcm = new float[len];
        Random rng = new Random(123L);
        float prev = 0f;
        for (int i = 0; i < len; i++) {
            float t   = (float) i / SAMPLE_RATE;
            float env = (float) Math.exp(-t * 65.0);
            float raw = rng.nextFloat() * 2f - 1f;
            float hp  = raw - prev * 0.86f; // first-order high-pass
            prev = raw;
            pcm[i] = env * hp * 0.48f;
        }
        return pcm;
    }

    /** Open hi-hat: high-pass filtered noise, 220 ms decay. */
    private float[] synthesizeHihatOpen() {
        int len = (int)(SAMPLE_RATE * 0.25f);
        float[] pcm = new float[len];
        Random rng = new Random(456L);
        float prev = 0f;
        for (int i = 0; i < len; i++) {
            float t   = (float) i / SAMPLE_RATE;
            float env = (float) Math.exp(-t * 9.0);
            float raw = rng.nextFloat() * 2f - 1f;
            float hp  = raw - prev * 0.86f;
            prev = raw;
            pcm[i] = env * hp * 0.50f;
        }
        return pcm;
    }

    /** Crash: long noise burst with low-frequency body, 600 ms decay. */
    private float[] synthesizeCrash() {
        int len = (int)(SAMPLE_RATE * 0.65f);
        float[] pcm = new float[len];
        Random rng = new Random(789L);
        float phase = 0f, prev = 0f;
        for (int i = 0; i < len; i++) {
            float t    = (float) i / SAMPLE_RATE;
            float env  = (float) Math.exp(-t * 4.5);
            float raw  = rng.nextFloat() * 2f - 1f;
            float hp   = raw - prev * 0.84f;
            prev = raw;
            float body = (float) Math.sin(phase) * 0.18f;
            pcm[i] = env * (hp * 0.60f + body) * 0.72f;
            phase += (float) (2.0 * Math.PI * 210.0 / SAMPLE_RATE);
            if (phase >= (float)(2.0 * Math.PI)) phase -= (float)(2.0 * Math.PI);
        }
        return pcm;
    }

    /** Clap: three staggered noise bursts (0 ms, 5 ms, 10 ms), 120 ms total. */
    private float[] synthesizeClap() {
        int len = (int)(SAMPLE_RATE * 0.14f);
        float[] pcm = new float[len];
        Random rng = new Random(321L);
        int[] offsets = {0, (int)(SAMPLE_RATE * 0.005), (int)(SAMPLE_RATE * 0.010)};
        for (int d : offsets) {
            for (int i = 0; i < len - d; i++) {
                float t   = (float) i / SAMPLE_RATE;
                float env = (float) Math.exp(-t * 38.0);
                pcm[i + d] += env * (rng.nextFloat() * 2f - 1f) * 0.55f;
            }
        }
        // Normalise so the loudest peak stays below ±1
        float peak = 0.001f;
        for (float v : pcm) peak = Math.max(peak, Math.abs(v));
        for (int i = 0; i < len; i++) pcm[i] = pcm[i] / peak * 0.88f;
        return pcm;
    }

    /**
     * Bass note: sine + 2nd/3rd harmonics, 500 ms decay.
     * Slight pitch droop at the start mimics a real bass string attack.
     */
    private float[] synthesizeBass(float freq) {
        int len = (int)(SAMPLE_RATE * 0.55f);
        float[] pcm = new float[len];
        float phase = 0f;
        for (int i = 0; i < len; i++) {
            float t   = (float) i / SAMPLE_RATE;
            float env = (float) Math.exp(-t * 5.5f);
            float f   = freq * (1f + 0.015f * (float) Math.exp(-t * 28.0));
            float s   = (float) Math.sin(phase) * 0.70f
                      + (float) Math.sin(phase * 2f) * 0.20f
                      + (float) Math.sin(phase * 3f) * 0.10f;
            pcm[i] = env * s * 0.82f;
            phase += (float)(2.0 * Math.PI * f / SAMPLE_RATE);
            if (phase >= (float)(2.0 * Math.PI)) phase -= (float)(2.0 * Math.PI);
        }
        return pcm;
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    /** Start the 16th-note scheduler. Safe to call multiple times — no-op if already running. */
    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DrumEngineScheduler");
            t.setDaemon(true);
            return t;
        });
        scheduleAtCurrentBpm();
    }

    private void scheduleAtCurrentBpm() {
        if (tickFuture != null) tickFuture.cancel(false);
        long intervalMs = (long)(60_000.0 / bpm / 4);
        tickFuture = scheduler.scheduleAtFixedRate(this::tick, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        int pat = Math.min(patternIdx, DRUM_PATTERNS.length - 1);
        boolean[][] drumPat = DRUM_PATTERNS[pat];
        int[]       bassPat = BASS_PATTERNS[pat];

        if (enabled) {
            if (drumPat[0][step]) triggerVoice(SND_KICK);
            if (drumPat[1][step]) triggerVoice(SND_SNARE);
            if (drumPat[2][step]) triggerVoice(SND_HIHAT_C);
            if (drumPat[3][step]) triggerVoice(SND_HIHAT_O);
            if (drumPat[4][step]) triggerVoice(SND_CRASH);
            if (drumPat[5][step]) triggerVoice(SND_CLAP);
        }
        if (bassEnabled && bassPat[step] >= 0) {
            triggerVoice(SND_BASS_E2 + bassPat[step]);
            lastBassHitMs.set(System.currentTimeMillis());
        }
        step = (step + 1) % 16;
    }

    /**
     * Claim a free voice slot for the given sound. If no slot is free the trigger is dropped
     * (the sound is too transient to matter). Runs on the scheduler thread only.
     * See class-level comment for the JMM happens-before argument.
     */
    private void triggerVoice(int soundIdx) {
        for (int i = 0; i < MAX_VOICES; i++) {
            if (voicePos.get(i) < 0) {
                voiceSound.set(i, soundIdx); // write sound first
                voicePos.set(i, 0);          // then publish position (volatile write)
                return;
            }
        }
        // No free slot — drop (should be rare with MAX_VOICES=16)
    }

    // ── Audio thread interface ─────────────────────────────────────────────────

    /**
     * Mix all active drum and bass voices into the theremin short[] buffer.
     * Called from ThereminAudioEngine on the audio thread — must be non-blocking
     * and allocation-free.
     */
    public void mixInto(short[] buffer, int count) {
        for (int v = 0; v < MAX_VOICES; v++) {
            int pos = voicePos.get(v); // volatile read
            if (pos < 0) continue;
            int snd = voiceSound.get(v);
            if (snd < 0 || snd >= NUM_SOUNDS) { voicePos.set(v, -1); continue; }
            float[] pcm = sounds[snd];
            if (pcm == null)               { voicePos.set(v, -1); continue; }

            for (int i = 0; i < count; i++) {
                if (pos >= pcm.length) { pos = -1; break; }
                int mixed = buffer[i] + (int)(pcm[pos] * Short.MAX_VALUE);
                buffer[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mixed));
                pos++;
            }
            voicePos.set(v, pos); // -1 when exhausted, or updated position
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Stop the scheduler and silence all active voices. */
    public void stop() {
        if (tickFuture != null) { tickFuture.cancel(false); tickFuture = null; }
        if (scheduler  != null) { scheduler.shutdown();     scheduler  = null; }
        step = 0;
        for (int i = 0; i < MAX_VOICES; i++) voicePos.set(i, -1);
    }

    /** Stop and release resources. Call from onDestroy. */
    public void release() { stop(); }

    // ── Getters / setters ──────────────────────────────────────────────────────

    public void setEnabled(boolean on)     { enabled = on; }
    public boolean isEnabled()             { return enabled; }

    public void setBassEnabled(boolean on) { bassEnabled = on; }
    public boolean isBassEnabled()         { return bassEnabled; }

    /**
     * Switch the active pattern (0 = Rock, 1 = Funk, 2 = Electronic).
     * The step counter is NOT reset so there is no timing glitch.
     */
    public void setPattern(int idx) {
        patternIdx = Math.max(0, Math.min(DRUM_PATTERNS.length - 1, idx));
    }
    public int getPattern()      { return patternIdx; }
    public int getPatternCount() { return DRUM_PATTERNS.length; }

    public void setBpm(int bpm) {
        this.bpm = Math.max(60, Math.min(200, bpm));
        if (scheduler != null && !scheduler.isShutdown()) scheduleAtCurrentBpm();
    }
    public int getBpm() { return bpm; }

    /** Returns the System.currentTimeMillis() of the last bass hit, or 0 if none yet. */
    public long getLastBassHitMs() { return lastBassHitMs.get(); }
}
