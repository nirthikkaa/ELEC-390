package com.example.thereminglovestest2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SoundEffectConstants;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.Arrays;

public class ToneKnobView extends View {

    public interface OnToneStepListener {
        void onToneStep(int delta);
    }

    private final Paint outerShadow = fill("#08111B");
    private final Paint outerFill = fill("#142235");
    private final Paint outerStroke = stroke("#3B6282", 1.1f);
    private final Paint ringGlow = stroke("#43E5FF", 1.8f);
    private final Paint faceFill = fill("#192A40");
    private final Paint faceShade = fill("#132033");
    private final Paint innerDish = fill("#122035");
    private final Paint accentArc = stroke("#43E5FF", 2.4f, Paint.Cap.BUTT);
    private final Paint majorTick = stroke("#5DDCF6", 1.5f, Paint.Cap.BUTT);
    private final Paint minorTick = stroke("#2F4D67", 1.0f, Paint.Cap.BUTT);
    private final Paint pointerGlow = stroke("#43E5FF", 4f, Paint.Cap.ROUND);
    private final Paint pointer = stroke("#86EEFF", 2.6f, Paint.Cap.ROUND);
    private final RectF arcRect = new RectF();

    private String[] tones = new String[0];
    private int currentIndex;
    private int activeTouchIndex = -1;
    private OnToneStepListener listener;

    public ToneKnobView(Context context) {
        this(context, null);
    }

