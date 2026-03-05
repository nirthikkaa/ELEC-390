package com.example.thereminglovestest2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

public class ThereminVisualizerView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint centerLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Paint glowWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mainWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint secondaryWavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sparkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final RectF scopeRect = new RectF();

    private final Path mainWavePath = new Path();
    private final Path glowWavePath = new Path();
    private final Path secondaryWavePath = new Path();

    private Shader cachedBgShader;

    private float freqHz = 523.25f;
    private float volume01 = 0f;
    private float freqMinHz = 20f;
    private float freqMaxHz = 2000f;

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
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.0f));
        borderPaint.setColor(Color.parseColor("#22A7D7FF"));

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(0.9f));
        gridPaint.setColor(Color.parseColor("#14B8D9FF"));

        centerLinePaint.setStyle(Paint.Style.STROKE);
        centerLinePaint.setStrokeWidth(dp(1.1f));
        centerLinePaint.setColor(Color.parseColor("#20D3E7FF"));

        glowWavePaint.setStyle(Paint.Style.STROKE);
        glowWavePaint.setStrokeCap(Paint.Cap.ROUND);
        glowWavePaint.setStrokeJoin(Paint.Join.ROUND);

        mainWavePaint.setStyle(Paint.Style.STROKE);
        mainWavePaint.setStrokeCap(Paint.Cap.ROUND);
        mainWavePaint.setStrokeJoin(Paint.Join.ROUND);

        secondaryWavePaint.setStyle(Paint.Style.STROKE);
        secondaryWavePaint.setStrokeCap(Paint.Cap.ROUND);
        secondaryWavePaint.setStrokeJoin(Paint.Join.ROUND);

        sparkPaint.setStyle(Paint.Style.FILL);

    }

    public void setThereminState(float freqHz, float volume01, float minHz, float maxHz) {
        this.freqHz = Math.max(0f, freqHz);
        this.volume01 = clamp01(volume01);

        if (maxHz <= minHz) {
            this.freqMinHz = 20f;
            this.freqMaxHz = 2000f;
        } else {
            this.freqMinHz = minHz;
            this.freqMaxHz = maxHz;
        }

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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);

        if (w <= 0 || h <= 0) return;

        cachedBgShader = new LinearGradient(
                0, 0, w, h,
                new int[]{
                        Color.parseColor("#100A2A"),
                        Color.parseColor("#15103F"),
                        Color.parseColor("#0B2346"),
                        Color.parseColor("#0B1C31")
                },
                null,
                Shader.TileMode.CLAMP
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        float t = (System.currentTimeMillis() % 100000L) * 0.001f;

        layoutRects(w, h);
        drawBackground(canvas);

        float normFreq = normalizeFreq(freqHz, freqMinHz, freqMaxHz);
        int waveColor = strongPitchColor(normFreq);
        int brightWaveColor = brightenByVolume(waveColor, volume01);

        drawGrid(canvas);
        drawOscilloscope(canvas, t, brightWaveColor, normFreq);

        if (animating) {
            postInvalidateOnAnimation();
        }
    }

    private void drawBackground(Canvas canvas) {
        bgPaint.setShader(cachedBgShader);
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

    private void drawGrid(Canvas canvas) {
        float innerPad = dp(0.5f);
        float left = scopeRect.left + innerPad;
        float top = scopeRect.top + innerPad;
        float right = scopeRect.right - innerPad;
        float bottom = scopeRect.bottom - innerPad;
        float colStep = (right - left) / 8f;
        float rowStep = (bottom - top) / 6f;

        for (int i = 1; i < 8; i++) {
            float x = left + i * colStep;
            canvas.drawLine(x, top, x, bottom, gridPaint);
        }

        for (int i = 1; i < 6; i++) {
            float y = top + i * rowStep;
            canvas.drawLine(left, y, right, y, gridPaint);
        }

        canvas.drawLine(left, scopeRect.centerY(), right, scopeRect.centerY(), centerLinePaint);
    }

    private void drawOscilloscope(Canvas canvas, float t, int brightWaveColor, float normFreq) {
        float left = scopeRect.left + dp(1.5f);
        float right = scopeRect.right - dp(1.5f);
        float centerY = scopeRect.centerY();
        float width = right - left;

        float amplitude = scopeRect.height() * (0.20f + volume01 * 0.20f);
        float cycles = 1.0f + normFreq * 5.8f;
        float phase = t * (2.4f + normFreq * 9.0f);

        mainWavePath.reset();
        glowWavePath.reset();
        secondaryWavePath.reset();

        int steps = 160;
        for (int i = 0; i <= steps; i++) {
            float u = i / (float) steps;
            float x = left + u * width;

            float theta = (float) (u * cycles * Math.PI * 2.0 + phase);
            float yMain = centerY
                    + (float) Math.sin(theta) * amplitude
                    + (float) Math.sin(theta * 0.35f + phase * 0.4f) * amplitude * 0.18f;

            float ySecondary = centerY
                    + (float) Math.sin(theta * 1.65f + phase * 0.55f) * amplitude * 0.34f;

            if (i == 0) {
                mainWavePath.moveTo(x, yMain);
                glowWavePath.moveTo(x, yMain);
                secondaryWavePath.moveTo(x, ySecondary);
            } else {
                mainWavePath.lineTo(x, yMain);
                glowWavePath.lineTo(x, yMain);
                secondaryWavePath.lineTo(x, ySecondary);
            }
        }

        glowWavePaint.setStrokeWidth(dp(10f) + volume01 * dp(6f));
        glowWavePaint.setColor(withAlpha(brightWaveColor, 60 + Math.round(volume01 * 90f)));

        mainWavePaint.setStrokeWidth(dp(4.2f) + volume01 * dp(1.6f));
        mainWavePaint.setColor(lighten(brightWaveColor, 0.16f));

        secondaryWavePaint.setStrokeWidth(dp(1.6f));
        secondaryWavePaint.setColor(withAlpha(lighten(brightWaveColor, 0.32f), 120));

        canvas.drawPath(glowWavePath, glowWavePaint);
        canvas.drawPath(secondaryWavePath, secondaryWavePaint);
        canvas.drawPath(mainWavePath, mainWavePaint);

        drawSparks(canvas, t, brightWaveColor, amplitude);
    }

    private void drawSparks(Canvas canvas, float t, int brightWaveColor, float amplitude) {
        sparkPaint.setColor(withAlpha(lighten(brightWaveColor, 0.45f), 170));

        float left = scopeRect.left + dp(1.5f);
        float right = scopeRect.right - dp(1.5f);
        float centerY = scopeRect.centerY();

        for (int i = 0; i < 12; i++) {
            float u = (i + 0.5f) / 12f;
            float x = left + u * (right - left);
            float y = centerY + (float) Math.sin(t * (3.0f + i * 0.15f) + i * 0.7f) * (amplitude * 0.65f);
            float r = dp(1.6f) + (i % 3) * dp(0.5f);
            canvas.drawCircle(x, y, r, sparkPaint);
        }
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

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}