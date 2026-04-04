package com.example.thereminglovestest2;

/**
 * Minimal JVM-test helper that mirrors the Beat Maker row routing used by DrumEngine custom
 * patterns. It exists only so JVM tests can validate row-to-sound mapping without
 * standing up the Android activity layer.
 */
final class GridPatternSource implements PatternSource {
    private static final int[] ROW_SOUNDS = {
            DrumEngine.SND_KICK,
            DrumEngine.SND_SNARE,
            DrumEngine.SND_HIHAT_C,
            DrumEngine.SND_HIHAT_O,
            DrumEngine.SND_CRASH,
            DrumEngine.SND_CLAP,
            DrumEngine.SND_BASS_E2,
            DrumEngine.SND_BASS_A2,
            DrumEngine.SND_BASS_D3,
            DrumEngine.SND_TOM_HI,
            DrumEngine.SND_TOM_LOW,
            DrumEngine.SND_RIM,
            DrumEngine.SND_SHAKER
    };

    private boolean[][] grid;

    GridPatternSource(boolean[][] grid) {
        setGrid(grid);
    }

    void setGrid(boolean[][] grid) {
        if (grid == null) {
            this.grid = null;
            return;
        }
        boolean[][] copy = new boolean[grid.length][];
        for (int i = 0; i < grid.length; i++) {
            copy[i] = grid[i] != null ? grid[i].clone() : new boolean[0];
        }
        this.grid = copy;
    }

    @Override
    public void query(int step16, int bar, boolean drumsOn, boolean bassOn,
                      TriggerDispatcher dispatcher) {
        if (dispatcher == null || grid == null || step16 < 0) return;

        int rowCount = Math.min(grid.length, ROW_SOUNDS.length);
        for (int row = 0; row < rowCount; row++) {
            boolean[] rowData = grid[row];
            if (rowData == null || step16 >= rowData.length || !rowData[step16]) continue;

            if (row >= 6 && row <= 8) {
                if (bassOn) dispatcher.fireBass(row - 6);
                continue;
            }
            if (drumsOn) dispatcher.fire(ROW_SOUNDS[row], 1.0f);
        }
    }
}
