package com.example.thereminglovestest2;

/**
 * File guide:
 * Single place for app settings persistence. It stores theremin settings in SQLite and small UI flags in shared preferences.
 */

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.Locale;

/** Small single-row settings store. */
public class SettingsStore extends SQLiteOpenHelper {
    // --- Storage keys for the single settings row and shared UI flags ---
    // Process-wide schema gate so repeated loads do not re-run PRAGMA/ALTER checks on every screen.
    private static volatile boolean schemaVerifiedForProcess;

    private static final String DB_NAME = "theremin_gloves.db";
    private static final int DB_VERSION = 4;

    private static final String TABLE = "app_settings";
    private static final int ROW_ID = 1;

    private static final String PREFS_APP = "theremin_prefs";
    private static final String PREFS_UI = "calibration_ui_prefs";
    private static final String KEY_BG_AUDIO_ENABLED = "bg_audio_enabled";
    private static final String KEY_EXTENDED_FREQ_RANGE = "extended_frequency_range_enabled";
    private static final String KEY_CALIBRATION_GUIDE_LEARNED = "calibration_guide_learned";
    private static final String KEY_AUDIO_COMPRESSION = "audio_compression";
    private static final String KEY_SHOW_RENAME_DIALOG = "show_rename_dialog_on_stop";

    private static final String COL_ID = "id";
    private static final String COL_PITCH_ANGLE_MIN = "pitch_angle_min_deg";
    private static final String COL_PITCH_ANGLE_MAX = "pitch_angle_max_deg";
    private static final String COL_FREQ_MIN = "freq_min_hz";
    private static final String COL_FREQ_MAX = "freq_max_hz";
    private static final String COL_VOL_ANGLE_MIN = "volume_angle_min_deg";
    private static final String COL_VOL_ANGLE_MAX = "volume_angle_max_deg";
    private static final String COL_PITCH_DIR_INV = "pitch_direction_inverted";
    private static final String COL_VOL_DIR_INV = "volume_direction_inverted";
    private static final String COL_TONE_TYPE = "tone_type";
    private static final String COL_PITCH_ENABLED = "pitch_enabled";
    private static final String COL_VOL_ENABLED = "volume_enabled";
    private static final String COL_UPDATED_AT_MS = "updated_at_ms";
    private static final String COL_SENSITIVITY_LEVEL = "sensitivity_level";
    private static final String COL_SENSITIVITY_CURVE = "sensitivity_curve";

    // Performance and effects columns that travel with the main theremin settings row.
    private static final String COL_ACTIVE_SCALE       = "active_scale";
    private static final String COL_OCTAVE_SHIFT       = "octave_shift";
    private static final String COL_REVERB_ENABLED     = "reverb_enabled";
    private static final String COL_REVERB_MIX         = "reverb_mix";
    private static final String COL_DELAY_ENABLED      = "delay_enabled";
    private static final String COL_DELAY_FEEDBACK     = "delay_feedback";
    private static final String COL_DELAY_MIX          = "delay_mix";
    private static final String COL_DISTORTION_ENABLED = "distortion_enabled";
    private static final String COL_DISTORTION_GAIN    = "distortion_gain";

    private static final String CREATE_SQL =
            "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                    COL_ID + " INTEGER PRIMARY KEY, " +
                    COL_PITCH_ANGLE_MIN + " REAL NOT NULL, " +
                    COL_PITCH_ANGLE_MAX + " REAL NOT NULL, " +
                    COL_FREQ_MIN + " REAL NOT NULL, " +
                    COL_FREQ_MAX + " REAL NOT NULL, " +
                    COL_VOL_ANGLE_MIN + " REAL NOT NULL, " +
                    COL_VOL_ANGLE_MAX + " REAL NOT NULL, " +
                    COL_PITCH_DIR_INV + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_VOL_DIR_INV + " INTEGER NOT NULL DEFAULT 0, " +
                    COL_TONE_TYPE + " TEXT NOT NULL DEFAULT 'THEREMIN', " +
                    COL_PITCH_ENABLED + " INTEGER NOT NULL DEFAULT 1, " +
                    COL_VOL_ENABLED + " INTEGER NOT NULL DEFAULT 1, " +
                    COL_UPDATED_AT_MS + " INTEGER NOT NULL)";

