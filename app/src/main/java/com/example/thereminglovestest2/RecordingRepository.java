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
 * SQLite repository for recording metadata.
 * Handles CRUD operations for recordings table.
 */
public class RecordingRepository extends SQLiteOpenHelper {
    
    private static final String DB_NAME = "recordings.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE_RECORDINGS = "recordings";
    
    // Column names
    private static final String COL_ID = "id";
    private static final String COL_FILE_PATH = "file_path";
    private static final String COL_DISPLAY_NAME = "display_name";
    private static final String COL_DURATION_MS = "duration_ms";
    private static final String COL_CREATED_AT_MS = "created_at_ms";
    
    public RecordingRepository(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }
    
    @Override
    public void onCreate(SQLiteDatabase db) {
        // Create recordings table
        String createTableQuery = "CREATE TABLE " + TABLE_RECORDINGS + " (" +
            COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
            COL_FILE_PATH + " TEXT NOT NULL, " +
            COL_DISPLAY_NAME + " TEXT NOT NULL, " +
            COL_DURATION_MS + " INTEGER NOT NULL DEFAULT 0, " +
            COL_CREATED_AT_MS + " INTEGER NOT NULL" +
            ")";
        
        db.execSQL(createTableQuery);
    }
    
    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Future migrations can be added here
    }
    
    /**
     * Save a recording to the database
     * @return The ID of the inserted recording
     */
    public long saveRecording(String filePath, String displayName, long durationMs) {
        SQLiteDatabase db = getWritableDatabase();
        
        ContentValues values = new ContentValues();
        values.put(COL_FILE_PATH, filePath);
        values.put(COL_DISPLAY_NAME, displayName);
        values.put(COL_DURATION_MS, durationMs);
        values.put(COL_CREATED_AT_MS, System.currentTimeMillis());
        
        long recordingId = db.insert(TABLE_RECORDINGS, null, values);
        db.close();
        
        return recordingId;
    }
    
    /**
     * Get all recordings, ordered by creation time (newest first)
     */
    public List<Recording> getAllRecordings() {
        List<Recording> recordings = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        
        String query = "SELECT * FROM " + TABLE_RECORDINGS + 
                      " ORDER BY " + COL_CREATED_AT_MS + " DESC";
        
        Cursor cursor = db.rawQuery(query, null);
        
        if (cursor.moveToFirst()) {
            do {
                Recording recording = new Recording(
                    cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_FILE_PATH)),
                    cursor.getString(cursor.getColumnIndexOrThrow(COL_DISPLAY_NAME)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(COL_DURATION_MS)),
                    cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT_MS))
                );
                recordings.add(recording);
            } while (cursor.moveToNext());
        }
        
        cursor.close();
        db.close();
        
        return recordings;
    }
    
    /**
     * Delete a recording by ID
     * ID-8.3: Also deletes the actual audio file
     */
    public void deleteRecording(long id) {
        SQLiteDatabase db = getWritableDatabase();
        
        // First, get the file path
        Cursor cursor = db.query(
            TABLE_RECORDINGS,
            new String[]{COL_FILE_PATH},
            COL_ID + " = ?",
            new String[]{String.valueOf(id)},
            null,
            null,
            null
        );
        
        if (cursor.moveToFirst()) {
            String filePath = cursor.getString(0);
            
            // Delete the actual file
            File f = new File(filePath);
            if (f.exists()) {
                f.delete();
            }
            
            // Delete the database row
            db.delete(
                TABLE_RECORDINGS,
                COL_ID + " = ?",
                new String[]{String.valueOf(id)}
            );
        }
        
        cursor.close();
        db.close();
    }
    
    /**
     * Recording data model
     */
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
        
        public String getDurationFormatted() {
            long seconds = durationMs / 1000;
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return String.format("%d:%02d", minutes, secs);
        }
    }
}
