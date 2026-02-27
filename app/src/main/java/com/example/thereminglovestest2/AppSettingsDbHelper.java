package com.example.thereminglovestest2;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/**
 * SQLite helper for app settings persistence.
 * This version is intentionally simple and robust (self-healing table creation).
 */
public class AppSettingsDbHelper extends SQLiteOpenHelper {

    public static final String DB_NAME = "theremin_gloves.db";

    // Bump version to force upgrade/recreate if a broken v1 schema already exists on device
    public static final int DB_VERSION = 2;

    public static final String TABLE_SETTINGS = "app_settings";

    public static final String COL_ID = "id";
    public static final String COL_PITCH_ANGLE_MIN = "pitch_angle_min_deg";
    public static final String COL_PITCH_ANGLE_MAX = "pitch_angle_max_deg";
    public static final String COL_FREQ_MIN = "freq_min_hz";
    public static final String COL_FREQ_MAX = "freq_max_hz";
    public static final String COL_VOLUME_ANGLE_MIN = "volume_angle_min_deg";
    public static final String COL_VOLUME_ANGLE_MAX = "volume_angle_max_deg";
    public static final String COL_PITCH_DIR_INV = "pitch_direction_inverted";
    public static final String COL_VOLUME_DIR_INV = "volume_direction_inverted";
    public static final String COL_TONE_TYPE = "tone_type";
    public static final String COL_PITCH_ENABLED = "pitch_enabled";
    public static final String COL_VOLUME_ENABLED = "volume_enabled";
    public static final String COL_UPDATED_AT_MS = "updated_at_ms";

    public AppSettingsDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    /**
     * Shared CREATE TABLE statement (safe to call multiple times with IF NOT EXISTS).
     */
    public static String createTableSql() {
        return "CREATE TABLE IF NOT EXISTS " + TABLE_SETTINGS + " (" +
                COL_ID + " INTEGER PRIMARY KEY, " + // we always use row id = 1
                COL_PITCH_ANGLE_MIN + " REAL NOT NULL, " +
                COL_PITCH_ANGLE_MAX + " REAL NOT NULL, " +
                COL_FREQ_MIN + " REAL NOT NULL, " +
                COL_FREQ_MAX + " REAL NOT NULL, " +
                COL_VOLUME_ANGLE_MIN + " REAL NOT NULL, " +
                COL_VOLUME_ANGLE_MAX + " REAL NOT NULL, " +
                COL_PITCH_DIR_INV + " INTEGER NOT NULL DEFAULT 0, " +
                COL_VOLUME_DIR_INV + " INTEGER NOT NULL DEFAULT 0, " +
                COL_TONE_TYPE + " TEXT NOT NULL DEFAULT 'SINE', " +
                COL_PITCH_ENABLED + " INTEGER NOT NULL DEFAULT 1, " +
                COL_VOLUME_ENABLED + " INTEGER NOT NULL DEFAULT 1, " +
                COL_UPDATED_AT_MS + " INTEGER NOT NULL" +
                ")";
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(createTableSql());
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);

        // Self-heal in case table is missing due to earlier bad run / partial DB creation
        if (db != null && db.isOpen()) {
            db.execSQL(createTableSql());
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // MVP-safe approach: rebuild settings table if schema changes.
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SETTINGS);
        onCreate(db);
    }
}