    private static final String[] EXTRA_COLUMNS = {
            COL_PITCH_DIR_INV,
            COL_VOL_DIR_INV,
            COL_TONE_TYPE,
            COL_PITCH_ENABLED,
            COL_VOL_ENABLED,
            COL_UPDATED_AT_MS,
            // Performance and effects settings
            COL_ACTIVE_SCALE,
            COL_OCTAVE_SHIFT,
            COL_REVERB_ENABLED,
            COL_REVERB_MIX,
            COL_DELAY_ENABLED,
            COL_DELAY_FEEDBACK,
            COL_DELAY_MIX,
            COL_DISTORTION_ENABLED,
            COL_DISTORTION_GAIN,
            COL_SENSITIVITY_LEVEL,
            COL_SENSITIVITY_CURVE
    };

    private static final String[] EXTRA_DEFS = {
            "INTEGER NOT NULL DEFAULT 0",
            "INTEGER NOT NULL DEFAULT 0",
            "TEXT NOT NULL DEFAULT 'SINE'",
            "INTEGER NOT NULL DEFAULT 1",
            "INTEGER NOT NULL DEFAULT 1",
            "INTEGER NOT NULL DEFAULT 0",
            // Performance and effects defaults
            "TEXT NOT NULL DEFAULT 'CHROMATIC'",
            "INTEGER NOT NULL DEFAULT 0",
            "INTEGER NOT NULL DEFAULT 0",
            "REAL NOT NULL DEFAULT 0.3",
            "INTEGER NOT NULL DEFAULT 0",
            "REAL NOT NULL DEFAULT 0.35",
            "REAL NOT NULL DEFAULT 0.4",
            "INTEGER NOT NULL DEFAULT 0",
            "REAL NOT NULL DEFAULT 3.0",
            "TEXT NOT NULL DEFAULT 'MEDIUM'",
            "REAL NOT NULL DEFAULT 1.0"
    };

