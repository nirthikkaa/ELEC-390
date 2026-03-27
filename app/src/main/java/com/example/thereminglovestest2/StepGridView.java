package com.example.thereminglovestest2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

/**
 * Custom Canvas-based 9×16 step sequencer grid.
 *
 * Draws instrument labels on the left, 16 step cells to the right grouped into
 * four beat groups of four (visual gaps + alternating backgrounds).
 * Handles tap-to-toggle and drag-to-paint-fill touch gestures.
 * Playhead column is highlighted by calling setPlayheadStep() from the UI thread.
 *
 * Replaces the ToggleButton-based approach which suffered from Material3
 * overriding custom backgrounds and height-alignment issues between two
 * separate LinearLayouts.
 */
public class StepGridView extends View {

    public interface Listener {
        /** Called when the user toggles a step. The view's internal state is already updated. */
        void onStepChanged(int row, int step, boolean active);
        /** Called when the user taps the instrument label (audition the sound). */
        void onRowLabelTapped(int row);
    }

    static final int NUM_ROWS  = 13;
    static final int NUM_STEPS = 16;

    // Layout constants (dp)
    private static final float LABEL_W_DP  = 56f;
    private static final float CELL_H_DP   = 33f;
    private static final float BEAT_GAP_DP =  5f;  // gap between each group of 4 steps
    private static final float CELL_PAD_DP =  2f;   // inset inside each cell
    private static final float CORNER_R_DP =  4f;

    // Row accent colours — match the DJ console palette in activity_main.xml
    private static final int[] ROW_COLORS = {
        0xFF00FF9D,  // KICK    green
        0xFFFF9D00,  // SNARE   orange
        0xFF00E5FF,  // HH-C    cyan
        0xFF7EB8FF,  // HH-O    blue
        0xFFFFD84D,  // CRASH   yellow
        0xFFFFD84D,  // CLAP    yellow
        0xFFCC44FF,  // BASS E  purple
        0xFFCC44FF,  // BASS A  purple
        0xFFCC44FF,  // BASS D  purple
        0xFFFF6633,  // TOM H   red-orange
        0xFFFF3333,  // TOM L   red
        0xFFFF99CC,  // RIM     pink
        0xFF99FF44,  // SHAKE   lime
    };

    static final String[] ROW_NAMES = {
        "KICK", "SNARE", "HH-C", "HH-O", "CRASH", "CLAP",
        "BASS E", "BASS A", "BASS D",
        "TOM H", "TOM L", "RIM", "SHAKE"
    };

    // ── State ─────────────────────────────────────────────────────────────────

    private final boolean[][] steps   = new boolean[NUM_ROWS][NUM_STEPS];
    private volatile int playheadStep = -1;
    private Listener listener;

    // ── Layout dimensions (computed in onSizeChanged, px) ─────────────────────

    private float density;
    private float labelW;   // label column width
    private float cellW;    // step cell width (excluding beat gaps)
    private float cellH;    // row height
    private float beatGap;  // gap between beat groups
    private float cellPad;  // inset inside each cell
    private float cornerR;  // rounded corner radius

    // ── Drag-paint ────────────────────────────────────────────────────────────
    // When the user drags across a row, all crossed steps are painted to the same
    // state as the first step they tapped (active or inactive).

    private boolean dragActive     = false;
    private int     dragRow        = -1;
    private boolean dragPaintState = false;

    // ── Paints (allocated once, reused per draw) ──────────────────────────────

    private final Paint fillP    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokeP  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelBgP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textP    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sepP     = new Paint();
    private final Paint altBgP   = new Paint();
    private final RectF rf       = new RectF(); // reused to avoid allocation in onDraw

    // ── Constructors ──────────────────────────────────────────────────────────

    public StepGridView(Context ctx) { super(ctx); init(ctx); }
    public StepGridView(Context ctx, AttributeSet a) { super(ctx, a); init(ctx); }
    public StepGridView(Context ctx, AttributeSet a, int s) { super(ctx, a, s); init(ctx); }

    private void init(Context ctx) {
        setClickable(true);
        density = ctx.getResources().getDisplayMetrics().density;
        // Hardware layer: GPU composites the grid drawing, reducing CPU load per frame
        setLayerType(LAYER_TYPE_HARDWARE, null);

        strokeP.setStyle(Paint.Style.STROKE);
        strokeP.setStrokeWidth(1.5f * density);

        labelBgP.setStyle(Paint.Style.FILL);

        textP.setTypeface(Typeface.MONOSPACE);
        textP.setFakeBoldText(true);
        textP.setTextAlign(Paint.Align.CENTER);
        textP.setTextSize(8f * density);

        sepP.setStyle(Paint.Style.STROKE);
        sepP.setStrokeWidth(1f * density);

        altBgP.setColor(0xFF0F0F24);  // slightly lighter than 0x0B0B1A for even groups
    }

