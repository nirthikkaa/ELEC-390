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

    private static final String DB_NAME = "theremin_gloves.db";
    private static final int DB_VERSION = 3;

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
                    COL_TONE_TYPE + " TEXT NOT NULL DEFAULT 'SINE', " +
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
            COL_SENSITIVITY_LEVEL
    };

    private static final String[] EXTRA_DEFS = {
            "INTEGER NOT NULL DEFAULT 0",
            "INTEGER NOT NULL DEFAULT 0",
            "TEXT NOT NULL DEFAULT 'SINE'",
            "INTEGER NOT NULL DEFAULT 1",
            "INTEGER NOT NULL DEFAULT 1",
            "INTEGER NOT NULL DEFAULT 0",
            "TEXT NOT NULL DEFAULT 'MEDIUM'"
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
        db.execSQL(CREATE_SQL);
        for (int i = 0; i < EXTRA_COLUMNS.length; i++) addColumnIfMissing(db, EXTRA_COLUMNS[i], EXTRA_DEFS[i]);
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
        v.put(COL_SENSITIVITY_LEVEL, AppSettings.normalizeSensitivityLevel(s.sensitivityLevel));
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
        s.toneType = AppSettings.normalizeToneType(getString(c, COL_TONE_TYPE, AppSettings.TONE_SINE));
        s.pitchEnabled = getInt(c, COL_PITCH_ENABLED, 1) != 0;
        s.volumeEnabled = getInt(c, COL_VOL_ENABLED, 1) != 0;
        s.sensitivityLevel = AppSettings.normalizeSensitivityLevel(getString(c, COL_SENSITIVITY_LEVEL, AppSettings.SENSITIVITY_MEDIUM));
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

    public static final String TONE_SINE = "SINE";
    public static final String TONE_SQUARE = "SQUARE";
    public static final String TONE_TRIANGLE = "TRIANGLE";
    public static final String TONE_SAW = "SAW";
    public static final String TONE_PULSE  = "PULSE";
    public static final String TONE_ORGAN  = "ORGAN";
    public static final String TONE_STRING = "STRING";
    public static final String TONE_BELL   = "BELL";
    public static final String TONE_PAD    = "PAD";

    public static final String SENSITIVITY_LOW    = "LOW";
    public static final String SENSITIVITY_MEDIUM = "MEDIUM";
    public static final String SENSITIVITY_HIGH   = "HIGH";

    public static String normalizeSensitivityLevel(String level) {
        if (SENSITIVITY_LOW.equals(level) || SENSITIVITY_HIGH.equals(level)) return level;
        return SENSITIVITY_MEDIUM;
    }

    public static String prettyLevel(String level) {
        if (SENSITIVITY_LOW.equals(level))  return "Low";
        if (SENSITIVITY_HIGH.equals(level)) return "High";
        return "Medium";
    }

    public static float levelToMultiplier(String level) {
        if (SENSITIVITY_HIGH.equals(level)) return 0.5f;
        if (SENSITIVITY_LOW.equals(level))  return 1.5f;
        return 1.0f; // MEDIUM
    }

    public String sensitivityLevel = SENSITIVITY_MEDIUM;

    public float pitchAngleMinDeg = DEFAULT_PITCH_ANGLE_MIN_DEG;
    public float pitchAngleMaxDeg = DEFAULT_PITCH_ANGLE_MAX_DEG;
    public float freqMinHz = DEFAULT_FREQ_MIN_HZ;
    public float freqMaxHz = DEFAULT_FREQ_MAX_HZ;
    public float volumeAngleMinDeg = DEFAULT_VOLUME_ANGLE_MIN_DEG;
    public float volumeAngleMaxDeg = DEFAULT_VOLUME_ANGLE_MAX_DEG;
    public boolean pitchDirectionInverted = DEFAULT_PITCH_DIRECTION_INVERTED;
    public boolean volumeDirectionInverted = DEFAULT_VOLUME_DIRECTION_INVERTED;
    public String toneType = TONE_SINE;
    public boolean pitchEnabled = true;
    public boolean volumeEnabled = true;

    public static String normalizeToneType(String tone) {
        String n = tone == null ? "" : tone.trim().toUpperCase(Locale.US);
        switch (n) {
            case TONE_SQUARE: case TONE_TRIANGLE: case TONE_SAW:
            case TONE_PULSE:  case TONE_ORGAN:   case TONE_STRING:
            case TONE_BELL:   case TONE_PAD:
                return n;
            default: return TONE_SINE;
        }
    }

    public static String prettyToneType(String tone) {
        switch (normalizeToneType(tone)) {
            case TONE_TRIANGLE: return "Triangle";
            case TONE_SAW:      return "Saw";
            case TONE_SQUARE:   return "Square";
            case TONE_PULSE:    return "Pulse";
            case TONE_ORGAN:    return "Organ";
            case TONE_STRING:   return "String";
            case TONE_BELL:     return "Bell";
            case TONE_PAD:      return "Warm Pad";
            default:            return "Sine";
        }
    }
}
