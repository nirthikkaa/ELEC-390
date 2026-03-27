package com.example.thereminglovestest2;

/**
 * Decides which instrument sounds fire on each sequencer clock tick.
 *
 * Responsibilities
 * ────────────────
 * A PatternSource receives a tick (step + bar + enable flags) and calls back into
 * a {@link TriggerDispatcher} for every sound that should play at that step.
 * It does not directly access the voice pool or PCM data — those are the engine's
 * concern.  This separation means:
 *
 *   • Adding a new pattern type (Euclidean, generative, MIDI-driven, …)
 *     = implement PatternSource.  Nothing else changes.
 *   • Adding a new instrument = add a SND_* constant and a synthesis method.
 *     Existing PatternSource implementations continue to work unchanged.
 *
 * The two trigger methods in {@link TriggerDispatcher} distinguish between
 * percussion hits (a specific sound index at a given accent level) and bass notes
 * (a pitch slot that the engine maps to the appropriate PCM buffer and also uses to
 * update the bass-hit timestamp for the UI pulse animation).
 *
 * @see GridPatternSource
 * @see BuiltInPatternSource
 */
public interface PatternSource {

    /**
     * Receives trigger commands issued by a pattern query.
     *
     * Implementations in DrumEngine are pre-allocated fields — zero allocation on
     * the scheduler hot path.
     */
     interface TriggerDispatcher {

        /**
         * Fire a percussion or melodic instrument hit.
         *
         * @param soundIdx  DrumEngine.SND_* constant identifying the sound
         * @param volScale  per-hit accent factor in (0, 1]; 1.0 = full level
         */
        void fire(int soundIdx, float volScale);

        /**
         * Fire one bass pitch.
         * The engine translates noteSlot to the corresponding sound index and
         * records the hit timestamp for the UI bass-pulse animation.
         *
         * @param noteSlot  0=E2, 1=A2, 2=D3, 3=G2  (maps to SND_BASS_E2 + noteSlot)
         */
        void fireBass(int noteSlot);

        /**
         * Fire one piano/keyboard note event.
         *
         * @param midiNote MIDI note number in the supported keyboard range
         */
        void firePiano(int midiNote);
    }

    /**
     * Evaluate this pattern for one clock tick and dispatch all sounds that fire.
     *
     * Constraints: must be non-blocking and allocation-free — called on the
     * SequencerClock thread at every 16th-note boundary (~125 ms at 120 BPM).
     *
     * @param step16   current 16th-note step within the bar (0–15)
     * @param bar      monotonically increasing bar index
     * @param drumsOn  whether the drum bus is currently enabled
     * @param bassOn   whether the bass bus is currently enabled
     * @param out      dispatcher to invoke for each sound that should trigger
     */
    void query(int step16, int bar, boolean drumsOn, boolean bassOn, TriggerDispatcher out);
}
