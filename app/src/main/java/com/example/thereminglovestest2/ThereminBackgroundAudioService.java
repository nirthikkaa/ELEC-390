package com.example.thereminglovestest2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class ThereminBackgroundAudioService extends Service {

    private static final String ACTION_START = "com.example.thereminglovestest2.action.START_BG_AUDIO";
    private static final String CHANNEL_ID = "theremin_background_audio";
    private static final int NOTIFICATION_ID = 1201;
    private static final long SETTINGS_REFRESH_MS = 500;
    private static final long SYNC_TICK_MS = 20;

    private static volatile boolean serviceActive;
    private static volatile AppSettings calibrationPreviewSettings;
    private static volatile ThereminBackgroundAudioService activeInstance;

    private final AppSettings fallbackSettings = new AppSettings();
    private ThereminAudioEngine audioEngine;
    private SettingsStore settingsStore;
    private Thread syncThread;
    private volatile boolean syncRunning;
    private volatile AppSettings cachedSettings = fallbackSettings;
    private volatile long lastSettingsRefreshMs;
    private String lastPushedToneType = "";

    public static boolean isServiceActive() { return serviceActive; }
    public static ThereminAudioEngine.VisualizerSnapshot getVisualizerSnapshot() {
        ThereminBackgroundAudioService service = activeInstance;
        return service != null && service.audioEngine != null ? service.audioEngine.getVisualizerSnapshot() : null;
    }

    public static void startIfNeeded(Context context) {
        if (context == null) return;
        Intent i = new Intent(context, ThereminBackgroundAudioService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(context, i);
    }

    public static void stopIfRunning(Context context) {
        if (context != null) context.stopService(new Intent(context, ThereminBackgroundAudioService.class));
    }

    public static void beginCalibrationPreview(Context context, AppSettings settings) {
        calibrationPreviewSettings = copySettings(settings);
        // Start the service if it isn't already running — the sync loop needs to be alive to play audio.
        if (!serviceActive && context != null) startIfNeeded(context);
    }

    public static void endCalibrationPreview() {
        calibrationPreviewSettings = null;
    }

    /**
     * Sprint 2: Hooks a RecordingManager into the background service's audio engine PCM tap.
     * MainActivity calls this in onPause (to hand recording off to the background engine) and
     * again in onResume with the foreground engine. Pass null to disconnect.
     */
    public static void setRecordingManager(RecordingManager rm) {
        ThereminBackgroundAudioService svc = activeInstance;
        if (svc != null && svc.audioEngine != null) {
            svc.audioEngine.setPcmListener(rm);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        activeInstance = this;
        BleSessionManager.initialize(getApplicationContext());
        settingsStore = new SettingsStore(getApplicationContext());
        audioEngine = new ThereminAudioEngine();
        createNotificationChannelIfNeeded();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        startLoopIfNeeded();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        stopLoop();
        if (audioEngine != null) audioEngine.shutdown();
        calibrationPreviewSettings = null;
        serviceActive = false;
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void startLoopIfNeeded() {
        if (syncRunning) return;
        serviceActive = syncRunning = true;
        refreshSettings(true);
        if (!audioEngine.isRunning()) audioEngine.start();

        syncThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            while (syncRunning) {
                refreshSettings(false);
                pushTargets(BleSessionManager.getSnapshot(), cachedSettings);
                try {
                    Thread.sleep(SYNC_TICK_MS);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
            syncRunning = false;
        }, "ThereminBgServiceSync");
        syncThread.start();
    }

    private void stopLoop() {
        syncRunning = false;
        Thread t = syncThread;
        syncThread = null;
        if (t == null) return;
        t.interrupt();
        try { t.join(250); } catch (InterruptedException ignored) {}
    }

    private void refreshSettings(boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - lastSettingsRefreshMs < SETTINGS_REFRESH_MS) return;
        lastSettingsRefreshMs = now;
        try {
            AppSettings loaded = settingsStore.load();
            cachedSettings = loaded != null ? loaded : fallbackSettings;
        } catch (Exception ignored) {
            cachedSettings = fallbackSettings;
        }
    }

    private void pushTargets(BleSnapshot s, AppSettings a) {
        AppSettings active = calibrationPreviewSettings != null ? calibrationPreviewSettings : a;
        float freq = map(s != null ? s.pitchActiveDeltaDeg : 0f,
                active.pitchAngleMinDeg, active.pitchAngleMaxDeg, active.freqMinHz, active.freqMaxHz);
        float volume = map(s != null ? s.volumeActiveDeltaDeg : 0f,
                active.volumeAngleMinDeg, active.volumeAngleMaxDeg, 0f, 1f);

        boolean pitchOk = s != null && s.isPitchConnected() && active.pitchEnabled;
        boolean volumeOk = s != null && s.isVolumeConnected() && active.volumeEnabled;
        boolean ready = s != null && s.isBluetoothOn() && pitchOk && volumeOk;

        if (!active.toneType.equals(lastPushedToneType)) {
            audioEngine.setToneType(active.toneType);
            lastPushedToneType = active.toneType;
        }
        audioEngine.setTargets(pitchOk ? freq : active.freqMinHz, ready ? volume : 0f);
    }

    private static AppSettings copySettings(AppSettings source) {
        AppSettings copy = new AppSettings();
        if (source == null) return copy;
        copy.pitchAngleMinDeg = source.pitchAngleMinDeg;
        copy.pitchAngleMaxDeg = source.pitchAngleMaxDeg;
        copy.freqMinHz = source.freqMinHz;
        copy.freqMaxHz = source.freqMaxHz;
        copy.volumeAngleMinDeg = source.volumeAngleMinDeg;
        copy.volumeAngleMaxDeg = source.volumeAngleMaxDeg;
        copy.pitchDirectionInverted = source.pitchDirectionInverted;
        copy.volumeDirectionInverted = source.volumeDirectionInverted;
        copy.toneType = AppSettings.normalizeToneType(source.toneType);
        copy.pitchEnabled = source.pitchEnabled;
        copy.volumeEnabled = source.volumeEnabled;
        return copy;
    }

    private static float map(float x, float inMin, float inMax, float outMin, float outMax) {
        if (Math.abs(inMax - inMin) < 1e-6f) return outMin;
        float t = Math.max(0f, Math.min(1f, (x - inMin) / (inMax - inMin)));
        return outMin + t * (outMax - outMin);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT |
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Theremin Gloves")
                .setContentText("Background audio is active")
                .setContentIntent(content)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Theremin Background Audio",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps theremin audio alive while the app is in the background.");
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);
    }
}
