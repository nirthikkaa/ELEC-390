package com.example.thereminglovestest2;

/**
 * App-side persisted settings (SQLite).
 * MVP scope for this step stores mapping values used by MainActivity.
 * Extra fields are included now so later screens (Settings/Calibration) can reuse the same schema.
 */
public class AppSettings {

    public static final String TONE_SINE = "SINE";
    public static final String TONE_SQUARE = "SQUARE";
    public static final String TONE_TRIANGLE = "TRIANGLE";
    public static final String TONE_SAW = "SAW";

    public float pitchAngleMinDeg = 0f;
    public float pitchAngleMaxDeg = 45f;

    public float freqMinHz = 523.25f;
    public float freqMaxHz = 880f;

    public float volumeAngleMinDeg = 0f;
    public float volumeAngleMaxDeg = 45f;

    // Future app-side toggles / settings
    public boolean pitchDirectionInverted = false;
    public boolean volumeDirectionInverted = false;
    public String toneType = TONE_SINE;
    public boolean pitchEnabled = true;
    public boolean volumeEnabled = true;
}