    public SettingsStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override public void onCreate(SQLiteDatabase db) { ensureSchema(db); }
    @Override public void onOpen(SQLiteDatabase db) { super.onOpen(db); ensureSchema(db); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { ensureSchema(db); }

    public synchronized void save(AppSettings settings) {
        if (settings == null) return;
        SQLiteDatabase db = getWritableDatabase();
        ensureSchema(db);
        db.insertWithOnConflict(TABLE, null, toValues(settings), SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized AppSettings load() {
        SQLiteDatabase db = getReadableDatabase();
        ensureSchema(db);
        try (Cursor c = db.query(TABLE, null, COL_ID + "=?", new String[]{String.valueOf(ROW_ID)}, null, null, null)) {
            return c.moveToFirst() ? fromCursor(c) : new AppSettings();
        }
    }

    private void ensureSchema(SQLiteDatabase db) {
        if (schemaVerifiedForProcess) return;
        synchronized (SettingsStore.class) {
            if (schemaVerifiedForProcess) return;
            db.execSQL(CREATE_SQL);
            for (int i = 0; i < EXTRA_COLUMNS.length; i++) addColumnIfMissing(db, EXTRA_COLUMNS[i], EXTRA_DEFS[i]);
            schemaVerifiedForProcess = true;
        }
    }

    private ContentValues toValues(AppSettings s) {
        ContentValues v = new ContentValues();
        v.put(COL_ID, ROW_ID);
        v.put(COL_PITCH_ANGLE_MIN, s.pitchAngleMinDeg);
        v.put(COL_PITCH_ANGLE_MAX, s.pitchAngleMaxDeg);
        v.put(COL_FREQ_MIN, s.freqMinHz);
        v.put(COL_FREQ_MAX, s.freqMaxHz);
        v.put(COL_VOL_ANGLE_MIN, s.volumeAngleMinDeg);
        v.put(COL_VOL_ANGLE_MAX, s.volumeAngleMaxDeg);
        v.put(COL_PITCH_DIR_INV, s.pitchDirectionInverted ? 1 : 0);
        v.put(COL_VOL_DIR_INV, s.volumeDirectionInverted ? 1 : 0);
        v.put(COL_TONE_TYPE, AppSettings.normalizeToneType(s.toneType));
        v.put(COL_PITCH_ENABLED, s.pitchEnabled ? 1 : 0);
        v.put(COL_VOL_ENABLED, s.volumeEnabled ? 1 : 0);
        v.put(COL_UPDATED_AT_MS, System.currentTimeMillis());
        // Persist performance-mode sound controls with the main calibration row.
        v.put(COL_ACTIVE_SCALE, s.activeScale != null ? s.activeScale : AppSettings.SCALE_CHROMATIC);
        v.put(COL_OCTAVE_SHIFT, s.octaveShift);
        v.put(COL_REVERB_ENABLED, s.reverbEnabled ? 1 : 0);
        v.put(COL_REVERB_MIX, s.reverbMix);
        v.put(COL_DELAY_ENABLED, s.delayEnabled ? 1 : 0);
        v.put(COL_DELAY_FEEDBACK, s.delayFeedback);
        v.put(COL_DELAY_MIX, s.delayMix);
        v.put(COL_DISTORTION_ENABLED, s.distortionEnabled ? 1 : 0);
        v.put(COL_DISTORTION_GAIN, s.distortionGain);
        float sensitivityCurve = AppSettings.clampSensitivityResponseCurve(s.sensitivityResponseCurve);
        v.put(COL_SENSITIVITY_LEVEL, AppSettings.curveToLegacySensitivityLevel(sensitivityCurve));
        v.put(COL_SENSITIVITY_CURVE, sensitivityCurve);
        return v;
    }

    private AppSettings fromCursor(Cursor c) {
        AppSettings s = new AppSettings();
        s.pitchAngleMinDeg = getFloat(c, COL_PITCH_ANGLE_MIN, s.pitchAngleMinDeg);
        s.pitchAngleMaxDeg = getFloat(c, COL_PITCH_ANGLE_MAX, s.pitchAngleMaxDeg);
        s.freqMinHz = getFloat(c, COL_FREQ_MIN, s.freqMinHz);
        s.freqMaxHz = getFloat(c, COL_FREQ_MAX, s.freqMaxHz);
        s.volumeAngleMinDeg = getFloat(c, COL_VOL_ANGLE_MIN, s.volumeAngleMinDeg);
        s.volumeAngleMaxDeg = getFloat(c, COL_VOL_ANGLE_MAX, s.volumeAngleMaxDeg);
        s.pitchDirectionInverted = getInt(c, COL_PITCH_DIR_INV, 0) != 0;
        s.volumeDirectionInverted = getInt(c, COL_VOL_DIR_INV, 0) != 0;
        s.toneType = AppSettings.normalizeToneType(getString(c, COL_TONE_TYPE, AppSettings.TONE_THEREMIN));
        s.pitchEnabled = getInt(c, COL_PITCH_ENABLED, 1) != 0;
        s.volumeEnabled = getInt(c, COL_VOL_ENABLED, 1) != 0;
        // Recover performance-mode sound controls alongside the calibration settings.
        s.activeScale = AppSettings.normalizeScale(getString(c, COL_ACTIVE_SCALE, AppSettings.SCALE_CHROMATIC));
        s.octaveShift = Math.max(-2, Math.min(2, getInt(c, COL_OCTAVE_SHIFT, 0)));
        s.reverbEnabled = getInt(c, COL_REVERB_ENABLED, 0) != 0;
        s.reverbMix = getFloat(c, COL_REVERB_MIX, 0.3f);
        s.delayEnabled = getInt(c, COL_DELAY_ENABLED, 0) != 0;
        s.delayFeedback = getFloat(c, COL_DELAY_FEEDBACK, 0.35f);
        s.delayMix = getFloat(c, COL_DELAY_MIX, 0.4f);
        s.distortionEnabled = getInt(c, COL_DISTORTION_ENABLED, 0) != 0;
        s.distortionGain = getFloat(c, COL_DISTORTION_GAIN, 3.0f);
        s.sensitivityLevel = AppSettings.normalizeSensitivityLevel(getString(c, COL_SENSITIVITY_LEVEL, AppSettings.SENSITIVITY_MEDIUM));
        s.sensitivityResponseCurve = AppSettings.clampSensitivityResponseCurve(
                getFloat(c, COL_SENSITIVITY_CURVE, AppSettings.levelToResponseCurve(s.sensitivityLevel)));
        s.sensitivityLevel = AppSettings.curveToLegacySensitivityLevel(s.sensitivityResponseCurve);
        return s;
    }

    private void addColumnIfMissing(SQLiteDatabase db, String name, String definition) {
        if (!hasColumn(db, name)) db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + name + " " + definition);
    }

    private boolean hasColumn(SQLiteDatabase db, String columnName) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + TABLE + ")", null)) {
            int i = c.getColumnIndex("name");
            while (c.moveToNext()) if (i >= 0 && columnName.equals(c.getString(i))) return true;
            return false;
        }
    }

    private static int index(Cursor c, String col) { return c.getColumnIndex(col); }

    private float getFloat(Cursor c, String col, float fallback) {
        int i = index(c, col);
        return i >= 0 && !c.isNull(i) ? c.getFloat(i) : fallback;
    }

    private int getInt(Cursor c, String col, int fallback) {
        int i = index(c, col);
        return i >= 0 && !c.isNull(i) ? c.getInt(i) : fallback;
    }

    private String getString(Cursor c, String col, String fallback) {
        int i = index(c, col);
        String value = (i >= 0 && !c.isNull(i)) ? c.getString(i) : null;
        return value != null ? value : fallback;
    }

    public static boolean isBgAudioEnabled(Context c) { return getBool(c, PREFS_APP, KEY_BG_AUDIO_ENABLED, true); }
    public static void setBgAudioEnabled(Context c, boolean v) { putBool(c, PREFS_APP, KEY_BG_AUDIO_ENABLED, v); }
    public static boolean isExtendedFreqRangeEnabled(Context c) { return getBool(c, PREFS_APP, KEY_EXTENDED_FREQ_RANGE, false); }
    public static void setExtendedFreqRangeEnabled(Context c, boolean v) { putBool(c, PREFS_APP, KEY_EXTENDED_FREQ_RANGE, v); }
    public static boolean isCalibrationGuideLearned(Context c) { return getBool(c, PREFS_UI, KEY_CALIBRATION_GUIDE_LEARNED, false); }
    public static void setCalibrationGuideLearned(Context c, boolean v) { putBool(c, PREFS_UI, KEY_CALIBRATION_GUIDE_LEARNED, v); }
    public static String getAudioCompression(Context c) { return getStr(c, PREFS_APP, KEY_AUDIO_COMPRESSION, AppSettings.COMPRESSION_HIGH); }
    public static void setAudioCompression(Context c, String v) { putStr(c, PREFS_APP, KEY_AUDIO_COMPRESSION, v); }
    public static boolean isRenameDialogEnabled(Context c) { return getBool(c, PREFS_APP, KEY_SHOW_RENAME_DIALOG, true); }
    public static void setRenameDialogEnabled(Context c, boolean v) { putBool(c, PREFS_APP, KEY_SHOW_RENAME_DIALOG, v); }

    private static boolean getBool(Context c, String prefs, String key, boolean fallback) {
        return prefs(c, prefs).getBoolean(key, fallback);
    }

    private static void putBool(Context c, String prefs, String key, boolean value) {
        prefs(c, prefs).edit().putBoolean(key, value).apply();
    }

    private static String getStr(Context c, String prefs, String key, String fallback) {
        String v = prefs(c, prefs).getString(key, null);
        return v != null ? v : fallback;
    }

    private static void putStr(Context c, String prefs, String key, String value) {
        prefs(c, prefs).edit().putString(key, value).apply();
    }

    private static SharedPreferences prefs(Context context, String name) {
        return context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }
}

