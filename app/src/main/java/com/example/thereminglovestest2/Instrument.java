package com.example.thereminglovestest2;

/**
 * Contract for a single playable instrument in the sequencer engine.
 *
 * Design principles
 * ─────────────────
 * • Each instrument is self-contained: it knows its identity, its audio data,
 *   whether it is active, and at what volume it plays.
 * • The engine treats every sound as an Instrument — no special-casing for kicks
 *   vs. bass vs. any future sound you add.
 *
 * Adding a new instrument
 * ───────────────────────
 *   1. Define a new SND_* constant in DrumEngine and assign the next integer.
 *   2. Create a SynthInstrument (or another implementation) with the PCM data.
 *   3. Call DrumEngine.registerInstrument(SND_*, instrument) — no existing code changes.
 *
 * Thread safety
 * ─────────────
 * All methods may be called from the UI thread.  Implementations must ensure that
 * getPcm() is safe to read from the audio thread concurrently (volatile field).
 */
public interface Instrument {

    /** Stable machine-readable ID.  Used in log messages and as a SharedPreferences suffix. */
    String getId();

    /** Human-readable display label shown in the Beat Maker row header, e.g. "KICK". */
    String getDisplayName();

    /**
     * Pre-rendered PCM samples at 48 kHz, normalised to [-1, 1].
     * Must never return null.  The reference may be swapped atomically at runtime
     * (e.g. via {@link SynthInstrument#replacePcm}) without stopping the engine.
     */
    float[] getPcm();

    /** Whether this instrument fires when triggered by the sequencer. */
    boolean isEnabled();
    void    setEnabled(boolean on);

    /**
     * Per-instrument output level applied at mix time.
     * 1.0 = nominal.  Values above 1.0 are legal (e.g. 1.4 for bass boost).
     */
    float getVolume();
    void  setVolume(float vol);  // implementations should clamp to [0, ∞)
}
