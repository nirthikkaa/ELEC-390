package com.example.thereminglovestest2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

public class ThereminVisualizerView extends View {

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint panelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint freqBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint volBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float freqHz = 523.25f;
    private float volume01 = 0f;
    private float freqMinHz = 20f;
    private float freqMaxHz = 2000f;

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
        bgPaint.setColor(Color.TRANSPARENT);

        panelPaint.setColor(Color.parseColor("#111827")); // dark slate
        panelPaint.setStyle(Paint.Style.FILL);

        strokePaint.setColor(Color.parseColor("#374151"));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(1.2f));

        freqBarPaint.setColor(Color.parseColor("#22C55E")); // green
        volBarPaint.setColor(Color.parseColor("#3B82F6"));  // blue

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(sp(18));
        textPaint.setFakeBoldText(true);

        subTextPaint.setColor(Color.parseColor("#D1D5DB"));
        subTextPaint.setTextSize(sp(13));
    }

    public void setThereminState(float freqHz, float volume01, float minHz, float maxHz) {
        this.freqHz = Math.max(0f, freqHz);
        this.volume01 = clamp01(volume01);

        // Prevent divide-by-zero / bad ranges
        if (maxHz <= minHz) {
            this.freqMinHz = 20f;
            this.freqMaxHz = 2000f;
        } else {
            this.freqMinHz = minHz;
            this.freqMaxHz = maxHz;
        }

        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) return;

        canvas.drawRect(0, 0, w, h, bgPaint);

        float pad = dp(12);
        RectF panel = new RectF(pad, pad, w - pad, h - pad);
        float corner = dp(18);

        canvas.drawRoundRect(panel, corner, corner, panelPaint);
        canvas.drawRoundRect(panel, corner, corner, strokePaint);

        float innerPad = dp(14);
        float x0 = panel.left + innerPad;
        float x1 = panel.right - innerPad;
        float y = panel.top + innerPad;

        // Title
        canvas.drawText("Live Theremin", x0, y + sp(16), textPaint);

        y += dp(30);

        // Frequency text
        String freqText = String.format(Locale.US, "Frequency: %.1f Hz", freqHz);
        canvas.drawText(freqText, x0, y + sp(14), subTextPaint);

        y += dp(22);

        // Frequency bar background
        RectF freqBg = new RectF(x0, y, x1, y + dp(18));
        drawBarBackground(canvas, freqBg);

        float fNorm = normalizeFreq(freqHz, freqMinHz, freqMaxHz);
        RectF freqFill = new RectF(freqBg.left, freqBg.top, freqBg.left + fNorm * freqBg.width(), freqBg.bottom);
        canvas.drawRoundRect(freqFill, dp(8), dp(8), freqBarPaint);

        y += dp(34);

        // Volume text
        String volText = String.format(Locale.US, "Volume: %.0f%%", volume01 * 100f);
        canvas.drawText(volText, x0, y + sp(14), subTextPaint);

        y += dp(22);

        RectF volBg = new RectF(x0, y, x1, y + dp(18));
        drawBarBackground(canvas, volBg);

        RectF volFill = new RectF(volBg.left, volBg.top, volBg.left + volume01 * volBg.width(), volBg.bottom);
        canvas.drawRoundRect(volFill, dp(8), dp(8), volBarPaint);

        // Right-side mini meter (decorative but live)
        float meterTop = panel.top + dp(24);
        float meterBottom = panel.bottom - dp(24);
        float meterRight = panel.right - dp(14);
        float meterLeft = meterRight - dp(14);

        RectF meterBg = new RectF(meterLeft, meterTop, meterRight, meterBottom);
        Paint meterBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        meterBgPaint.setColor(Color.parseColor("#1F2937"));
        canvas.drawRoundRect(meterBg, dp(7), dp(7), meterBgPaint);
        canvas.drawRoundRect(meterBg, dp(7), dp(7), strokePaint);

        float meterFillHeight = volume01 * meterBg.height();
        RectF meterFill = new RectF(meterBg.left, meterBg.bottom - meterFillHeight, meterBg.right, meterBg.bottom);
        canvas.drawRoundRect(meterFill, dp(7), dp(7), volBarPaint);
    }

    private void drawBarBackground(Canvas canvas, RectF rect) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.parseColor("#1F2937"));
        canvas.drawRoundRect(rect, dp(8), dp(8), p);
        canvas.drawRoundRect(rect, dp(8), dp(8), strokePaint);
    }

    private float normalizeFreq(float f, float min, float max) {
        if (max <= min) return 0f;
        float t = (f - min) / (max - min);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return t;
    }

    private float clamp01(float x) {
        if (x < 0f) return 0f;
        if (x > 1f) return 1f;
        return x;
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}