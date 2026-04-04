package com.example.thereminglovestest2;

import android.content.Context;
import android.content.res.Resources;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;

/**
 * PCM-based drum and bass backing engine.
 *
 * All sounds are synthesised at construction time as float[] PCM arrays at 48 kHz.
 * The scheduler thread triggers voices by writing into a lock-free voice pool.
 * The audio thread calls mixInto() which adds active voices sample-by-sample into
 * a caller-provided mono short[] buffer.
 *
 * BPM scheduling uses a self-rescheduling single-shot approach: each tick() schedules
 * the next tick based on System.currentTimeMillis(), so BPM changes take effect on the
 * next tick with no restart, no step-position reset, and no audible glitch.
 *
 * Each sound keeps its own mix weight in trackVolumes[], and the user-facing drumGain slider is
 * applied on top of that during mixing so the relative kick/snare/hat balance stays intact.
 */
public class DrumEngine {

    private static final int SAMPLE_RATE = 48000;
    private static final int MAX_VOICES  = 32;
    private static final float DRUM_MIX_HEADROOM = 0.26f;
    private static final float PIANO_MIX_HEADROOM = 0.22f;
    private static final float KICK_PITCH_RATIO = 1.20f;
    private static final float DEFAULT_KICK_VOL = 1.00f;
    private static final float DEFAULT_SNARE_VOL = 0.84f;
    private static final float DEFAULT_HIHAT_VOL = 0.44f;
    private static final float DEFAULT_CRASH_VOL = 0.48f;
    private static final float DEFAULT_CLAP_VOL = 0.58f;
    private static final float DEFAULT_BASS_VOL = 0.72f;
    private static final float DEFAULT_TOM_VOL = 0.54f;
    private static final float DEFAULT_RIM_VOL = 0.50f;
    private static final float DEFAULT_SHAKER_VOL = 0.42f;
    private static final int RENDER_WORKERS =
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
    private static final int WARMUP_RENDER_WORKERS = 2;

    // Sound indices
    static final int SND_KICK    = 0;
    static final int SND_SNARE   = 1;
    static final int SND_HIHAT_C = 2;
    static final int SND_HIHAT_O = 3;
    static final int SND_CRASH   = 4;
    static final int SND_CLAP    = 5;
    static final int SND_BASS_E2 = 6;  // E2  ~82 Hz
    static final int SND_BASS_A2 = 7;  // A2 ~110 Hz
    static final int SND_BASS_D3 = 8;  // D3 ~147 Hz
    static final int SND_BASS_G2 = 9;  // G2  ~98 Hz
    static final int SND_TOM_HI  = 10; // high tom ~300 Hz
    static final int SND_TOM_LOW = 11; // low  tom ~180 Hz
    static final int SND_RIM     = 12; // rimshot
    static final int SND_SHAKER  = 13; // shaker
    static final int NUM_SOUNDS  = 14;

    // Track volume group indices (used by setters)
    private static final int GRP_KICK  = SND_KICK;
    private static final int GRP_SNARE = SND_SNARE;
    // hihat: SND_HIHAT_C and SND_HIHAT_O share the same group
    // cymbal: SND_CRASH and SND_CLAP
    // bass: SND_BASS_E2..SND_BASS_G2

    private final float[][] sounds = new float[NUM_SOUNDS][];
    private final boolean warmupConstruction;

    // Per-sound mix profile [0=kick, 1=snare, 2=closed hat, 3=open hat, 4=crash, 5=clap,
    // 6-9=bass notes, 10=high tom, 11=low tom, 12=rim, 13=shaker]. The global drumGain slider is
    // applied later in mixInto() so these relative balances survive volume changes.
    private final float[] trackVolumes = new float[NUM_SOUNDS];

    // Lock-free voice pool — see class comment for JMM happens-before argument.
    private final AtomicIntegerArray voiceSound = new AtomicIntegerArray(MAX_VOICES);
    private final AtomicIntegerArray voicePos   = new AtomicIntegerArray(MAX_VOICES);

    // Piano key one-shot hits — C3 (MIDI 48) through C5 (MIDI 72), 25 chromatic notes
    private static final int PIANO_MIDI_BASE  = 48;
    private static final int NUM_PIANO_KEYS   = 25;
    private static final int MAX_PIANO_VOICES = 8;
    private static final int NUM_PIANO_SYNTH_MODES = 3;
    private final float[][][] pianoSounds = new float[NUM_PIANO_SYNTH_MODES][NUM_PIANO_KEYS][];
    private final AtomicIntegerArray pianoVoiceKey = new AtomicIntegerArray(MAX_PIANO_VOICES);
    private final AtomicIntegerArray pianoVoiceMode = new AtomicIntegerArray(MAX_PIANO_VOICES);
    private final AtomicIntegerArray pianoVoicePos = new AtomicIntegerArray(MAX_PIANO_VOICES);
    private volatile float pianoVolume = 1.0f;

    private volatile ScheduledExecutorService scheduler;

    private volatile boolean sequencerEnabled = false;
    private volatile boolean bassEnabled      = false;
    private volatile boolean melodyEnabled    = false;
    private volatile boolean paused           = false;
    private volatile int     bpm             = 120;
    private volatile int     drumPatternIdx  = 0;
    private volatile int     bassPatternIdx  = 0;
    // Piano arpeggio loop: up to 4 simultaneous root notes for harmonic chords.
    // Each slot holds a MIDI root, or -1 if unused.
    private static final int MAX_MELODY_ROOTS = 4;
    private final AtomicIntegerArray melodyRoots = new AtomicIntegerArray(MAX_MELODY_ROOTS);
    private volatile int[] currentArpPattern = null;
    // Sample-accurate arpeggio sequencer — only touched by audio thread (except arpPendingStart)
    private volatile boolean arpPendingStart = false;
    private long arpSampleClock    = 0L;
    private long arpNextFireSample = 0L;
    private int  arpNoteIdx        = 0;
    // Per-voice buffer start offset — set when a voice is triggered mid-buffer
    private final AtomicIntegerArray pianoVoiceStartAt = new AtomicIntegerArray(MAX_PIANO_VOICES);
    private volatile int     step            = 0; // scheduler thread writes, UI thread reads
    private long             lastTickMs      = 0; // only touched on scheduler thread

    /** Timestamp (System.currentTimeMillis) of the most recent bass note trigger. Used by UI for pulse animation. */
    private final AtomicLong lastBassHitMs = new AtomicLong(0L);

