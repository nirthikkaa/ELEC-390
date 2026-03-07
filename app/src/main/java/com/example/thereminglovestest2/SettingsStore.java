package com.example.thereminglovestest2;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * Tiny settings database.
 *
 * We only keep one row on purpose.
 * That makes the rest of the app dead simple: load once, edit fields, save once.
 */
public class SettingsStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "theremin_gloves.db";
    private static final int DB_VERSION = 3;

    private static final String TABLE = "app_settings";
    private static final int ROW_ID = 1;

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
                    COL_UPDATED_AT_MS + " INTEGER NOT NULL" +
                    ")";

    public SettingsStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_SQL);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL(CREATE_SQL);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Keep old data whenever possible instead of wiping calibration.
        db.execSQL(CREATE_SQL);
        addColumnIfMissing(db, COL_PITCH_DIR_INV, "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, COL_VOL_DIR_INV, "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, COL_TONE_TYPE, "TEXT NOT NULL DEFAULT 'SINE'");
        addColumnIfMissing(db, COL_PITCH_ENABLED, "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(db, COL_VOL_ENABLED, "INTEGER NOT NULL DEFAULT 1");
        addColumnIfMissing(db, COL_UPDATED_AT_MS, "INTEGER NOT NULL DEFAULT 0");
    }

    public synchronized void save(AppSettings settings) {
        if (settings == null) {
            return;
        }

        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(CREATE_SQL);
        db.insertWithOnConflict(TABLE, null, toValues(settings), SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized AppSettings load() {
        SQLiteDatabase db = getReadableDatabase();
        db.execSQL(CREATE_SQL);

        try (Cursor c = db.query(
                TABLE,
                null,
                COL_ID + "=?",
                new String[]{String.valueOf(ROW_ID)},
                null,
                null,
                null
        )) {
            if (!c.moveToFirst()) {
                return new AppSettings();
            }
            return fromCursor(c);
        }
    }

    private ContentValues toValues(AppSettings settings) {
        ContentValues values = new ContentValues();
        values.put(COL_ID, ROW_ID);
        values.put(COL_PITCH_ANGLE_MIN, settings.pitchAngleMinDeg);
        values.put(COL_PITCH_ANGLE_MAX, settings.pitchAngleMaxDeg);
        values.put(COL_FREQ_MIN, settings.freqMinHz);
        values.put(COL_FREQ_MAX, settings.freqMaxHz);
        values.put(COL_VOL_ANGLE_MIN, settings.volumeAngleMinDeg);
        values.put(COL_VOL_ANGLE_MAX, settings.volumeAngleMaxDeg);
        values.put(COL_PITCH_DIR_INV, settings.pitchDirectionInverted ? 1 : 0);
        values.put(COL_VOL_DIR_INV, settings.volumeDirectionInverted ? 1 : 0);
        values.put(COL_TONE_TYPE, AppSettings.normalizeToneType(settings.toneType));
        values.put(COL_PITCH_ENABLED, settings.pitchEnabled ? 1 : 0);
        values.put(COL_VOL_ENABLED, settings.volumeEnabled ? 1 : 0);
        values.put(COL_UPDATED_AT_MS, System.currentTimeMillis());
        return values;
    }

    private AppSettings fromCursor(Cursor c) {
        AppSettings settings = new AppSettings();
        settings.pitchAngleMinDeg = getFloat(c, COL_PITCH_ANGLE_MIN, settings.pitchAngleMinDeg);
        settings.pitchAngleMaxDeg = getFloat(c, COL_PITCH_ANGLE_MAX, settings.pitchAngleMaxDeg);
        settings.freqMinHz = getFloat(c, COL_FREQ_MIN, settings.freqMinHz);
        settings.freqMaxHz = getFloat(c, COL_FREQ_MAX, settings.freqMaxHz);
        settings.volumeAngleMinDeg = getFloat(c, COL_VOL_ANGLE_MIN, settings.volumeAngleMinDeg);
        settings.volumeAngleMaxDeg = getFloat(c, COL_VOL_ANGLE_MAX, settings.volumeAngleMaxDeg);
        settings.pitchDirectionInverted = getInt(c, COL_PITCH_DIR_INV, 0) != 0;
        settings.volumeDirectionInverted = getInt(c, COL_VOL_DIR_INV, 0) != 0;
        settings.toneType = AppSettings.normalizeToneType(getString(c, COL_TONE_TYPE, AppSettings.TONE_SINE));
        settings.pitchEnabled = getInt(c, COL_PITCH_ENABLED, 1) != 0;
        settings.volumeEnabled = getInt(c, COL_VOL_ENABLED, 1) != 0;
        return settings;
    }

    private void addColumnIfMissing(SQLiteDatabase db, String name, String definition) {
        if (hasColumn(db, name)) {
            return;
        }
        db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + name + " " + definition);
    }

    private boolean hasColumn(SQLiteDatabase db, String columnName) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + TABLE + ")", null)) {
            int nameIndex = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (nameIndex >= 0 && columnName.equals(c.getString(nameIndex))) {
                    return true;
                }
            }
        }
        return false;
    }

    private float getFloat(Cursor c, String col, float fallback) {
        int i = c.getColumnIndex(col);
        try {
            return (i >= 0 && !c.isNull(i)) ? c.getFloat(i) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private int getInt(Cursor c, String col, int fallback) {
        int i = c.getColumnIndex(col);
        try {
            return (i >= 0 && !c.isNull(i)) ? c.getInt(i) : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private String getString(Cursor c, String col, String fallback) {
        int i = c.getColumnIndex(col);
        try {
            if (i < 0 || c.isNull(i)) {
                return fallback;
            }
            String value = c.getString(i);
            return value != null ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }
}
