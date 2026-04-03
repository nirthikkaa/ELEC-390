package com.example.thereminglovestest2;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Instrumented repository tests for the Sprint 2 recording and library backlog.
 */
@RunWith(AndroidJUnit4.class)
public class RecordingRepositoryIntegrationTest {

    @Before
    public void setUp() {
        TestAppState.resetAll();
    }

    @Test
    public void recordings_canBeSavedRenamedMovedAndDeleted() throws IOException {
        RecordingRepository repo = TestAppState.repo();
        RecordingRepository.Recording saved = TestAppState.saveRecording(
                "Sprint Clip",
                42_000L,
                System.currentTimeMillis(),
                AppSettings.COMPRESSION_HIGH);
        long folderId = repo.createFolder("Practice");

        repo.renameRecording(saved.id, "Renamed Clip");
        repo.moveRecording(saved.id, folderId);

        List<RecordingRepository.Recording> recordings = repo.getAllRecordings();
        assertEquals(1, recordings.size());
        RecordingRepository.Recording moved = recordings.get(0);
        assertEquals("Renamed Clip", moved.displayName);
        assertEquals(folderId, moved.folderId);
        assertEquals(AppSettings.COMPRESSION_HIGH, moved.quality);

        File audioFile = new File(moved.filePath);
        assertTrue(audioFile.exists());
        repo.deleteRecording(moved.id);

        assertTrue(repo.getAllRecordings().isEmpty());
        assertFalse(audioFile.exists());
    }

    @Test
    public void deletingFolderMovesRecordingsBackToRoot() throws IOException {
        RecordingRepository repo = TestAppState.repo();
        long folderId = repo.createFolder("Folder To Delete");
        RecordingRepository.Recording recording = TestAppState.saveRecording(
                "Inside Folder",
                10_000L,
                System.currentTimeMillis(),
                AppSettings.COMPRESSION_MEDIUM);
        repo.moveRecording(recording.id, folderId);

        repo.deleteFolder(folderId);

        assertTrue(repo.getAllFolders().isEmpty());
        List<RecordingRepository.Recording> recordings = repo.getAllRecordings();
        assertEquals(1, recordings.size());
        assertEquals(-1L, recordings.get(0).folderId);
    }

    @Test
    public void recordings_areReturnedNewestFirst() throws IOException {
        RecordingRepository repo = TestAppState.repo();
        TestAppState.saveRecording("Oldest", 5_000L, 1_000L, AppSettings.COMPRESSION_LOW);
        TestAppState.saveRecording("Newest", 6_000L, 9_000L, AppSettings.COMPRESSION_HIGH);
        TestAppState.saveRecording("Middle", 7_000L, 5_000L, AppSettings.COMPRESSION_MEDIUM);

        List<RecordingRepository.Recording> recordings = repo.getAllRecordings();

        assertEquals(3, recordings.size());
        assertEquals("Newest", recordings.get(0).displayName);
        assertEquals("Middle", recordings.get(1).displayName);
        assertEquals("Oldest", recordings.get(2).displayName);
    }

    @Test
    public void deletingUnknownRecordingIsSafe() {
        RecordingRepository repo = TestAppState.repo();

        repo.deleteRecording(99_999L);

        assertTrue(repo.getAllRecordings().isEmpty());
    }
}
