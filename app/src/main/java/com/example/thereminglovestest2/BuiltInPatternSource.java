package com.example.thereminglovestest2;

import java.util.Random;

/**
 * {@link PatternSource} backed by the built-in genre preset library.
 *
 * This class owns all data and logic that was previously embedded inside DrumEngine:
 *   • 8 drum beat patterns (32 steps = 2-bar phrases)
 *   • 8 chord-bass patterns (16 steps, chord changes every 2 bars)
 *   • Hi-hat humanization (random per-hit velocity in a realistic range)
 *   • 8-bar phrase structure: drum fill in bar 7, crash on bar 8N boundary
 *   • Clap loop accent pattern (rhythmic ghost-clap overlay)
 *   • Clap variant rotation (4 slightly different clap timbres)
 *
 * Adding a new genre
 * ──────────────────
 *   1. Append one entry to {@link #DRUM_PATTERNS}    (6 rows × 32 steps).
 *   2. Append one entry to {@link #CHORD_BASS_PATTERNS} (4 chords × 16 steps).
 *   3. Append the name to {@link #GENRE_NAMES}.
 *   No other code changes required.
 *
 * Clap variant injection
 * ──────────────────────
 * Synthesis lives in DrumEngine (it owns all PCM generation).  Before firing a clap,
 * this class calls {@link ClapPcmUpdater#update} with the chosen variant PCM so the
 * engine's soundPcm table is correct for the next mix call.
 */
public final class BuiltInPatternSource implements PatternSource {

    /** Callback supplied by DrumEngine to swap the active clap PCM buffer. */
    public interface ClapPcmUpdater {
        void update(float[] variantPcm);
    }

    // ── Genre names ───────────────────────────────────────────────────────────

    public static final String[] GENRE_NAMES =
        { "Rock", "Funk", "EDM", "Hip-Hop", "Reggae", "Jazz", "Trap", "Latin" };

    // ── Arpeggio patterns (referenced by DrumEngine's piano system) ───────────

    public static final int[][] ARPEGGIO_PATTERNS = {
        {},                       // 0: unused  (OFF — single-note mode)
        {0, 4, 7, 12},            // 1: Major  up
        {0, 3, 7, 12},            // 2: Minor  up
        {0, 2, 4, 7, 9, 12},      // 3: Pentatonic run
        {0, 4, 7, 12, 7, 4},      // 4: Major up & back
    };

