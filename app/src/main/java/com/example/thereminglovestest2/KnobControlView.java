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

public class KnobControlView extends LinearLayout {

    public interface OnKnobValueChangedListener {
        void onValueChanged(KnobControlView knob, float value, boolean fromUser);
    }

    public interface OnKnobCommitListener {
        void onValueCommitted(KnobControlView knob, float value);
    }

    private final TextView titleView;
    private final TextView valueView;
    private final DialFaceView dialView;

    private float minValue;
    private float maxValue = 100f;
    private float stepSize = 1f;
    private float currentValue;

    private OnKnobValueChangedListener valueChangedListener;
    private OnKnobCommitListener commitListener;

    public KnobControlView(Context context) { this(context, null); }
    public KnobControlView(Context context, @Nullable AttributeSet attrs) { this(context, attrs, 0); }

    public KnobControlView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setPadding(dp(4), dp(2), dp(4), dp(2));

        titleView = newLabel(context, 11f, false);
        titleView.setMaxLines(2);
        addView(titleView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        FrameLayout frame = new FrameLayout(context);
        LayoutParams frameLp = new LayoutParams(dp(148), dp(148));
        frameLp.topMargin = dp(4);
        frame.setLayoutParams(frameLp);
        frame.setClipChildren(false);
        frame.setClipToPadding(false);

        dialView = new DialFaceView(context);
        frame.addView(dialView, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        valueView = newLabel(context, 15f, true);
        valueView.setClickable(true);
        valueView.setFocusable(true);
        valueView.setMinWidth(dp(82));
        valueView.setMinHeight(dp(44));
        valueView.setPadding(dp(10), dp(6), dp(10), dp(6));
        valueView.setBackgroundColor(Color.TRANSPARENT);
        valueView.setIncludeFontPadding(false);
        valueView.setShadowLayer(dp(4), 0f, 0f, 0xCC090614);
        frame.addView(valueView, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        addView(frame, frameLp);
        refreshVisuals();
    }

    private TextView newLabel(Context context, float sizeSp, boolean bold) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(themeColor(android.R.attr.textColorPrimary, Color.WHITE));
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        return tv;
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
        this.maxValue = Math.max(minValue, maxValue);
        setValue(currentValue, false);
    }

    public void setStepSize(float stepSize) {
        this.stepSize = stepSize <= 0f ? 1f : stepSize;
        setValue(currentValue, false);
    }

    public void setValue(float value) { setValue(value, false); }
    public float getValue() { return currentValue; }

    public void setOnKnobValueChangedListener(@Nullable OnKnobValueChangedListener listener) {
        valueChangedListener = listener;
    }

    public void setOnKnobCommitListener(@Nullable OnKnobCommitListener listener) {
        commitListener = listener;
    }

    @Override
    public void setOnClickListener(@Nullable OnClickListener l) {
        valueView.setOnClickListener(l);
    }

    private void setValue(float value, boolean fromUser) {
        float snapped = snap(clamp(value, minValue, maxValue));
        if (fromUser && Math.abs(snapped - currentValue) < 0.0001f) {
            refreshVisuals();
            return;
        }
        currentValue = snapped;
        refreshVisuals();
        if (valueChangedListener != null) valueChangedListener.onValueChanged(this, currentValue, fromUser);
    }

    private void commitValue() {
        if (commitListener != null) commitListener.onValueCommitted(this, currentValue);
    }

    private void refreshVisuals() {
        dialView.invalidate();
        valueView.bringToFront();
        dialView.setContentDescription(titleView.getText() + ": " + valueView.getText());
    }

    private float fraction() {
        float span = maxValue - minValue;
        return span <= 0f ? 0f : (currentValue - minValue) / span;
    }

    private boolean isAngleDial() {
        CharSequence label = titleView.getText();
        return label != null && label.toString().toLowerCase(Locale.US).contains("angle");
    }

    private float snap(float value) {
        if (stepSize <= 0f) return value;
        return clamp(Math.round((value - minValue) / stepSize) * stepSize + minValue, minValue, maxValue);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int themeColor(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (!getContext().getTheme().resolveAttribute(attr, tv, true)) return fallback;
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) return tv.data;
        if (tv.resourceId != 0) {
            try { return ContextCompat.getColor(getContext(), tv.resourceId); }
            catch (Exception ignored) { }
        }
        return fallback;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void disallowParentIntercept(boolean disallow) {
        for (ViewParent p = getParent(); p != null; p = p.getParent()) p.requestDisallowInterceptTouchEvent(disallow);
    }

    private final class DialFaceView extends View {
        private static final float START = 120f;
        private static final float SWEEP = 300f;

        private final RectF arcRect = new RectF();
        private final Paint bezelFill = fill(0xFF07051A);
        private final Paint faceFill = fill(0xFF0A061E);
        private final Paint haloFill = fill(0xCC140F2C);
        private final Paint bezelStroke = stroke(alpha(themeColor(android.R.attr.textColorSecondary, 0xFFAFA8D6), 95), 2);
        private final Paint track = roundStroke(alpha(themeColor(android.R.attr.textColorSecondary, 0xFFAFA8D6), 120), 8);
        private final Paint progress = roundStroke(alpha(themeColor(android.R.attr.colorAccent, 0xFF41D8FF), 235), 8);
        private final Paint majorTick = roundStroke(alpha(themeColor(android.R.attr.textColorPrimary, 0xFFFFFFFF), 220), 2);
        private final Paint minorTick = roundStroke(alpha(themeColor(android.R.attr.textColorPrimary, 0xFFFFFFFF), 130), 1);
        private final Paint pointer = roundStroke(themeColor(android.R.attr.colorAccent, 0xFF41D8FF), 4);
        private final Paint hub = fill(alpha(themeColor(android.R.attr.textColorPrimary, 0xFFFFFFFF), 235));
        private final Paint label = text(alpha(themeColor(android.R.attr.textColorPrimary, 0xFFFFFFFF), 225), 9);

        DialFaceView(Context context) { super(context); }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float w = getWidth(), h = getHeight(), cx = w / 2f, cy = h / 2f;
            boolean angleDial = isAngleDial();
            float outer = Math.min(w, h) / 2f - dp(angleDial ? 8 : 10);
            float face = outer - dp(angleDial ? 12 : 10);

            canvas.drawCircle(cx, cy, outer, bezelFill);
            canvas.drawCircle(cx, cy, outer, bezelStroke);
            canvas.drawCircle(cx, cy, face, faceFill);
            drawTicks(canvas, cx, cy, outer, angleDial);

            float inset = (Math.min(w, h) / 2f) - (face - dp(8));
            arcRect.set(inset, inset, w - inset, h - inset);
            canvas.drawArc(arcRect, START, SWEEP, false, track);
            canvas.drawArc(arcRect, START, SWEEP * fraction(), false, progress);

            if (angleDial) drawAngleLabels(canvas, cx, cy, outer - dp(29));
            drawPointer(canvas, cx, cy, face);
            canvas.drawCircle(cx, cy, face * 0.34f, haloFill);
        }

        private void drawTicks(Canvas canvas, float cx, float cy, float outerRadius, boolean angleDial) {
            int tickCount = angleDial ? 36 : 30;
            int majorEvery = angleDial ? 9 : 5;
            float outer = outerRadius - dp(7);
            float minorInner = outerRadius - dp(14);
            float majorInner = outerRadius - dp(20);
            for (int i = 0; i <= tickCount; i++) {
                float angle = START + SWEEP * (i / (float) tickCount);
                double rad = Math.toRadians(angle);
                boolean major = i % majorEvery == 0;
                float inner = major ? majorInner : minorInner;
                Paint paint = major ? majorTick : minorTick;
                canvas.drawLine(
                        cx + (float) Math.cos(rad) * inner,
                        cy + (float) Math.sin(rad) * inner,
                        cx + (float) Math.cos(rad) * outer,
                        cy + (float) Math.sin(rad) * outer,
                        paint);
            }
        }

        private void drawAngleLabels(Canvas canvas, float cx, float cy, float radius) {
            int[] values = {-90, -45, 0, 45, 90};
            float baseline = (label.ascent() + label.descent()) / 2f;
            for (int i = 0; i < values.length; i++) {
                double rad = Math.toRadians(START + SWEEP * (i / 4f));
                canvas.drawText(String.valueOf(values[i]),
                        cx + (float) Math.cos(rad) * radius,
                        cy + (float) Math.sin(rad) * radius - baseline,
                        label);
            }
        }

        private void drawPointer(Canvas canvas, float cx, float cy, float faceRadius) {
            double rad = Math.toRadians(START + SWEEP * fraction());
            float start = faceRadius * 0.48f, end = faceRadius * 0.82f;
            canvas.drawLine(
                    cx + (float) Math.cos(rad) * start,
                    cy + (float) Math.sin(rad) * start,
                    cx + (float) Math.cos(rad) * end,
                    cy + (float) Math.sin(rad) * end,
                    pointer);
            canvas.drawCircle(cx, cy, dp(4), hub);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    disallowParentIntercept(true);
                    updateFromTouch(event.getX(), event.getY());
                    return true;
                case MotionEvent.ACTION_UP:
                    disallowParentIntercept(true);
                    updateFromTouch(event.getX(), event.getY());
                    performClick();
                    commitValue();
                    post(() -> disallowParentIntercept(false));
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    commitValue();
                    post(() -> disallowParentIntercept(false));
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        private void updateFromTouch(float x, float y) {
            float raw = (float) Math.toDegrees(Math.atan2(y - getHeight() / 2f, x - getWidth() / 2f));
            if (raw < 0f) raw += 360f;

            float start = START % 360f;
            float end = (START + SWEEP) % 360f;
            float fraction;
            if (end < start && raw > end && raw < start) {
                float toStart = angularDistance(raw, start);
                float toEnd = angularDistance(raw, end);
                fraction = toStart <= toEnd ? 0f : 1f;
            } else {
                fraction = raw >= start ? (raw - start) / SWEEP : ((raw + 360f) - start) / SWEEP;
            }
            setValue(minValue + clamp(fraction, 0f, 1f) * (maxValue - minValue), true);
        }

        @Override public boolean performClick() { return super.performClick(); }

        private float angularDistance(float a, float b) {
            float diff = Math.abs(a - b) % 360f;
            return diff > 180f ? 360f - diff : diff;
        }

        private Paint fill(int color) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            return p;
        }

        private Paint stroke(int color, int widthDp) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(widthDp));
            p.setColor(color);
            return p;
        }

        private Paint roundStroke(int color, int widthDp) {
            Paint p = stroke(color, widthDp);
            p.setStrokeCap(Paint.Cap.ROUND);
            return p;
        }

        private Paint text(int color, int sizeDp) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.FILL);
            p.setColor(color);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(sizeDp));
            return p;
        }

        private int alpha(int color, int alpha255) {
            return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha255)) << 24);
        }
    }
}
