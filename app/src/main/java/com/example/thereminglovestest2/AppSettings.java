package com.example.thereminglovestest2;

import java.util.Locale;

/**
 * Saved mapping and sound settings.
 *
 * This is just a plain data holder so the rest of the app can stay simple.
 */
public class AppSettings {

    public static final String TONE_SINE = "SINE";
    public static final String TONE_SQUARE = "SQUARE";
    public static final String TONE_TRIANGLE = "TRIANGLE";
    public static final String TONE_SAW = "SAW";

    public float pitchAngleMinDeg = Defaults.PITCH_ANGLE_MIN_DEG;
    public float pitchAngleMaxDeg = Defaults.PITCH_ANGLE_MAX_DEG;
    public float freqMinHz = Defaults.FREQ_MIN_HZ;
    public float freqMaxHz = Defaults.FREQ_MAX_HZ;
    public float volumeAngleMinDeg = Defaults.VOLUME_ANGLE_MIN_DEG;
    public float volumeAngleMaxDeg = Defaults.VOLUME_ANGLE_MAX_DEG;

    public boolean pitchDirectionInverted = Defaults.PITCH_DIRECTION_INVERTED;
    public boolean volumeDirectionInverted = Defaults.VOLUME_DIRECTION_INVERTED;

    public String toneType = TONE_SINE;
    public boolean pitchEnabled = true;
    public boolean volumeEnabled = true;

    public static String normalizeToneType(String tone) {
        if (tone == null) {
            return TONE_SINE;
        }

        String normalized = tone.trim().toUpperCase(Locale.US);
        if (TONE_SQUARE.equals(normalized)
                || TONE_TRIANGLE.equals(normalized)
                || TONE_SAW.equals(normalized)) {
            return normalized;
        }
        return TONE_SINE;
    }

    public static String prettyToneType(String tone) {
        String normalized = normalizeToneType(tone);
        if (TONE_TRIANGLE.equals(normalized)) return "Triangle";
        if (TONE_SAW.equals(normalized)) return "Saw";
        if (TONE_SQUARE.equals(normalized)) return "Square";
        return "Sine";
    }
}