    // ── Measurement & layout ──────────────────────────────────────────────────

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int desired = Math.round(CELL_H_DP * density * NUM_ROWS);
        int hMode = MeasureSpec.getMode(heightSpec);
        int hSize = MeasureSpec.getSize(heightSpec);
        int h = (hMode == MeasureSpec.EXACTLY) ? hSize
              : (hMode == MeasureSpec.AT_MOST)  ? Math.min(desired, hSize)
              :                                    desired;
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        density  = getContext().getResources().getDisplayMetrics().density;
        labelW   = LABEL_W_DP  * density;
        beatGap  = BEAT_GAP_DP * density;
        cellPad  = CELL_PAD_DP * density;
        cornerR  = CORNER_R_DP * density;
        cellH    = (float) h / NUM_ROWS;
        // 16 cells + 3 inter-group gaps fit in the step area
        cellW    = (w - labelW - 3f * beatGap) / NUM_STEPS;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        if (cellW <= 0 || cellH <= 0) return;

        int W = getWidth(), H = getHeight();
        canvas.drawColor(0xFF0B0B1A);

        // Alternating beat-group backgrounds (groups 0 and 2 are slightly lighter)
        for (int g = 0; g < 4; g += 2) {
            float x0 = labelW + g * (4f * cellW + beatGap);
            canvas.drawRect(x0, 0, x0 + 4f * cellW, H, altBgP);
        }

        // Step cells
        for (int r = 0; r < NUM_ROWS; r++) {
            float y0 = r * cellH;
            for (int s = 0; s < NUM_STEPS; s++) {
                drawCell(canvas, labelW + stepX(s), y0, r, steps[r][s], s == playheadStep);
            }
        }

        // Beat-group dividers (vertical lines between groups)
        sepP.setColor(0xFF1A2A1A);
        sepP.setStrokeWidth(1f * density);
        for (int sep = 1; sep < 4; sep++) {
            float x = labelW + sep * (4f * cellW + beatGap) - beatGap * 0.5f;
            canvas.drawLine(x, 0, x, H, sepP);
        }

        // Major section separators
        sepP.setStrokeWidth(2f * density);
        // Drum / bass separator (between rows 5 and 6)
        sepP.setColor(0xFF2A2A50);
        canvas.drawLine(labelW, 6f * cellH, W, 6f * cellH, sepP);
        // Bass / extra percussion separator (between rows 8 and 9)
        sepP.setColor(0xFF2A1A3A);
        canvas.drawLine(labelW, 9f * cellH, W, 9f * cellH, sepP);

        // Subtle row separators
        sepP.setColor(0xFF131325);
        sepP.setStrokeWidth(1f * density);
        for (int r = 1; r < NUM_ROWS; r++) {
            if (r == 6 || r == 9) continue; // already drawn above
            canvas.drawLine(0, r * cellH, W, r * cellH, sepP);
        }

