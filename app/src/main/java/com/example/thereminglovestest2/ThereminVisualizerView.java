package com.example.thereminglovestest2;

/**
 * File guide:
 * Custom view that draws the live waveform shown on the Play screen.
 */

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.util.AttributeSet;
import android.view.View;

import java.util.Arrays;

public class ThereminVisualizerView extends View {

    private static final int BG = Color.parseColor("#141733");
    private static final int BORDER = Color.parseColor("#2D6BFF");
    private static final int WAVE = Color.parseColor("#4EA2FF");

    private final Paint bgPaint   = paint(Paint.Style.FILL, BG, 0f);
    private final Paint borderPaint = paint(Paint.Style.STROKE, alpha(BORDER, 90), 1f);
    private final Paint glowPaint = paint(Paint.Style.STROKE, alpha(WAVE, 110), 0f);
    private final Paint wavePaint = paint(Paint.Style.STROKE, WAVE, 0f);
    private final RectF rect = new RectF();
    private final Path  wave = new Path();

    // 8-bit background pixel grid
    private static final int MAX_COLS = 64;  // ceiling (High density)
    private int   pCols = 64;
    private int   pRows = 28;
    private int   densityLevel = 3;          // 0=Very Low … 3=High
    private final float[]  waveRow    = new float[MAX_COLS];
    private final float[]  hsv        = new float[3];
    private final Paint    pixelPaint = new Paint();        // no AA — crisp pixel look
    private final RectF    pixelRect  = new RectF();
    private long           animStartMs = System.currentTimeMillis();
    // P3 color space cached once (API 26+)
    private final ColorSpace p3Space = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ? ColorSpace.get(ColorSpace.Named.DISPLAY_P3) : null;

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
        this.samples  = samples == null ? new float[0] : Arrays.copyOf(samples, samples.length);
        computeColumnLevels();
        postInvalidateOnAnimation();
    }

    /** Called from Settings when user changes the density slider (0=Very Low … 3=High). */
    public void setPixelDensityLevel(int level) {
        densityLevel = Math.max(0, Math.min(3, level));
        int[][] presets = {{8,4},{20,10},{40,18},{64,28}};
        pCols = presets[densityLevel][0];
        pRows = presets[densityLevel][1];
        postInvalidate();
    }

    private void computeColumnLevels() {
        float halfRows = (pRows - 1) * 0.5f;
        if (samples.length < 2) {
            Arrays.fill(waveRow, 0, pCols, halfRows);
            return;
        }
        for (int c = 0; c < pCols; c++) {
            int idx = (pCols > 1) ? c * (samples.length - 1) / (pCols - 1) : 0;
            float s = clamp(samples[idx], -1f, 1f);
            float target = halfRows - s * halfRows;
            waveRow[c] = waveRow[c] * 0.45f + target * 0.55f;
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float pad = dp(0.5f), radius = dp(18f);
        rect.set(pad, pad, w - pad, h - pad);
        canvas.drawRoundRect(rect, radius, radius, bgPaint);

        // 8-bit pixel grid drawn before the waveform
        draw8BitGrid(canvas);

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

        // Keep redrawing so the hue animation plays even during silence
        postInvalidateOnAnimation();
    }

    private void draw8BitGrid(Canvas canvas) {
        float gap    = (densityLevel >= 3) ? dp(1f) : dp(1.5f);
        float innerW = rect.width()  - dp(3f);
        float innerH = rect.height() - dp(3f);
        float cellW  = (innerW - gap * (pCols - 1)) / pCols;
        float cellH  = (innerH - gap * (pRows - 1)) / pRows;
        float startX = rect.left + dp(1.5f);
        float startY = rect.top  + dp(1.5f);

        float elapsed = (System.currentTimeMillis() - animStartMs) / 1000f;
        float baseHue = (elapsed * 25f) % 360f;
        float falloff = pRows * 0.38f;

        for (int c = 0; c < pCols; c++) {
            float wr   = waveRow[c];
            float hueC = (baseHue + (float) c / pCols * 110f) % 360f;
            float px   = startX + c * (cellW + gap);

            for (int r = 0; r < pRows; r++) {
                float dist = Math.abs(r - wr);
                float t    = Math.max(0f, 1f - dist / falloff);
                float peak    = t * t * volume01;
                float ambient = volume01 * 0.10f * (1f - dist / pRows);
                float intensity = Math.min(1f, peak + ambient);

                if (intensity < 0.015f) continue;

                float hue = (hueC + r * 5f) % 360f;
                hsv[0] = hue;
                hsv[1] = 1f;
                hsv[2] = 0.15f + 0.85f * intensity;
                int alpha = Math.round(35 + 220 * intensity);

                setPixelColor(canvas, alpha);

                float py = startY + r * (cellH + gap);
                pixelRect.set(px, py, px + cellW, py + cellH);
                canvas.drawRect(pixelRect, pixelPaint);
            }
        }
    }

    /** Sets pixelPaint color using Display P3 wide gamut on API 29+, sRGB otherwise. */
    private void setPixelColor(Canvas canvas, int alpha) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && canvas.isHardwareAccelerated() && p3Space != null) {
            // HSV → linear RGB, then pack in P3 (wider gamut = more vivid neons)
            float h = hsv[0], s = hsv[1], v = hsv[2];
            float c2 = v * s;
            float hp = h / 60f;
            float x  = c2 * (1f - Math.abs(hp % 2f - 1f));
            float r, g, b;
            int hi = (int) hp % 6;
            switch (hi) {
                case 0: r = c2; g =  x; b =  0; break;
                case 1: r =  x; g = c2; b =  0; break;
                case 2: r =  0; g = c2; b =  x; break;
                case 3: r =  0; g =  x; b = c2; break;
                case 4: r =  x; g =  0; b = c2; break;
                default:r = c2; g =  0; b =  x; break;
            }
            float m = v - c2;
            pixelPaint.setColor(Color.pack(r + m, g + m, b + m, alpha / 255f, p3Space));
        } else {
            pixelPaint.setColor(Color.HSVToColor(alpha, hsv));
        }
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
