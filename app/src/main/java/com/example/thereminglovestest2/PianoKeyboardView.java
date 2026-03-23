package com.example.thereminglovestest2;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.Collection;
import java.util.HashSet;

/**
 * On-screen piano keyboard spanning C3–C5 (2 octaves, 25 notes).
 * Draws white and black keys on a Canvas. Touch events fire NoteListener callbacks
 * with the MIDI-derived frequency, so the caller can pass the value directly to
 * ThereminAudioEngine.setTargets().
 */
public class PianoKeyboardView extends View {

    public interface NoteListener {
        /** Called once when the user's finger lands on a key (or slides to a new key). */
        void onNoteTrigger(int midiNote);
    }

    // C3..C5 = MIDI 48..72
    private static final int NUM_WHITE = 15;  // C3 D3 E3 F3 G3 A3 B3 | C4 D4 E4 F4 G4 A4 B4 | C5
    private static final int[] WHITE_MIDI = {
        48, 50, 52, 53, 55, 57, 59,   // C3 D3 E3 F3 G3 A3 B3
        60, 62, 64, 65, 67, 69, 71,   // C4 D4 E4 F4 G4 A4 B4
        72                             // C5
    };

    // Black keys (sharps) with their center x as a multiple of white-key width
    private static final int NUM_BLACK = 10;
    private static final int[] BLACK_MIDI = {
        49, 51, 54, 56, 58,   // C#3 D#3 F#3 G#3 A#3
        61, 63, 66, 68, 70    // C#4 D#4 F#4 G#4 A#4
    };
    // Center of each black key expressed in white-key-width units from the left edge
    private static final float[] BLACK_X_FRAC = {
        0.67f,  1.67f,  3.67f,  4.67f,  5.67f,   // octave 3
        7.67f,  8.67f, 10.67f, 11.67f, 12.67f    // octave 4
    };

    private final Paint whiteFill    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blackFill    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pressedFill  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeArpFill= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activeArpGlow= new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint border       = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect         = new RectF();
    private final RectF glowRect     = new RectF();

    // MIDI notes currently looping an arpeggio (persistent highlight)
    private final HashSet<Integer> activeMidiSet = new HashSet<>();

    private NoteListener listener;
    private int activePointer = -1;
    private int pressedMidi   = -1;

    public PianoKeyboardView(Context ctx) { super(ctx); init(); }
    public PianoKeyboardView(Context ctx, AttributeSet a) { super(ctx, a); init(); }
    public PianoKeyboardView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        whiteFill.setColor(0xFFDDDDEE);
        whiteFill.setStyle(Paint.Style.FILL);

        blackFill.setColor(0xFF111122);
        blackFill.setStyle(Paint.Style.FILL);

        pressedFill.setColor(0xFF00FF9D);
        pressedFill.setStyle(Paint.Style.FILL);

        activeArpFill.setColor(0xFF00CCFF);
        activeArpFill.setStyle(Paint.Style.FILL);

        activeArpGlow.setColor(0x6000CCFF);  // semi-transparent cyan outer glow
        activeArpGlow.setStyle(Paint.Style.STROKE);
        activeArpGlow.setStrokeWidth(4f);

        border.setColor(0xFF3A3A5A);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(1.2f);
    }

    public void setNoteListener(NoteListener l) { listener = l; }

    /** Set which MIDI notes should show the arpeggio-active glow. Pass empty/null to clear. */
    public void setActiveMidiNotes(Collection<Integer> notes) {
        activeMidiSet.clear();
        if (notes != null) activeMidiSet.addAll(notes);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w   = getWidth();
        float h   = getHeight();
        float wkw = w / NUM_WHITE;       // white key width
        float bkw = wkw * 0.58f;         // black key width
        float bkh = h * 0.60f;           // black key height

        // White keys (drawn first so black keys appear on top)
        for (int i = 0; i < NUM_WHITE; i++) {
            boolean pressed = (WHITE_MIDI[i] == pressedMidi);
            boolean active  = activeMidiSet.contains(WHITE_MIDI[i]);
            rect.set(i * wkw + 1, 1, (i + 1) * wkw - 1, h - 2);
            // Draw outer glow ring before fill for active keys
            if (active) {
                glowRect.set(rect.left - 2, rect.top - 2, rect.right + 2, rect.bottom + 2);
                canvas.drawRoundRect(glowRect, 6, 6, activeArpGlow);
            }
            canvas.drawRoundRect(rect, 4, 4, active ? activeArpFill : (pressed ? pressedFill : whiteFill));
            canvas.drawRoundRect(rect, 4, 4, border);
        }

        // Black keys on top
        for (int i = 0; i < NUM_BLACK; i++) {
            boolean pressed = (BLACK_MIDI[i] == pressedMidi);
            boolean active  = activeMidiSet.contains(BLACK_MIDI[i]);
            float cx = BLACK_X_FRAC[i] * wkw;
            rect.set(cx - bkw / 2f, 0, cx + bkw / 2f, bkh);
            if (active) {
                glowRect.set(rect.left - 2, rect.top - 2, rect.right + 2, rect.bottom + 2);
                canvas.drawRoundRect(glowRect, 5, 5, activeArpGlow);
            }
            canvas.drawRoundRect(rect, 3, 3, active ? activeArpFill : (pressed ? pressedFill : blackFill));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pIdx   = event.getActionIndex();
        int pId    = event.getPointerId(pIdx);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (activePointer == -1) {
                    activePointer = pId;
                    setPressedNote(midiAt(event.getX(pIdx), event.getY(pIdx)));
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (activePointer != -1) {
                    int pi = event.findPointerIndex(activePointer);
                    if (pi >= 0) {
                        int midi = midiAt(event.getX(pi), event.getY(pi));
                        if (midi != pressedMidi) setPressedNote(midi);
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_CANCEL:
                if (pId == activePointer || action == MotionEvent.ACTION_CANCEL) {
                    activePointer = -1;
                    setPressedNote(-1);
                }
                break;
        }
        return true;
    }

    /** Returns the MIDI note number at screen coordinates (x, y), or -1 if none. */
    private int midiAt(float x, float y) {
        float wkw = (float) getWidth() / NUM_WHITE;
        float bkw = wkw * 0.58f;
        float bkh = getHeight() * 0.60f;

        // Black keys take priority (they sit on top of white keys visually)
        if (y < bkh) {
            for (int i = 0; i < NUM_BLACK; i++) {
                float cx = BLACK_X_FRAC[i] * wkw;
                if (x >= cx - bkw / 2f && x <= cx + bkw / 2f) {
                    return BLACK_MIDI[i];
                }
            }
        }

        // White key fallback
        int slot = (int)(x / wkw);
        if (slot >= 0 && slot < NUM_WHITE) return WHITE_MIDI[slot];
        return -1;
    }

    private void setPressedNote(int midi) {
        int prev = pressedMidi;
        pressedMidi = midi;
        invalidate();
        // Fire trigger on press or slide to a different key — not on release
        if (midi >= 0 && midi != prev && listener != null) {
            listener.onNoteTrigger(midi);
        }
    }
}
