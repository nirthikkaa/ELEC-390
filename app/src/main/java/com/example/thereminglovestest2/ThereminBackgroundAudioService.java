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
    // Octave shift is applied in the sync loop before target frequencies are pushed to the
    // background audio engine. Range is clamped to [-2, 2].
    private static volatile int octaveShift = 0;

    private final AppSettings fallbackSettings = new AppSettings();
    private ThereminAudioEngine audioEngine;
    // The service owns DrumEngine so beats and bass can keep running while Play is off-screen.
    private DrumEngine drumEngine;
    private SettingsStore settingsStore;
    private Thread syncThread;
    private volatile boolean syncRunning;
    private volatile AppSettings cachedSettings = fallbackSettings;
    private volatile long lastSettingsRefreshMs;
    private String lastPushedToneType = "";

    // Background-playback state mirrored from the UI and applied on each sync tick.
    private static volatile String  bgActiveScale       = "CHROMATIC";
    private static volatile boolean bgReverbEnabled     = false;
    private static volatile float   bgReverbMix         = 0.3f;
    private static volatile boolean bgDelayEnabled      = false;
    private static volatile float   bgDelayFeedback     = 0.35f;
    private static volatile float   bgDelayMix          = 0.4f;
    private static volatile boolean bgDistortionEnabled = false;
    private static volatile float   bgDistortionGain    = 3.0f;
    private static volatile boolean bgDrumEnabled          = false;
    private static volatile boolean bgBassEnabled          = false;
    private static volatile float   bgSensitivityResponseCurve = 1.0f;

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
        ThereminBackgroundAudioService svc = activeInstance;
        // Force silence first so "off" actions do not wait on async service teardown.
        thereminMuted = true;
        if (svc != null) {
            if (svc.audioEngine != null) svc.audioEngine.setTargets(440f, 0f);
            if (svc.drumEngine != null) svc.drumEngine.setPaused(true);
        }
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

    /** Push a tone change into the live background audio engine immediately. */
    public static void setToneTypeNow(String toneType) {
        ThereminBackgroundAudioService svc = activeInstance;
        if (svc == null || svc.audioEngine == null) return;
        String normalized = AppSettings.normalizeToneType(toneType);
        svc.audioEngine.setToneType(normalized);
        svc.lastPushedToneType = normalized;
    }

    public static void setThereminMuted(boolean muted) {
        thereminMuted = muted;
        DrumEngine d = getDrumEngine();
        if (d != null) d.setPaused(muted);
    }

    /**
     * Shift the theremin pitch by whole octaves without changing glove mapping.
     * The sync thread applies this just before it pushes the current target frequency.
     *
     * @param shift Number of octaves to shift; clamped to [-2, 2].
     */
    public static void setOctaveShift(int shift) {
        octaveShift = Math.max(-2, Math.min(2, shift));
    }

    public static void setSensitivityResponseCurve(float exponent) {
        float clamped = AppSettings.clampSensitivityResponseCurve(exponent);
        bgSensitivityResponseCurve = clamped;
        AppSettings preview = calibrationPreviewSettings;
        if (preview != null) {
            preview.sensitivityResponseCurve = clamped;
            preview.sensitivityLevel = AppSettings.curveToLegacySensitivityLevel(clamped);
        }
        ThereminBackgroundAudioService svc = activeInstance;
        if (svc != null && svc.cachedSettings != null) {
            svc.cachedSettings.sensitivityResponseCurve = clamped;
            svc.cachedSettings.sensitivityLevel = AppSettings.curveToLegacySensitivityLevel(clamped);
        }
    }

    /** Returns the current octave shift value. */
    public static int getOctaveShift() { return octaveShift; }

    /** Background-playback control values mirrored from the UI and applied on each sync tick. */
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
     * Expose the service-owned DrumEngine so Play can update beats without building a second one.
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
        // Release the service-owned beat engine before the process lets the service go.
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
            bgSensitivityResponseCurve =
                    AppSettings.clampSensitivityResponseCurve(cachedSettings.sensitivityResponseCurve);
        } catch (Exception ignored) {
            cachedSettings = fallbackSettings;
            bgSensitivityResponseCurve =
                    AppSettings.clampSensitivityResponseCurve(fallbackSettings.sensitivityResponseCurve);
        }
    }

    private void pushTargets(BleSnapshot s, AppSettings a) {
        AppSettings active = calibrationPreviewSettings != null ? calibrationPreviewSettings : a;
        float sens = bgSensitivityResponseCurve;

        float pitchNorm = applySensitivityCurve(
                normalizeClamped(s != null ? s.pitchActiveDeltaDeg : 0f,
                        active.pitchAngleMinDeg, active.pitchAngleMaxDeg),
                sens);
        float volNorm = applySensitivityCurve(
                normalizeClamped(s != null ? s.volumeActiveDeltaDeg : 0f,
                        active.volumeAngleMinDeg, active.volumeAngleMaxDeg),
                sens);

        float freq = lerp(active.freqMinHz, active.freqMaxHz, pitchNorm);
        float volume = lerp(0f, 1f, volNorm);

        boolean pitchOk = s != null && s.isPitchConnected() && active.pitchEnabled;
        boolean volumeOk = s != null && s.isVolumeConnected() && active.volumeEnabled;
        boolean ready = s != null && s.isBluetoothOn() && pitchOk && volumeOk;

        if (!active.toneType.equals(lastPushedToneType)) {
            audioEngine.setToneType(active.toneType);
            lastPushedToneType = active.toneType;
        }

        // Mirror the current drum and bass toggles into the service-owned beat engine every tick.
        if (drumEngine != null) {
            drumEngine.setEnabled(bgDrumEnabled);
            drumEngine.setBassEnabled(bgBassEnabled);
        }

        // Mirror scale lock and effects into the background synth every tick.
        audioEngine.setActiveScale(bgActiveScale);
        audioEngine.setReverbEnabled(bgReverbEnabled);
        audioEngine.setReverbMix(bgReverbMix);
        audioEngine.setDelayEnabled(bgDelayEnabled);
        audioEngine.setDelayFeedback(bgDelayFeedback);
        audioEngine.setDelayMix(bgDelayMix);
        audioEngine.setDistortionEnabled(bgDistortionEnabled);
        audioEngine.setDistortionGain(bgDistortionGain);

        // Apply octave shift after mapping glove motion into frequency, then clamp the result.
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

    private static float normalizeClamped(float x, float inMin, float inMax) {
        if (Math.abs(inMax - inMin) < 1e-6f) return 0f;
        return Math.max(0f, Math.min(1f, (x - inMin) / (inMax - inMin)));
    }

    private static float applySensitivityCurve(float normalized, float exponent) {
        float clamped = Math.max(0f, Math.min(1f, normalized));
        float safeExponent = AppSettings.clampSensitivityResponseCurve(exponent);
        return (float) Math.pow(clamped, safeExponent);
    }

    private static float lerp(float start, float end, float t) {
        float clamped = Math.max(0f, Math.min(1f, t));
        return start + (end - start) * clamped;
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
