package com.example.thereminglovestest2;

/**
 * {@link PatternSource} backed by the user-programmed step grid from the Beat Maker.
 *
 * Row-to-sound mapping (mirrors {@link StepGridView#ROW_NAMES} order):
 * <pre>
 *   Row  0  KICK    → DrumEngine.SND_KICK
 *   Row  1  SNARE   → DrumEngine.SND_SNARE
 *   Row  2  HH-C    → DrumEngine.SND_HIHAT_C
 *   Row  3  HH-O    → DrumEngine.SND_HIHAT_O
 *   Row  4  CRASH   → DrumEngine.SND_CRASH
 *   Row  5  CLAP    → DrumEngine.SND_CLAP
 *   Row  6  BASS E  → bass note slot 0  (E2)
 *   Row  7  BASS A  → bass note slot 1  (A2)
 *   Row  8  BASS D  → bass note slot 2  (D3)
 *   Row  9  TOM H   → DrumEngine.SND_TOM_HI
 *   Row 10  TOM L   → DrumEngine.SND_TOM_LOW
 *   Row 11  RIM     → DrumEngine.SND_RIM
 *   Row 12  SHAKE   → DrumEngine.SND_SHAKER
 * </pre>
 *
 * Adding a new row to the Beat Maker grid
 * ─────────────────────────────────────────
 *   1. Append one entry to {@link #ROW_SOUND} (sound index, or -1 for bass).
 *   2. Append the corresponding entry to {@link #ROW_BASS_SLOT} (-1 for non-bass).
 *   3. Update {@link StepGridView} ROW_NAMES and ROW_COLORS.
 *   No other code changes are required.
 */
public final class GridPatternSource implements PatternSource {
    private static final int NUM_STEPS = 16;

    /**
     * Drum sound index for each grid row.
     * -1 marks a bass row — see {@link #ROW_BASS_SLOT} for the pitch slot.
     */
    private static final int[] ROW_SOUND = {
        DrumEngine.SND_KICK,      // row  0
        DrumEngine.SND_SNARE,     // row  1
        DrumEngine.SND_HIHAT_C,   // row  2
        DrumEngine.SND_HIHAT_O,   // row  3
        DrumEngine.SND_CRASH,     // row  4
        DrumEngine.SND_CLAP,      // row  5
        -1, -1, -1,               // rows 6-8: bass (handled via ROW_BASS_SLOT)
        DrumEngine.SND_TOM_HI,    // row  9
        DrumEngine.SND_TOM_LOW,   // row 10
        DrumEngine.SND_RIM,       // row 11
        DrumEngine.SND_SHAKER,    // row 12
    };

    /**
     * Bass note slot for bass rows; -1 for percussion rows.
     * Slot meaning: 0=E2, 1=A2, 2=D3.
     */
    private static final int[] ROW_BASS_SLOT = {
        -1, -1, -1, -1, -1, -1,   // rows 0-5: percussion
         0,  1,  2,               // rows 6-8: E2, A2, D3
        -1, -1, -1, -1,           // rows 9-12: percussion
    };

    /** The user-programmed step grid: [row][step16].  Volatile for thread-safe swap. */
    private volatile boolean[][] grid;
    /** Optional keyboard lane: MIDI note per step, or -1 for empty. */
    private volatile int[] pianoSteps;

    /**
     * @param grid  boolean[rows][16] from the Beat Maker; may have fewer rows than ROW_SOUND.
     */
    public GridPatternSource(boolean[][] grid) {
        this(grid, null);
    }

    public GridPatternSource(boolean[][] grid, int[] pianoSteps) {
        this.grid = (grid != null) ? grid : new boolean[0][0];
        setPianoSteps(pianoSteps);
    }

    /**
     * Replace the step grid atomically.  The change is visible to the sequencer on
     * the next clock tick.  Safe to call from any thread.
     */
    public void setGrid(boolean[][] grid) {
        this.grid = (grid != null) ? grid : new boolean[0][0];
    }

    public void setPianoSteps(int[] pianoSteps) {
        int[] copy = new int[NUM_STEPS];
        for (int i = 0; i < NUM_STEPS; i++) copy[i] = -1;
        if (pianoSteps != null) {
            System.arraycopy(pianoSteps, 0, copy, 0, Math.min(NUM_STEPS, pianoSteps.length));
        }
        this.pianoSteps = copy;
    }

    /** Returns the current grid reference (may be null after clearing). */
    public boolean[][] getGrid() { return grid; }

    // ── PatternSource ─────────────────────────────────────────────────────────

    @Override
    public void query(int step16, int bar,
                      boolean drumsOn, boolean bassOn,
                      TriggerDispatcher out) {
        boolean[][] g    = grid;
        int         rows = Math.min(g.length, ROW_SOUND.length);

        for (int row = 0; row < rows; row++) {
            boolean[] rowData = g[row];
            if (rowData == null || step16 >= rowData.length || !rowData[step16]) continue;

            int bassSlot = ROW_BASS_SLOT[row];
            if (bassSlot >= 0) {
                if (bassOn) out.fireBass(bassSlot);
            } else if (drumsOn && ROW_SOUND[row] >= 0) {
                out.fire(ROW_SOUND[row], 1.0f);
            }
        }

        int[] piano = pianoSteps;
        if (drumsOn && piano != null && step16 < piano.length && piano[step16] >= 0) {
            out.firePiano(piano[step16]);
        }
    }
}
