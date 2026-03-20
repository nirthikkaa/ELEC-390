package com.example.thereminglovestest2;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 2: Local recording storage. Owned by Nirthika.
 *
 * Uses a dedicated recordings.db (separate from theremin_gloves.db) to avoid merge conflicts
 * with SettingsStore while both are being developed in parallel.
 *
 * File deletion is handled here on deleteRecording() so callers don't need to manage files
 * directly.
 */
public class RecordingRepository {

    public static final class Recording {
        public final long id;
        public final String filePath;
        public final String displayName;
        public final long durationMs;
        public final long createdAtMs;

        public Recording(long id, String filePath, String displayName, long durationMs, long createdAtMs) {
            this.id = id;
            this.filePath = filePath;
            this.displayName = displayName;
            this.durationMs = durationMs;
            this.createdAtMs = createdAtMs;
        }
    }

    private static final class DbHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "recordings.db";
        private static final int DB_VERSION = 1;
        private static final String TABLE = "recordings";

        DbHelper(Context context) {
            super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "file_path TEXT NOT NULL, " +
                "display_name TEXT NOT NULL, " +
                "duration_ms INTEGER NOT NULL DEFAULT 0, " +
                "created_at_ms INTEGER NOT NULL)"
            );
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Additive migrations only — no destructive upgrades.
        }
    }

    private final DbHelper dbHelper;

    public RecordingRepository(Context context) {
        dbHelper = new DbHelper(context);
    }

    public void saveRecording(String filePath, String name, long durationMs) {
        ContentValues v = new ContentValues();
        v.put("file_path", filePath);
        v.put("display_name", name);
        v.put("duration_ms", durationMs);
        v.put("created_at_ms", System.currentTimeMillis());
        dbHelper.getWritableDatabase().insert("recordings", null, v);
    }

    public List<Recording> getAllRecordings() {
        List<Recording> list = new ArrayList<>();
        try (Cursor c = dbHelper.getReadableDatabase()
                .query("recordings", null, null, null, null, null, "created_at_ms DESC")) {
            while (c.moveToNext()) {
                list.add(new Recording(
                    c.getLong(c.getColumnIndexOrThrow("id")),
                    c.getString(c.getColumnIndexOrThrow("file_path")),
                    c.getString(c.getColumnIndexOrThrow("display_name")),
                    c.getLong(c.getColumnIndexOrThrow("duration_ms")),
                    c.getLong(c.getColumnIndexOrThrow("created_at_ms"))
                ));
            }
        }
        return list;
    }

    /** Deletes the DB row and the audio file on disk. */
    public void deleteRecording(long id) {
        // Resolve file path before deleting the row.
        try (Cursor c = dbHelper.getReadableDatabase().query(
                "recordings", new String[]{"file_path"}, "id=?",
                new String[]{String.valueOf(id)}, null, null, null)) {
            if (c.moveToFirst()) {
                File f = new File(c.getString(0));
                if (f.exists()) f.delete();
            }
        }
        dbHelper.getWritableDatabase().delete("recordings", "id=?", new String[]{String.valueOf(id)});
    }
}
