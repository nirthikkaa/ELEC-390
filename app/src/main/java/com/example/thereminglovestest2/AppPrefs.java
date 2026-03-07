package com.example.thereminglovestest2;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Tiny wrapper around SharedPreferences.
 *
 * The point is not fancy architecture.
 * The point is to stop preference keys from being copied all over the app.
 */
public final class AppPrefs {

    private static final String PREFS_APP = "theremin_prefs";
    private static final String PREFS_UI = "calibration_ui_prefs";

    private static final String KEY_BG_AUDIO_ENABLED = "bg_audio_enabled";
    private static final String KEY_EXTENDED_FREQ_RANGE = "extended_frequency_range_enabled";
    private static final String KEY_CALIBRATION_GUIDE_LEARNED = "calibration_guide_learned";

    private AppPrefs() {
    }

    public static boolean isBgAudioEnabled(Context context) {
        return appPrefs(context).getBoolean(KEY_BG_AUDIO_ENABLED, true);
    }

    public static void setBgAudioEnabled(Context context, boolean enabled) {
        appPrefs(context).edit().putBoolean(KEY_BG_AUDIO_ENABLED, enabled).apply();
    }

    public static boolean isExtendedFreqRangeEnabled(Context context) {
        return appPrefs(context).getBoolean(KEY_EXTENDED_FREQ_RANGE, false);
    }

    public static void setExtendedFreqRangeEnabled(Context context, boolean enabled) {
        appPrefs(context).edit().putBoolean(KEY_EXTENDED_FREQ_RANGE, enabled).apply();
    }

    public static boolean isCalibrationGuideLearned(Context context) {
        return uiPrefs(context).getBoolean(KEY_CALIBRATION_GUIDE_LEARNED, false);
    }

    public static void setCalibrationGuideLearned(Context context, boolean learned) {
        uiPrefs(context).edit().putBoolean(KEY_CALIBRATION_GUIDE_LEARNED, learned).apply();
    }

    private static SharedPreferences appPrefs(Context context) {
        return context.getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE);
    }

    private static SharedPreferences uiPrefs(Context context) {
        return context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE);
    }
}
