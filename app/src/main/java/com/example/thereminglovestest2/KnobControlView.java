package com.example.thereminglovestest2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * Reusable dial/knob control for calibration.
 *
 * Preserved behavior:
 * - center value remains clickable for manual entry
 * - dial updates continuously while dragging
 * - one commit on release
 * - parent scroll blocked while rotating
 * - dead-zone crossing clamps instead of wrapping
 *
 * Visual goal:
 * - real instrument-style dial
 * - angle knobs show scale markings
 * - center value is readable without a chunky chip background
 */
public class KnobControlView extends LinearLayout {

    public interface OnKnobValueChangedListener {
        void onValueChanged(KnobControlView knob, float value, boolean fromUser);
    }

    public interface OnKnobCommitListener {
        void onValueCommitted(KnobControlView knob, float value);
    }

    private final TextView titleView;
    private final TextView valueView;
    private final DialFaceView dialFaceView;

    private float minValue = 0f;
    private float maxValue = 100f;
    private float stepSize = 1f;
    private float currentValue = 0f;

    private OnKnobValueChangedListener valueChangedListener;
    private OnKnobCommitListener commitListener;

    public KnobControlView(Context context) {
        this(context, null);
    }

    public KnobControlView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KnobControlView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(6), dp(4), dp(6), dp(4));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        titleView.setGravity(Gravity.CENTER);
        titleView.setMaxLines(2);
        titleView.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE));
        LayoutParams titleLp = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
        );
        addView(titleView, titleLp);

        FrameLayout knobFrame = new FrameLayout(context);
        LayoutParams frameLp = new LayoutParams(dp(136), dp(136));
        frameLp.topMargin = dp(6);
        knobFrame.setLayoutParams(frameLp);
        knobFrame.setClipChildren(false);
        knobFrame.setClipToPadding(false);

        dialFaceView = new DialFaceView(context);
        FrameLayout.LayoutParams dialLp = new FrameLayout.LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
        );
        knobFrame.addView(dialFaceView, dialLp);

        valueView = new TextView(context);
        valueView.setGravity(Gravity.CENTER);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        valueView.setTextColor(resolveThemeColor(android.R.attr.textColorPrimary, Color.WHITE));
        valueView.setTypeface(Typeface.DEFAULT_BOLD);
        valueView.setClickable(true);
        valueView.setFocusable(true);
        valueView.setMinWidth(dp(76));
        valueView.setMinHeight(dp(44));
        valueView.setPadding(dp(12), dp(8), dp(12), dp(8));
        valueView.setBackgroundColor(Color.TRANSPARENT);
        valueView.setIncludeFontPadding(false);
        valueView.setShadowLayer(dp(6), 0f, 0f, 0xCC090614);

        FrameLayout.LayoutParams valueLp = new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        knobFrame.addView(valueView, valueLp);

        addView(knobFrame, frameLp);
        refreshVisuals();
    }

    public void setLabelText(String text) {
        titleView.setText(text == null ? "" : text);
        refreshVisuals();
    }

    public void setValueText(String text) {
        valueView.setText(text == null ? "" : text);
        refreshVisuals();
    }

    public void setRange(float minValue, float maxValue) {
        this.minValue = minValue;
        this.maxValue = Math.max(maxValue, minValue);
        setValue(currentValue, false);
    }

    public void setStepSize(float stepSize) {
        this.stepSize = stepSize <= 0f ? 1f : stepSize;
        setValue(currentValue, false);
    }

    public void setValue(float value) {
        setValue(value, false);
    }

    public float getValue() {
        return currentValue;
    }

    public void setOnKnobValueChangedListener(@Nullable OnKnobValueChangedListener listener) {
        this.valueChangedListener = listener;
    }

    public void setOnKnobCommitListener(@Nullable OnKnobCommitListener listener) {
        this.commitListener = listener;
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        valueView.setOnClickListener(l);
    }

    private void setValue(float value, boolean fromUser) {
        float clamped = clamp(value, minValue, maxValue);
        float snapped = snap(clamped);

        if (Math.abs(snapped - currentValue) < 0.0001f && fromUser) {
            refreshVisuals();
            return;
        }

        currentValue = snapped;
        refreshVisuals();

        if (valueChangedListener != null) {
            valueChangedListener.onValueChanged(this, currentValue, fromUser);
        }
    }

    private void commitValue() {
        if (commitListener != null) {
            commitListener.onValueCommitted(this, currentValue);
        }
    }

    private void refreshVisuals() {
        dialFaceView.invalidate();
        valueView.bringToFront();
        dialFaceView.setContentDescription(titleView.getText() + ": " + valueView.getText());
    }

    private float snap(float value) {
        if (stepSize <= 0f) return value;
        float snapped = Math.round((value - minValue) / stepSize) * stepSize + minValue;
        return clamp(snapped, minValue, maxValue);
    }

    private float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private float getFraction() {
        float span = maxValue - minValue;
        if (span <= 0f) return 0f;
        return (currentValue - minValue) / span;
    }

    private boolean isAngleDial() {
        CharSequence label = titleView.getText();
        if (label == null) return false;
        return label.toString().toLowerCase(Locale.US).contains("angle");
    }

    private int resolveThemeColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        boolean found = getContext().getTheme().resolveAttribute(attr, tv, true);
        if (!found) return fallback;

        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }

        if (tv.resourceId != 0) {
            try {
                return ContextCompat.getColor(getContext(), tv.resourceId);
            } catch (Exception ignored) {
                return fallback;
            }
        }

        return fallback;
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }

    private void requestParentDisallowIntercept(boolean disallow) {
        ViewParent parent = getParent();
        while (parent != null) {
            parent.requestDisallowInterceptTouchEvent(disallow);
            parent = parent.getParent();
        }
    }

    private final class DialFaceView extends View {

        private static final float START_ANGLE = 120f;
        private static final float SWEEP_ANGLE = 300f;

        private final Paint bezelFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bezelStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint faceFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint centerHaloPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint majorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint minorTickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint hubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final RectF arcRect = new RectF();

        DialFaceView(Context context) {
            super(context);
            initPaints();
        }

        private void initPaints() {
            int primary = resolveThemeColor(android.R.attr.colorAccent, 0xFF41D8FF);
            int secondary = resolveThemeColor(android.R.attr.textColorSecondary, 0xFFAFA8D6);
            int textPrimary = resolveThemeColor(android.R.attr.textColorPrimary, 0xFFFFFFFF);

            bezelFillPaint.setStyle(Paint.Style.FILL);
            bezelFillPaint.setColor(0xFF07051A);

            bezelStrokePaint.setStyle(Paint.Style.STROKE);
            bezelStrokePaint.setStrokeWidth(dp(2));
            bezelStrokePaint.setColor(withAlpha(secondary, 95));

            faceFillPaint.setStyle(Paint.Style.FILL);
            faceFillPaint.setColor(0xFF0A061E);

            centerHaloPaint.setStyle(Paint.Style.FILL);
            centerHaloPaint.setColor(0xCC140F2C);

            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeCap(Paint.Cap.ROUND);
            trackPaint.setStrokeWidth(dp(8));
            trackPaint.setColor(withAlpha(secondary, 120));

            progressPaint.setStyle(Paint.Style.STROKE);
            progressPaint.setStrokeCap(Paint.Cap.ROUND);
            progressPaint.setStrokeWidth(dp(8));
            progressPaint.setColor(withAlpha(primary, 235));

            majorTickPaint.setStyle(Paint.Style.STROKE);
            majorTickPaint.setStrokeCap(Paint.Cap.ROUND);
            majorTickPaint.setStrokeWidth(dp(2));
            majorTickPaint.setColor(withAlpha(textPrimary, 220));

            minorTickPaint.setStyle(Paint.Style.STROKE);
            minorTickPaint.setStrokeCap(Paint.Cap.ROUND);
            minorTickPaint.setStrokeWidth(dp(1));
            minorTickPaint.setColor(withAlpha(textPrimary, 130));

            pointerPaint.setStyle(Paint.Style.STROKE);
            pointerPaint.setStrokeCap(Paint.Cap.ROUND);
            pointerPaint.setStrokeWidth(dp(4));
            pointerPaint.setColor(primary);

            hubPaint.setStyle(Paint.Style.FILL);
            hubPaint.setColor(withAlpha(textPrimary, 235));

            labelPaint.setStyle(Paint.Style.FILL);
            labelPaint.setColor(withAlpha(textPrimary, 225));
            labelPaint.setTextAlign(Paint.Align.CENTER);
            labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
            labelPaint.setTextSize(dp(9));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float w = getWidth();
            float h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;

            boolean angleDial = isAngleDial();

            float outerRadius = Math.min(w, h) / 2f - dp(angleDial ? 8 : 10);
            float faceRadius = outerRadius - dp(angleDial ? 12 : 10);

            canvas.drawCircle(cx, cy, outerRadius, bezelFillPaint);
            canvas.drawCircle(cx, cy, outerRadius, bezelStrokePaint);
            canvas.drawCircle(cx, cy, faceRadius, faceFillPaint);

            drawTicks(canvas, cx, cy, outerRadius, angleDial);

            float arcInset = (Math.min(w, h) / 2f) - (faceRadius - dp(8));
            arcRect.set(arcInset, arcInset, w - arcInset, h - arcInset);
            canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, trackPaint);
            canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE * getFraction(), false, progressPaint);

            if (angleDial) {
                drawAngleLabels(canvas, cx, cy, outerRadius);
            }

            drawPointer(canvas, cx, cy, faceRadius);
            drawCenterHalo(canvas, cx, cy, faceRadius);
        }

        private void drawTicks(Canvas canvas, float cx, float cy, float outerRadius, boolean angleDial) {
            int tickCount = angleDial ? 36 : 30;
            int majorEvery = angleDial ? 9 : 5;

            float outer = outerRadius - dp(7);
            float minorInner = outerRadius - dp(14);
            float majorInner = outerRadius - dp(20);

            for (int i = 0; i <= tickCount; i++) {
                float fraction = i / (float) tickCount;
                float angle = START_ANGLE + (SWEEP_ANGLE * fraction);
                double rad = Math.toRadians(angle);

                boolean isMajor = (i % majorEvery == 0);
                float inner = isMajor ? majorInner : minorInner;
                Paint paint = isMajor ? majorTickPaint : minorTickPaint;

                float sx = cx + (float) Math.cos(rad) * inner;
                float sy = cy + (float) Math.sin(rad) * inner;
                float ex = cx + (float) Math.cos(rad) * outer;
                float ey = cy + (float) Math.sin(rad) * outer;
                canvas.drawLine(sx, sy, ex, ey, paint);
            }
        }

        private void drawAngleLabels(Canvas canvas, float cx, float cy, float outerRadius) {
            float labelRadius = outerRadius - dp(29);
            int[] values = new int[]{-90, -45, 0, 45, 90};

            for (int i = 0; i < values.length; i++) {
                float fraction = i / 4f;
                float angle = START_ANGLE + (SWEEP_ANGLE * fraction);
                double rad = Math.toRadians(angle);

                float tx = cx + (float) Math.cos(rad) * labelRadius;
                float ty = cy + (float) Math.sin(rad) * labelRadius
                        - ((labelPaint.ascent() + labelPaint.descent()) / 2f);

                canvas.drawText(String.valueOf(values[i]), tx, ty, labelPaint);
            }
        }

        private void drawPointer(Canvas canvas, float cx, float cy, float faceRadius) {
            float indicatorAngle = START_ANGLE + (SWEEP_ANGLE * getFraction());
            double rad = Math.toRadians(indicatorAngle);

            float lineStart = faceRadius * 0.48f;
            float lineEnd = faceRadius * 0.82f;

            float startX = cx + (float) Math.cos(rad) * lineStart;
            float startY = cy + (float) Math.sin(rad) * lineStart;
            float endX = cx + (float) Math.cos(rad) * lineEnd;
            float endY = cy + (float) Math.sin(rad) * lineEnd;

            canvas.drawLine(startX, startY, endX, endY, pointerPaint);
            canvas.drawCircle(cx, cy, dp(4), hubPaint);
        }

        private void drawCenterHalo(Canvas canvas, float cx, float cy, float faceRadius) {
            canvas.drawCircle(cx, cy, faceRadius * 0.34f, centerHaloPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    requestParentDisallowIntercept(true);
                    updateFromTouch(event.getX(), event.getY(), true);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    requestParentDisallowIntercept(true);
                    updateFromTouch(event.getX(), event.getY(), true);
                    return true;

                case MotionEvent.ACTION_UP:
                    requestParentDisallowIntercept(true);
                    updateFromTouch(event.getX(), event.getY(), true);
                    performClick();
                    commitValue();
                    post(() -> requestParentDisallowIntercept(false));
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    requestParentDisallowIntercept(true);
                    commitValue();
                    post(() -> requestParentDisallowIntercept(false));
                    return true;

                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        private void updateFromTouch(float x, float y, boolean fromUser) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;

            float dx = x - cx;
            float dy = y - cy;

            float rawAngle = (float) Math.toDegrees(Math.atan2(dy, dx));
            if (rawAngle < 0f) rawAngle += 360f;

            float startNormalized = START_ANGLE % 360f;
            float endNormalized = (START_ANGLE + SWEEP_ANGLE) % 360f;

            float fraction;
            if (isInDeadZone(rawAngle, startNormalized, endNormalized)) {
                float distToStart = angularDistance(rawAngle, startNormalized);
                float distToEnd = angularDistance(rawAngle, endNormalized);
                fraction = distToStart <= distToEnd ? 0f : 1f;
            } else if (rawAngle >= startNormalized) {
                fraction = (rawAngle - startNormalized) / SWEEP_ANGLE;
            } else {
                fraction = ((rawAngle + 360f) - startNormalized) / SWEEP_ANGLE;
            }

            fraction = clamp(fraction, 0f, 1f);
            float newValue = minValue + fraction * (maxValue - minValue);
            setValue(newValue, fromUser);
        }

        private boolean isInDeadZone(float rawAngle, float startNormalized, float endNormalized) {
            return endNormalized < startNormalized
                    && rawAngle > endNormalized
                    && rawAngle < startNormalized;
        }

        private float angularDistance(float a, float b) {
            float diff = Math.abs(a - b) % 360f;
            return diff > 180f ? 360f - diff : diff;
        }

        private int withAlpha(int color, int alpha255) {
            alpha255 = Math.max(0, Math.min(255, alpha255));
            return (color & 0x00FFFFFF) | (alpha255 << 24);
        }
    }
}