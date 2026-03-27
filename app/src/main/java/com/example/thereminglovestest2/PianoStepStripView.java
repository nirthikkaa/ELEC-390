package com.example.thereminglovestest2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Arrays;

/**
 * Compact 16-step piano lane for Beat Maker.
 * Shows one note label per step, the current playhead, and a selected edit step.
 */
public final class PianoStepStripView extends View {

    interface Listener {
        void onStepTapped(int step);
    }

    private static final int NUM_STEPS = 16;
    private static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint playheadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private final int[] notes = new int[NUM_STEPS];
    private int playheadStep = -1;
    private int selectedStep = 0;
    private Listener listener;

    public PianoStepStripView(Context context) { super(context); init(); }
    public PianoStepStripView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public PianoStepStripView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        Arrays.fill(notes, -1);
        fillPaint.setColor(0xFF111426);
        activePaint.setColor(0xFF00CCFF);
        playheadPaint.setColor(0x3300FF9D);
        selectedPaint.setColor(0x337EB8FF);
        borderPaint.setColor(0xFF334455);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        textPaint.setColor(0xFFBFD7FF);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(10f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setFakeBoldText(true);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setNotes(int[] source) {
        Arrays.fill(notes, -1);
        if (source != null) System.arraycopy(source, 0, notes, 0, Math.min(NUM_STEPS, source.length));
        invalidate();
    }

    void setPlayheadStep(int step) {
        playheadStep = step;
        invalidate();
    }

    void setSelectedStep(int step) {
        selectedStep = Math.max(0, Math.min(NUM_STEPS - 1, step));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desiredHeight = Math.round(38f * getResources().getDisplayMetrics().density);
        int height = resolveSize(desiredHeight, heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cellW = getWidth() / (float) NUM_STEPS;
        float h = getHeight();
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = h / 2f - (fm.ascent + fm.descent) / 2f;
        for (int step = 0; step < NUM_STEPS; step++) {
            rect.set(step * cellW + 1f, 1f, (step + 1) * cellW - 1f, h - 1f);
            Paint base = notes[step] >= 0 ? activePaint : fillPaint;
            canvas.drawRoundRect(rect, 6f, 6f, base);
            if (step == playheadStep) canvas.drawRoundRect(rect, 6f, 6f, playheadPaint);
            if (step == selectedStep) canvas.drawRoundRect(rect, 6f, 6f, selectedPaint);
            canvas.drawRoundRect(rect, 6f, 6f, borderPaint);
            canvas.drawText(formatMidi(notes[step]), rect.centerX(), textY, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return true;
        int step = stepAt(event.getX());
        if (step >= 0 && listener != null) listener.onStepTapped(step);
        return true;
    }

    private int stepAt(float x) {
        if (getWidth() <= 0) return -1;
        int step = (int) (x / (getWidth() / (float) NUM_STEPS));
        return Math.max(0, Math.min(NUM_STEPS - 1, step));
    }

    private static String formatMidi(int midi) {
        if (midi < 0) return "·";
        return NOTE_NAMES[Math.floorMod(midi, 12)];
    }
}