class AppSettings {
    public static final float DEFAULT_PITCH_ANGLE_MIN_DEG = 0f;
    public static final float DEFAULT_PITCH_ANGLE_MAX_DEG = 90f;
    public static final float DEFAULT_VOLUME_ANGLE_MIN_DEG = 0f;
    public static final float DEFAULT_VOLUME_ANGLE_MAX_DEG = 90f;
    public static final float DEFAULT_FREQ_MIN_HZ = 20f;
    public static final float DEFAULT_FREQ_MAX_HZ = 2000f;

    public static final float DEFAULT_PLAY_PITCH_ANGLE_MIN_DEG = -15f;
    public static final float DEFAULT_PLAY_PITCH_ANGLE_MAX_DEG = 55f;
    public static final float DEFAULT_PLAY_VOLUME_ANGLE_MIN_DEG = -10f;
    public static final float DEFAULT_PLAY_VOLUME_ANGLE_MAX_DEG = 55f;
    public static final float DEFAULT_PLAY_FREQ_MIN_HZ = 880f;
    public static final float DEFAULT_PLAY_FREQ_MAX_HZ = 2000f;

    public static final boolean DEFAULT_PITCH_DIRECTION_INVERTED = false;
    public static final boolean DEFAULT_VOLUME_DIRECTION_INVERTED = true;

