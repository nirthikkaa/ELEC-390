package com.example.thereminglovestest2;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import androidx.test.platform.app.InstrumentationRegistry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Shared instrumentation-test helpers for resetting local app state and seeding library content.
 */
final class TestAppState {
    private static final String SETTINGS_DB = "theremin_gloves.db";
    private static final String RECORDINGS_DB = "recordings.db";
    private static final String PREFS_APP = "theremin_prefs";
    private static final String PREFS_UI = "calibration_ui_prefs";
    private static final String PACKAGE_NAME = "com.example.thereminglovestest2";

    private TestAppState() {}

    interface BleCondition {
        boolean matches(BleSnapshot snapshot);
    }

    static Context targetContext() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    static void resetAll() {
        Context context = targetContext();
        // Stop any background owner so tests always start from a quiet transport state.
        ThereminBackgroundAudioService.stopIfRunning(context);
        ThereminBackgroundAudioService.setThereminMuted(false);
        ThereminBackgroundAudioService.setDrumEnabled(false);
        ThereminBackgroundAudioService.setBassEnabled(false);
        ThereminBackgroundAudioService.setActiveScale(AppSettings.SCALE_CHROMATIC);
        ThereminBackgroundAudioService.setOctaveShift(0);
        ThereminBackgroundAudioService.setReverbEnabled(false);
        ThereminBackgroundAudioService.setDelayEnabled(false);
        ThereminBackgroundAudioService.setDistortionEnabled(false);
        ThereminBackgroundAudioService.setSensitivityResponseCurve(AppSettings.DEFAULT_SENSITIVITY_RESPONSE_CURVE);
        context.stopService(new Intent(context, ThereminBackgroundAudioService.class));

        clearPrefs(context, PREFS_APP);
        clearPrefs(context, PREFS_UI);
        deleteRecordingsDir(context);
        resetSettingsDatabase(context);
        context.deleteDatabase(RECORDINGS_DB);
        resetSettingsStoreSchemaGate();
    }

    static void enableBluetooth() {
        // Keep Play-screen UI tests out of the system Bluetooth prompt path.
        executeShell("cmd bluetooth_manager enable");
        executeShell("cmd bluetooth_manager wait-for-state:STATE_ON");
    }

    static void disableBluetooth() {
        // Force the launch loader down the Bluetooth-enable prompt path.
        executeShell("cmd bluetooth_manager disable");
        executeShell("cmd bluetooth_manager wait-for-state:STATE_OFF");
    }

    static void waitForBluetoothEnabled(boolean enabled, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (BleSessionManager.isBluetoothEnabled(targetContext()) == enabled) return;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Bluetooth state did not reach " + enabled + " within " + timeoutMs + "ms");
    }

