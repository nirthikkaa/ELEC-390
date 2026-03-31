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

    private final Paint outerShadow = fill("#08101A");
    private final Paint outerFill = fill("#111522");
    private final Paint outerStroke = stroke("#5B709A", 1.1f);
    private final Paint ringGlow = stroke("#43E5FF", 1.8f);
    private final Paint faceFill = fill("#1B2335");
    private final Paint faceShade = fill("#24314B");
    private final Paint faceGloss = fill("#80D5E8FF");
    private final Paint innerDish = fill("#10182A");
    private final Paint accentArc = stroke("#43E5FF", 2.8f);
    private final Paint majorTick = stroke("#E3F4FF", 1.8f);
    private final Paint minorTick = stroke("#506788", 1.2f);
    private final Paint pointerGlow = stroke("#43E5FF", 5f);
    private final Paint pointer = stroke("#F6F1FF", 3f);
    private final Paint notch = fill("#EAF6FF");
    private final Paint hubShadow = fill("#4D87A6C0");
    private final Paint hub = fill("#F8FCFF");
    private final Paint hubInner = fill("#B7DBF4");
    private final Paint label = text("#F6F1FF", 12f);
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
        canvas.drawCircle(cx, cy - dp(12), faceR * 0.72f, faceGloss);
        canvas.drawCircle(cx, cy, dishR, innerDish);

        arcRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
        canvas.drawArc(arcRect, 120f, 300f, false, accentArc);
        drawTicks(canvas, cx, cy, ringR);
        drawPointer(canvas, cx, cy, dishR - dp(3));
        drawNotch(canvas, cx, cy, faceR);
        canvas.drawCircle(cx, cy + dp(1.5f), dp(8), hubShadow);
        canvas.drawCircle(cx, cy, dp(7), hub);
        canvas.drawCircle(cx, cy - dp(1.2f), dp(3.2f), hubInner);
        canvas.drawText(currentAbbrev(), cx, cy + dp(27), label);
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
            double rad = Math.toRadians(120f + i * span);
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

    private void drawNotch(Canvas canvas, float cx, float cy, float radius) {
        canvas.drawCircle(cx, cy - radius + dp(8), dp(2.7f), notch);
    }

    private void updateShaders(float cx, float cy, float outerR, float faceR, float dishR) {
        outerFill.setShader(new RadialGradient(
                cx, cy - outerR * 0.35f, outerR * 1.15f,
                new int[]{Color.parseColor("#29324A"), Color.parseColor("#111522"), Color.parseColor("#090C14")},
                new float[]{0f, 0.58f, 1f},
                Shader.TileMode.CLAMP));
        faceFill.setShader(new RadialGradient(
                cx - faceR * 0.28f, cy - faceR * 0.32f, faceR * 1.15f,
                new int[]{Color.parseColor("#344564"), Color.parseColor("#202A3F"), Color.parseColor("#121A28")},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        faceShade.setShader(new LinearGradient(
                cx, cy - faceR, cx, cy + faceR,
                Color.parseColor("#00000000"),
                Color.parseColor("#7A05070D"),
                Shader.TileMode.CLAMP));
        faceGloss.setShader(new RadialGradient(
                cx, cy - faceR * 0.72f, faceR * 0.9f,
                new int[]{Color.parseColor("#70E8F6FF"), Color.parseColor("#18E8F6FF"), Color.TRANSPARENT},
                new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        innerDish.setShader(new RadialGradient(
                cx - dishR * 0.18f, cy - dishR * 0.18f, dishR * 1.1f,
                new int[]{Color.parseColor("#21314A"), Color.parseColor("#0D1422")},
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

    private String currentAbbrev() {
        if (tones.length == 0) return "SIN";
        return abbrev(tones[wrapIndex(currentIndex)]);
    }

    private static Paint fill(String color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor(color));
        return p;
    }

    private Paint stroke(String color, float widthDp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Color.parseColor(color));
        p.setStrokeWidth(dp(widthDp));
        return p;
    }

    private Paint text(String color, float sizeSp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Color.parseColor(color));
        p.setTextAlign(Paint.Align.CENTER);
        p.setFakeBoldText(true);
        p.setTextSize(sizeSp * getResources().getDisplayMetrics().scaledDensity);
        return p;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static String abbrev(String tone) {
        switch (AppSettings.normalizeToneType(tone)) {
            case AppSettings.TONE_THEREMIN: return "THR";
            case AppSettings.TONE_AIR_PAD:  return "AIR";
            case AppSettings.TONE_CELLO:    return "CEL";
            case AppSettings.TONE_SWEET_LEAD:return "SWT";
            case AppSettings.TONE_CHOIR:    return "CHR";
            case AppSettings.TONE_VOWEL_O:  return "VOX";
            case AppSettings.TONE_CLARINET: return "CLR";
            case AppSettings.TONE_OBOE:     return "OBO";
            case AppSettings.TONE_LEAD:     return "LED";
            case AppSettings.TONE_VIOLIN:   return "VLN";
            case AppSettings.TONE_GUITAR:   return "GTR";
            case AppSettings.TONE_FLUTE:    return "FLT";
            case AppSettings.TONE_TRUMPET:  return "TRP";
            case AppSettings.TONE_SQUARE:   return "SQR";
            case AppSettings.TONE_TRIANGLE: return "TRI";
            case AppSettings.TONE_SAW:      return "SAW";
            case AppSettings.TONE_PULSE:    return "PLS";
            case AppSettings.TONE_ORGAN:    return "ORG";
            case AppSettings.TONE_STRING:   return "STR";
            case AppSettings.TONE_BELL:     return "BEL";
            case AppSettings.TONE_PAD:      return "PAD";
            default:                        return "THR";
        }
    }
}
