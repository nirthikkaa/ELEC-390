package com.example.thereminglovestest2;

/**
 * File guide:
 * Custom view that draws the live waveform shown on the Play screen.
 * Plain dark background with a bright waveform line + soft glow overlay.
 */

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

    private static final int BG     = Color.parseColor("#0D0F1E");
    private static final int BORDER = Color.parseColor("#2D6BFF");
    private static final int WAVE   = Color.parseColor("#4EA2FF");

    private final Paint bgPaint     = paint(Paint.Style.FILL,   BG, 0f);
    private final Paint borderPaint = paint(Paint.Style.STROKE,  alpha(BORDER, 90), 1f);
    private final Paint glowPaint   = paint(Paint.Style.STROKE,  alpha(WAVE, 110), 0f);
    private final Paint wavePaint   = paint(Paint.Style.STROKE,  WAVE, 0f);
    private final RectF rect = new RectF();
    private final Path  wave = new Path();

    // ── Audio state ──────────────────────────────────────────────────────────
    private float   volume01 = 0f;
    private float[] samples  = new float[0];

    // ── Constructors ─────────────────────────────────────────────────────────
    public ThereminVisualizerView(Context ctx)                          { super(ctx);         init(); }
    public ThereminVisualizerView(Context ctx, AttributeSet a)          { super(ctx, a);      init(); }
    public ThereminVisualizerView(Context ctx, AttributeSet a, int def) { super(ctx, a, def); init(); }

    private void init() {
        glowPaint.setStrokeCap(Paint.Cap.ROUND);
        glowPaint.setStrokeJoin(Paint.Join.ROUND);
        wavePaint.setStrokeCap(Paint.Cap.ROUND);
        wavePaint.setStrokeJoin(Paint.Join.ROUND);
        borderPaint.setStrokeWidth(dp(1f));
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public void setAudioWave(float[] samples, float freqHz, float volume01,
                             float minHz, float maxHz) {
        this.volume01 = clamp01(volume01);
        this.samples  = (samples == null) ? new float[0] : Arrays.copyOf(samples, samples.length);
        postInvalidateOnAnimation();
    }

    /** No-op — kept for call-site compatibility. */
    public void pulse(float strength) {}

    /** No-op — kept for call-site compatibility. */
    public void setPixelDensityLevel(int level) {}

    // ── Draw ─────────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pad    = dp(0.5f);
        float radius = dp(18f);
        rect.set(pad, pad, w - pad, h - pad);

        canvas.drawRoundRect(rect, radius, radius, bgPaint);
        canvas.drawRoundRect(rect, radius, radius, borderPaint);
        drawWaveform(canvas);
    }

    private void drawWaveform(Canvas canvas) {
        float left    = rect.left  + dp(2f);
        float right   = rect.right - dp(2f);
        float centerY = rect.centerY();
        // Increased amplitude: fills ~80% of the view height at full volume
        float amp     = rect.height() * (0.15f + 0.65f * volume01);

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

        glowPaint.setStrokeWidth(dp(6f + 5f * volume01));
        glowPaint.setAlpha(55 + Math.round(70f * volume01));
        wavePaint.setStrokeWidth(dp(2.5f + 1.5f * volume01));
        canvas.drawPath(wave, glowPaint);
        canvas.drawPath(wave, wavePaint);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private Paint paint(Paint.Style style, int color, float strokeDp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(style);
        p.setColor(color);
        if (strokeDp > 0f) p.setStrokeWidth(dp(strokeDp));
        return p;
    }

    private static int   alpha(int color, int a)           { return Color.argb(clampInt(a,0,255), Color.red(color), Color.green(color), Color.blue(color)); }
    private float        dp(float v)                        { return v * getResources().getDisplayMetrics().density; }
    private static float clamp01(float v)                   { return Math.max(0f, Math.min(1f, v)); }
    private static float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }
    private static int   clampInt(int v, int lo, int hi)    { return Math.max(lo, Math.min(hi, v)); }
}