    public static final String COMPRESSION_LOSSLESS = "LOSSLESS";
    public static final String COMPRESSION_HIGH     = "HIGH";
    public static final String COMPRESSION_MEDIUM   = "MEDIUM";
    public static final String COMPRESSION_LOW      = "LOW";

    // Scale-lock labels persisted in settings and forwarded to the audio engine.
    public static final String SCALE_CHROMATIC  = "CHROMATIC";
    public static final String SCALE_MAJOR      = "MAJOR";
    public static final String SCALE_MINOR      = "MINOR";
    public static final String SCALE_PENTATONIC = "PENTATONIC";

    public static String normalizeScale(String scale) {
        if (scale == null) return SCALE_CHROMATIC;
        switch (scale.trim().toUpperCase(Locale.US)) {
            case SCALE_MAJOR:      return SCALE_MAJOR;
            case SCALE_MINOR:      return SCALE_MINOR;
            case SCALE_PENTATONIC: return SCALE_PENTATONIC;
            default:               return SCALE_CHROMATIC;
        }
    }

    public static final String TONE_THEREMIN = "THEREMIN";
    public static final String TONE_SINE    = "SINE";   // legacy alias → maps to THEREMIN
    public static final String TONE_SQUARE  = "SQUARE";
    public static final String TONE_TRIANGLE= "TRIANGLE";
    public static final String TONE_SAW     = "SAW";
    public static final String TONE_PULSE   = "PULSE";
    public static final String TONE_ORGAN   = "ORGAN";
    public static final String TONE_STRING  = "STRING";
    public static final String TONE_BELL    = "BELL";
    public static final String TONE_PAD     = "PAD";
    public static final String TONE_AIR_PAD   = "AIR_PAD";
    public static final String TONE_CELLO     = "CELLO";
    public static final String TONE_SWEET_LEAD= "SWEET_LEAD";
    public static final String TONE_LEAD      = "LEAD";
    public static final String TONE_KEYS      = "KEYS";
    public static final String TONE_VOWEL_A   = "VOWEL_A";
    public static final String TONE_VOWEL_O   = "VOWEL_O";
    public static final String TONE_VOWEL_I   = "VOWEL_I";
    public static final String TONE_FLUTE     = "FLUTE";
    public static final String TONE_CLARINET  = "CLARINET";
    // Keep the legacy DRUM value mapped to the old rotor-like sound so existing saved settings
    // still load the same tone after the UI rename to Helicopter.
    public static final String TONE_HELICOPTER= "DRUM";
    public static final String TONE_DRUM      = "DRUM_KIT";
    public static final String TONE_OBOE      = "OBOE";
    public static final String TONE_TRUMPET   = "TRUMPET";
    public static final String TONE_VIOLIN    = "VIOLIN";
    public static final String TONE_CHOIR     = "CHOIR";
    public static final String TONE_GUITAR    = "GUITAR";
    // Keep the public tone picker focused on a smaller set of clearly distinct sounds.
    public static final String[] USER_SELECTABLE_TONES = {
            TONE_THEREMIN,
            TONE_AIR_PAD,
            TONE_CELLO,
            TONE_PAD,
            TONE_CHOIR,
            TONE_FLUTE,
            TONE_CLARINET,
            TONE_TRIANGLE,
            TONE_SAW,
            TONE_SQUARE,
            TONE_HELICOPTER
            // Future reimplementation candidates for the public selector:
            // TONE_SWEET_LEAD,
            // TONE_VOWEL_O,
            // TONE_VIOLIN,
            // TONE_GUITAR,
            // TONE_OBOE,
            // TONE_TRUMPET,
            // TONE_LEAD,
            // TONE_PULSE,
            // TONE_ORGAN,
            // TONE_STRING,
            // TONE_BELL
    };

