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
        public final long folderId; // -1 = root (no folder)
        public final String quality; // e.g. "LOSSLESS", "HIGH", "MEDIUM", "LOW", or "" for old recordings

        public Recording(long id, String filePath, String displayName,
                         long durationMs, long createdAtMs, long folderId, String quality) {
            this.id = id;
            this.filePath = filePath;
            this.displayName = displayName;
            this.durationMs = durationMs;
            this.createdAtMs = createdAtMs;
            this.folderId = folderId;
            this.quality = quality != null ? quality : "";
        }
    }

    public static final class Folder {
        public final long id;
        public final String name;
        public final long createdAtMs;
        public final int recordingCount;

        public Folder(long id, String name, long createdAtMs, int recordingCount) {
            this.id = id;
            this.name = name;
            this.createdAtMs = createdAtMs;
            this.recordingCount = recordingCount;
        }
    }

    private static final class DbHelper extends SQLiteOpenHelper {
        private static final String DB_NAME = "recordings.db";
        private static final int DB_VERSION = 3;

        DbHelper(Context context) {
            super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS folders (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "created_at_ms INTEGER NOT NULL)"
            );
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS recordings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "file_path TEXT NOT NULL, " +
                "display_name TEXT NOT NULL, " +
                "duration_ms INTEGER NOT NULL DEFAULT 0, " +
                "created_at_ms INTEGER NOT NULL, " +
                "folder_id INTEGER NOT NULL DEFAULT -1, " +
                "quality TEXT NOT NULL DEFAULT '')"
            );
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion < 2) {
                db.execSQL("CREATE TABLE IF NOT EXISTS folders (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "name TEXT NOT NULL, " +
                        "created_at_ms INTEGER NOT NULL)");
                db.execSQL("ALTER TABLE recordings ADD COLUMN folder_id INTEGER NOT NULL DEFAULT -1");
            }
            if (oldVersion < 3) {
                db.execSQL("ALTER TABLE recordings ADD COLUMN quality TEXT NOT NULL DEFAULT ''");
            }
        }
    }

    private final DbHelper dbHelper;

    public RecordingRepository(Context context) {
        dbHelper = new DbHelper(context);
    }

    // ── Recordings ────────────────────────────────────────────────────────────

    public void saveRecording(String filePath, String name, long durationMs, String quality) {
        saveRecording(filePath, name, durationMs, quality, null);
    }

    public void saveRecording(String filePath, String name, long durationMs, String quality, String exportedPath) {
        ContentValues v = new ContentValues();
        v.put("file_path", filePath);
        v.put("display_name", name);
        v.put("duration_ms", durationMs);
        v.put("created_at_ms", System.currentTimeMillis());
        v.put("folder_id", -1);
        v.put("quality", quality != null ? quality : "");
        dbHelper.getWritableDatabase().insert("recordings", null, v);
    }

    public List<Recording> getAllRecordings() {
        List<Recording> list = new ArrayList<>();
        try (Cursor c = dbHelper.getReadableDatabase()
                .query("recordings", null, null, null, null, null, "created_at_ms DESC")) {
            while (c.moveToNext()) {
                list.add(fromCursor(c));
            }
        }
        return list;
    }

    public void renameRecording(long id, String newName) {
        ContentValues v = new ContentValues();
        v.put("display_name", newName);
        dbHelper.getWritableDatabase().update("recordings", v, "id=?", new String[]{String.valueOf(id)});
    }

    public void moveRecording(long id, long folderId) {
        ContentValues v = new ContentValues();
        v.put("folder_id", folderId);
        dbHelper.getWritableDatabase().update("recordings", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** Deletes the DB row and the audio file on disk. */
    public void deleteRecording(long id) {
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

    // ── Folders ───────────────────────────────────────────────────────────────

    public long createFolder(String name) {
        ContentValues v = new ContentValues();
        v.put("name", name);
        v.put("created_at_ms", System.currentTimeMillis());
        return dbHelper.getWritableDatabase().insert("folders", null, v);
    }

    public void renameFolder(long id, String newName) {
        ContentValues v = new ContentValues();
        v.put("name", newName);
        dbHelper.getWritableDatabase().update("folders", v, "id=?", new String[]{String.valueOf(id)});
    }

    /** Moves all recordings in the folder back to root, then deletes the folder. */
    public void deleteFolder(long id) {
        ContentValues v = new ContentValues();
        v.put("folder_id", -1);
        dbHelper.getWritableDatabase().update("recordings", v, "folder_id=?", new String[]{String.valueOf(id)});
        dbHelper.getWritableDatabase().delete("folders", "id=?", new String[]{String.valueOf(id)});
    }

    public List<Folder> getAllFolders() {
        List<Folder> list = new ArrayList<>();
        String sql = "SELECT f.id, f.name, f.created_at_ms, COUNT(r.id) AS cnt " +
                     "FROM folders f " +
                     "LEFT JOIN recordings r ON r.folder_id = f.id " +
                     "GROUP BY f.id " +
                     "ORDER BY f.name COLLATE NOCASE ASC";
        try (Cursor c = dbHelper.getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                list.add(new Folder(c.getLong(0), c.getString(1), c.getLong(2), c.getInt(3)));
            }
        }
        return list;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Recording fromCursor(Cursor c) {
        int folderIdx  = c.getColumnIndex("folder_id");
        long folderId  = folderIdx >= 0 ? c.getLong(folderIdx) : -1;
        int qualityIdx = c.getColumnIndex("quality");
        String quality = qualityIdx >= 0 ? c.getString(qualityIdx) : "";
        return new Recording(
            c.getLong(c.getColumnIndexOrThrow("id")),
            c.getString(c.getColumnIndexOrThrow("file_path")),
            c.getString(c.getColumnIndexOrThrow("display_name")),
            c.getLong(c.getColumnIndexOrThrow("duration_ms")),
            c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
            folderId,
            quality
        );
    }
}
