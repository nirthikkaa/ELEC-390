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

/**
 * Background audio owner.
 *
 * Why this exists:
 * - A normal activity thread is fine while Play is on screen.
 * - Once the app leaves the foreground, Android can schedule that activity less reliably.
 * - For smooth continuous sound, background playback needs a real foreground service owner.
 */
public class ThereminBackgroundAudioService extends Service {

    private static final String ACTION_START = "com.example.thereminglovestest2.action.START_BG_AUDIO";
    private static final String CHANNEL_ID = "theremin_background_audio";
    private static final int NOTIFICATION_ID = 1201;
    private static final long SETTINGS_REFRESH_MS = 500;
    private static final long SYNC_TICK_MS = 20;

    private static volatile boolean serviceActive = false;

    private ThereminAudioEngine audioEngine;
    private SettingsStore settingsStore;
    private Thread syncThread;
    private volatile boolean syncRunning = false;
    private volatile AppSettings cachedSettings = new AppSettings();
    private volatile long lastSettingsRefreshMs = 0L;

    public static boolean isServiceActive() {
        return serviceActive;
    }

    public static void startIfNeeded(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, ThereminBackgroundAudioService.class);
        intent.setAction(ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stopIfRunning(Context context) {
        if (context == null) return;
        context.stopService(new Intent(context, ThereminBackgroundAudioService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        BleSessionManager.initialize(getApplicationContext());
        settingsStore = new SettingsStore(getApplicationContext());
        audioEngine = new ThereminAudioEngine();
        createNotificationChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        startPlaybackLoopIfNeeded();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopPlaybackLoop();
        if (audioEngine != null) {
            audioEngine.shutdown();
        }
        serviceActive = false;
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startPlaybackLoopIfNeeded() {
        if (syncRunning) return;

        serviceActive = true;
        syncRunning = true;
        refreshSettings(true);

        if (audioEngine != null && !audioEngine.isRunning()) {
            audioEngine.start();
        }

        syncThread = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);

            while (syncRunning) {
                refreshSettings(false);
                pushLatestTargets();

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

    private void stopPlaybackLoop() {
        syncRunning = false;

        Thread thread = syncThread;
        syncThread = null;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(250);
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void refreshSettings(boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - lastSettingsRefreshMs < SETTINGS_REFRESH_MS) return;

        lastSettingsRefreshMs = now;
        try {
            AppSettings loaded = settingsStore != null ? settingsStore.load() : null;
            cachedSettings = loaded != null ? loaded : new AppSettings();
        } catch (Exception ignored) {
            cachedSettings = new AppSettings();
        }
    }

    private void pushLatestTargets() {
        if (audioEngine == null) return;

        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        AppSettings settings = cachedSettings != null ? cachedSettings : new AppSettings();

        boolean btEnabled = BleUiText.isBluetoothOn(snapshot);
        boolean pitchConnected = BleUiText.isGloveConnected(snapshot, true);
        boolean volumeConnected = BleUiText.isGloveConnected(snapshot, false);

        float freq = mapLinearClamped(
                snapshot != null ? snapshot.pitchActiveDeltaDeg : 0f,
                settings.pitchAngleMinDeg,
                settings.pitchAngleMaxDeg,
                settings.freqMinHz,
                settings.freqMaxHz
        );
        float volume = mapLinearClamped(
                snapshot != null ? snapshot.volumeActiveDeltaDeg : 0f,
                settings.volumeAngleMinDeg,
                settings.volumeAngleMaxDeg,
                0f,
                1f
        );

        if (!pitchConnected) freq = settings.freqMinHz;
        if (!volumeConnected) volume = 0f;
        if (!btEnabled || !(pitchConnected && volumeConnected)) volume = 0f;
        if (!settings.pitchEnabled) freq = settings.freqMinHz;
        if (!settings.volumeEnabled) volume = 0f;

        audioEngine.setToneType(settings.toneType);
        audioEngine.setTargets(freq, volume);
    }

    private float mapLinearClamped(float x, float inMin, float inMax, float outMin, float outMax) {
        if (Math.abs(inMax - inMin) < 1e-6f) return outMin;
        float t = clamp((x - inMin) / (inMax - inMin), 0f, 1f);
        return outMin + t * (outMax - outMin);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                openIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                        : PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Theremin Gloves")
                .setContentText("Background audio is active")
                .setContentIntent(contentIntent)
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