    public ToneKnobView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ToneKnobView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setClickable(true);
        setFocusable(true);
    }

    public void setToneSequence(String[] sequence) {
        tones = sequence == null ? new String[0] : Arrays.copyOf(sequence, sequence.length);
        currentIndex = clampIndex(currentIndex);
        invalidate();
    }

    public void setCurrentTone(String tone) {
        currentIndex = indexOf(tone);
        invalidate();
    }

    public void setOnToneStepListener(@Nullable OnToneStepListener stepListener) {
        listener = stepListener;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float outerR = Math.min(w, h) / 2f - dp(4);
        float faceR = outerR - dp(8);
        float ringR = faceR - dp(10);
        float dishR = faceR - dp(18);

        updateShaders(cx, cy, outerR, faceR, dishR);
        canvas.drawCircle(cx, cy + dp(2), outerR, outerShadow);
        canvas.drawCircle(cx, cy, outerR, outerFill);
        canvas.drawCircle(cx, cy, outerR, outerStroke);
        canvas.drawCircle(cx, cy, outerR - dp(2.4f), ringGlow);
        canvas.drawCircle(cx, cy, faceR, faceFill);
        canvas.drawCircle(cx, cy, faceR, faceShade);
        canvas.drawCircle(cx, cy, dishR, innerDish);

        arcRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
        canvas.drawArc(arcRect, 120f, 300f, false, accentArc);
        drawTicks(canvas, cx, cy, ringR);
        drawPointer(canvas, cx, cy, dishR - dp(3));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (tones.length == 0) return super.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!insideKnob(event.getX(), event.getY())) return false;
                activeTouchIndex = angleToIndex(event.getX(), event.getY());
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (activeTouchIndex < 0) return false;
                stepToward(angleToIndex(event.getX(), event.getY()));
                return true;
            case MotionEvent.ACTION_UP:
                if (activeTouchIndex < 0) return false;
                stepToward(angleToIndex(event.getX(), event.getY()));
                activeTouchIndex = -1;
                performClick();
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                activeTouchIndex = -1;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void stepToward(int targetIndex) {
        if (activeTouchIndex < 0 || targetIndex == activeTouchIndex) return;
        int delta = shortestDelta(activeTouchIndex, targetIndex);
        while (delta != 0) {
            int step = delta > 0 ? 1 : -1;
            activeTouchIndex = wrapIndex(activeTouchIndex + step);
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            playSoundEffect(SoundEffectConstants.CLICK);
            if (listener != null) listener.onToneStep(step);
            delta -= step;
        }
    }

    private int shortestDelta(int from, int to) {
        int size = Math.max(1, tones.length);
        int forward = (to - from + size) % size;
        int backward = forward - size;
        return Math.abs(forward) <= Math.abs(backward) ? forward : backward;
    }

    private int angleToIndex(float x, float y) {
        if (tones.length <= 1) return 0;
        float raw = (float) Math.toDegrees(Math.atan2(y - getHeight() / 2f, x - getWidth() / 2f));
        if (raw < 0f) raw += 360f;
        // Arc occupies 120° → 420° (≡ 60°) CW; shift so arc-start maps to 0°.
        float adj = raw - 120f;
        if (adj < 0f) adj += 360f;
        int idx = Math.round(adj / 300f * (tones.length - 1));
        return clampIndex(idx);
    }

    private void drawTicks(Canvas canvas, float cx, float cy, float radius) {
        int count = Math.max(tones.length, 1);
        float span = count > 1 ? 300f / (count - 1) : 0f;
        for (int i = 0; i < count; i++) {
            float angle = 120f + i * span;
            float normalizedAngle = angle % 360f;
            if (normalizedAngle < 0f) normalizedAngle += 360f;
            // The small control reads the top-center resting tick as a bright dot on some phones.
            if (i != currentIndex && Math.abs(normalizedAngle - 270f) < 0.01f) continue;
            double rad = Math.toRadians(angle);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            float outer = radius;
            float inner = radius - dp(i == currentIndex ? 13 : 7);
            Paint paint = i == currentIndex ? majorTick : minorTick;
            canvas.drawLine(cx + cos * inner, cy + sin * inner, cx + cos * outer, cy + sin * outer, paint);
        }
    }

    private void drawPointer(Canvas canvas, float cx, float cy, float radius) {
        if (tones.length == 0) return;
        float span = tones.length > 1 ? 300f / (tones.length - 1) : 0f;
        double rad = Math.toRadians(120f + currentIndex * span);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        float start = dp(13);
        canvas.drawLine(cx + cos * start, cy + sin * start, cx + cos * radius, cy + sin * radius, pointerGlow);
        canvas.drawLine(cx + cos * start, cy + sin * start, cx + cos * radius, cy + sin * radius, pointer);
    }

    private void updateShaders(float cx, float cy, float outerR, float faceR, float dishR) {
        outerFill.setShader(new RadialGradient(
                cx, cy - outerR * 0.35f, outerR * 1.15f,
                new int[]{Color.parseColor("#223751"), Color.parseColor("#17263A"), Color.parseColor("#0D1623")},
                new float[]{0f, 0.62f, 1f},
                Shader.TileMode.CLAMP));
        faceFill.setShader(new RadialGradient(
                cx - faceR * 0.28f, cy - faceR * 0.32f, faceR * 1.15f,
                new int[]{Color.parseColor("#2A4564"), Color.parseColor("#20344C"), Color.parseColor("#162638")},
                new float[]{0f, 0.56f, 1f},
                Shader.TileMode.CLAMP));
        faceShade.setShader(new LinearGradient(
                cx, cy - faceR, cx, cy + faceR,
                Color.parseColor("#00000000"),
                Color.parseColor("#2A122033"),
                Shader.TileMode.CLAMP));
        innerDish.setShader(new RadialGradient(
                cx - dishR * 0.18f, cy - dishR * 0.18f, dishR * 1.1f,
                new int[]{Color.parseColor("#243B57"), Color.parseColor("#142337")},
                new float[]{0f, 1f},
                Shader.TileMode.CLAMP));
    }

    private boolean insideKnob(float x, float y) {
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float dx = x - cx;
        float dy = y - cy;
        float r = Math.min(getWidth(), getHeight()) / 2f;
        return dx * dx + dy * dy <= r * r;
    }

    private int indexOf(String tone) {
        String normalized = AppSettings.normalizeToneType(tone);
        for (int i = 0; i < tones.length; i++) {
            if (normalized.equals(AppSettings.normalizeToneType(tones[i]))) return i;
        }
        return clampIndex(currentIndex);
    }

    private int clampIndex(int index) {
        if (tones.length == 0) return 0;
        return Math.max(0, Math.min(tones.length - 1, index));
    }

    private int wrapIndex(int index) {
        if (tones.length == 0) return 0;
        int size = tones.length;
        int wrapped = index % size;
        return wrapped < 0 ? wrapped + size : wrapped;
    }

    private static Paint fill(String color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor(color));
        return p;
    }

    private Paint stroke(String color, float widthDp, Paint.Cap cap) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(cap);
        p.setColor(Color.parseColor(color));
        p.setStrokeWidth(dp(widthDp));
        return p;
    }

    private Paint stroke(String color, float widthDp) {
        return stroke(color, widthDp, Paint.Cap.ROUND);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