    public static final String SENSITIVITY_LOW    = "LOW";
    public static final String SENSITIVITY_MEDIUM = "MEDIUM";
    public static final String SENSITIVITY_HIGH   = "HIGH";
    public static final float MIN_SENSITIVITY_RESPONSE_CURVE = 0.25f;
    public static final float MAX_SENSITIVITY_RESPONSE_CURVE = 2.50f;
    public static final float DEFAULT_SENSITIVITY_RESPONSE_CURVE = 1.0f;

    public static String normalizeSensitivityLevel(String level) {
        if (SENSITIVITY_LOW.equals(level) || SENSITIVITY_HIGH.equals(level)) return level;
        return SENSITIVITY_MEDIUM;
    }

    public static String prettyLevel(String level) {
        if (SENSITIVITY_LOW.equals(level))  return "Low";
        if (SENSITIVITY_HIGH.equals(level)) return "High";
        return "Medium";
    }

    public static float clampSensitivityResponseCurve(float curve) {
        if (Float.isNaN(curve) || Float.isInfinite(curve)) return DEFAULT_SENSITIVITY_RESPONSE_CURVE;
        return Math.max(MIN_SENSITIVITY_RESPONSE_CURVE, Math.min(MAX_SENSITIVITY_RESPONSE_CURVE, curve));
    }

    public static String curveToLegacySensitivityLevel(float curve) {
        float clamped = clampSensitivityResponseCurve(curve);
        if (clamped <= 0.7f) return SENSITIVITY_HIGH;
        if (clamped >= 1.4f) return SENSITIVITY_LOW;
        return SENSITIVITY_MEDIUM;
    }

    public static String prettySensitivityCurve(float curve) {
        float clamped = clampSensitivityResponseCurve(curve);
        if (clamped <= 0.55f) return "Very Sensitive";
        if (clamped < 0.9f) return "Sensitive";
        if (clamped <= 1.15f) return "Balanced";
        if (clamped < 1.8f) return "Precise";
        return "Very Precise";
    }

    /**
     * Response curve exponent applied after normalizing glove motion to 0..1.
     * Lower exponents feel more sensitive; higher exponents feel more precise.
     */
    public static float levelToResponseCurve(String level) {
        if (SENSITIVITY_HIGH.equals(level)) return 0.4f;
        if (SENSITIVITY_LOW.equals(level))  return 2.0f;
        return 1.0f; // MEDIUM
    }

    public String sensitivityLevel = SENSITIVITY_MEDIUM;
    public float sensitivityResponseCurve = DEFAULT_SENSITIVITY_RESPONSE_CURVE;

