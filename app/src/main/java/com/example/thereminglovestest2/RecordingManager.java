package com.example.thereminglovestest2;

/** Sprint 2: Recording engine. Owned by Ayan (UI side) and Nirthika (storage side). */
public class RecordingManager {
    public interface RecordingListener {
        void onRecordingStarted();
        void onRecordingStopped(String filePath, long durationMs);
        void onRecordingError(String error);
    }

    public RecordingManager(android.content.Context context) { /* Nirthika implements */ }
    public void startRecording() { /* Ayan triggers, Nirthika implements */ }
    public void stopRecording() { /* Ayan triggers, Nirthika implements */ }
    public void setListener(RecordingListener listener) { /* Ayan uses */ }
    public boolean isRecording() { return false; /* Nirthika implements */ }
    public void release() { /* Nirthika implements */ }
}