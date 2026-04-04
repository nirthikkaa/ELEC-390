package com.example.thereminglovestest2;

/**
 * Small PCM-instrument contract retained for synth/sample wrappers.
 *
 * The current {@link DrumEngine} still mixes raw float[] buffers directly, so this interface is
 * not the live engine entry point today. It remains useful for standalone PCM holders such as
 * {@link SynthInstrument}, and it keeps a clean contract in place if the beat engine is refactored
 * to work with richer instrument objects later.
 *
 * Thread safety
 * ─────────────
 * Callers may touch these methods from the UI thread while audio code reads the PCM buffer on a
 * worker thread. Implementations therefore need to keep {@link #getPcm()} safe for concurrent
 * reads, typically by swapping the buffer reference atomically.
 */
public interface Instrument {

    /** Stable machine-readable ID for callers that want to log or persist the instrument. */
    String getId();

    /** Human-readable label suitable for UI surfaces such as Beat Maker row headers. */
    String getDisplayName();

    /**
     * Pre-rendered PCM samples at 48 kHz, normalised to [-1, 1].
     * Must never return null.  The reference may be swapped atomically at runtime
     * (e.g. via {@link SynthInstrument#replacePcm}) without stopping the engine.
     */
    float[] getPcm();

    /** Whether callers should currently treat this instrument as active. */
    boolean isEnabled();
    void    setEnabled(boolean on);

    /**
     * Per-instrument output level applied at mix time.
     * 1.0 = nominal.  Values above 1.0 are legal (e.g. 1.4 for bass boost).
     */
    float getVolume();
    void  setVolume(float vol);  // implementations should clamp to [0, ∞)
}