    public float pitchAngleMinDeg = DEFAULT_PITCH_ANGLE_MIN_DEG;
    public float pitchAngleMaxDeg = DEFAULT_PITCH_ANGLE_MAX_DEG;
    public float freqMinHz = DEFAULT_FREQ_MIN_HZ;
    public float freqMaxHz = DEFAULT_FREQ_MAX_HZ;
    public float volumeAngleMinDeg = DEFAULT_VOLUME_ANGLE_MIN_DEG;
    public float volumeAngleMaxDeg = DEFAULT_VOLUME_ANGLE_MAX_DEG;
    public boolean pitchDirectionInverted = DEFAULT_PITCH_DIRECTION_INVERTED;
    public boolean volumeDirectionInverted = DEFAULT_VOLUME_DIRECTION_INVERTED;
    public String toneType = TONE_THEREMIN;
    public boolean pitchEnabled = true;
    public boolean volumeEnabled = true;

    // Performance/effects state
    public String  activeScale        = SCALE_CHROMATIC;
    public int     octaveShift        = 0;
    public boolean reverbEnabled      = false;
    public float   reverbMix          = 0.3f;
    public boolean delayEnabled       = false;
    public float   delayFeedback      = 0.35f;
    public float   delayMix           = 0.4f;
    public boolean distortionEnabled  = false;
    public float   distortionGain     = 3.0f;

    public static String normalizeToneType(String tone) {
        String n = tone == null ? "" : tone.trim().toUpperCase(Locale.US);
        switch (n) {
            case TONE_THEREMIN:
            case TONE_SQUARE:  case TONE_TRIANGLE: case TONE_SAW:
            case TONE_PULSE:   case TONE_ORGAN:   case TONE_STRING:
            case TONE_BELL:    case TONE_PAD:     case TONE_AIR_PAD:
            case TONE_CELLO:   case TONE_SWEET_LEAD: case TONE_LEAD:
            case TONE_KEYS:     case TONE_VOWEL_A:  case TONE_VOWEL_O:
            case TONE_VOWEL_I:  case TONE_FLUTE:    case TONE_CLARINET:
            case TONE_HELICOPTER:
            case TONE_DRUM:
            case TONE_OBOE:     case TONE_TRUMPET:  case TONE_VIOLIN:
            case TONE_CHOIR:    case TONE_GUITAR:
                return n;
            default: return TONE_THEREMIN; // "SINE" and any unknown string → THEREMIN
        }
    }

    public static boolean isUserSelectableTone(String tone) {
        String normalized = normalizeToneType(tone);
        for (String selectableTone : USER_SELECTABLE_TONES) {
            if (normalized.equals(selectableTone)) return true;
        }
        return false;
    }

    public static String coerceUserSelectableTone(String tone) {
        String normalized = normalizeToneType(tone);
        return isUserSelectableTone(normalized) ? normalized : TONE_THEREMIN;
    }

    public static String prettyToneType(String tone) {
        switch (normalizeToneType(tone)) {
            case TONE_THEREMIN: return "Theremin";
            case TONE_TRIANGLE: return "Triangle";
            case TONE_SAW:      return "Saw";
            case TONE_SQUARE:   return "Square";
            case TONE_PULSE:    return "Pulse";
            case TONE_ORGAN:    return "Organ";
            case TONE_STRING:   return "String";
            case TONE_BELL:     return "Bell";
            case TONE_PAD:      return "Pad";
            case TONE_AIR_PAD:  return "Air Pad";
            case TONE_CELLO:    return "Cello";
            case TONE_SWEET_LEAD:return "Sweet Lead";
            case TONE_CHOIR:    return "Choir";
            case TONE_VOWEL_O:  return "Vocal O";
            case TONE_CLARINET: return "Clarinet";
            case TONE_DRUM:     return "Drum";
            case TONE_HELICOPTER:return "Helicopter";
            case TONE_OBOE:     return "Oboe";
            case TONE_LEAD:     return "Bright Lead";
            case TONE_VIOLIN:   return "Violin";
            case TONE_GUITAR:   return "Guitar";
            case TONE_FLUTE:    return "Flute";
            case TONE_TRUMPET:  return "Trumpet";
            default:            return "Theremin";
        }
    }
}