    // ── Drum patterns ─────────────────────────────────────────────────────────
    // DRUM_PATTERNS[genre][drumRow][step32]
    // drumRow: 0=kick, 1=snare, 2=HH-closed, 3=HH-open, 4=crash, 5=clap
    // 32 steps = two 4/4 bars of 16th notes.
    // Beat grid within each bar:  beat1=0, beat2=4, beat3=8, beat4=12
    //   e-of-beat=+1, &-of-beat=+2, a-of-beat=+3
    private static final boolean[][][] DRUM_PATTERNS = {
        { // 0: Classic Rock back-beat
            { true, false,false,false, false,false,false,false, true, false,false,false, false,false,false,false,
              true, false,false,false, false,false,false,false, true, false,false,false, false,false,false,true  },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false },
            { true, false,true, false, true, false,true, false, true, false,true, false, true, false,true, false,
              true, false,true, false, true, false,true, false, true, false,true, false, true, false,true, false },
            { false,false,false,false, false,false,true, false, false,false,false,false, false,false,true, false,
              false,false,false,false, false,false,true, false, false,false,false,false, false,false,false,false },
            { true, false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false },
        },
        { // 1: Classic Funk pocket
            { true, false,false,true, false,false,true, false, false,false,false,true, false,false,false,false,
              true, false,false,true, false,false,false,false, false,false,false,true, false,true, false,false  },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,true, false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,true, false },
            { true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true,
              true, true, true, true, true, true, true, true, true, true, true, true, true, true, true, true  },
            { false,false,false,false, false,false,true, false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, true, false,false,false, false,false,true, false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,true, false, true, false,false,false },
        },
        { // 2: Disco / House  (4-on-floor kick)
            { true, false,false,false, true, false,false,false, true, false,false,false, true, false,false,false,
              true, false,false,false, true, false,false,true,  true, false,false,false, true, false,false,false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,true, false, false,false,true, false, false,false,true, false, false,false,true, false,
              true, false,true, false, false,false,true, false, false,false,true, false, false,false,true, false },
            { true, false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false },
        },
        { // 3: Boom-Bap hip-hop
            { true, false,false,true,  false,false,false,false, false,false,false,false, false,false,false,false,
              true, false,false,false, false,false,false,false, false,false,false,true,  false,false,true, false },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false },
            { true, false,true, false, true, false,true, false, true, false,true, false, true, false,true, false,
              true, false,true, false, true, false,true, false, true, false,true, false, true, false,true, false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,true, false, false,false,false,false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false },
        },
        { // 4: One-Drop Reggae
            { false,false,false,false, false,false,false,false, true, false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, true, false,false,false, false,false,false,true  },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false },
            { true, false,true, false, true, false,true, false, true, false,true, false, true, false,true, false,
              true, false,true, false, true, false,true, false, true, false,true, false, true, false,true, false },
            { false,false,true, false, false,false,true, false, false,false,true, false, false,false,true, false,
              false,false,true, false, false,false,true, false, false,false,true, false, false,false,true, false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
        },
        { // 5: Shuffle / Swing
            { true, false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, true, false,false,false, false,false,true, false },
            { false,false,false,false, false,false,false,true,  false,false,false,false, false,false,false,true,
              false,false,false,false, false,false,false,true,  false,false,false,false, false,false,false,true  },
            { true, false,true, false, true, false,true, false, true, false,true, false, true, false,true, false,
              true, false,true, false, true, false,true, false, true, false,true, false, true, false,true, false },
            { false,false,false,true,  false,false,false,true,  false,false,false,true,  false,false,false,true,
              false,false,false,true,  false,false,false,true,  false,false,false,true,  false,false,false,true  },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
        },
        { // 6: Motown / Driving Pop
            { true, false,false,false, true, false,false,false, true, false,false,false, true, false,false,false,
              true, false,false,false, false,false,true, false, true, false,false,false, false,false,false,false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { true, true, true, true,  true, true, true, true,  true, true, true, true,  true, true, true, true,
              true, true, true, true,  true, true, true, true,  true, true, true, true,  true, true, true, true  },
            { false,false,true, false, false,false,false,false, false,false,true, false, false,false,false,false,
              false,false,false,false, false,false,true, false, false,false,false,false, false,false,true, false },
            { false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false },
        },
        { // 7: Latin / Clave  (3-2 clave in bar 1, 2-3 in bar 2)
            { true, false,false,true,  false,false,true, false, false,false,true, false, false,true, false,false,
              false,true, false,false, false,false,true, false, false,false,false,false, true, false,false,true  },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false },
            { true, true, true, true,  true, true, true, true,  true, true, true, true,  true, true, true, true,
              true, true, true, true,  true, true, true, true,  true, true, true, true,  true, true, true, true  },
            { false,false,false,false, false,false,true, false, false,false,false,false, false,false,true, false,
              false,false,false,false, false,false,true, false, false,false,false,false, false,false,true, false },
            { true, false,false,false, false,false,false,false, false,false,false,false, false,false,false,false,
              false,false,false,false, false,false,false,false, false,false,false,false, false,false,false,false },
            { false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false,
              false,false,false,false, true, false,false,false, false,false,false,false, true, false,false,false },
        },
    };

    // ── Chord-bass patterns ───────────────────────────────────────────────────
    // CHORD_BASS_PATTERNS[genre][chordIdx][step16]
    // Note slots: 0=E2, 1=A2, 2=D3, 3=G2.  -1 = rest.
    // Chord progression  0=Am, 1=G, 2=Dm, 3=Em  — repeats every 2 bars (8 bars total).
    private static final int[][][] CHORD_BASS_PATTERNS = {
        { // 0 Rock  — root on beat 1, chord-tone answer on beat 3
            { 1,-1,-1,-1,-1,-1,-1,-1, 0,-1,-1,-1,-1,-1,-1,-1 },
            { 3,-1,-1,-1,-1,-1,-1,-1, 2,-1,-1,-1,-1,-1,-1,-1 },
            { 2,-1,-1,-1,-1,-1,-1,-1, 1,-1,-1,-1,-1,-1,-1,-1 },
            { 0,-1,-1,-1,-1,-1,-1,-1, 3,-1,-1,-1,-1,-1,-1,-1 },
        },
        { // 1 Funk  — root, fifth, colour tone, fifth
            { 1,-1,-1,-1,-1,0,-1,-1, 3,-1,-1,-1,-1,0,-1,-1 },
            { 3,-1,-1,-1,-1,2,-1,-1, 0,-1,-1,-1,-1,2,-1,-1 },
            { 2,-1,-1,-1,-1,1,-1,-1, 0,-1,-1,-1,-1,1,-1,-1 },
            { 0,-1,-1,-1,-1,3,-1,-1, 2,-1,-1,-1,-1,3,-1,-1 },
        },
        { // 2 EDM  — root / fifth alternation with repeatable hook
            { 1,-1,-1,-1,0,-1,-1,-1, 1,-1,-1,-1,3,-1,-1,-1 },
            { 3,-1,-1,-1,2,-1,-1,-1, 3,-1,-1,-1,0,-1,-1,-1 },
            { 2,-1,-1,-1,1,-1,-1,-1, 2,-1,-1,-1,0,-1,-1,-1 },
            { 0,-1,-1,-1,3,-1,-1,-1, 0,-1,-1,-1,2,-1,-1,-1 },
        },
        { // 3 Hip-Hop  — sparse root + late answering tone
            { 1,-1,-1,-1,-1,-1,-1,-1,-1,-1,3,-1,-1,-1,-1,-1 },
            { 3,-1,-1,-1,-1,-1,-1,-1,-1,-1,2,-1,-1,-1,-1,-1 },
            { 2,-1,-1,-1,-1,-1,-1,-1,-1,-1,1,-1,-1,-1,-1,-1 },
            { 0,-1,-1,-1,-1,-1,-1,-1,-1,-1,3,-1,-1,-1,-1,-1 },
        },
        { // 4 Reggae  — one-drop: root centered on beat 3
            { -1,-1,-1,-1,-1,-1,-1,-1, 1,-1,-1,-1,-1,-1,-1,-1 },
            { -1,-1,-1,-1,-1,-1,-1,-1, 3,-1,-1,-1,-1,-1,-1,-1 },
            { -1,-1,-1,-1,-1,-1,-1,-1, 2,-1,-1,-1,-1,-1,-1,-1 },
            { -1,-1,-1,-1,-1,-1,-1,-1, 0,-1,-1,-1,-1,-1,-1,-1 },
        },
        { // 5 Jazz  — walking shape, each bar moves toward next chord
            { 1,-1,-1,-1,0,-1,-1,-1, 3,-1,-1,-1,0,-1,-1,-1 },
            { 3,-1,-1,-1,2,-1,-1,-1, 0,-1,-1,-1,2,-1,-1,-1 },
            { 2,-1,-1,-1,1,-1,-1,-1, 0,-1,-1,-1,1,-1,-1,-1 },
            { 0,-1,-1,-1,3,-1,-1,-1, 2,-1,-1,-1,3,-1,-1,-1 },
        },
        { // 6 Trap  — minimal 808: root on beat 1 only
            { 1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1 },
            { 3,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1 },
            { 2,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1 },
            { 0,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1,-1 },
        },
        { // 7 Latin  — clave rhythm with root/fifth answer
            { 1,-1,-1,0,-1,-1,-1,-1, 1,-1,3,-1,-1,-1,-1,-1 },
            { 3,-1,-1,2,-1,-1,-1,-1, 3,-1,0,-1,-1,-1,-1,-1 },
            { 2,-1,-1,1,-1,-1,-1,-1, 2,-1,0,-1,-1,-1,-1,-1 },
            { 0,-1,-1,3,-1,-1,-1,-1, 0,-1,2,-1,-1,-1,-1,-1 },
        },
    };

    // ── State ─────────────────────────────────────────────────────────────────

    private volatile int     drumPatternIdx  = 0;
    private volatile int     bassPatternIdx  = 0;
    private volatile boolean clapLoopEnabled = false;

    private final Random      humanizeRng    = new Random();
    private final float[][]   clapVariants;          // reference owned by DrumEngine
    private int               clapVariantCursor = 0;
    private final ClapPcmUpdater clapPcmUpdater;     // callback into DrumEngine's soundPcm[]

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * @param clapVariants   DrumEngine's clap variant PCM array (4 entries, may be null initially)
     * @param clapPcmUpdater called before each clap trigger to set the variant PCM in soundPcm[]
     */
    public BuiltInPatternSource(float[][] clapVariants, ClapPcmUpdater clapPcmUpdater) {
        this.clapVariants    = clapVariants;
        this.clapPcmUpdater  = clapPcmUpdater;
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    public void setDrumPattern(int idx) {
        drumPatternIdx = Math.max(0, Math.min(DRUM_PATTERNS.length - 1, idx));
    }
    public void setBassPattern(int idx) {
        bassPatternIdx = Math.max(0, Math.min(CHORD_BASS_PATTERNS.length - 1, idx));
    }
    public void setGenre(int idx) {
        int clamped    = Math.max(0, Math.min(DRUM_PATTERNS.length - 1, idx));
        drumPatternIdx = clamped;
        bassPatternIdx = clamped;
    }

    public int  getDrumPattern()       { return drumPatternIdx; }
    public int  getBassPattern()       { return bassPatternIdx; }
    public int  getPatternCount()      { return DRUM_PATTERNS.length; }

    public void    setClapLoopEnabled(boolean on) { clapLoopEnabled = on; }
    public boolean isClapLoopEnabled()            { return clapLoopEnabled; }

    // ── PatternSource ─────────────────────────────────────────────────────────

    @Override
    public void query(int step16, int bar,
                      boolean drumsOn, boolean bassOn,
                      TriggerDispatcher out) {

        // Map step16 + bar into the 32-step pattern phrase.
        // (bar % 2) selects the first or second bar of the 2-bar pattern.
        int step32 = (bar % 2) * 16 + step16;

        boolean[][] pat     = DRUM_PATTERNS[drumPatternIdx];
        int[]       bassPat = CHORD_BASS_PATTERNS[bassPatternIdx][(bar / 2) % 4];

        if (drumsOn) {
            boolean isFillBar  = (bar % 8 == 7);
            boolean isFillStep = isFillBar && step16 >= 12;
            boolean isCrash    = (bar % 8 == 0) && step16 == 0 && bar > 0;

            if (isFillStep) {
                // Replace the last 4 steps of every 8th bar with a drum fill.
                if (step16 == 12) out.fire(DrumEngine.SND_KICK,    1.0f);
                if (step16 >= 13) out.fire(DrumEngine.SND_SNARE,   1.0f);
                out.fire(DrumEngine.SND_HIHAT_C, 1.0f);
            } else {
                if (pat[0][step32]) out.fire(DrumEngine.SND_KICK,    1.0f);
                if (pat[1][step32]) out.fire(DrumEngine.SND_SNARE,   1.0f);
                if (pat[2][step32]) out.fire(DrumEngine.SND_HIHAT_C,
                                             0.65f + humanizeRng.nextFloat() * 0.35f);
                if (pat[3][step32]) out.fire(DrumEngine.SND_HIHAT_O, 1.0f);
                if (pat[4][step32]) out.fire(DrumEngine.SND_CRASH,   1.0f);
                if (pat[5][step32]) fireClapVariant(1.0f, out);
            }
            if (isCrash) out.fire(DrumEngine.SND_CRASH, 1.0f);
        }

        if (clapLoopEnabled) maybeTriggerClapLoop(step16, bar, out);

        if (bassOn && bassPat[step16] >= 0) {
            out.fireBass(bassPat[step16]);
        }
    }

    // ── Clap variant rotation ─────────────────────────────────────────────────

    /**
     * Rotate to the next clap variant, update the engine's soundPcm table, then fire.
     * Four variants keep the clap from sounding identically mechanical every hit.
     */
    private void fireClapVariant(float volScale, TriggerDispatcher out) {
        int variant = clapVariantCursor++ & 3;
        if (clapVariants != null && clapVariants[variant] != null && clapPcmUpdater != null)
            clapPcmUpdater.update(clapVariants[variant]);
        out.fire(DrumEngine.SND_CLAP, volScale);
    }

    /**
     * Ghost-clap loop: a rhythmic accent pattern layered on top of the beat,
     * adding a periodic handclap feel that evolves bar-by-bar over an 8-bar phrase.
     */
    private void maybeTriggerClapLoop(int step16, int bar, TriggerDispatcher out) {
        int phraseBar = bar % 8;
        if (step16 == 4 || step16 == 12) {
            float base = (step16 == 4 ? 0.82f : 0.92f)
                       + (phraseBar % 2 == 0 ? 0.04f : -0.02f);
            fireClapVariant(base + humanizeRng.nextFloat() * 0.08f, out);
            return;
        }
        // Bar-specific ghost hits for variety
        if      (phraseBar == 3 && step16 == 11) fireClapVariant(0.42f + humanizeRng.nextFloat() * 0.06f, out);
        else if (phraseBar == 5 && step16 == 13) fireClapVariant(0.36f + humanizeRng.nextFloat() * 0.08f, out);
        else if (phraseBar == 7 && step16 ==  3) fireClapVariant(0.30f + humanizeRng.nextFloat() * 0.05f, out);
    }
}
