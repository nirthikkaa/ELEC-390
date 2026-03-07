package com.example.thereminglovestest2;

/**
 * Single source of truth for all default values.
 * Change a value here and it applies everywhere automatically.
 */
public final class Defaults {

    private Defaults() {}

    // ── Calibration screen defaults (knobs) ───────────────────────────────
    public static final float PITCH_ANGLE_MIN_DEG  =  0.0f;
    public static final float PITCH_ANGLE_MAX_DEG  = 90.0f;
    public static final float VOLUME_ANGLE_MIN_DEG =  0.0f;
    public static final float VOLUME_ANGLE_MAX_DEG = 90.0f;
    public static final float FREQ_MIN_HZ          = 20.0f;
    public static final float FREQ_MAX_HZ          = 2000.0f;

    // ── Play screen "Defaults" button ─────────────────────────────────────
    public static final float PLAY_PITCH_ANGLE_MIN_DEG  = -15.0f;
    public static final float PLAY_PITCH_ANGLE_MAX_DEG  =  55.0f;
    public static final float PLAY_VOLUME_ANGLE_MIN_DEG = -10.0f;
    public static final float PLAY_VOLUME_ANGLE_MAX_DEG =  55.0f;
    public static final float PLAY_FREQ_MIN_HZ          = 880.0f;
    public static final float PLAY_FREQ_MAX_HZ          = 2000.0f;

    // ── Direction ──────────────────────────────────────────────────────────
    public static final boolean PITCH_DIRECTION_INVERTED  = false;
    public static final boolean VOLUME_DIRECTION_INVERTED = true;   // ← change here only
}