        // Instrument labels
        Paint.FontMetrics fm = textP.getFontMetrics();
        float textShift = -(fm.ascent + fm.descent) / 2f;
        for (int r = 0; r < NUM_ROWS; r++) {
            float y0 = r * cellH;
            float cy = y0 + cellH / 2f;
            int   c  = ROW_COLORS[r];

            // Pill background
            float px = cellPad * 2.5f, py = cellH * 0.14f;
            rf.set(px, y0 + py, labelW - px, y0 + cellH - py);
            labelBgP.setColor((c & 0x00FFFFFF) | 0x22000000);
            canvas.drawRoundRect(rf, cornerR * 1.5f, cornerR * 1.5f, labelBgP);

            textP.setColor(c);
            canvas.drawText(ROW_NAMES[r], labelW / 2f, cy + textShift, textP);
        }
    }

    private void drawCell(Canvas canvas, float x0, float y0,
                          int row, boolean active, boolean playhead) {
        int color = ROW_COLORS[row];
        rf.set(x0 + cellPad, y0 + cellPad, x0 + cellW - cellPad, y0 + cellH - cellPad);

        if (playhead) {
            fillP.setColor(0xCCFFFFFF);
            strokeP.setColor(0xFFFFFFFF);
        } else if (active) {
            fillP.setColor((color & 0x00FFFFFF) | 0x8C000000); // row colour ~55% alpha
            strokeP.setColor(color);
        } else {
            fillP.setColor(0xFF0D0D22);
            strokeP.setColor((color & 0x00FFFFFF) | 0x33000000); // dim colour border
        }
        canvas.drawRoundRect(rf, cornerR, cornerR, fillP);
        canvas.drawRoundRect(rf, cornerR, cornerR, strokeP);
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    /** X offset (from start of step area) of step s. */
    private float stepX(int s) {
        return (s / 4) * (4f * cellW + beatGap) + (s % 4) * cellW;
    }

    /** Step index (0-15) for x within the step area, or -1 if in a beat gap. */
    private int xToStep(float stepAreaX) {
        for (int g = 0; g < 4; g++) {
            float gx0 = g * (4f * cellW + beatGap);
            float gx1 = gx0 + 4f * cellW;
            if (stepAreaX >= gx0 && stepAreaX < gx1)
                return g * 4 + Math.min((int) ((stepAreaX - gx0) / cellW), 3);
        }
        return -1; // in a beat gap — snap to nearest if needed
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (cellW <= 0 || cellH <= 0) return true;

        float tx = e.getX(), ty = e.getY();
        int row = Math.max(0, Math.min(NUM_ROWS - 1, (int) (ty / cellH)));

        switch (e.getActionMasked()) {

            case MotionEvent.ACTION_DOWN:
                if (tx < labelW) {
                    // Tapped the label — audition sound
                    if (listener != null) listener.onRowLabelTapped(row);
                } else {
                    int col = resolveCol(tx - labelW);
                    if (col >= 0) {
                        dragActive     = true;
                        dragRow        = row;
                        dragPaintState = !steps[row][col];
                        steps[row][col] = dragPaintState;
                        if (listener != null) listener.onStepChanged(row, col, dragPaintState);
                        invalidate();
                    }
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragActive) return true;
                // Paint in the locked row only (ignore row changes mid-drag)
                int col = resolveCol(tx - labelW);
                if (col >= 0 && steps[dragRow][col] != dragPaintState) {
                    steps[dragRow][col] = dragPaintState;
                    if (listener != null) listener.onStepChanged(dragRow, col, dragPaintState);
                    invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragActive = false;
                return true;
        }
        return super.onTouchEvent(e);
    }

    /**
     * Resolves the step column for an X position within the step area.
     * When the touch is in a beat gap, snaps to the nearest adjacent step.
     */
    private int resolveCol(float stepAreaX) {
        if (stepAreaX < 0) return 0;
        int direct = xToStep(stepAreaX);
        if (direct >= 0) return direct;
        // In a beat gap — find which group boundary we're near
        for (int g = 1; g < 4; g++) {
            float gapCenter = g * (4f * cellW + beatGap) - beatGap * 0.5f;
            if (Math.abs(stepAreaX - gapCenter) <= beatGap) {
                // Snap to step g*4-1 (end of left group) or g*4 (start of right group)
                return (stepAreaX < gapCenter) ? g * 4 - 1 : g * 4;
            }
        }
        return -1;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setListener(Listener l) { this.listener = l; }

    /**
     * Update the playhead column. Safe to call from the UI thread at ~50ms intervals.
     * Uses postInvalidate() so it can also be called from background threads.
     */
    public void setPlayheadStep(int step) {
        if (step != playheadStep) {
            playheadStep = step;
            postInvalidate();
        }
    }

    /** Remove the playhead highlight (call when playback stops). */
    public void clearPlayhead() {
        playheadStep = -1;
        postInvalidate();
    }

    /** Bulk-set the grid state (e.g. when loading from SharedPreferences). */
    public void setSteps(boolean[][] pattern) {
        for (int r = 0; r < NUM_ROWS && r < pattern.length; r++) {
            if (pattern[r] != null)
                System.arraycopy(pattern[r], 0, steps[r], 0,
                        Math.min(NUM_STEPS, pattern[r].length));
        }
        invalidate();
    }

    /** Returns a copy of the current grid state. */
    public boolean[][] getSteps() {
        boolean[][] copy = new boolean[NUM_ROWS][NUM_STEPS];
        for (int r = 0; r < NUM_ROWS; r++)
            System.arraycopy(steps[r], 0, copy[r], 0, NUM_STEPS);
        return copy;
    }

    /** Clear all steps in all rows. */
    public void clearAll() {
        for (int r = 0; r < NUM_ROWS; r++) Arrays.fill(steps[r], false);
        invalidate();
    }
}
