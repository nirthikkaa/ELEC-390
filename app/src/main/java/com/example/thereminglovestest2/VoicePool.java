package com.example.thereminglovestest2;

import java.util.concurrent.atomic.AtomicIntegerArray;

/**
 * Lock-free polyphonic voice pool for one-shot PCM sample playback.
 *
 * Memory model
 * ────────────
 * Trigger side (scheduler thread):
 *   writes voiceVolScale[i], then calls voiceSound.set(i, ...), then voicePos.set(i, 0).
 * Mix side (audio thread):
 *   reads voicePos.get(i) — if ≥ 0, reads voiceSound.get(i) and voiceVolScale[i].
 *
 * The AtomicIntegerArray store/load provide a happens-before edge:
 *   voicePos.set(0) (scheduler) hb voicePos.get() > 0 (audio thread).
 * Therefore voiceVolScale[i] written before voicePos.set() is guaranteed visible
 * to the audio thread after it observes voicePos > 0.
 *
 * Polyphony
 * ─────────
 * Change MAX_VOICES to tune simultaneous hit capacity.  No other code changes needed.
 *
 * Voice stealing
 * ──────────────
 * When all slots are occupied, the slot furthest into playback (nearest to finishing)
 * is stolen.  This minimises the perceptible artefact: only a truncated tail on the
 * oldest sound, which is typically inaudible in a dense drum mix.
 */
public final class VoicePool {

    private static final int MAX_VOICES = 16;

    private final AtomicIntegerArray voiceSound    = new AtomicIntegerArray(MAX_VOICES);
    private final AtomicIntegerArray voicePos      = new AtomicIntegerArray(MAX_VOICES);
    /**
     * Sub-buffer sample offset for the first mix call on each voice.
     * Set alongside voicePos=0 so the voice begins at the exact sample within the
     * current buffer where its trigger fired (sample-accurate onset).
     * Only used when pos==0; on subsequent buffer fills the voice starts at i=0.
     */
    private final AtomicIntegerArray voiceStartAt  = new AtomicIntegerArray(MAX_VOICES);
    /**
     * Per-voice amplitude scale for accent/humanization.
     * Written before voicePos.set() → happens-before read after voicePos.get() > 0.
     */
    private final float[]            voiceVolScale = new float[MAX_VOICES];

    public VoicePool() {
        for (int i = 0; i < MAX_VOICES; i++) { voicePos.set(i, -1); voiceStartAt.set(i, 0); }
        java.util.Arrays.fill(voiceVolScale, 1.0f);
    }

    // ── Trigger side (scheduler thread) ──────────────────────────────────────

    /**
     * Arm a one-shot voice for the given sound, starting at sample 0 of the next buffer.
     * Thread-safe; intended for the scheduler thread.
     *
     * @param soundIdx  index into the pcmTable passed to {@link #mixInto}
     * @param volScale  per-hit amplitude scale in (0, 1]; use 1.0 for unaccented hits
     */
    public void trigger(int soundIdx, float volScale) {
        trigger(soundIdx, volScale, 0);
    }

    /**
     * Arm a one-shot voice with a sub-buffer start offset for sample-accurate onset.
     * Call this from the audio thread when the trigger falls within the current buffer.
     *
     * @param soundIdx  index into the pcmTable passed to {@link #mixInto}
     * @param volScale  per-hit amplitude scale in (0, 1]; use 1.0 for unaccented hits
     * @param startAt   sample index within the current buffer where the sound begins (0 = immediate)
     */
    public void trigger(int soundIdx, float volScale, int startAt) {
        for (int i = 0; i < MAX_VOICES; i++) {
            if (voicePos.get(i) < 0) {
                arm(i, soundIdx, volScale, startAt);
                return;
            }
        }
        // All slots busy — steal the one furthest through its sample (most nearly finished).
        int stale = 0, maxPos = 0;
        for (int i = 0; i < MAX_VOICES; i++) {
            int p = voicePos.get(i);
            if (p > maxPos) { maxPos = p; stale = i; }
        }
        arm(stale, soundIdx, volScale, startAt);
    }

    private void arm(int slot, int soundIdx, float volScale, int startAt) {
        voiceVolScale[slot] = volScale;        // happens-before voicePos.set() below
        voiceSound.set(slot, soundIdx);
        voiceStartAt.set(slot, startAt);
        voicePos.set(slot, 0);                 // arms the voice; audio thread picks up here
    }

    // ── Mix side (audio thread) ───────────────────────────────────────────────

    /**
     * Mix all active voices into {@code buffer}.
     *
     * Must be called on the audio thread.  Allocation-free, non-blocking.
     *
     * @param buffer    output sample buffer (modified in place); already contains prior audio
     * @param count     number of frames to process (≤ buffer.length)
     * @param pcmTable  PCM data indexed by soundIdx; entries may be null (voice skipped)
     * @param volTable  per-sound volume scale, parallel to pcmTable
     * @param busGain   overall output level for this pool (e.g. 0.58 for the drum bus)
     */
    public void mixInto(short[] buffer, int count,
                        float[][] pcmTable, float[] volTable, float busGain) {
        for (int v = 0; v < MAX_VOICES; v++) {
            int pos = voicePos.get(v);
            if (pos < 0) continue;

            int snd = voiceSound.get(v);
            if (snd < 0 || snd >= pcmTable.length) { voicePos.set(v, -1); continue; }
            float[] pcm = pcmTable[snd];
            if (pcm == null)                        { voicePos.set(v, -1); continue; }

            float gain = volTable[snd] * voiceVolScale[v] * busGain;
            // On the very first buffer for this voice, honor the sub-buffer start offset
            // so the onset falls at the exact sample where the trigger fired.
            int iStart = (pos == 0) ? Math.max(0, voiceStartAt.get(v)) : 0;
            for (int i = iStart; i < count; i++) {
                if (pos >= pcm.length) { pos = -1; break; }
                int mixed = buffer[i] + Math.round(pcm[pos] * gain * Short.MAX_VALUE);
                buffer[i] = (short) Math.max(Short.MIN_VALUE,
                                    Math.min(Short.MAX_VALUE, mixed));
                pos++;
            }
            voicePos.set(v, pos);
        }
    }

    /** Silence all active voices immediately.  Call on sequencer stop. */
    public void reset() {
        for (int i = 0; i < MAX_VOICES; i++) { voicePos.set(i, -1); voiceStartAt.set(i, 0); }
    }
}
