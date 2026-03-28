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
    private static volatile boolean thereminMuted = false;
    // Sprint 3: Octave shift applied in the sync thread before pushing freq targets.
    // Range is clamped to [-2, 2] (4 octaves total).
    private static volatile int octaveShift = 0;

    private final AppSettings fallbackSettings = new AppSettings();
    private ThereminAudioEngine audioEngine;
    // Sprint 3: Drum engine owned by the service so it survives Play screen navigation.
    private DrumEngine drumEngine;
    private SettingsStore settingsStore;
    private Thread syncThread;
    private volatile boolean syncRunning;
    private volatile AppSettings cachedSettings = fallbackSettings;
    private volatile long lastSettingsRefreshMs;
    private String lastPushedToneType = "";

    // Sprint 3: Scale lock + effects + drum/bass — forwarded to background drumEngine every tick.
    private static volatile String  bgActiveScale       = "CHROMATIC";
    private static volatile boolean bgReverbEnabled     = false;
    private static volatile float   bgReverbMix         = 0.3f;
    private static volatile boolean bgDelayEnabled      = false;
    private static volatile float   bgDelayFeedback     = 0.35f;
    private static volatile float   bgDelayMix          = 0.4f;
    private static volatile boolean bgDistortionEnabled = false;
    private static volatile float   bgDistortionGain    = 3.0f;
    private static volatile boolean bgDrumEnabled       = false;
    private static volatile boolean bgBassEnabled       = false;

    public static boolean isServiceActive()  { return serviceActive; }
    public static boolean isThereminMuted()  { return thereminMuted; }
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
    public static void setToneTypeNow(String toneType) {
        ThereminBackgroundAudioService svc = activeInstance;
        if (svc == null || svc.audioEngine == null) return;
        String normalized = AppSettings.normalizeToneType(toneType);
        svc.audioEngine.setToneType(normalized);
        svc.lastPushedToneType = normalized;
    }

    public static void setThereminMuted(boolean muted) {
        thereminMuted = muted;
    }

    /**
     * Sprint 3: Shift the theremin pitch by whole octaves without changing the
     * glove mapping. Applied in the sync thread before setTargets() is called.
     * @param shift Number of octaves to shift; clamped to [-2, 2].
     */
    public static void setOctaveShift(int shift) {
        octaveShift = Math.max(-2, Math.min(2, shift));
    }

    /** Sprint 3: Returns the current octave shift value. */
    public static int getOctaveShift() { return octaveShift; }

    /** Sprint 3: Scale lock + effects + drum/bass — forwarded to background engines every sync tick. */
    public static void setActiveScale(String scale)       { bgActiveScale = (scale != null) ? scale : "CHROMATIC"; }
    public static void setReverbEnabled(boolean on)       { bgReverbEnabled = on; }
    public static void setReverbMix(float mix)            { bgReverbMix = mix; }
    public static void setDelayEnabled(boolean on)        { bgDelayEnabled = on; }
    public static void setDelayFeedback(float fb)         { bgDelayFeedback = fb; }
    public static void setDelayMix(float mix)             { bgDelayMix = mix; }
    public static void setDistortionEnabled(boolean on)   { bgDistortionEnabled = on; }
    public static void setDistortionGain(float gain)      { bgDistortionGain = gain; }
    public static void setDrumEnabled(boolean on)         { bgDrumEnabled = on; }
    public static void setBassEnabled(boolean on)         { bgBassEnabled = on; }
    public static void setMixGain(float gain) {
        ThereminBackgroundAudioService svc = activeInstance;
        if (svc != null && svc.audioEngine != null) svc.audioEngine.setMixGain(gain);
    }
    public static void setDrumBpm(int bpm) {
        DrumEngine d = getDrumEngine();
        if (d != null) d.setBpm(bpm);
    }
    public static void setDrumGain(float gain) {
        DrumEngine d = getDrumEngine();
        if (d != null) d.setDrumGain(gain);
    }
    public static void setClapTone(float tone) {
        DrumEngine d = getDrumEngine();
        if (d != null) d.setClapTone(tone);
    }
    public static void setKeyboardSynthMode(int mode) {
        DrumEngine d = getDrumEngine();
        if (d != null) d.setKeyboardSynthMode(mode);
    }
    public static void setCustomPattern(boolean[][] grid, int[] pianoSteps) {
        DrumEngine d = getDrumEngine();
        if (d != null) {
            if (grid != null) d.setCustomPattern(grid, pianoSteps);
            else d.clearCustomPattern();
        }
    }
    public static void setCustomPattern(boolean[][] grid) {
        setCustomPattern(grid, null);
    }

    /**
     * Sprint 3: Expose the service's DrumEngine so MainActivity can toggle
     * drums without owning a separate instance.
     */
    public static DrumEngine getDrumEngine() {
        ThereminBackgroundAudioService svc = activeInstance;
        return svc != null ? svc.drumEngine : null;
    }

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
        // Sprint 3: Create and start the drum engine; wire it to the audio engine for reference.
        drumEngine = new DrumEngine(getApplicationContext());
        drumEngine.start();
        audioEngine.setDrumEngine(drumEngine);
        createNotificationChannelIfNeeded();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        startLoopIfNeeded();
        return START_NOT_STICKY; // don't auto-restart; user must explicitly press Play
    }

    /** Stop the service when the user swipes the app away from recents. */
    @Override public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override public void onDestroy() {
        stopLoop();
        if (audioEngine != null) audioEngine.shutdown();
        // Sprint 3: release drum engine resources before the service exits.
        if (drumEngine != null) { drumEngine.release(); drumEngine = null; }
        calibrationPreviewSettings = null;
        serviceActive = false;
        if (activeInstance == this) activeInstance = null;
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private void startLoopIfNeeded() {
        if (syncRunning) return;
        if (audioEngine == null) return;
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

        // Sprint 3: Forward drum/bass enabled state to background drum engine every tick.
        if (drumEngine != null) {
            drumEngine.setEnabled(bgDrumEnabled);
            drumEngine.setBassEnabled(bgBassEnabled);
            drumEngine.setPaused(thereminMuted);
        }

        // Sprint 3: Forward scale lock + effects to the background audio engine every tick.
        audioEngine.setActiveScale(bgActiveScale);
        audioEngine.setReverbEnabled(bgReverbEnabled);
        audioEngine.setReverbMix(bgReverbMix);
        audioEngine.setDelayEnabled(bgDelayEnabled);
        audioEngine.setDelayFeedback(bgDelayFeedback);
        audioEngine.setDelayMix(bgDelayMix);
        audioEngine.setDistortionEnabled(bgDistortionEnabled);
        audioEngine.setDistortionGain(bgDistortionGain);

        // Sprint 3: Apply octave shift — multiply frequency by 2^shift, then re-clamp.
        float pitchFreq = pitchOk ? freq : active.freqMinHz;
        int shift = octaveShift;
        if (shift != 0) pitchFreq = Math.max(20f, Math.min(20000f, pitchFreq * (float) Math.pow(2.0, shift)));

        audioEngine.setTargets(pitchFreq, (ready && !thereminMuted) ? volume : 0f);
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
