package com.example.thereminglovestest2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;

public class ThereminVisualizerView extends View {

    private static final int BG = Color.parseColor("#141733");
    private static final int BORDER = Color.parseColor("#2D6BFF");
    private static final int WAVE = Color.parseColor("#4EA2FF");

    private final Paint bgPaint = paint(Paint.Style.FILL, BG, 0f);
    private final Paint borderPaint = paint(Paint.Style.STROKE, alpha(BORDER, 90), 1f);
    private final Paint glowPaint = paint(Paint.Style.STROKE, alpha(WAVE, 110), 0f);
    private final Paint wavePaint = paint(Paint.Style.STROKE, WAVE, 0f);
    private final RectF rect = new RectF();
    private final Path wave = new Path();

    private float volume01;
    private float[] samples = new float[0];

    public ThereminVisualizerView(Context context) { super(context); init(); }
    public ThereminVisualizerView(Context context, AttributeSet attrs) { super(context, attrs); init(); }
    public ThereminVisualizerView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }

    private void init() {
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        borderPaint.setStrokeWidth(dp(1f));
    }

    // Keep the same signature so the rest of the app does not need to change.
    public void setAudioWave(float[] samples, float freqHz, float volume01, float minHz, float maxHz) {
        this.volume01 = clamp01(volume01);
        this.samples = samples == null ? new float[0] : Arrays.copyOf(samples, samples.length);
        postInvalidateOnAnimation();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pad = dp(0.5f), radius = dp(18f);
        rect.set(pad, pad, w - pad, h - pad);
        canvas.drawRoundRect(rect, radius, radius, bgPaint);
        canvas.drawRoundRect(rect, radius, radius, borderPaint);

        float left = rect.left + dp(2f), right = rect.right - dp(2f), centerY = rect.centerY();
        float amp = rect.height() * (0.10f + 0.40f * volume01);
        wave.reset();

        if (samples.length < 2) {
            wave.moveTo(left, centerY);
            wave.lineTo(right, centerY);
        } else {
            int last = samples.length - 1;
            for (int i = 0; i <= last; i++) {
                float x = left + (right - left) * i / last;
                float y = centerY - clamp(samples[i], -1f, 1f) * amp;
                if (i == 0) wave.moveTo(x, y); else wave.lineTo(x, y);
            }
        }

        glowPaint.setStrokeWidth(dp(7f + 5f * volume01));
        glowPaint.setAlpha(70 + Math.round(70f * volume01));
        wavePaint.setStrokeWidth(dp(3f + 2.5f * volume01));
        canvas.drawPath(wave, glowPaint);
        canvas.drawPath(wave, wavePaint);
    }

    private Paint paint(Paint.Style style, int color, float strokeDp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(style);
        p.setColor(color);
        if (strokeDp > 0f) p.setStrokeWidth(dp(strokeDp));
        return p;
    }

    private static int alpha(int color, int a) {
        return Color.argb(Math.max(0, Math.min(255, a)), Color.red(color), Color.green(color), Color.blue(color));
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private static float clamp01(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static float clamp(float v, float min, float max) { return Math.max(min, Math.min(max, v)); }
}
