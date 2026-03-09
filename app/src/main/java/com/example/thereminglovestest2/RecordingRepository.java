package com.example.thereminglovestest2;

import java.util.List;

/** Sprint 2: Local recording storage. Owned by Nirthika. */
public class RecordingRepository {
    public static final class Recording {
        public final long id;
        public final String filePath;
        public final String displayName;
        public final long durationMs;
        public final long createdAtMs;
        public Recording(long id, String filePath, String displayName, long durationMs, long createdAtMs) {
            this.id = id; this.filePath = filePath; this.displayName = displayName;
            this.durationMs = durationMs; this.createdAtMs = createdAtMs;
        }
    }

    public RecordingRepository(android.content.Context context) { /* Nirthika implements */ }
    public void saveRecording(String filePath, String name, long durationMs) { /* Nirthika implements */ }
    public List<Recording> getAllRecordings() { return new java.util.ArrayList<>(); /* Nirthika implements */ }
    public void deleteRecording(long id) { /* Nirthika implements */ }
}