    // ── Patterns ──────────────────────────────────────────────────────────────
    // DRUM_PATTERNS[patternIdx][soundRow][step]
    // Rows: 0=kick, 1=snare, 2=closed HH, 3=open HH, 4=crash, 5=clap
    // Steps: 16 steps = 16th notes. Beat positions: 0=1, 4=2, 8=3, 12=4
    //        e.g. step 2 = "1-and", step 6 = "2-and", step 10 = "3-and", step 14 = "4-and"
    private static final boolean[][][] DRUM_PATTERNS = {
        { // Pattern 0: Rock — kick on 1 & 3 with pickups, 8th-note hi-hat, open HH on upbeats
            {true, false,false,false,false,true, false,false,true, false,false,false,false,true, false,false}, // kick: 1, e-of-2, 3, e-of-4
            {false,false,false,false,true, false,false,false,false,false,false,false,true, false,false,false}, // snare: 2, 4
            {true, false,true, false,true, false,true, false,true, false,true, false,true, false,true, false}, // hihat_c: 8th notes
            {false,false,false,false,false,false,true, false,false,false,false,false,false,false,true, false}, // hihat_o: &-of-2, &-of-4
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // crash
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // clap
        },
        { // Pattern 1: Funk — syncopated kick, 16th-note hi-hat, open HH on &-of-2
            {true, false,false,true, false,false,true, false,false,false,true, false,false,false,false,false}, // kick: 1, a-of-1, &-of-2, 3-and-a
            {false,false,false,false,true, false,false,false,false,false,false,true, true, false,false,false}, // snare: 2, 4-a, 4 (ghost+main)
            {true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true},  // hihat_c: all 16ths
            {false,false,false,false,false,false,true, false,false,false,false,false,false,false,false,false}, // hihat_o: &-of-2
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // crash
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // clap
        },
        { // Pattern 2: EDM — four-on-floor kick, clap on 2 & 4, upbeat open hihats, crash on 1
            {true, false,false,false,true, false,false,false,true, false,false,false,true, false,false,false}, // kick: every beat
            {false,false,false,false,true, false,false,false,false,false,false,false,true, false,false,false}, // snare: 2, 4
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // hihat_c: none (open hh takes over)
            {false,false,true, false,false,false,true, false,false,false,true, false,false,false,true, false}, // hihat_o: all upbeats
            {true, false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // crash: on the 1
            {false,false,false,false,true, false,false,false,false,false,false,false,true, false,false,false}, // clap: 2, 4
        },
        { // Pattern 3: Hip-Hop — heavy syncopated kick, sparse snare, 8th hihats
            {true, false,false,true, false,false,false,false,false,true, false,false,false,false,false,true},  // kick: 1, a-of-1, 3-e, 4-and
            {false,false,false,false,true, false,false,false,false,false,false,false,true, false,false,false}, // snare: 2, 4
            {true, false,true, false,true, false,true, false,true, false,true, false,true, false,true, false}, // hihat_c: 8th notes
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // hihat_o
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // crash
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // clap
        },
        { // Pattern 4: Reggae (One-Drop) — no kick on 1, kick lands on 3, open hats on &s
            {false,false,false,false,false,false,false,false,true, false,false,false,false,false,false,false}, // kick: beat 3 only
            {false,false,false,false,true, false,false,false,false,false,false,false,true, false,false,false}, // snare: 2, 4
            {true, false,true, false,true, false,true, false,true, false,true, false,true, false,true, false}, // hihat_c: 8th notes
            {false,false,true, false,false,false,true, false,false,false,true, false,false,false,true, false}, // hihat_o: & of every beat
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // crash
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // clap
        },
        { // Pattern 5: Jazz — swing 8th ride, sparse kick, brush snare on &-of-2 and &-of-4
            {true, false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // kick: beat 1 comp
            {false,false,false,false,false,false,false,true, false,false,false,false,false,false,false,true},  // snare: &-of-2, &-of-4
            {true, false,true, false,true, false,true, false,true, false,true, false,true, false,true, false}, // hihat_c: ride-like 8ths
            {false,false,false,true, false,false,false,true, false,false,false,true, false,false,false,true},  // hihat_o: &-of-every-beat (swing)
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // crash
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // clap
        },
        { // Pattern 6: Trap — four-on-floor kick, clap on 2&4, dense 16th hats, open hat sprinkle
            {true, false,false,false,true, false,false,false,true, false,false,false,true, false,false,false}, // kick: every beat
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // snare: none (clap)
            {true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true},  // hihat_c: all 16ths
            {false,false,true, false,false,false,false,false,false,false,true, false,false,false,false,false}, // hihat_o: sparse
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // crash
            {false,false,false,false,true, false,false,false,false,false,false,false,true, false,false,false}, // clap: 2, 4
        },
        { // Pattern 7: Latin/Samba — clave-inspired kick, 16th hats, open hat on &-of-2 and &-of-4
            {true, false,false,true, false,false,true, false,false,false,true, false,false,true, false,false}, // kick: clave rhythm
            {false,false,false,false,true, false,false,false,false,false,false,false,true, false,false,false}, // snare: 2, 4
            {true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true},  // hihat_c: 16ths
            {false,false,false,false,false,false,true, false,false,false,false,false,false,false,true, false}, // hihat_o: &-of-2, &-of-4
            {true, false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // crash: beat 1
            {false,false,false,false,false,false,false,false,false,false,false,false,false,false,false,false}, // clap
        },
    };

    // BASS_PATTERNS[patternIdx][step]: bass sound index (0=E2, 1=A2, 2=D3, 3=G2), or -1 = silent
    private static final int[][] BASS_PATTERNS = {
        // Rock: E2 on 1, walk through G2 on &-of-1, back to E2 on 3, G2 on &-of-3
        {0,-1,3,-1,-1,-1,-1,-1, 0,-1,3,-1,-1,-1,-1,-1},
        // Funk: A2 groove with E2 anchor on beat 1
        {0,-1,-1,1,-1,-1,1,-1, -1,-1,0,-1,1,-1,-1,-1},
        // EDM: D3 pounding all four beats
        {2,-1,-1,-1,2,-1,-1,-1, 2,-1,-1,-1,2,-1,-1,-1},
        // Hip-hop: low E2 with A2 in the pocket
        {0,-1,-1,1,-1,-1,-1,-1, 0,-1,-1,-1,1,-1,0,-1},
        // Reggae: slow E2 groove, walk up to G2 on the &
        {0,-1,-1,-1,-1,-1,3,-1, 0,-1,-1,-1,-1,-1,3,-1},
        // Jazz: walking bass E2→A2→D3→G2
        {0,-1,1,-1,2,-1,3,-1, 0,-1,3,-1,2,-1,1,-1},
        // Trap: heavy D3 808 on 1 and 3
        {2,-1,-1,-1,-1,-1,-1,-1, 2,-1,-1,-1,-1,-1,-1,-1},
        // Latin: E2 and A2 syncopated
        {0,-1,-1,1,-1,-1,0,-1, -1,-1,1,-1,-1,0,-1,-1},
    };

    // Arpeggio interval sets (semitone offsets from root).
    // Index 0 = OFF (single-note mode, handled in MainActivity — not used here).
    // Indices 1-4 map to the 4 MELODY mode states of the piano toggle button.
    static final int[][] ARPEGGIO_PATTERNS = {
        {},                          // 0: unused (OFF)
        {0, 4, 7, 12},               // 1: Major  up
        {0, 3, 7, 12},               // 2: Minor  up
        {0, 2, 4, 7, 9, 12},         // 3: Pentatonic run
        {0, 4, 7, 12, 7, 4},         // 4: Major up & back
    };

    // ── Piano synth modes ─────────────────────────────────────────────────────
    static final int      PIANO_SYNTH_KEYS   = 0;
    static final int      PIANO_SYNTH_BELLS  = 1;
    static final int      PIANO_SYNTH_ORGAN  = 2;
    static final String[] PIANO_SYNTH_LABELS = {"KEYS", "BELLS", "ORGAN"};
    private volatile int pianoSynthMode = PIANO_SYNTH_KEYS;

    public static int clampPianoSynthMode(int mode) {
        return ((mode % PIANO_SYNTH_LABELS.length) + PIANO_SYNTH_LABELS.length) % PIANO_SYNTH_LABELS.length;
    }
    public void setPianoSynthMode(int mode) { pianoSynthMode = clampPianoSynthMode(mode); }

    // ── Custom pattern (from Beat Maker) ──────────────────────────────────────
    private volatile boolean[][] customDrumGrid    = null;
    private volatile int[]       customPianoSteps  = null;
    private volatile boolean     customPatternActive = false;

    /** Set a custom step pattern from the Beat Maker. Pass null to revert to built-in patterns. */
    public void setCustomPattern(boolean[][] grid, int[] pianoSteps) {
        if (grid == null) {
            customPatternActive = false;
            return;
        }
        // Deep-copy so mutations to the caller's array don't affect playback.
        boolean[][] copy = new boolean[grid.length][];
        for (int i = 0; i < grid.length; i++) {
            copy[i] = grid[i] != null ? grid[i].clone() : new boolean[0];
        }
        this.customDrumGrid   = copy;
        this.customPianoSteps = pianoSteps != null ? pianoSteps.clone() : null;
        this.customPatternActive = grid.length > 0;
    }

    /** Convenience overload — no piano steps. */
    public void setCustomPattern(boolean[][] grid) { setCustomPattern(grid, null); }

    /**
     * Deactivate custom pattern and revert to built-in patterns.
     * The stored grid data is retained so the pattern can be re-applied later.
     */
    public void clearCustomPattern() {
        customPatternActive = false;
        customPianoSteps = null;
    }

    /** Returns true when a Beat Maker custom pattern is active. */
    public boolean isCustomPatternActive() { return customPatternActive; }

    /** Returns the last grid set via {@link #setCustomPattern}, or null if none was ever set. */
    public boolean[][] getCustomPattern() { return customDrumGrid; }

    /**
     * Placeholder hook for clap-brightness control.
     * The UI and background service still forward this value, but the current synthesized clap
     * ignores it, so this setter intentionally stores no state yet.
     */
    public void setClapTone(float tone) { /* tone brightness reserved for future use */ }

    // Stored drum gain multiplier (1.0 = default mix level). Applied during mixInto() so the
    // kick/snare/hat balance stays intact when the user changes the overall beat volume.
    private volatile float drumGain = 1.0f;

    /** Set overall drum mix gain (0 = mute, 1 = default, 2 = max). Clamped to [0, 2]. */
    public void setDrumGain(float gain) {
        drumGain = Math.max(0f, Math.min(2f, gain));
    }

    /** Returns the current drum gain multiplier (default 1.0). */
    public float getDrumGain() { return drumGain; }

    /** Alias for setPianoSynthMode — called from background service wiring. */
    public void setKeyboardSynthMode(int mode) { setPianoSynthMode(mode); }

    /** Returns the current 16th-note step position (0–15). Safe to read from any thread. */
    public int getCurrentStep16() { return step; }

    /** Immediately trigger a one-shot hit for audition/preview. Safe to call from any thread. */
    public void auditSound(int sndIdx) { triggerVoice(sndIdx); }

    // ── Constructor ───────────────────────────────────────────────────────────

    static DrumEngine createWarmup(Context context) {
        return new DrumEngine(context, true);
    }

    public DrumEngine(Context context) {
        this(context, false);
    }

    private DrumEngine(Context context, boolean warmupConstruction) {
        this(warmupConstruction);
        if (context != null) {
            // Load bundled WAV samples on a background thread so the constructor returns
            // immediately. Synthesized fallback sounds (from this()) are used until loading
            // completes. Java reference writes are atomic, so the audio thread safely picks
            // up the new samples without a lock.
            final android.content.res.Resources res =
                    context.getApplicationContext().getResources();
            Thread loader = new Thread(() -> {
                if (this.warmupConstruction) {
                    // Launch warmup should never outrun the UI thread just to decode optional samples.
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                }
                try {
                    loadBundledSamples(res);
                } catch (Exception ignored) {
                    // Keep synthesized fallback sounds if WAV decoding fails.
                }
            }, "DrumSampleLoader");
            loader.setDaemon(true);
            loader.start();
        }
    }

    public DrumEngine() {
        this(false);
    }

    private DrumEngine(boolean warmupConstruction) {
        this.warmupConstruction = warmupConstruction;
        for (int i = 0; i < MAX_VOICES; i++) voicePos.set(i, -1);
        for (int i = 0; i < MAX_PIANO_VOICES; i++) {
            pianoVoiceMode.set(i, PIANO_SYNTH_KEYS);
            pianoVoicePos.set(i, -1);
            pianoVoiceStartAt.set(i, 0);
        }
        for (int i = 0; i < MAX_MELODY_ROOTS; i++) melodyRoots.set(i, -1);
        applyDefaultTrackMix();
        synthesizeSounds();
        synthesizePianoSounds();
    }

    // ── Sound synthesis ───────────────────────────────────────────────────────

    private void synthesizeSounds() {
        // multicore: parallelize one-time sound-bank rendering, but keep the
        // real-time audio thread single-threaded and predictable.
        runRenderJobs(
                () -> sounds[SND_KICK]    = synthesizeKick(),
                () -> sounds[SND_SNARE]   = synthesizeSnare(),
                () -> sounds[SND_HIHAT_C] = synthesizeHihatClosed(),
                () -> sounds[SND_HIHAT_O] = synthesizeHihatOpen(),
                () -> sounds[SND_CRASH]   = synthesizeCrash(),
                () -> sounds[SND_CLAP]    = synthesizeClap(),
                () -> sounds[SND_BASS_E2] = synthesizeBass(82.41f),
                () -> sounds[SND_BASS_A2] = synthesizeBass(110.0f),
                () -> sounds[SND_BASS_D3] = synthesizeBass(146.83f),
                () -> sounds[SND_BASS_G2] = synthesizeBass(98.0f),
                () -> sounds[SND_TOM_HI]  = synthesizeTom(300f, 0.14f),
                () -> sounds[SND_TOM_LOW] = synthesizeTom(180f, 0.18f),
                () -> sounds[SND_RIM]     = synthesizeRim(),
                () -> sounds[SND_SHAKER]  = synthesizeShaker()
        );
    }

    private void synthesizePianoSounds() {
        Runnable[] jobs = new Runnable[NUM_PIANO_KEYS * NUM_PIANO_SYNTH_MODES];
        int jobIdx = 0;
        for (int mode = 0; mode < NUM_PIANO_SYNTH_MODES; mode++) {
            final int synthMode = mode;
            for (int k = 0; k < NUM_PIANO_KEYS; k++) {
                final int key = k;
                jobs[jobIdx++] = () -> {
                    float freq = (float) (440.0 * Math.pow(2.0, (PIANO_MIDI_BASE + key - 69) / 12.0));
                    pianoSounds[synthMode][key] = synthesizePianoHit(freq, synthMode);
                };
            }
        }
        runRenderJobs(jobs);
    }

    private void runRenderJobs(Runnable... jobs) {
        if (jobs == null || jobs.length == 0) return;
        int workerCount = Math.min(RENDER_WORKERS, jobs.length);
        if (warmupConstruction) {
            // Warmup still prepares the sound bank, but with fewer background workers so launch stays responsive.
            workerCount = Math.min(workerCount, WARMUP_RENDER_WORKERS);
        }
        if (workerCount <= 1) {
            for (Runnable job : jobs) job.run();
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(() -> {
                if (warmupConstruction) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                }
                r.run();
            }, "DrumRenderWorker");
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>(jobs.length);
        try {
            for (Runnable job : jobs) futures.add(pool.submit(job));
            for (Future<?> future : futures) future.get();
        } catch (Exception e) {
            throw new RuntimeException("DrumEngine render failed", e);
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface IoJob {
        void run() throws IOException;
    }

    private void runIoJobs(IoJob... jobs) throws IOException {
        if (jobs == null || jobs.length == 0) return;
        int workerCount = Math.min(RENDER_WORKERS, jobs.length);
        if (warmupConstruction) {
            // Keep launch-time sample decoding polite for the same reason as render warmup.
            workerCount = Math.min(workerCount, WARMUP_RENDER_WORKERS);
        }
        if (workerCount <= 1) {
            for (IoJob job : jobs) runIoJob(job);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(workerCount, r -> {
            Thread t = new Thread(() -> {
                if (warmupConstruction) {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                }
                r.run();
            }, "DrumSampleWorker");
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> futures = new ArrayList<>(jobs.length);
        try {
            for (IoJob job : jobs) futures.add(pool.submit(() -> {
                runIoJob(job);
                return null;
            }));
            for (Future<?> future : futures) future.get();
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof IOException) throw (IOException) cause;
            throw new IOException("Bundled sample load failed", cause);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void runIoJob(IoJob job) throws IOException {
        if (job != null) job.run();
    }

    private void loadBundledSamples(Resources res) throws IOException {
        runIoJobs(
                // Give the kick sample a little extra level so it stays present under hats/claps.
                () -> sounds[SND_KICK]    = pitchShiftPcm(
                        loadWavMonoAs48k(res, R.raw.drum_kick, 1.16f), KICK_PITCH_RATIO),
                () -> sounds[SND_SNARE]   = loadWavMonoAs48k(res, R.raw.drum_snare, 0.86f),
                () -> sounds[SND_HIHAT_C] = loadWavMonoAs48k(res, R.raw.drum_hihat, 0.72f),
                () -> sounds[SND_HIHAT_O] = loadWavMonoAs48k(res, R.raw.drum_hihat_open, 0.62f),
                () -> sounds[SND_CLAP]    = loadWavMonoAs48k(res, R.raw.drum_clap, 0.76f),
                () -> sounds[SND_BASS_E2] = loadWavMonoAs48k(res, R.raw.bass_e2, 0.72f),
                () -> sounds[SND_BASS_A2] = loadWavMonoAs48k(res, R.raw.bass_a2, 0.72f),
                () -> sounds[SND_BASS_D3] = loadWavMonoAs48k(res, R.raw.bass_d3, 0.72f),
                () -> sounds[SND_BASS_G2] = loadWavMonoAs48k(res, R.raw.bass_g2, 0.72f)
        );
    }

    private float[] loadWavMonoAs48k(Resources res, int rawId, float gain) throws IOException {
        byte[] bytes;
        try (InputStream in = res.openRawResource(rawId);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
            bytes = out.toByteArray();
        }

        if (bytes.length < 44 || readLe32(bytes, 0) != 0x46464952 || readLe32(bytes, 8) != 0x45564157) {
            throw new IOException("Invalid WAV");
        }

        int channels = 1;
        int sampleRate = SAMPLE_RATE;
        int bitsPerSample = 16;
        int formatCode = 1;
        int dataOffset = -1;
        int dataSize = 0;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            int chunkId = readLe32(bytes, pos);
            int chunkSize = readLe32(bytes, pos + 4);
            int chunkData = pos + 8;
            if (chunkData + chunkSize > bytes.length) break;
            if (chunkId == 0x20746d66) { // "fmt "
                formatCode = readLe16(bytes, chunkData);
                channels = readLe16(bytes, chunkData + 2);
                sampleRate = readLe32(bytes, chunkData + 4);
                bitsPerSample = readLe16(bytes, chunkData + 14);
            } else if (chunkId == 0x61746164) { // "data"
                dataOffset = chunkData;
                dataSize = chunkSize;
                break;
            }
            pos = chunkData + chunkSize + (chunkSize & 1);
        }
        if (dataOffset < 0 || channels < 1) throw new IOException("Missing WAV data");

        int frames;
        float[] mono;
        if (formatCode == 1 && bitsPerSample == 16) {
            int frameSize = channels * 2;
            frames = dataSize / frameSize;
            mono = new float[frames];
            for (int i = 0; i < frames; i++) {
                int sample = readLe16Signed(bytes, dataOffset + i * frameSize);
                mono[i] = (sample / 32768f) * gain;
            }
        } else if (formatCode == 3 && bitsPerSample == 32) {
            int frameSize = channels * 4;
            frames = dataSize / frameSize;
            mono = new float[frames];
            for (int i = 0; i < frames; i++) {
                int bits = readLe32(bytes, dataOffset + i * frameSize);
                mono[i] = clamp(Float.intBitsToFloat(bits) * gain, -1f, 1f);
            }
        } else {
            throw new IOException("Unsupported WAV format");
        }

        if (sampleRate == SAMPLE_RATE) return mono;
        return resampleLinear(mono, sampleRate, SAMPLE_RATE);
    }

    private float[] resampleLinear(float[] input, int srcRate, int dstRate) {
        if (input.length == 0 || srcRate <= 0 || dstRate <= 0) return input;
        int outLen = Math.max(1, Math.round(input.length * (dstRate / (float) srcRate)));
        float[] out = new float[outLen];
        float scale = (input.length - 1f) / Math.max(1f, outLen - 1f);
        for (int i = 0; i < outLen; i++) {
            float srcPos = i * scale;
            int idx = (int) srcPos;
            int next = Math.min(input.length - 1, idx + 1);
            float frac = srcPos - idx;
            out[i] = input[idx] + (input[next] - input[idx]) * frac;
        }
        return out;
    }

    private float[] pitchShiftPcm(float[] input, float ratio) {
        if (input == null || input.length == 0) return input;
        float speed = Math.max(0.5f, Math.min(2.0f, ratio));
        if (Math.abs(speed - 1f) < 0.0001f) return input;

        // Simple resampling raises the pitch and shortens the hit, which works well for percussion.
        int outLen = Math.max(1, Math.round(input.length / speed));
        float[] out = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            float srcPos = i * speed;
            int idx = (int) srcPos;
            int next = Math.min(input.length - 1, idx + 1);
            float frac = srcPos - idx;
            out[i] = input[idx] + (input[next] - input[idx]) * frac;
        }
        return out;
    }

    /** Kick: wide pitch sweep 255→55 Hz with harmonics and slow punch envelope. */
    private float[] synthesizeKick() {
        int len = (int)(SAMPLE_RATE * 0.40f);
        float[] pcm = new float[len];
        float phase = 0f;
        for (int i = 0; i < len; i++) {
            float t    = (float) i / SAMPLE_RATE;
            float env  = (float) Math.exp(-t * 6.0);   // slower decay = more punch
            float freq = (55f + 200f * (float) Math.exp(-t * 30.0)) * KICK_PITCH_RATIO;
            // 3 harmonics for a fuller sub-bass body
            float s    = (float) Math.sin(phase) * 0.70f
                       + (float) Math.sin(phase * 2f)  * 0.22f
                       + (float) Math.sin(phase * 3f)  * 0.08f;
            pcm[i] = env * s * 1.06f;
            phase += (float) (2.0 * Math.PI * freq / SAMPLE_RATE);
            if (phase >= (float)(2.0 * Math.PI)) phase -= (float)(2.0 * Math.PI);
        }
        return pcm;
    }

    /** Snare: 220 Hz body + HPF noise (coeff 0.93 for crisp attack), 200 ms. */
    private float[] synthesizeSnare() {
        int len = (int)(SAMPLE_RATE * 0.20f);
        float[] pcm = new float[len];
        Random rng = new Random(42L);
        float phase = 0f, prev = 0f;
        for (int i = 0; i < len; i++) {
            float t        = (float) i / SAMPLE_RATE;
            float envBody  = (float) Math.exp(-t * 18.0);
            float envNoise = (float) Math.exp(-t * 14.0);
            float body     = envBody * (float) Math.sin(phase) * 0.50f;
            float raw      = rng.nextFloat() * 2f - 1f;
            float hp       = raw - prev * 0.93f;  // stronger HPF = crisper attack, less mud
            prev = raw;
            float noise    = envNoise * hp * 0.52f;
            pcm[i] = (body + noise) * 0.85f;
            phase += (float) (2.0 * Math.PI * 220.0 / SAMPLE_RATE);
            if (phase >= (float)(2.0 * Math.PI)) phase -= (float)(2.0 * Math.PI);
        }
        return pcm;
    }

    /** Closed hi-hat: metallic HPF noise (coeff 0.97), very tight click (exp(-40t)). */
    private float[] synthesizeHihatClosed() {
        int len = (int)(SAMPLE_RATE * 0.07f);
        float[] pcm = new float[len];
        Random rng = new Random(123L);
        float prev = 0f;
        for (int i = 0; i < len; i++) {
            float t   = (float) i / SAMPLE_RATE;
            float env = (float) Math.exp(-t * 40.0);  // tight, punchy click
            float raw = rng.nextFloat() * 2f - 1f;
            float hp  = raw - prev * 0.97f;  // very high cutoff = thin, metallic
            prev = raw;
            pcm[i] = env * hp * 0.48f;
        }
        return pcm;
    }

    /** Open hi-hat: HPF noise (coeff 0.95), longer sustain (exp(-8t)). */
    private float[] synthesizeHihatOpen() {
        int len = (int)(SAMPLE_RATE * 0.25f);
        float[] pcm = new float[len];
        Random rng = new Random(456L);
        float prev = 0f;
        for (int i = 0; i < len; i++) {
            float t   = (float) i / SAMPLE_RATE;
            float env = (float) Math.exp(-t * 8.0);
            float raw = rng.nextFloat() * 2f - 1f;
            float hp  = raw - prev * 0.95f;  // slightly warmer than closed hat
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
        float peak = 0.001f;
        for (float v : pcm) peak = Math.max(peak, Math.abs(v));
        for (int i = 0; i < len; i++) pcm[i] = pcm[i] / peak * 0.88f;
        return pcm;
    }

    /**
     * Bass note: 4 harmonics, natural pitch sag (starts 1.5% flat, rises to nominal),
     * slow decay (exp(-3t)) for deep sustained sub-bass.
     */
    private float[] synthesizeBass(float freq) {
        int len = (int)(SAMPLE_RATE * 0.55f);
        float[] pcm = new float[len];
        float phase = 0f;
        for (int i = 0; i < len; i++) {
            float t   = (float) i / SAMPLE_RATE;
            float env = (float) Math.exp(-t * 3.0f);  // slow decay = sustained sub
            // Starts flat, rises to nominal (like a real bass string pluck)
            float f   = freq * (1f - 0.015f * (float) Math.exp(-t * 15.0));
            // Boost 2nd/3rd harmonics — fundamental is often inaudible on phone speakers
            float s   = (float) Math.sin(phase) * 0.55f
                      + (float) Math.sin(phase * 2f) * 0.35f
                      + (float) Math.sin(phase * 3f) * 0.14f
                      + (float) Math.sin(phase * 4f) * 0.05f;
            pcm[i] = env * s * 1.0f;
            phase += (float)(2.0 * Math.PI * f / SAMPLE_RATE);
            if (phase >= (float)(2.0 * Math.PI)) phase -= (float)(2.0 * Math.PI);
        }
        return pcm;
    }

    /** Tom: pitched sine decay with pitch sweep from startFreq. */
    private float[] synthesizeTom(float startFreq, float decaySec) {
        int len = (int)(SAMPLE_RATE * decaySec * 3f);
        float[] pcm = new float[len];
        float phase = 0f;
        for (int i = 0; i < len; i++) {
            float t   = (float) i / SAMPLE_RATE;
            float env = (float) Math.exp(-t / decaySec);
            float freq = startFreq * (float) Math.exp(-t * 8.0);
            pcm[i] = env * (float) Math.sin(phase) * 0.80f;
            phase += (float)(2.0 * Math.PI * freq / SAMPLE_RATE);
            if (phase >= (float)(2.0 * Math.PI)) phase -= (float)(2.0 * Math.PI);
        }
        return pcm;
    }

    /** Rimshot: short HPF noise burst with a sharp transient. */
    private float[] synthesizeRim() {
        int len = (int)(SAMPLE_RATE * 0.05f);
        float[] pcm = new float[len];
        Random rng = new Random(99L);
        float prev = 0f;
        for (int i = 0; i < len; i++) {
            float t   = (float) i / SAMPLE_RATE;
            float env = (float) Math.exp(-t * 60.0);
            float noise = (float) rng.nextGaussian();
            float hp = noise - prev; prev = noise;  // simple 1-pole HPF
            pcm[i] = env * hp * 0.70f;
        }
        return pcm;
    }

    /** Shaker: high-frequency noise burst, 80 ms. */
    private float[] synthesizeShaker() {
        int len = (int)(SAMPLE_RATE * 0.08f);
        float[] pcm = new float[len];
        Random rng = new Random(77L);
        float prev = 0f;
        for (int i = 0; i < len; i++) {
            float t   = (float) i / SAMPLE_RATE;
            float env = (float) Math.exp(-t * 30.0);
            float noise = (float) rng.nextGaussian();
            float hp = noise - prev * 0.85f; prev = noise;
            pcm[i] = env * hp * 0.45f;
        }
        return pcm;
    }

    /**
     * Piano key hit: 3 ms linear attack ramp (kills onset click) followed by a smooth
     * single-exponent decay (exp(-4.5t)).  At 120 BPM the note is still at ~57% when the
     * next 16th fires, so consecutive hits overlap cleanly instead of chopping.
     */
    private float[] synthesizePianoHit(float freq, int mode) {
        switch (clampPianoSynthMode(mode)) {
            case PIANO_SYNTH_BELLS:
                return synthesizeBellHit(freq);
            case PIANO_SYNTH_ORGAN:
                return synthesizeOrganHit(freq);
            case PIANO_SYNTH_KEYS:
            default:
                return synthesizeKeysHit(freq);
        }
    }

    private float[] synthesizeKeysHit(float freq) {
        int len = (int)(SAMPLE_RATE * 0.80f);           // 0.8 s total
        float[] pcm = new float[len];
        float phase = 0f;
        final float TWO_PI  = (float)(2.0 * Math.PI);
        final int   ATTACK  = (int)(SAMPLE_RATE * 0.003f); // 3 ms linear ramp
        for (int i = 0; i < len; i++) {
            float t      = (float) i / SAMPLE_RATE;
            float attack = (i < ATTACK) ? (float) i / ATTACK : 1f;
            float env    = attack * (float) Math.exp(-4.5 * t);
            // Warm mallet/marimba harmonics — fundamental dominant, gentle overtones
            float s = (float)(0.70 * Math.sin(phase)
                            + 0.20 * Math.sin(2 * phase)
                            + 0.07 * Math.sin(3 * phase)
                            + 0.03 * Math.sin(4 * phase));
            pcm[i] = env * s * 0.88f;
            phase += TWO_PI * freq / SAMPLE_RATE;
            if (phase >= TWO_PI) phase -= TWO_PI;
        }
        return pcm;
    }

    private float[] synthesizeBellHit(float freq) {
        int len = (int) (SAMPLE_RATE * 1.25f);
        float[] pcm = new float[len];
        float phase = 0f;
        final float twoPi = (float) (2.0 * Math.PI);
        final int attack = (int) (SAMPLE_RATE * 0.0025f);
        for (int i = 0; i < len; i++) {
            float t = (float) i / SAMPLE_RATE;
            float attackEnv = (i < attack) ? (float) i / attack : 1f;
            float env = attackEnv * ((float) Math.exp(-3.8 * t) * 0.72f
                    + (float) Math.exp(-8.0 * t) * 0.28f);
            float shimmer = (float) (0.56 * Math.sin(phase)
                    + 0.24 * Math.sin(2.76f * phase)
                    + 0.14 * Math.sin(5.43f * phase)
                    + 0.06 * Math.sin(8.21f * phase));
            pcm[i] = env * shimmer * 0.95f;
            phase += twoPi * freq / SAMPLE_RATE;
            if (phase >= twoPi) phase -= twoPi;
        }
        return pcm;
    }

    private float[] synthesizeOrganHit(float freq) {
        int len = (int) (SAMPLE_RATE * 0.95f);
        float[] pcm = new float[len];
        float phase = 0f;
        final float twoPi = (float) (2.0 * Math.PI);
        final int attack = (int) (SAMPLE_RATE * 0.010f);
        for (int i = 0; i < len; i++) {
            float t = (float) i / SAMPLE_RATE;
            float attackEnv = (i < attack) ? (float) i / attack : 1f;
            float env = attackEnv * (0.82f + 0.18f * (float) Math.exp(-2.2 * t));
            float vibrato = (float) Math.sin(twoPi * 5.2f * t) * 0.0025f;
            float voicedPhase = phase + vibrato * twoPi;
            float organ = (float) (0.58 * Math.sin(voicedPhase)
                    + 0.26 * Math.sin(2.0f * voicedPhase)
                    + 0.11 * Math.sin(3.0f * voicedPhase)
                    + 0.05 * Math.sin(4.0f * voicedPhase));
            pcm[i] = env * organ * 0.72f;
            phase += twoPi * freq / SAMPLE_RATE;
            if (phase >= twoPi) phase -= twoPi;
        }
        return pcm;
    }

    /**
     * Trigger a one-shot piano key hit at the given MIDI note (48–72 = C3–C5).
     * Safe to call from any thread. The hit is mixed into the audio output immediately.
     */
    public void triggerPianoKey(int midiNote) {
        int k = midiNote - PIANO_MIDI_BASE;
        if (k < 0 || k >= NUM_PIANO_KEYS) return;
        int mode = pianoSynthMode;
        // Find a free voice slot
        for (int i = 0; i < MAX_PIANO_VOICES; i++) {
            if (pianoVoicePos.get(i) < 0) {
                pianoVoiceKey.set(i, k);
                pianoVoiceMode.set(i, mode);
                pianoVoiceStartAt.set(i, 0);
                pianoVoicePos.set(i, 0);
                return;
            }
        }
        // All voices busy: steal the one furthest along (most likely nearly finished)
        int stale = 0, maxPos = 0;
        for (int i = 0; i < MAX_PIANO_VOICES; i++) {
            int p = pianoVoicePos.get(i);
            if (p > maxPos) { maxPos = p; stale = i; }
        }
        pianoVoiceKey.set(stale, k);
        pianoVoiceMode.set(stale, mode);
        pianoVoiceStartAt.set(stale, 0);
        pianoVoicePos.set(stale, 0);
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────

    /** Start the 16th-note scheduler. Safe to call multiple times — no-op if already running. */
    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DrumEngineScheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler = s;
        step = 0;
        lastTickMs = System.currentTimeMillis();
        long intervalMs = intervalMs();
        s.schedule(this::tick, intervalMs, TimeUnit.MILLISECONDS);
    }

    private long intervalMs() {
        return (long)(60_000.0 / bpm / 4);
    }

    private void tick() {
        // Record the time this tick should have fired (not when it actually ran, to prevent drift)
        lastTickMs += intervalMs();

        // Advance step counter regardless — keeps timing stable even when muted.
        if (paused) {
            step = (step + 1) % 16;
            long nextInterval = intervalMs();
            long delay = Math.max(0L, lastTickMs + nextInterval - System.currentTimeMillis());
            ScheduledExecutorService s = scheduler;
            if (s != null && !s.isShutdown()) s.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
            return;
        }

        boolean[][] customGrid = customDrumGrid;
        if (customPatternActive && customGrid != null) {
            // Beat Maker custom pattern: rows map to ROW_SOUNDS order
            int[] rowSounds = {
                SND_KICK, SND_SNARE, SND_HIHAT_C, SND_HIHAT_O, SND_CRASH, SND_CLAP,
                SND_BASS_E2, SND_BASS_A2, SND_BASS_D3,
                SND_TOM_HI, SND_TOM_LOW, SND_RIM, SND_SHAKER
            };
            int[] bassSounds = {SND_BASS_E2, SND_BASS_A2, SND_BASS_D3};
            for (int row = 0; row < Math.min(customGrid.length, rowSounds.length); row++) {
                boolean[] rowData = customGrid[row];
                if (rowData == null || step >= rowData.length || !rowData[step]) continue;
                int snd = rowSounds[row];
                // rows 6-8 are bass
                if (snd == SND_BASS_E2 || snd == SND_BASS_A2 || snd == SND_BASS_D3) {
                    if (bassEnabled) { triggerVoice(snd); lastBassHitMs.set(System.currentTimeMillis()); }
                } else if (sequencerEnabled) {
                    triggerVoice(snd);
                }
            }
            // Piano steps from custom pattern
            int[] cps = customPianoSteps;
            if (sequencerEnabled && cps != null && step < cps.length && cps[step] >= 0) {
                triggerPianoKey(cps[step]);
            }
        } else {
            boolean[][] drumPat = DRUM_PATTERNS[Math.min(drumPatternIdx, DRUM_PATTERNS.length - 1)];
            int[]       bassPat = BASS_PATTERNS[Math.min(bassPatternIdx, BASS_PATTERNS.length - 1)];
            if (sequencerEnabled) {
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
        }
        step = (step + 1) % 16;

        // Self-reschedule: next tick fires at lastTickMs + one interval from current BPM.
        // BPM changes take effect here naturally — no restart, no glitch.
        long nextInterval = intervalMs();
        long delay = Math.max(0L, lastTickMs + nextInterval - System.currentTimeMillis());

        ScheduledExecutorService s = scheduler;
        if (s != null && !s.isShutdown()) s.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
    }

    private void triggerVoice(int soundIdx) {
        for (int i = 0; i < MAX_VOICES; i++) {
            if (voicePos.get(i) < 0) {
                voiceSound.set(i, soundIdx);
                voicePos.set(i, 0);
                return;
            }
        }
    }

    // ── Audio thread interface ─────────────────────────────────────────────────

    /**
     * Mix all active drum, bass, and piano voices into the caller-provided mono short[] buffer.
     * Each voice uses its per-sound mix weight from trackVolumes[] and the overall drumGain
     * multiplier, so changing beat volume does not flatten the kit balance.
     * Called from the live audio and preview threads, so it must be non-blocking and allocation-free.
     */
    public void mixInto(short[] buffer, int count) {
        if (paused) return;

        float globalDrumGain = drumGain;
        for (int v = 0; v < MAX_VOICES; v++) {
            int pos = voicePos.get(v);
            if (pos < 0) continue;
            int snd = voiceSound.get(v);
            if (snd < 0 || snd >= NUM_SOUNDS) { voicePos.set(v, -1); continue; }
            float[] pcm = sounds[snd];
            if (pcm == null)               { voicePos.set(v, -1); continue; }

            float vol = trackVolumes[snd];
            for (int i = 0; i < count; i++) {
                if (pos >= pcm.length) { pos = -1; break; }
                float dry = buffer[i] / (float) Short.MAX_VALUE;
                float mixed = dry + pcm[pos] * vol * globalDrumGain * DRUM_MIX_HEADROOM;
                buffer[i] = (short) (clamp(mixed, -0.98f, 0.98f) * Short.MAX_VALUE);
                pos++;
            }
            voicePos.set(v, pos);
        }

        // --- Sample-accurate arpeggio sequencer (runs on audio thread — zero jitter) ---
        {
            int[] arp = currentArpPattern;
            boolean anyRoot = false;
            for (int ri = 0; ri < MAX_MELODY_ROOTS; ri++) {
                if (melodyRoots.get(ri) >= 0) { anyRoot = true; break; }
            }
            // Guard: arpeggio must respect the enabled flag — no sound when instrument is off
            if (melodyEnabled && anyRoot && arp != null && arp.length > 0) {
                if (arpPendingStart) {
                    arpPendingStart   = false;
                    arpSampleClock    = 0L;
                    arpNoteIdx        = 0;
                    arpNextFireSample = 0L; // fire first note immediately
                }
                // Fire every note whose sample position falls within this buffer
                while (arpNextFireSample < arpSampleClock + count) {
                    int offset = (int) Math.max(0L, arpNextFireSample - arpSampleClock);
                    // Fire the arpeggio step for every active root (harmonic chord)
                    for (int ri = 0; ri < MAX_MELODY_ROOTS; ri++) {
                        int root = melodyRoots.get(ri);
                        if (root < 0) continue;
                        int midi = Math.min(72, root + arp[arpNoteIdx % arp.length]);
                        fireArpNote(midi, offset);
                    }
                    arpNoteIdx++;
                    // 8th-note interval in samples: SAMPLE_RATE * 30 / bpm
                    long stepSamples = (long)(SAMPLE_RATE * 30.0 / bpm);
                    arpNextFireSample += stepSamples;
                }
                arpSampleClock += count;
            } else {
                // Arpeggio off — reset clock so next activation fires immediately
                arpSampleClock = 0L;
                arpPendingStart = false;
            }
        }

        // Mix piano key one-shot voices (with sub-buffer start offset support)
        float pianoVol = pianoVolume;
        for (int v = 0; v < MAX_PIANO_VOICES; v++) {
            int pos = pianoVoicePos.get(v);
            if (pos < 0) continue;
            int k = pianoVoiceKey.get(v);
            if (k < 0 || k >= NUM_PIANO_KEYS) { pianoVoicePos.set(v, -1); continue; }
            int mode = clampPianoSynthMode(pianoVoiceMode.get(v));
            float[] pcm = pianoSounds[mode][k];
            if (pcm == null) { pianoVoicePos.set(v, -1); continue; }
            int startAt = pianoVoiceStartAt.get(v);
            for (int i = startAt; i < count; i++) {
                if (pos >= pcm.length) { pos = -1; break; }
                float dry = buffer[i] / (float) Short.MAX_VALUE;
                float mixed = dry + pcm[pos] * pianoVol * PIANO_MIX_HEADROOM;
                buffer[i] = (short) (clamp(mixed, -0.98f, 0.98f) * Short.MAX_VALUE);
                pos++;
            }
            pianoVoiceStartAt.set(v, 0); // subsequent buffers always start at offset 0
            pianoVoicePos.set(v, pos);
        }
    }

    /**
     * Fire one arpeggio note at sample position {@code bufferOffset} within the current buffer.
     * Audio-thread only — must be non-blocking and allocation-free.
     */
    private void fireArpNote(int midiNote, int bufferOffset) {
        int k = midiNote - PIANO_MIDI_BASE;
        if (k < 0 || k >= NUM_PIANO_KEYS) return;
        int mode = pianoSynthMode;
        for (int i = 0; i < MAX_PIANO_VOICES; i++) {
            if (pianoVoicePos.get(i) < 0) {
                pianoVoiceKey.set(i, k);
                pianoVoiceMode.set(i, mode);
                pianoVoiceStartAt.set(i, bufferOffset);
                pianoVoicePos.set(i, 0);
                return;
            }
        }
        // Steal the voice furthest through its sample
        int stale = 0, maxPos = 0;
        for (int i = 0; i < MAX_PIANO_VOICES; i++) {
            int p = pianoVoicePos.get(i);
            if (p > maxPos) { maxPos = p; stale = i; }
        }
        pianoVoiceKey.set(stale, k);
        pianoVoiceMode.set(stale, mode);
        pianoVoiceStartAt.set(stale, bufferOffset);
        pianoVoicePos.set(stale, 0);
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /** Stop the scheduler and silence all active voices. */
    public void stop() {
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) s.shutdownNow();
        step = 0;
        sequencerEnabled = false;
        bassEnabled = false;
        melodyEnabled = false;
        paused = false;
        clearTriggeredVoices();
        clearMelodyRoot();
    }

    /** Stop and release resources. Call from onDestroy. */
    public void release() { stop(); }

    // ── Getters / setters ──────────────────────────────────────────────────────

    public void setEnabled(boolean on) {
        sequencerEnabled = on;
        if (!on) clearDrumVoices();
    }
    public boolean isEnabled()             { return sequencerEnabled; }
    public void setMelodyEnabled(boolean on) {
        melodyEnabled = on;
        if (!on) {
            arpSampleClock = 0L;
            arpNextFireSample = 0L;
            arpNoteIdx = 0;
            arpPendingStart = false;
            clearPianoVoices();
        }
    }
    public boolean isMelodyEnabled()       { return melodyEnabled; }
    public void setPaused(boolean on) {
        if (paused == on) return;
        paused = on;
        if (!on) return;
        clearTriggeredVoices();
        arpSampleClock = 0L;
        arpNextFireSample = 0L;
        arpNoteIdx = 0;
        arpPendingStart = melodyEnabled && hasAnyMelodyRoot();
    }

    public void setBassEnabled(boolean on) {
        bassEnabled = on;
        if (!on) clearBassVoices();
    }
    public boolean isBassEnabled()            { return bassEnabled; }

    /** Returns true if any arpeggio root is currently active. */
    public boolean hasAnyMelodyRoot() {
        for (int i = 0; i < MAX_MELODY_ROOTS; i++) if (melodyRoots.get(i) >= 0) return true;
        return false;
    }

    /** Returns true if this specific MIDI note is an active arpeggio root. */
    public boolean isMelodyRootActive(int midiNote) {
        for (int i = 0; i < MAX_MELODY_ROOTS; i++) if (melodyRoots.get(i) == midiNote) return true;
        return false;
    }

    /**
     * Add one arpeggio root (harmonic layer). Up to 4 simultaneous roots are supported.
     * The clock resets only if no roots were active before this call.
     * @param midiNote MIDI root (48–72)
     * @param arpPattern semitone offsets from root, e.g. {0,4,7,12} for major
     */
    public void addMelodyRoot(int midiNote, int[] arpPattern) {
        currentArpPattern = arpPattern;
        boolean wasEmpty = !hasAnyMelodyRoot();
        melodyEnabled = true;
        // Reuse same slot if already present, otherwise find a free one
        for (int i = 0; i < MAX_MELODY_ROOTS; i++) {
            int v = melodyRoots.get(i);
            if (v == midiNote || v < 0) {
                melodyRoots.set(i, midiNote);
                if (wasEmpty) arpPendingStart = true;
                return;
            }
        }
        // All slots full: overwrite slot 0
        melodyRoots.set(0, midiNote);
        if (wasEmpty) arpPendingStart = true;
    }

    /** Remove one arpeggio root. If no roots remain, the sequencer stops automatically. */
    public void removeMelodyRoot(int midiNote) {
        for (int i = 0; i < MAX_MELODY_ROOTS; i++) {
            if (melodyRoots.get(i) == midiNote) melodyRoots.set(i, -1);
        }
        if (!hasAnyMelodyRoot()) setMelodyEnabled(false);
    }

    /**
     * Replace all active roots with a single root. Resets the arpeggio clock.
     * Backward-compatible with single-key melody mode.
     */
    public void setMelodyRoot(int midiNote, int[] arpPattern) {
        clearMelodyRoot();
        addMelodyRoot(midiNote, arpPattern);
    }

    /** Stop all arpeggio loops. */
    public void clearMelodyRoot() {
        for (int i = 0; i < MAX_MELODY_ROOTS; i++) melodyRoots.set(i, -1);
        currentArpPattern = null;
        setMelodyEnabled(false);
    }

    /** Returns the first active MIDI root, or -1 if none (backward-compat). */
    public int getMelodyRootMidi() {
        for (int i = 0; i < MAX_MELODY_ROOTS; i++) {
            int v = melodyRoots.get(i);
            if (v >= 0) return v;
        }
        return -1;
    }

    /** Switch the drum beat (0–7). Step counter NOT reset — no timing glitch. */
    public void setDrumPattern(int idx) {
        drumPatternIdx = Math.max(0, Math.min(DRUM_PATTERNS.length - 1, idx));
    }
    /** Switch the bass line (0–7), independently of the drum beat. */
    public void setBassPattern(int idx) {
        bassPatternIdx = Math.max(0, Math.min(BASS_PATTERNS.length - 1, idx));
    }
    public int getDrumPattern()  { return drumPatternIdx; }
    public int getBassPattern()  { return bassPatternIdx; }


    /**
     * Change BPM. Takes effect on the next self-scheduled tick — no restart, no glitch.
     */
    public void setBpm(int newBpm) {
        this.bpm = Math.max(60, Math.min(200, newBpm));
    }
    public int getBpm() { return bpm; }

    /** Returns the System.currentTimeMillis() of the last bass hit, or 0 if none yet. */
    public long getLastBassHitMs() { return lastBassHitMs.get(); }

    // ── Per-sound volume ───────────────────────────────────────────────────────

    /** Set the volume applied to all piano key one-shot hits. */
    public void setPianoVolume(float vol) { pianoVolume = Math.max(0f, vol); }

    /** Set volume for a specific sound index (0.0 = silent, 1.0 = full). */
    public void setTrackVolume(int sndIdx, float vol) {
        if (sndIdx >= 0 && sndIdx < NUM_SOUNDS) trackVolumes[sndIdx] = Math.max(0f, Math.min(1f, vol));
    }

    /** Group setter: kick drum volume. */
    public void setKickVolume(float vol) {
        trackVolumes[SND_KICK] = Math.max(0f, Math.min(1f, vol));
    }

    /** Group setter: snare drum volume. */
    public void setSnareVolume(float vol) {
        trackVolumes[SND_SNARE] = Math.max(0f, Math.min(1f, vol));
    }

    /** Group setter: upper-percussion volume (closed/open hi-hat, crash, and clap). */
    public void setHihatVolume(float vol) {
        float v = Math.max(0f, Math.min(1f, vol));
        trackVolumes[SND_HIHAT_C] = v;
        trackVolumes[SND_HIHAT_O] = v;
        trackVolumes[SND_CRASH]   = v;
        trackVolumes[SND_CLAP]    = v;
    }

    /** Group setter: bass note volume. */
    public void setBassVolume(float vol) {
        float v = Math.max(0f, Math.min(1f, vol));
        trackVolumes[SND_BASS_E2] = v;
        trackVolumes[SND_BASS_A2] = v;
        trackVolumes[SND_BASS_D3] = v;
        trackVolumes[SND_BASS_G2] = v;
    }

    /** Get current track volume for a sound index. */
    public float getTrackVolume(int sndIdx) {
        return (sndIdx >= 0 && sndIdx < NUM_SOUNDS) ? trackVolumes[sndIdx] : 1f;
    }

    private void applyDefaultTrackMix() {
        // Bias the default kit toward a clearer kick pocket so it does not disappear under hats.
        trackVolumes[SND_KICK] = DEFAULT_KICK_VOL;
        trackVolumes[SND_SNARE] = DEFAULT_SNARE_VOL;
        trackVolumes[SND_HIHAT_C] = DEFAULT_HIHAT_VOL;
        trackVolumes[SND_HIHAT_O] = DEFAULT_HIHAT_VOL;
        trackVolumes[SND_CRASH] = DEFAULT_CRASH_VOL;
        trackVolumes[SND_CLAP] = DEFAULT_CLAP_VOL;
        trackVolumes[SND_BASS_E2] = DEFAULT_BASS_VOL;
        trackVolumes[SND_BASS_A2] = DEFAULT_BASS_VOL;
        trackVolumes[SND_BASS_D3] = DEFAULT_BASS_VOL;
        trackVolumes[SND_BASS_G2] = DEFAULT_BASS_VOL;
        trackVolumes[SND_TOM_HI] = DEFAULT_TOM_VOL;
        trackVolumes[SND_TOM_LOW] = DEFAULT_TOM_VOL;
        trackVolumes[SND_RIM] = DEFAULT_RIM_VOL;
        trackVolumes[SND_SHAKER] = DEFAULT_SHAKER_VOL;
    }

    private void clearTriggeredVoices() {
        clearDrumVoices();
        clearBassVoices();
        clearPianoVoices();
    }

    private void clearDrumVoices() {
        for (int i = 0; i < MAX_VOICES; i++) {
            int pos = voicePos.get(i);
            if (pos < 0) continue;
            int snd = voiceSound.get(i);
            if (snd >= 0 && snd < SND_BASS_E2) voicePos.set(i, -1);
        }
    }

    private void clearBassVoices() {
        for (int i = 0; i < MAX_VOICES; i++) {
            int pos = voicePos.get(i);
            if (pos < 0) continue;
            int snd = voiceSound.get(i);
            if (snd >= SND_BASS_E2 && snd <= SND_BASS_G2) voicePos.set(i, -1);
        }
    }

    private void clearPianoVoices() {
        for (int i = 0; i < MAX_PIANO_VOICES; i++) {
            pianoVoiceMode.set(i, PIANO_SYNTH_KEYS);
            pianoVoicePos.set(i, -1);
            pianoVoiceStartAt.set(i, 0);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int readLe16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int readLe16Signed(byte[] bytes, int offset) {
        return (short) readLe16(bytes, offset);
    }

    private static int readLe32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
