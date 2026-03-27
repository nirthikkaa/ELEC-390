package com.example.thereminglovestest2;

/**
 * A concrete {@link Instrument} backed by a synthesized (or loaded) float[] PCM buffer.
 *
 * All mutable fields are volatile so the audio thread always reads fresh values without
 * requiring locks.  The PCM reference is also volatile, so calling {@link #replacePcm}
 * from the UI thread (e.g. after loading a WAV file) is immediately visible to the
 * audio thread on its next buffer fill.
 *
 * Typical usage
 * ─────────────
 *   // Synthesize at startup
 *   SynthInstrument kick = new SynthInstrument("kick", "KICK", synthesizeKick());
 *   engine.registerInstrument(DrumEngine.SND_KICK, kick);
 *
 *   // Upgrade to a WAV sample later (hot-swap, no engine restart)
 *   float[] wavPcm = WavLoader.load(context, R.raw.drum_kick);
 *   kick.replacePcm(wavPcm);
 */
public final class SynthInstrument implements Instrument {

    private final  String  id;
    private final  String  displayName;
    private volatile float[] pcm;
    private volatile boolean enabled = true;
    private volatile float   volume  = 1.0f;

    /**
     * @param id          stable machine-readable identifier, non-empty
     * @param displayName short UI label
     * @param pcm         initial PCM buffer; must not be null
     * @throws IllegalArgumentException if any argument is null or id is empty
     */
    public SynthInstrument(String id, String displayName, float[] pcm) {
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("id must be non-empty");
        if (displayName == null)        throw new IllegalArgumentException("displayName is null");
        if (pcm == null)                throw new IllegalArgumentException("pcm is null");
        this.id          = id;
        this.displayName = displayName;
        this.pcm         = pcm;
    }

    @Override public String  getId()          { return id; }
    @Override public String  getDisplayName() { return displayName; }
    @Override public float[] getPcm()         { return pcm; }
    @Override public boolean isEnabled()      { return enabled; }
    @Override public void    setEnabled(boolean on) { this.enabled = on; }
    @Override public float   getVolume()      { return volume; }
    @Override public void    setVolume(float vol) { this.volume = Math.max(0f, vol); }

    /**
     * Atomically replace the backing PCM buffer.
     * The audio thread will read the new buffer on its next mix call.
     * Typical use: hot-swap a synthesized placeholder for a higher-quality WAV.
     *
     * @param newPcm replacement buffer; ignored if null
     */
    public void replacePcm(float[] newPcm) {
        if (newPcm != null) this.pcm = newPcm;
    }
}
