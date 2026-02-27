package com.example.thereminglovestest2;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/**
 * Repository wrapper for SQLite app settings.
 * Stores exactly one row (id = 1).
 */
public class AppSettingsRepository {

    private static final int SINGLE_ROW_ID = 1;

    private final AppSettingsDbHelper dbHelper;

    public AppSettingsRepository(Context context) {
        this.dbHelper = new AppSettingsDbHelper(context.getApplicationContext());
    }

    public synchronized void save(AppSettings settings) {
        if (settings == null) return;

        SQLiteDatabase db = dbHelper.getWritableDatabase();

        // Ensure table exists (extra safety)
        db.execSQL(AppSettingsDbHelper.createTableSql());

        ContentValues cv = new ContentValues();
        cv.put(AppSettingsDbHelper.COL_ID, SINGLE_ROW_ID);

        cv.put(AppSettingsDbHelper.COL_PITCH_ANGLE_MIN, settings.pitchAngleMinDeg);
        cv.put(AppSettingsDbHelper.COL_PITCH_ANGLE_MAX, settings.pitchAngleMaxDeg);

        cv.put(AppSettingsDbHelper.COL_FREQ_MIN, settings.freqMinHz);
        cv.put(AppSettingsDbHelper.COL_FREQ_MAX, settings.freqMaxHz);

        cv.put(AppSettingsDbHelper.COL_VOLUME_ANGLE_MIN, settings.volumeAngleMinDeg);
        cv.put(AppSettingsDbHelper.COL_VOLUME_ANGLE_MAX, settings.volumeAngleMaxDeg);

        cv.put(AppSettingsDbHelper.COL_PITCH_DIR_INV, settings.pitchDirectionInverted ? 1 : 0);
        cv.put(AppSettingsDbHelper.COL_VOLUME_DIR_INV, settings.volumeDirectionInverted ? 1 : 0);

        cv.put(AppSettingsDbHelper.COL_TONE_TYPE, sanitizeTone(settings.toneType));

        cv.put(AppSettingsDbHelper.COL_PITCH_ENABLED, settings.pitchEnabled ? 1 : 0);
        cv.put(AppSettingsDbHelper.COL_VOLUME_ENABLED, settings.volumeEnabled ? 1 : 0);

        cv.put(AppSettingsDbHelper.COL_UPDATED_AT_MS, System.currentTimeMillis());

        db.insertWithOnConflict(
                AppSettingsDbHelper.TABLE_SETTINGS,
                null,
                cv,
                SQLiteDatabase.CONFLICT_REPLACE
        );
    }

    public synchronized AppSettings load() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();

        // Ensure table exists (extra safety)
        db.execSQL(AppSettingsDbHelper.createTableSql());

        Cursor c = db.query(
                AppSettingsDbHelper.TABLE_SETTINGS,
                null,
                AppSettingsDbHelper.COL_ID + "=?",
                new String[]{String.valueOf(SINGLE_ROW_ID)},
                null,
                null,
                null
        );

        try {
            if (!c.moveToFirst()) {
                return null;
            }

            AppSettings s = new AppSettings();

            s.pitchAngleMinDeg = getFloat(c, AppSettingsDbHelper.COL_PITCH_ANGLE_MIN, s.pitchAngleMinDeg);
            s.pitchAngleMaxDeg = getFloat(c, AppSettingsDbHelper.COL_PITCH_ANGLE_MAX, s.pitchAngleMaxDeg);

            s.freqMinHz = getFloat(c, AppSettingsDbHelper.COL_FREQ_MIN, s.freqMinHz);
            s.freqMaxHz = getFloat(c, AppSettingsDbHelper.COL_FREQ_MAX, s.freqMaxHz);

            s.volumeAngleMinDeg = getFloat(c, AppSettingsDbHelper.COL_VOLUME_ANGLE_MIN, s.volumeAngleMinDeg);
            s.volumeAngleMaxDeg = getFloat(c, AppSettingsDbHelper.COL_VOLUME_ANGLE_MAX, s.volumeAngleMaxDeg);

            s.pitchDirectionInverted = getInt(c, AppSettingsDbHelper.COL_PITCH_DIR_INV, 0) != 0;
            s.volumeDirectionInverted = getInt(c, AppSettingsDbHelper.COL_VOLUME_DIR_INV, 0) != 0;

            s.toneType = sanitizeTone(getString(c, AppSettingsDbHelper.COL_TONE_TYPE, AppSettings.TONE_SINE));

            s.pitchEnabled = getInt(c, AppSettingsDbHelper.COL_PITCH_ENABLED, 1) != 0;
            s.volumeEnabled = getInt(c, AppSettingsDbHelper.COL_VOLUME_ENABLED, 1) != 0;

            return s;
        } finally {
            c.close();
        }
    }

    private float getFloat(Cursor c, String col, float fallback) {
        int idx = c.getColumnIndex(col);
        if (idx < 0 || c.isNull(idx)) return fallback;
        try {
            return c.getFloat(idx);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int getInt(Cursor c, String col, int fallback) {
        int idx = c.getColumnIndex(col);
        if (idx < 0 || c.isNull(idx)) return fallback;
        try {
            return c.getInt(idx);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String getString(Cursor c, String col, String fallback) {
        int idx = c.getColumnIndex(col);
        if (idx < 0 || c.isNull(idx)) return fallback;
        try {
            String v = c.getString(idx);
            return v == null ? fallback : v;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String sanitizeTone(String tone) {
        if (tone == null) return AppSettings.TONE_SINE;
        String t = tone.trim().toUpperCase();
        if (AppSettings.TONE_SINE.equals(t)) return t;
        if (AppSettings.TONE_SQUARE.equals(t)) return t;
        if (AppSettings.TONE_TRIANGLE.equals(t)) return t;
        if (AppSettings.TONE_SAW.equals(t)) return t;
        return AppSettings.TONE_SINE;
    }
}