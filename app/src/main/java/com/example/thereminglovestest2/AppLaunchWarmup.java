package com.example.thereminglovestest2;

/**
 * File guide:
 * Lightweight process-wide warmup cache used to make the first Play open feel faster.
 *
 * The app launches through Setup/Launch first, so we can spend that idle time preparing the
 * heaviest Play dependencies on a background thread. MainActivity then claims those objects
 * instead of constructing everything from scratch on the first Play open.
 */

import android.content.Context;

/**
 * Process-wide warmup coordinator.
 *
 * This keeps the implementation intentionally small:
 * warm once, claim once, and fall back safely if Play opens before the warm thread finishes.
 */
final class AppLaunchWarmup {

    private static final Object LOCK = new Object();

    private static boolean started;
    private static SettingsStore warmedSettingsStore;
    private static AppSettings warmedSettingsSnapshot;
    private static RecordingRepository warmedRecordingRepository;
    private static DrumEngine warmedDrumEngine;

    private AppLaunchWarmup() {}

    static void begin(Context context) {
        if (context == null) return;
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            if (started) return;
            started = true;
        }

        // Warm the expensive Play dependencies off the main thread during app launch.
        Thread warmThread = new Thread(() -> {
            // Warmup should help Play, never compete with launch enough to stall or crash it.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            try {
                SettingsStore settingsStore = new SettingsStore(appContext);
                AppSettings settingsSnapshot = settingsStore.load();
                RecordingRepository recordingRepository = new RecordingRepository(appContext);
                DrumEngine drumEngine = DrumEngine.createWarmup(appContext);
                synchronized (LOCK) {
                    if (warmedSettingsStore == null) warmedSettingsStore = settingsStore;
                    if (warmedSettingsSnapshot == null) warmedSettingsSnapshot = settingsSnapshot;
                    if (warmedRecordingRepository == null) warmedRecordingRepository = recordingRepository;
                    if (warmedDrumEngine == null) warmedDrumEngine = drumEngine;
                }
            } catch (Throwable ignored) {
                // MainActivity can always fall back to cold construction if warmup misses or fails.
            }
        }, "AppLaunchWarmup");
        warmThread.setDaemon(true);
        warmThread.start();
    }

    static SettingsStore takeSettingsStore() {
        synchronized (LOCK) {
            SettingsStore store = warmedSettingsStore;
            warmedSettingsStore = null;
            return store;
        }
    }

    static AppSettings takeSettingsSnapshot() {
        synchronized (LOCK) {
            AppSettings settings = warmedSettingsSnapshot;
            warmedSettingsSnapshot = null;
            return settings;
        }
    }

    static RecordingRepository takeRecordingRepository() {
        synchronized (LOCK) {
            RecordingRepository repository = warmedRecordingRepository;
            warmedRecordingRepository = null;
            return repository;
        }
    }

    static DrumEngine takeDrumEngine() {
        synchronized (LOCK) {
            DrumEngine drumEngine = warmedDrumEngine;
            warmedDrumEngine = null;
            return drumEngine;
        }
    }
}
