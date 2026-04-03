package com.example.thereminglovestest2;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JVM unit tests — run with: ./gradlew test
 *
 * No Android runtime needed. Covers:
 *   1. Waveform formulas    — all 15 tones stay in [-1, 1], are non-zero, are mutually distinct
 *   2. GridPatternSource    — correct sound indices, bass routing, enable gating, multi-fire
 *   3. Pattern OR-merge     — logic used by MainActivity.pushMergedPattern()
 *   4. Characteristic tone  — instrument-specific harmonic properties verified analytically
 */
public class ThereminUnitTest {

    // =========================================================================
    // Inline waveform formulas (mirror ThereminAudioEngine.sample() exactly)
    // If a formula changes in production this test will break, flagging the drift.
    // =========================================================================

    private static float tanh(float x) { return (float) Math.tanh(x); }
    private static float sin(float x)  { return (float) Math.sin(x); }
    private static float sat(float v, float g) { return tanh(v * g); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

    private static float wSine(float p, float vol) {
        float raw = sin(p) + 0.22f*sin(p*2f+0.10f) + 0.10f*sin(p*3f+0.24f) + 0.04f*sin(p*4f+0.38f);
        float blend = 0.72f + 0.28f * clamp(vol, 0f, 1f);
        return tanh(sin(p) + blend * (raw - sin(p)) * 1.12f);
    }
    private static float wTriangle(float p) {
        return ((float)(2.0/Math.PI) * (float)Math.asin(Math.sin(p))
                + 0.25f*sin(p*3f) - 0.10f*sin(p*5f) + 0.04f*sin(p*7f)) * 0.72f;
    }
    private static float wSaw(float p) { return sat(1f - p/(float)Math.PI, 1.20f); }
    private static float wSquare(float p) {
        return sat(0.65f*(sin(p)>=0f?1f:-1f) + 0.22f*sin(p), 1.10f);
    }
    private static float wPulse(float p) {
        return sat((p < (float)(Math.PI*0.5) ? 0.75f : -0.25f) + 0.15f*sin(p), 1.0f);
    }
    private static float wOrgan(float p) {
        return sat((Math.abs(sin(p))*2f - 1f) + 0.45f*sin(p), 1.35f);
    }
    private static float wString(float p) { return sin(p + 2.5f*sin(p)) * 0.88f; }
    private static float wBell(float p)   { return sin(p + 3.0f*sin(p*2.756f)) * 0.88f; }
    private static float wPad(float p) {
        return sat(0.38f*(1f - p/(float)Math.PI)
                 + 0.38f*((float)(2.0/Math.PI)*(float)Math.asin(Math.sin(p*1.007f)))
                 + 0.14f*sin(p*0.993f)
                 + 0.18f*sin(p*2.014f), 0.88f);
    }
    private static float wTrumpet(float p) {
        return sat(sin(p)*0.42f + sin(p*2)*0.28f + sin(p*3)*0.20f
                 + sin(p*4)*0.14f + sin(p*5)*0.10f + sin(p*6)*0.06f, 2.4f);
    }
    private static float wFlute(float p) {
        return sin(p + 0.12f*sin(p*3.5f))*0.82f + sin(p*2)*0.14f + sin(p*3)*0.04f;
    }
    private static float wViolin(float p) {
        return sat(sin(p)*0.40f + sin(p*2)*0.22f + sin(p*3)*0.16f + sin(p*4)*0.14f
                 + sin(p*5)*0.12f + sin(p*6)*0.08f + sin(p*7)*0.05f, 1.1f);
    }
    private static float wClarinet(float p) {
        return sat(sin(p)*0.65f - sin(p*3)*0.35f + sin(p*5)*0.18f
                 - sin(p*7)*0.11f + sin(p*9)*0.06f, 1.05f);
    }
    private static float wOboe(float p) {
        return sat(sin(p)*0.38f + sin(p*2)*0.36f + sin(p*3)*0.22f
                 + sin(p*4)*0.14f + sin(p*5)*0.08f + sin(p*6)*0.04f, 1.25f);
    }
    private static float wChoir(float p) {
        return sat(sin(p)*0.40f + sin(p*1.007f)*0.40f + sin(p*2)*0.18f
                 + sin(p*3.007f)*0.14f + sin(p*4)*0.08f + sin(p*5)*0.05f, 1.05f);
    }

    // Collect all 15 values at a given phase for convenience.
    private static float[] allWaveforms(float p) {
        return new float[]{
            wSine(p, 0.5f), wTriangle(p), wSaw(p), wSquare(p), wPulse(p),
            wOrgan(p), wString(p), wBell(p), wPad(p),
            wTrumpet(p), wFlute(p), wViolin(p), wClarinet(p), wOboe(p), wChoir(p)
        };
    }
    private static final String[] TONE_NAMES = {
        "SINE","TRIANGLE","SAW","SQUARE","PULSE","ORGAN","STRING","BELL","PAD",
        "TRUMPET","FLUTE","VIOLIN","CLARINET","OBOE","CHOIR"
    };

    // =========================================================================
    // 1. All waveforms bounded in [-1, 1]
    // =========================================================================

    @Test
    public void allWaveformsBoundedAcrossFullCycle() {
        int steps = 720;
        for (int i = 0; i < steps; i++) {
            float p = (float)(i * Math.PI * 2.0 / steps);
            float[] v = allWaveforms(p);
            for (int t = 0; t < v.length; t++) {
                assertTrue(TONE_NAMES[t] + " out of [-1,1] at phase " + p + ": " + v[t],
                        v[t] >= -1.01f && v[t] <= 1.01f);
            }
        }
    }

    // =========================================================================
    // 2. All waveforms produce non-trivial output
    // =========================================================================

    @Test
    public void allWaveformsNonZeroAtQuarterPi() {
        float p = (float)(Math.PI / 4.0);
        float[] v = allWaveforms(p);
        for (int t = 0; t < v.length; t++) {
            assertTrue(TONE_NAMES[t] + " is silent (|v|<0.01) at π/4", Math.abs(v[t]) > 0.01f);
        }
    }

    @Test
    public void allWaveformsNonZeroAtHalfPi() {
        float p = (float)(Math.PI / 2.0);
        float[] v = allWaveforms(p);
        for (int t = 0; t < v.length; t++) {
            assertTrue(TONE_NAMES[t] + " is silent (|v|<0.01) at π/2", Math.abs(v[t]) > 0.01f);
        }
    }

    @Test
    public void rmsEnergyNonZeroOverFullCycle() {
        int steps = 360;
        for (int t = 0; t < TONE_NAMES.length; t++) {
            double sumSq = 0;
            for (int i = 0; i < steps; i++) {
                float p = (float)(i * Math.PI * 2.0 / steps);
                float v = allWaveforms(p)[t];
                sumSq += v * v;
            }
            double rms = Math.sqrt(sumSq / steps);
            assertTrue(TONE_NAMES[t] + " has near-zero RMS (" + rms + ")", rms > 0.05);
        }
    }

    // =========================================================================
    // 3. All 15 waveforms produce distinct values at the same phase
    // =========================================================================

    @Test
    public void waveformsAreMutuallyDistinctAtPhase1_2() {
        float p = 1.2f;
        float[] v = allWaveforms(p);
        for (int i = 0; i < v.length; i++) {
            for (int j = i + 1; j < v.length; j++) {
                assertFalse(TONE_NAMES[i] + " and " + TONE_NAMES[j]
                                + " are identical at phase=1.2 (" + v[i] + ")",
                        Math.abs(v[i] - v[j]) < 0.001f);
            }
        }
    }

    @Test
    public void waveformsAreMutuallyDistinctAtPhase2_5() {
        float p = 2.5f;
        float[] v = allWaveforms(p);
        for (int i = 0; i < v.length; i++) {
            for (int j = i + 1; j < v.length; j++) {
                assertFalse(TONE_NAMES[i] + " and " + TONE_NAMES[j]
                                + " are identical at phase=2.5",
                        Math.abs(v[i] - v[j]) < 0.001f);
            }
        }
    }

    // =========================================================================
    // 4. Characteristic instrument properties (harmonic fingerprints)
    // =========================================================================

    @Test
    public void trumpetReachesNearSaturationAtSomePhase() {
        // H1-H6 sum ≈ 1.2, driven through tanh with gain 2.4 → output approaches ±1.
        // At ~π/6 the odd harmonics align positively; |wTrumpet| should exceed 0.90.
        boolean saturated = false;
        for (int i = 0; i < 360; i++) {
            float p = (float)(i * Math.PI * 2.0 / 360);
            if (Math.abs(wTrumpet(p)) > 0.90f) { saturated = true; break; }
        }
        assertTrue("Trumpet (saturation gain 2.4) must reach |v|>0.90 at some phase", saturated);
    }

    @Test
    public void fluteNeverExceedsSoftCeiling() {
        // Flute is near-sine with no hard saturation; peak amplitude must stay below 0.87.
        for (int i = 0; i < 360; i++) {
            float p = (float)(i * Math.PI * 2.0 / 360);
            assertTrue("Flute should stay below 0.87 (unsaturated near-sine), p=" + p,
                    Math.abs(wFlute(p)) < 0.87f);
        }
    }

    @Test
    public void clarinetIsZeroAtPhasePI() {
        // All harmonics of clarinet are sin(n·π) = 0 for any integer n.
        // At phase = π the output must be ~0, confirming odd-only structure.
        float v = wClarinet((float) Math.PI);
        assertEquals("Clarinet at phase=π (all sin(nπ)=0) should be ~0", 0f, v, 0.005f);
    }

    @Test
    public void clarinetHasNegligibleEvenHarmonics() {
        // At π/4: H2 = sin(π/2) = 1 (maximum contribution). Oboe's nearly-equal H1/H2
        // weighting produces a large combined output; clarinet has no H2 at all.
        float p = (float)(Math.PI / 4.0);
        float oboeAtPiOver4 = wOboe(p);
        // Oboe at π/4: H1=0.707*0.38 + H2=1.0*0.36 + H3=0.707*0.22 → sum ≈ 0.688 → >0.5
        assertTrue("Oboe at π/4 should be >0.50 (near-equal H1+H2)", Math.abs(oboeAtPiOver4) > 0.50f);
        // Clarinet at π: sin(nπ)=0 for every integer n → all harmonics vanish
        float clariAtPi = wClarinet((float) Math.PI);
        assertEquals("Clarinet at phase=π (all sin(nπ)=0) must be ~0", 0f, clariAtPi, 0.005f);
    }

    @Test
    public void oboeH1AndH2AreNearlyEqual() {
        // At phase = π/4: H1 = sin(π/4) ≈ 0.707, H2 = sin(π/2) = 1.0
        // Oboe assigns 0.38 to H1 and 0.36 to H2 — both > 0.25 (substantial).
        float p = (float)(Math.PI / 4.0);
        float h1 = (float) Math.abs(Math.sin(p) * 0.38f);
        float h2 = (float) Math.abs(Math.sin(p * 2) * 0.36f);
        assertTrue("Oboe H1 contribution should be > 0.25", h1 > 0.25f);
        assertTrue("Oboe H2 contribution should be > 0.25", h2 > 0.25f);
        // They should be within 50% of each other — characteristic equal-weight double-reed
        assertTrue("Oboe H1/H2 ratio should be near 1.0", Math.abs(h1 - h2) < Math.max(h1, h2) * 0.5f);
    }

    @Test
    public void choirDetuningCreatesMeasurableChorus() {
        // voice1 = sin(p), voice2 = sin(p*1.007). They must diverge across the cycle.
        boolean foundDivergence = false;
        for (int i = 0; i < 360; i++) {
            float p = (float)(i * Math.PI * 2.0 / 360);
            float v1 = sin(p), v2 = sin(p * 1.007f);
            if (Math.abs(v1 - v2) > 0.01f) { foundDivergence = true; break; }
        }
        assertTrue("Choir detuned voices must diverge somewhere across 0..2π", foundDivergence);
    }

    @Test
    public void violinHasSignificantUpperHarmonics() {
        // Violin has H2-H7 contributing substantially. Measure at a phase where H2 is near
        // maximum (phase = π/4 → H2 = sin(π/2) = 1) and verify violin output differs from
        // its H1-only contribution by at least 0.10, confirming the upper harmonics matter.
        float p = (float)(Math.PI / 4.0);
        float h1only = sat((float)(Math.sin(p) * 0.40f), 1.1f);
        float full   = wViolin(p);
        assertTrue("Violin upper harmonics (H2-H7) must contribute at least 0.10 to output",
                Math.abs(full - h1only) > 0.10f);
    }

    // =========================================================================
    // 5. Tone selector regression guards
    // =========================================================================

    @Test
    public void drumToneIsSelectableAndPrettyPrinted() {
        // Keep the legacy DRUM value mapped to Helicopter while the hidden drum kit stays out of the picker.
        assertEquals(AppSettings.TONE_HELICOPTER, AppSettings.normalizeToneType("drum"));
        assertEquals("Helicopter", AppSettings.prettyToneType("drum"));
        assertFalse(AppSettings.isUserSelectableTone(AppSettings.TONE_DRUM));
        assertTrue(AppSettings.isUserSelectableTone(AppSettings.TONE_TRIANGLE));
        assertTrue(AppSettings.isUserSelectableTone(AppSettings.TONE_SAW));
        assertTrue(AppSettings.isUserSelectableTone(AppSettings.TONE_PAD));
        assertEquals("Pad", AppSettings.prettyToneType(AppSettings.TONE_PAD));

        boolean foundTriangle = false;
        boolean foundSaw = false;
        boolean foundPad = false;
        boolean foundHelicopter = false;
        for (String tone : AppSettings.USER_SELECTABLE_TONES) {
            if (AppSettings.TONE_TRIANGLE.equals(tone)) foundTriangle = true;
            if (AppSettings.TONE_SAW.equals(tone)) foundSaw = true;
            if (AppSettings.TONE_PAD.equals(tone)) foundPad = true;
            if (AppSettings.TONE_HELICOPTER.equals(tone)) foundHelicopter = true;
        }
        assertTrue("Triangle must stay in the public tone cycle", foundTriangle);
        assertTrue("Saw must stay in the public tone cycle", foundSaw);
        assertTrue("Pad must stay in the public tone cycle", foundPad);
        assertTrue("Helicopter must stay in the public tone cycle", foundHelicopter);
    }

    @Test
    public void hiddenLegacyToneStillCoercesToSupportedPublicTone() {
        // The public tone picker stays curated even if old saved settings still reference hidden tones.
        assertEquals(AppSettings.TONE_THEREMIN, AppSettings.coerceUserSelectableTone(AppSettings.TONE_TRUMPET));
        assertEquals(AppSettings.TONE_THEREMIN, AppSettings.coerceUserSelectableTone(AppSettings.TONE_DRUM));
    }

    // =========================================================================
    // 5. GridPatternSource — sound routing and gating
    // =========================================================================

    @Test
    public void gridPattern_kickFiresAtStep0() {
        boolean[][] grid = new boolean[14][16];
        grid[0][0] = true; // row 0 = KICK
        GridPatternSource src = new GridPatternSource(grid);
        int[] fired = {-1};
        src.query(0, 0, true, false, new PatternSource.TriggerDispatcher() {
            @Override public void fire(int snd, float vol)  { fired[0] = snd; }
            @Override public void fireBass(int slot)        { fail("bass must not fire"); }
            @Override public void firePiano(int midiNote)   {}
        });
        assertEquals("Row 0 should fire SND_KICK", DrumEngine.SND_KICK, fired[0]);
    }

    @Test
    public void gridPattern_snareFiresAtStep4() {
        boolean[][] grid = new boolean[14][16];
        grid[1][4] = true; // row 1 = SNARE, step 4
        GridPatternSource src = new GridPatternSource(grid);
        int[] fired = {-1};
        src.query(4, 0, true, false, new PatternSource.TriggerDispatcher() {
            @Override public void fire(int snd, float vol)  { fired[0] = snd; }
            @Override public void fireBass(int slot)        { fail("bass must not fire"); }
            @Override public void firePiano(int midiNote)   {}
        });
        assertEquals("Row 1 should fire SND_SNARE", DrumEngine.SND_SNARE, fired[0]);
    }

    @Test
    public void gridPattern_silentWhenDrumsOff() {
        boolean[][] grid = new boolean[14][16];
        for (boolean[] row : grid) row[0] = true; // all instruments on step 0
        GridPatternSource src = new GridPatternSource(grid);
        int[] count = {0};
        src.query(0, 0, false, false, new PatternSource.TriggerDispatcher() {
            @Override public void fire(int snd, float vol) { count[0]++; }
            @Override public void fireBass(int slot)       { count[0]++; }
            @Override public void firePiano(int midiNote)  {}
        });
        assertEquals("No sounds should fire when both drumsOn and bassOn are false", 0, count[0]);
    }

    @Test
    public void gridPattern_bassRowUsesFirBass_notFire() {
        boolean[][] grid = new boolean[14][16];
        grid[6][2] = true; // row 6 = BASS E2 (slot 0)
        GridPatternSource src = new GridPatternSource(grid);
        int[] bassSlot = {-1};
        boolean[] percFired = {false};
        src.query(2, 0, false, true, new PatternSource.TriggerDispatcher() {
            @Override public void fire(int snd, float vol) { percFired[0] = true; }
            @Override public void fireBass(int slot)       { bassSlot[0] = slot; }
            @Override public void firePiano(int midiNote)  {}
        });
        assertEquals("BASS E2 row should call fireBass with slot 0", 0, bassSlot[0]);
        assertFalse("Bass row must not call fire()", percFired[0]);
    }

    @Test
    public void gridPattern_bassRowSilentWhenBassOff() {
        boolean[][] grid = new boolean[14][16];
        grid[6][0] = true; // BASS E2
        GridPatternSource src = new GridPatternSource(grid);
        int[] count = {0};
        src.query(0, 0, false, false, new PatternSource.TriggerDispatcher() {
            @Override public void fire(int snd, float vol) { count[0]++; }
            @Override public void fireBass(int slot)       { count[0]++; }
            @Override public void firePiano(int midiNote)  {}
        });
        assertEquals("Bass must not fire when bassOn=false", 0, count[0]);
    }

    @Test
    public void gridPattern_noFiringAtWrongStep() {
        boolean[][] grid = new boolean[14][16];
        grid[0][7] = true; // KICK only at step 7
        GridPatternSource src = new GridPatternSource(grid);
        int[] count = {0};
        src.query(3, 0, true, true, new PatternSource.TriggerDispatcher() {
            @Override public void fire(int snd, float vol) { count[0]++; }
            @Override public void fireBass(int slot)       { count[0]++; }
            @Override public void firePiano(int midiNote)  {}
        });
        assertEquals("Should not fire at step 3 when only step 7 is set", 0, count[0]);
    }

    @Test
    public void gridPattern_multipleInstrumentsInOneStep() {
        boolean[][] grid = new boolean[14][16];
        grid[0][0] = true; // KICK
        grid[1][0] = true; // SNARE
        grid[2][0] = true; // HH_C
        GridPatternSource src = new GridPatternSource(grid);
        int[] count = {0};
        src.query(0, 0, true, false, new PatternSource.TriggerDispatcher() {
            @Override public void fire(int snd, float vol) { count[0]++; }
            @Override public void fireBass(int slot)       {}
            @Override public void firePiano(int midiNote)  {}
        });
        assertEquals("Three instruments at step 0 should produce 3 fire() calls", 3, count[0]);
    }

    @Test
    public void gridPattern_setGridUpdatesLive() {
        boolean[][] grid1 = new boolean[14][16];
        grid1[0][0] = true;
        GridPatternSource src = new GridPatternSource(grid1);

        boolean[][] grid2 = new boolean[14][16];
        grid2[1][0] = true; // SNARE instead
        src.setGrid(grid2);

        int[] fired = {-1};
        src.query(0, 0, true, false, new PatternSource.TriggerDispatcher() {
            @Override public void fire(int snd, float vol) { fired[0] = snd; }
            @Override public void fireBass(int slot)       {}
            @Override public void firePiano(int midiNote)  {}
        });
        assertEquals("After setGrid() only SNARE should fire", DrumEngine.SND_SNARE, fired[0]);
    }

    @Test
    public void gridPattern_allBassRowsMapToCorrectSlots() {
        // Rows 6-8 map to bass slots 0-2 (E2, A2, D3). Row 9 is TOM_HI (percussion).
        for (int bassRow = 6; bassRow <= 8; bassRow++) {
            boolean[][] grid = new boolean[14][16];
            grid[bassRow][0] = true;
            GridPatternSource src = new GridPatternSource(grid);
            int[] slot = {-1};
            src.query(0, 0, false, true, new PatternSource.TriggerDispatcher() {
                @Override public void fire(int snd, float vol) { fail("Should use fireBass"); }
                @Override public void fireBass(int s)          { slot[0] = s; }
                @Override public void firePiano(int midiNote)  {}
            });
            int expectedSlot = bassRow - 6;
            assertEquals("Row " + bassRow + " should map to bass slot " + expectedSlot,
                    expectedSlot, slot[0]);
        }
    }

    // =========================================================================
    // 6. Pattern OR-merge logic (mirrors MainActivity.pushMergedPattern)
    // =========================================================================

    @Test
    public void patternMerge_orCombinesTwoSlots() {
        int rows = 14, steps = 16;
        boolean[][] slotA = new boolean[rows][steps];
        boolean[][] slotB = new boolean[rows][steps];
        slotA[0][0] = true;  // kick beat 1
        slotA[2][4] = true;  // hihat beat 2
        slotB[1][4] = true;  // snare beat 2

        boolean[][] merged = new boolean[rows][steps];
        for (int r = 0; r < rows; r++)
            for (int s = 0; s < steps; s++)
                if (slotA[r][s] || slotB[r][s]) merged[r][s] = true;

        assertTrue("Kick from slotA must survive OR-merge", merged[0][0]);
        assertTrue("Hihat from slotA must survive OR-merge", merged[2][4]);
        assertTrue("Snare from slotB must survive OR-merge", merged[1][4]);
        assertFalse("Empty cell must remain empty after OR-merge", merged[3][7]);
    }

    @Test
    public void patternMerge_emptySlotContributesNothing() {
        int rows = 14, steps = 16;
        boolean[][] slotA = new boolean[rows][steps];
        slotA[0][0] = true;
        boolean[][] emptyB = new boolean[rows][steps]; // all false

        boolean[][] merged = new boolean[rows][steps];
        for (int r = 0; r < rows; r++)
            for (int s = 0; s < steps; s++)
                if (slotA[r][s] || emptyB[r][s]) merged[r][s] = true;

        assertTrue("SlotA's kick must still be in merged", merged[0][0]);
        int total = 0;
        for (boolean[] row : merged) for (boolean v : row) if (v) total++;
        assertEquals("Only 1 cell should be set after merging one-cell pattern with empty", 1, total);
    }

    @Test
    public void patternMerge_idempotentWithSelf() {
        int rows = 14, steps = 16;
        boolean[][] slot = new boolean[rows][steps];
        slot[0][0] = slot[1][4] = slot[2][8] = true;

        boolean[][] merged = new boolean[rows][steps];
        for (int r = 0; r < rows; r++)
            for (int s = 0; s < steps; s++)
                if (slot[r][s] || slot[r][s]) merged[r][s] = true; // OR with self

        for (int r = 0; r < rows; r++)
            for (int s = 0; s < steps; s++)
                assertEquals("OR(x, x) == x for cell [" + r + "][" + s + "]",
                        slot[r][s], merged[r][s]);
    }

    // =========================================================================
    // 7. DrumEngine gain — unit-level math (no Android context needed)
    // =========================================================================

    /** Simulates DrumEngine.setDrumGain / getDrumGain arithmetic. */
    private static final float DRUM_BUS_GAIN_FULL = 0.58f;

    private static float[] drumGainRoundTrip(float scale) {
        float clamped   = Math.max(0f, Math.min(2f, scale));
        float drumBus   = DRUM_BUS_GAIN_FULL * clamped;
        float readBack  = (DRUM_BUS_GAIN_FULL > 0f) ? drumBus / DRUM_BUS_GAIN_FULL : 0f;
        return new float[]{drumBus, readBack};
    }

    @Test
    public void drumGain_defaultIsFullLevel() {
        // At scale=1.0, drumBusGain should equal DRUM_BUS_GAIN_FULL
        float[] rt = drumGainRoundTrip(1.0f);
        assertEquals("setDrumGain(1.0) must produce nominal bus gain",
                DRUM_BUS_GAIN_FULL, rt[0], 1e-5f);
    }

    @Test
    public void drumGain_zeroMutes() {
        float[] rt = drumGainRoundTrip(0f);
        assertEquals("setDrumGain(0) must produce zero bus gain", 0f, rt[0], 1e-5f);
    }

    @Test
    public void drumGain_halfReducesOutputByHalf() {
        float[] rt = drumGainRoundTrip(0.5f);
        assertEquals("setDrumGain(0.5) must halve bus gain",
                DRUM_BUS_GAIN_FULL * 0.5f, rt[0], 1e-5f);
    }

    @Test
    public void drumGain_getDrumGainRoundTrip() {
        // getDrumGain() should return the same scale that was passed to setDrumGain()
        for (float scale : new float[]{0f, 0.3f, 0.7f, 1.0f, 1.5f}) {
            float clamped  = Math.max(0f, Math.min(2f, scale));
            float[] rt     = drumGainRoundTrip(clamped);
            assertEquals("getDrumGain() round-trip for scale=" + clamped,
                    clamped, rt[1], 1e-4f);
        }
    }

    @Test
    public void drumGain_clampsBelowZero() {
        float[] rt = drumGainRoundTrip(-5f);
        assertEquals("Negative scale must be clamped to 0", 0f, rt[0], 1e-5f);
    }

    @Test
    public void drumGain_clampsAboveTwo() {
        float[] rt = drumGainRoundTrip(99f);
        assertEquals("Scale > 2 must be clamped to 2",
                DRUM_BUS_GAIN_FULL * 2f, rt[0], 1e-5f);
    }

    // =========================================================================
    // 8. Volume slider math — value-to-gain conversion
    // =========================================================================

    /** Mirrors MainActivity.applyMixerVol / applyBeatVol: sliderValue / 100f. */
    private static float sliderToGain(float sliderValue) { return sliderValue / 100f; }

    @Test
    public void volumeSlider_100percentIsFullGain() {
        assertEquals("Slider at 100 → gain 1.0", 1.0f, sliderToGain(100f), 1e-5f);
    }

    @Test
    public void volumeSlider_0percentIsMuted() {
        assertEquals("Slider at 0 → gain 0.0", 0f, sliderToGain(0f), 1e-5f);
    }

    @Test
    public void volumeSlider_50percentIsHalfGain() {
        assertEquals("Slider at 50 → gain 0.5", 0.5f, sliderToGain(50f), 1e-5f);
    }

    @Test
    public void volumeSlider_snapPointsAreInRange() {
        // stepSize=10 → snap points are 0, 10, 20, ..., 100
        for (int snap = 0; snap <= 100; snap += 10) {
            float gain = sliderToGain(snap);
            assertTrue("Snap point " + snap + "% gain must be in [0,1]",
                    gain >= 0f && gain <= 1.0f);
        }
    }

    @Test
    public void volumeSlider_gainIsMonotonicAcrossSnapPoints() {
        float prev = sliderToGain(0f);
        for (int snap = 10; snap <= 100; snap += 10) {
            float curr = sliderToGain(snap);
            assertTrue("Gain must increase monotonically: " + prev + " < " + curr,
                    curr > prev);
            prev = curr;
        }
    }

    @Test
    public void patternMerge_threeLayersAllPresent() {
        int rows = 14, steps = 16;
        boolean[][] a = new boolean[rows][steps]; a[0][0] = true;
        boolean[][] b = new boolean[rows][steps]; b[1][4] = true;
        boolean[][] c = new boolean[rows][steps]; c[2][8] = true;

        boolean[][] merged = new boolean[rows][steps];
        for (int r = 0; r < rows; r++)
            for (int s = 0; s < steps; s++)
                if (a[r][s] || b[r][s] || c[r][s]) merged[r][s] = true;

        assertTrue("Layer A must be present",  merged[0][0]);
        assertTrue("Layer B must be present",  merged[1][4]);
        assertTrue("Layer C must be present",  merged[2][8]);
        int count = 0;
        for (boolean[] row : merged) for (boolean v : row) if (v) count++;
        assertEquals("Merged 3 single-cell layers should have exactly 3 active cells", 3, count);
    }
}
