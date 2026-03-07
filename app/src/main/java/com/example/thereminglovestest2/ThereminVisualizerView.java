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

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mainWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF scopeRect = new RectF();
    private final Path mainWavePath = new Path();
    private final Path glowWavePath = new Path();

    private float freqHz = 523.25f;
    private float volume01 = 0f;
    private float freqMinHz = 20f;
    private float freqMaxHz = 2000f;
    private float[] waveSamples = new float[0];

    private boolean animating = false;

    public ThereminVisualizerView(Context context) {
        super(context);
        init();
    }

    public ThereminVisualizerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ThereminVisualizerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        // Solid dark panel so the real waveform stands out without the extra grid.
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(Color.parseColor("#141733"));

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.0f));
        borderPaint.setColor(Color.parseColor("#22A7D7FF"));

        glowWavePaint.setStyle(Paint.Style.STROKE);
        glowWavePaint.setStrokeCap(Paint.Cap.ROUND);
        glowWavePaint.setStrokeJoin(Paint.Join.ROUND);

        mainWavePaint.setStyle(Paint.Style.STROKE);
        mainWavePaint.setStrokeCap(Paint.Cap.ROUND);
        mainWavePaint.setStrokeJoin(Paint.Join.ROUND);
    }

    public void setAudioWave(float[] samples, float freqHz, float volume01, float minHz, float maxHz) {
        this.freqHz = Math.max(0f, freqHz);
        this.volume01 = clamp01(volume01);

        if (maxHz <= minHz) {
            this.freqMinHz = 20f;
            this.freqMaxHz = 2000f;
        } else {
            this.freqMinHz = minHz;
            this.freqMaxHz = maxHz;
        }

        this.waveSamples = samples == null ? new float[0] : Arrays.copyOf(samples, samples.length);

        if (!animating) {
            animating = true;
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animating = true;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animating = false;
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        animating = visibility == VISIBLE;
        if (animating) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        layoutRects(w, h);
        drawBackground(canvas);

        float normFreq = normalizeFreq(freqHz, freqMinHz, freqMaxHz);
        int waveColor = strongPitchColor(normFreq);
        int brightWaveColor = brightenByVolume(waveColor, volume01);

        drawActualWave(canvas, brightWaveColor);

        if (animating) {
            postInvalidateOnAnimation();
        }
    }

    private void drawBackground(Canvas canvas) {
        canvas.drawRoundRect(scopeRect, dp(18), dp(18), bgPaint);
        canvas.drawRoundRect(scopeRect, dp(18), dp(18), borderPaint);
    }

    private void layoutRects(float w, float h) {
        float pad = dp(0.5f);
        scopeRect.set(
                pad,
                pad,
                w - pad,
                h - pad
        );
    }

    private void drawActualWave(Canvas canvas, int brightWaveColor) {
        float left = scopeRect.left + dp(1.5f);
        float right = scopeRect.right - dp(1.5f);
        float centerY = scopeRect.centerY();
        float amplitude = scopeRect.height() * 0.33f;

        mainWavePath.reset();
        glowWavePath.reset();

        if (waveSamples == null || waveSamples.length < 2) {
            mainWavePath.moveTo(left, centerY);
            mainWavePath.lineTo(right, centerY);
            glowWavePath.set(mainWavePath);
        } else {
            int lastIndex = waveSamples.length - 1;
            for (int i = 0; i <= lastIndex; i++) {
                float u = i / (float) lastIndex;
                float x = left + u * (right - left);
                float y = centerY - clamp(waveSamples[i], -1f, 1f) * amplitude;

                if (i == 0) {
                    mainWavePath.moveTo(x, y);
                    glowWavePath.moveTo(x, y);
                } else {
                    mainWavePath.lineTo(x, y);
                    glowWavePath.lineTo(x, y);
                }
            }
        }

        glowWavePaint.setStrokeWidth(dp(9f) + volume01 * dp(6f));
        glowWavePaint.setColor(withAlpha(brightWaveColor, 55 + Math.round(volume01 * 90f)));

        mainWavePaint.setStrokeWidth(dp(4f) + volume01 * dp(1.8f));
        mainWavePaint.setColor(lighten(brightWaveColor, 0.16f));

        canvas.drawPath(glowWavePath, glowWavePaint);
        canvas.drawPath(mainWavePath, mainWavePaint);
    }

    private int strongPitchColor(float norm) {
        norm = clamp01(norm);

        if (norm < 0.33f) {
            return blend(Color.parseColor("#1EA8FF"), Color.parseColor("#4E7BFF"), norm / 0.33f);
        } else if (norm < 0.66f) {
            return blend(Color.parseColor("#4E7BFF"), Color.parseColor("#BC55FF"), (norm - 0.33f) / 0.33f);
        } else {
            return blend(Color.parseColor("#BC55FF"), Color.parseColor("#FF4C58"), (norm - 0.66f) / 0.34f);
        }
    }

    private int brightenByVolume(int color, float vol) {
        float amount = 0.08f + 0.65f * clamp01(vol);
        return lighten(color, amount);
    }

    private float normalizeFreq(float f, float min, float max) {
        if (max <= min) return 0f;
        return clamp01((f - min) / (max - min));
    }

    private int blend(int c1, int c2, float t) {
        t = clamp01(t);
        int a = (int) (Color.alpha(c1) + (Color.alpha(c2) - Color.alpha(c1)) * t);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.argb(a, r, g, b);
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(
                Math.max(0, Math.min(255, alpha)),
                Color.red(color),
                Color.green(color),
                Color.blue(color)
        );
    }

    private int lighten(int color, float amount) {
        amount = clamp01(amount);
        int r = (int) (Color.red(color) + (255 - Color.red(color)) * amount);
        int g = (int) (Color.green(color) + (255 - Color.green(color)) * amount);
        int b = (int) (Color.blue(color) + (255 - Color.blue(color)) * amount);
        return Color.argb(Color.alpha(color), r, g, b);
    }

    private float clamp01(float x) {
        if (x < 0f) return 0f;
        if (x > 1f) return 1f;
        return x;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