    static void grantBlePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        grantRuntimePermission(android.Manifest.permission.BLUETOOTH_SCAN);
        grantRuntimePermission(android.Manifest.permission.BLUETOOTH_CONNECT);
    }

    static void revokeBlePermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        revokeRuntimePermission(android.Manifest.permission.BLUETOOTH_SCAN);
        revokeRuntimePermission(android.Manifest.permission.BLUETOOTH_CONNECT);
    }

    static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.contains("emulator")
                || Build.HARDWARE.contains("ranchu")
                || Build.PRODUCT.contains("sdk_gphone")
                || Build.DEVICE.contains("emu")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("sdk_gphone")
                || Build.MODEL.contains("Android SDK built for x86");
    }

    static void setReturningUser(boolean returningUser) {
        targetContext().getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(LaunchActivity.KEY_FIRST_LAUNCH_DONE, returningUser)
                .putBoolean(LaunchActivity.KEY_GRID_HINT_PENDING, false)
                .commit();
    }

    static BleSnapshot waitForBleSnapshot(BleCondition condition, long timeoutMs) {
        BleSnapshot snapshot = waitForBleSnapshotOrNull(condition, timeoutMs);
        if (snapshot == null) throw new AssertionError("BLE condition not reached within " + timeoutMs + "ms");
        return snapshot;
    }

    static BleSnapshot waitForBleSnapshotOrNull(BleCondition condition, long timeoutMs) {
        BleSessionManager.initialize(targetContext());
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            BleSnapshot snapshot = BleSessionManager.getSnapshot();
            if (condition.matches(snapshot)) return snapshot;
            SystemClock.sleep(150L);
        }
        return null;
    }

    static void seedBeatPresetSlot(int slotIdx, int bpm) {
        android.content.SharedPreferences.Editor editor = targetContext()
                .getSharedPreferences(PREFS_APP, Context.MODE_PRIVATE)
                .edit();
        String prefix = slotIdx == 0 ? "beat_maker" : "beat_slot_" + slotIdx;
        if (slotIdx == 0) editor.putBoolean(prefix + "_custom_active", true);
        else editor.putBoolean(prefix + "_saved", true);
        // Seed one simple kick hit so the preset is treated as a real programmed beat.
        editor.putString(prefix + "_row_0", "1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0");
        editor.putInt("beat_master_bpm", bpm);
        editor.putBoolean(LaunchActivity.KEY_GRID_HINT_PENDING, false);
        editor.commit();
    }

    static RecordingRepository.Recording saveRecording(String name, long durationMs,
                                                       long createdAtMs, String quality) throws IOException {
        File file = createSilentWav(name, Math.max(200, (int) Math.min(durationMs, 1_000L)));
        saveRecordingMetadata(file.getAbsolutePath(), name, durationMs, quality, createdAtMs);
        return findRecording(name);
    }

    static RecordingRepository.Recording saveBrokenRecording(String name, long durationMs,
                                                             long createdAtMs, String quality) {
        File missing = new File(recordingsDir(), sanitize(name) + "_missing.wav");
        saveRecordingMetadata(missing.getAbsolutePath(), name, durationMs, quality, createdAtMs);
        return findRecording(name);
    }

    static RecordingRepository repo() {
        return new RecordingRepository(targetContext());
    }

    private static void saveRecordingMetadata(String filePath, String name, long durationMs,
                                              String quality, long createdAtMs) {
        repo().saveRecording(filePath, name, durationMs, quality);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                targetContext().getDatabasePath(RECORDINGS_DB).getAbsolutePath(),
                null,
                SQLiteDatabase.OPEN_READWRITE)) {
            ContentValues values = new ContentValues();
            values.put("created_at_ms", createdAtMs);
            db.update("recordings", values, "display_name=?", new String[]{name});
        }
    }

    private static RecordingRepository.Recording findRecording(String name) {
        List<RecordingRepository.Recording> recordings = repo().getAllRecordings();
        for (RecordingRepository.Recording recording : recordings) {
            if (name.equals(recording.displayName)) return recording;
        }
        throw new AssertionError("Recording not found: " + name);
    }

    private static File createSilentWav(String name, int durationMs) throws IOException {
        File file = new File(recordingsDir(), sanitize(name) + ".wav");
        int sampleRate = 48_000;
        int channels = 1;
        int bitsPerSample = 16;
        int sampleCount = Math.max(1, sampleRate * durationMs / 1_000);
        int dataBytes = sampleCount * channels * (bitsPerSample / 8);
        byte[] pcm = new byte[dataBytes];

        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R', 'I', 'F', 'F'});
        header.putInt(36 + dataBytes);
        header.put(new byte[]{'W', 'A', 'V', 'E'});
        header.put(new byte[]{'f', 'm', 't', ' '});
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(sampleRate * channels * (bitsPerSample / 8));
        header.putShort((short) (channels * (bitsPerSample / 8)));
        header.putShort((short) bitsPerSample);
        header.put(new byte[]{'d', 'a', 't', 'a'});
        header.putInt(dataBytes);

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(header.array());
            out.write(pcm);
        }
        return file;
    }

    private static File recordingsDir() {
        File dir = new File(targetContext().getFilesDir(), "recordings");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new AssertionError("Could not create recordings dir: " + dir);
        }
        return dir;
    }

    private static String sanitize(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9._-]+", "_");
    }

    private static void clearPrefs(Context context, String name) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
    }

    private static void grantRuntimePermission(String permission) {
        executeShell("pm grant " + PACKAGE_NAME + " " + permission);
    }

    private static void revokeRuntimePermission(String permission) {
        executeShell("pm revoke " + PACKAGE_NAME + " " + permission);
    }

    private static void deleteRecordingsDir(Context context) {
        deleteRecursively(new File(context.getFilesDir(), "recordings"));
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        // Best-effort cleanup is enough here because the DB reset removes stale rows.
        file.delete();
    }

    private static void resetSettingsStoreSchemaGate() {
        try {
            Field field = SettingsStore.class.getDeclaredField("schemaVerifiedForProcess");
            field.setAccessible(true);
            field.setBoolean(null, false);
        } catch (Exception e) {
            throw new AssertionError("Could not reset SettingsStore schema gate", e);
        }
    }

    private static void resetSettingsDatabase(Context context) {
        // Reset the settings row in place instead of deleting the DB file. Some Play-screen
        // background persistence tasks can still outlive the previous ActivityScenario briefly,
        // and keeping the same file avoids SQLITE_READONLY_DBMOVED races during those writes.
        resetSettingsStoreSchemaGate();
        SettingsStore store = new SettingsStore(context);
        try {
            store.save(new AppSettings());
        } finally {
            store.close();
        }
    }

    private static void executeShell(String command) {
        ParcelFileDescriptor pfd = InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
             ByteArrayOutputStream sink = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[256];
            int read;
            while ((read = in.read(buffer)) != -1) {
                sink.write(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new AssertionError("Shell command failed: " + command, e);
        }
    }
}
