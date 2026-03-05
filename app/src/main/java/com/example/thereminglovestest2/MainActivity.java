package com.example.thereminglovestest2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Play screen (instrument).
 *
 * IMPORTANT:
 * - BLE is owned by BleSessionManager (via BleHostBridge).
 * - This activity only renders snapshots + plays audio.
 * - Legacy in-activity BLE/GATT code has been removed to avoid double owners.
 */
public class MainActivity extends AppCompatActivity {

    private static WeakReference<MainActivity> activeHostRef = new WeakReference<>(null);

    // ===== Preferences =====
    private static final String PREFS_NAME = "theremin_prefs";
    private static final String PREF_BG_AUDIO_ENABLED = "bg_audio_enabled";
    private static final String PREF_EXTENDED_FREQ_RANGE_ENABLED = "extended_frequency_range_enabled";

    // ===== UI tick =====
    private static final long UI_TICK_MS = 80;

    // ===== Log throttling =====
    private static final int LOG_MAX_LINES = 200;
    private static final long LOG_FLUSH_MIN_INTERVAL_MS = 600;

    // ===== Slider ranges =====
    private static final float ANGLE_MIN = -90f;
    private static final float ANGLE_MAX = 90f;
    private static final float ANGLE_STEP = 0.5f;
    private static final int ANGLE_PROGRESS_MAX = (int) ((ANGLE_MAX - ANGLE_MIN) / ANGLE_STEP);

    private static final float FREQ_MIN_UI = 20f;
    private static final float FREQ_STANDARD_MAX_UI = 2000f;
    private static final float FREQ_EXTENDED_MAX_UI = 20000f;

    // ===== UI =====
    private TextView tvStatus, tvPitchConn, tvVolConn, tvAudio;
    private TextView tvPitchValue, tvVolValue, tvToneValue;
    private TextView tvPlayRemoteLabel, tvReconnectLabel, tvBackgroundLabel;
    private TextView tvPitchLast, tvVolLast, tvLog;

    private TextView tvPitchAngleMinVal, tvPitchAngleMaxVal, tvFreqMinVal, tvFreqMaxVal;
    private TextView tvVolAngleMinVal, tvVolAngleMaxVal;

    private SeekBar sbPitchAngleMin, sbPitchAngleMax, sbFreqMin, sbFreqMax;
    private SeekBar sbVolAngleMin, sbVolAngleMax;

    private MaterialButton btnScanConnect, btnAudioStart, btnAudioStop;
    private Button btnDisconnectAll, btnOpenCalibration;
    private Button btnNeutralPitch, btnNeutralVol, btnDirectionPitch, btnDirectionVol, btnHelpPitch, btnHelpVol;
    private Button btnDefaults;

    private ThereminVisualizerView thereminVisualizerView;
    private Animation calibrateGlowAnimation;

    // ===== State =====
    private volatile boolean bgAudioEnabled = true;
    private volatile boolean playUiVisible = false;
    private volatile float currentFreqMaxUi = FREQ_STANDARD_MAX_UI;

    // ===== Mapping settings =====
    private volatile float pitchAngleMinDeg = -15.0f;
    private volatile float pitchAngleMaxDeg = 55.0f;
    private volatile float freqMinHz = 880.0f;
    private volatile float freqMaxHz = 2000.0f;

    private volatile float volumeAngleMinDeg = -10.0f;
    private volatile float volumeAngleMaxDeg = 55.0f;

    // ===== Latest glove values (from shared manager snapshots) =====
    private volatile float pitchActiveDeltaDeg = 0.0f;
    private volatile float volActiveDeltaDeg = 0.0f;
    private volatile boolean pitchHasAngle = false;
    private volatile boolean volHasAngle = false;

    private volatile float mappedFreqHz = 880.0f;
    private volatile float mappedVolumeLinear = 0.0f;

    private volatile float audioTargetFreqHz = 880.0f;
    private volatile float audioTargetVolumeLinear = 0.0f;

    private volatile boolean suppressSliderCallbacks = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Performance: bounded log buffer
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private volatile long lastLogFlushMs = 0;

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            refreshUiFast();
            mainHandler.postDelayed(this, UI_TICK_MS);
        }
    };

    private final Runnable backgroundAudioSyncTicker = new Runnable() {
        @Override
        public void run() {
            syncAudioTargetsFromSharedBleState();
            if (shouldRunBackgroundAudioSync()) {
                mainHandler.postDelayed(this, UI_TICK_MS);
            }
        }
    };

    private AudioEngine audioEngine;

    // ===== Persistence =====
    private AppSettingsRepository settingsRepo;

    private static MainActivity getActiveHost() {
        return activeHostRef.get();
    }

    /**
     * Called from Settings via BleHostBridge when the background-audio toggle changes.
     * We only apply it if this Activity is alive.
     */
    public static void refreshBackgroundAudioPreferenceFromFacade() {
        MainActivity host = getActiveHost();
        if (host == null) return;
        host.mainHandler.post(host::applyBackgroundAudioPreferenceNow);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BleHostBridge.initialize(getApplicationContext());
        activeHostRef = new WeakReference<>(this);

        bindViews();

        audioEngine = new AudioEngine();

        loadBgAudioPref();
        refreshFrequencyRangeLimit();

        setupSlidersAndClickNumbers();
        syncAllMappingControlsFromState();
        recomputeMappedOutputs();

        wireButtons();

        appendLogSafe("Play opened");
        appendLogSafe("BG audio: " + (bgAudioEnabled ? "ON" : "OFF"));
        updateAudioStatusText();
        updateBleButtonText();
    }

    @Override
    protected void onStart() {
        super.onStart();
        activeHostRef = new WeakReference<>(this);
        playUiVisible = true;

        loadBgAudioPref();
        refreshFrequencyRangeLimit();
        reloadMappingSettingsFromRepository();
        syncAudioTargetsFromSharedBleState();

        mainHandler.removeCallbacks(backgroundAudioSyncTicker);
        mainHandler.removeCallbacks(uiTicker);
        mainHandler.post(uiTicker);

        // Play is instrument-focused. Entering this screen should not silently
        // start a new BLE session; connection is explicit from Home/Connect/Play.
    }

    @Override
    protected void onResume() {
        super.onResume();
        activeHostRef = new WeakReference<>(this);
        playUiVisible = true;

        mainHandler.removeCallbacks(backgroundAudioSyncTicker);

        loadBgAudioPref();
        refreshFrequencyRangeLimit();

        // Preserve user's manual play/pause choice when returning to Play.
        updateAudioStatusText();

        reloadMappingSettingsFromRepository();
        syncAudioTargetsFromSharedBleState();
        syncAllMappingControlsFromState();
        updateBleButtonText();
    }

    @Override
    protected void onPause() {
        super.onPause();
        playUiVisible = false;

        if (audioEngine != null) {
            if (!bgAudioEnabled && !isChangingConfigurations()) {
                audioEngine.stop();
                appendLogSafe("onPause -> audio stopped (background off)");
            } else if (bgAudioEnabled && audioEngine.isRunning()) {
                appendLogSafe("onPause -> audio kept running in background");
            }
        }

        restartBackgroundAudioSyncIfNeeded();
    }

    @Override
    protected void onStop() {
        super.onStop();
        playUiVisible = false;
        mainHandler.removeCallbacks(uiTicker);

        if (isChangingConfigurations()) {
            restartBackgroundAudioSyncIfNeeded();
            return;
        }

        if (bgAudioEnabled) {
            restartBackgroundAudioSyncIfNeeded();
        } else {
            mainHandler.removeCallbacks(backgroundAudioSyncTicker);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        activeHostRef = new WeakReference<>(null);

        mainHandler.removeCallbacks(uiTicker);
        mainHandler.removeCallbacks(backgroundAudioSyncTicker);

        // If the Activity is finishing, stop audio. If it's being recreated (rotation),
        // keep behavior consistent with earlier builds (do not force-stop when BG audio is enabled).
        if (audioEngine != null) {
            if (!bgAudioEnabled || isFinishing()) {
                audioEngine.shutdown();
            }
        }
    }

    private void loadBgAudioPref() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bgAudioEnabled = sp.getBoolean(PREF_BG_AUDIO_ENABLED, true);
    }

    private void saveBgAudioPref() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putBoolean(PREF_BG_AUDIO_ENABLED, bgAudioEnabled).apply();
    }

    private boolean isExtendedFrequencyRangeEnabled() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return sp.getBoolean(PREF_EXTENDED_FREQ_RANGE_ENABLED, false);
    }

    private void refreshFrequencyRangeLimit() {
        currentFreqMaxUi = isExtendedFrequencyRangeEnabled()
                ? FREQ_EXTENDED_MAX_UI
                : FREQ_STANDARD_MAX_UI;
    }

    private int getFreqProgressMax() {
        return Math.round(currentFreqMaxUi - FREQ_MIN_UI);
    }

    private void applyBackgroundAudioPreferenceNow() {
        boolean wasEnabled = bgAudioEnabled;
        loadBgAudioPref();
        updateAudioStatusText();

        if (!bgAudioEnabled && wasEnabled && !playUiVisible && audioEngine != null && audioEngine.isRunning()) {
            audioEngine.stop();
            appendLogSafe("BG audio pref changed -> audio stopped");
        }

        restartBackgroundAudioSyncIfNeeded();
    }

    private boolean shouldRunBackgroundAudioSync() {
        return !playUiVisible
                && bgAudioEnabled
                && audioEngine != null
                && audioEngine.isRunning();
    }

    private void restartBackgroundAudioSyncIfNeeded() {
        mainHandler.removeCallbacks(backgroundAudioSyncTicker);
        if (shouldRunBackgroundAudioSync()) {
            mainHandler.post(backgroundAudioSyncTicker);
        }
    }

    /**
     * Pulls the latest shared BLE state and updates audio targets.
     * This is the ONLY data path used for audio mapping.
     */
    private void syncAudioTargetsFromSharedBleState() {
        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        if (calSnapshot != null && calSnapshot.hostReady) {
            pitchActiveDeltaDeg = calSnapshot.pitchActiveDeltaDeg;
            volActiveDeltaDeg = calSnapshot.volumeActiveDeltaDeg;
            pitchHasAngle = calSnapshot.pitchConnected;
            volHasAngle = calSnapshot.volumeConnected;
        } else {
            pitchHasAngle = false;
            volHasAngle = false;
        }

        recomputeMappedOutputs();

        // If Bluetooth is off (per shared manager), hard-mute.
        if (bleSnapshot != null && bleSnapshot.hostReady && !bleSnapshot.bluetoothEnabled) {
            audioTargetVolumeLinear = 0f;
            mappedVolumeLinear = 0f;
        }
    }

    private void toastSafe(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void bindViews() {
        TopNavBarView topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) {
            topNavBar.setTitleText("Play");
        }

        tvStatus = findViewById(R.id.tvStatus);
        tvPitchConn = findViewById(R.id.tvPitchConn);
        tvVolConn = findViewById(R.id.tvVolConn);
        tvAudio = findViewById(R.id.tvAudio);

        tvPitchValue = findViewById(R.id.tvPitchValue);
        tvVolValue = findViewById(R.id.tvVolValue);
        tvToneValue = findViewById(R.id.tvToneValue);

        tvPlayRemoteLabel = findViewById(R.id.tvPlayRemoteLabel);
        tvReconnectLabel = findViewById(R.id.tvReconnectLabel);
        tvBackgroundLabel = findViewById(R.id.tvBackgroundLabel);

        tvPitchLast = findViewById(R.id.tvPitchLast);
        tvVolLast = findViewById(R.id.tvVolLast);
        tvLog = findViewById(R.id.tvLog);

        tvPitchAngleMinVal = findViewById(R.id.tvPitchAngleMinVal);
        tvPitchAngleMaxVal = findViewById(R.id.tvPitchAngleMaxVal);
        tvFreqMinVal = findViewById(R.id.tvFreqMinVal);
        tvFreqMaxVal = findViewById(R.id.tvFreqMaxVal);

        tvVolAngleMinVal = findViewById(R.id.tvVolAngleMinVal);
        tvVolAngleMaxVal = findViewById(R.id.tvVolAngleMaxVal);

        sbPitchAngleMin = findViewById(R.id.sbPitchAngleMin);
        sbPitchAngleMax = findViewById(R.id.sbPitchAngleMax);
        sbFreqMin = findViewById(R.id.sbFreqMin);
        sbFreqMax = findViewById(R.id.sbFreqMax);

        sbVolAngleMin = findViewById(R.id.sbVolAngleMin);
        sbVolAngleMax = findViewById(R.id.sbVolAngleMax);

        btnScanConnect = findViewById(R.id.btnScanConnect);
        btnDisconnectAll = findViewById(R.id.btnDisconnectAll);
        btnAudioStart = findViewById(R.id.btnAudioStart);
        btnAudioStop = findViewById(R.id.btnAudioStop);
        btnOpenCalibration = findViewById(R.id.btnOpenCalibration);

        btnNeutralPitch = findViewById(R.id.btnNeutralPitch);
        btnNeutralVol = findViewById(R.id.btnNeutralVol);
        btnDirectionPitch = findViewById(R.id.btnDirectionPitch);
        btnDirectionVol = findViewById(R.id.btnDirectionVol);
        btnHelpPitch = findViewById(R.id.btnHelpPitch);
        btnHelpVol = findViewById(R.id.btnHelpVol);

        btnDefaults = findViewById(R.id.btnDefaults);

        thereminVisualizerView = findViewById(R.id.thereminVisualizerView);
    }

    private void wireButtons() {
        if (btnDisconnectAll != null) {
            btnDisconnectAll.setVisibility(View.GONE);
        }

        // NOTE: The reconnect icon button is small, and users naturally tap the label too.
        // Make BOTH the icon button and its label trigger the same reconnect action.
        btnScanConnect.setOnClickListener(v -> {
            appendLogSafe("Reconnect pressed");
            onBleTogglePressed();
        });

        if (tvReconnectLabel != null) {
            tvReconnectLabel.setClickable(true);
            tvReconnectLabel.setFocusable(true);
            tvReconnectLabel.setOnClickListener(v -> {
                appendLogSafe("Reconnect label pressed");
                onBleTogglePressed();
            });
        }

        btnAudioStart.setOnClickListener(v -> {
            if (audioEngine == null) return;

            BleHostBridge.BleUiSnapshot snapshot = BleHostBridge.getBleUiSnapshot();
            if (snapshot != null && snapshot.hostReady && !snapshot.bluetoothEnabled) {
                toastSafe("Bluetooth is OFF");
                return;
            }

            if (audioEngine.isRunning()) {
                audioEngine.stop();
            } else {
                audioEngine.start();
            }

            updateAudioStatusText();
        });

        btnAudioStop.setOnClickListener(v -> {
            bgAudioEnabled = !bgAudioEnabled;
            saveBgAudioPref();
            appendLogSafe("BG audio toggled -> " + (bgAudioEnabled ? "ON" : "OFF"));
            toastSafe("Background audio: " + (bgAudioEnabled ? "ON" : "OFF"));

            if (!bgAudioEnabled && !playUiVisible && audioEngine != null && audioEngine.isRunning()) {
                audioEngine.stop();
                appendLogSafe("BG audio disabled while app in background -> audio stopped");
            }

            restartBackgroundAudioSyncIfNeeded();
            updateAudioStatusText();
        });

        if (btnOpenCalibration != null) {
            btnOpenCalibration.setText("✨ Calibrate Precisely");
            btnOpenCalibration.setOnClickListener(v -> openCalibrationScreen());
        }

        // Hidden command buttons remain compile-safe (layout keeps IDs).
        btnNeutralPitch.setOnClickListener(v -> BleHostBridge.requestCaptureNeutral(true));
        btnNeutralVol.setOnClickListener(v -> BleHostBridge.requestCaptureNeutral(false));

        btnDirectionPitch.setOnClickListener(v -> {
            appendLogSafe("Pitch: toggle direction");
            BleHostBridge.requestToggleDirection(true);
        });

        btnDirectionVol.setOnClickListener(v -> {
            appendLogSafe("Volume: toggle direction");
            BleHostBridge.requestToggleDirection(false);
        });

        btnHelpPitch.setOnClickListener(v -> showHelpDialog("Pitch glove"));
        btnHelpVol.setOnClickListener(v -> showHelpDialog("Volume glove"));

        btnDefaults.setOnClickListener(v -> {
            setDefaultMappingValues();
            syncAllMappingControlsFromState();
            recomputeMappedOutputs();
            persistSettings();
            appendLogSafe("Defaults restored");
        });

        updateAudioStatusText();
        updateBleButtonText();
    }

    private void onBleTogglePressed() {
        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        // Quick user feedback so the button never feels "dead".
        if (bleSnapshot != null && bleSnapshot.hostReady && !bleSnapshot.bluetoothEnabled) {
            toastSafe("Bluetooth is OFF");
            return;
        }

        boolean pitchConnected = calSnapshot != null && calSnapshot.hostReady && calSnapshot.pitchConnected;
        boolean volConnected = calSnapshot != null && calSnapshot.hostReady && calSnapshot.volumeConnected;
        if (pitchConnected && volConnected) {
            toastSafe("Both gloves are already connected");
            return;
        }

        toastSafe("Connecting missing glove(s)...");
        BleHostBridge.requestConnectMissingGloves();
        refreshUiFromBleHost();
        updateBleButtonText();
    }

    private void refreshUiFromBleHost() {
        refreshUiFast();
    }

    private void updateBleButtonText() {
        if (btnScanConnect == null) return;

        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        String label;
        String contentDescription;

        if (bleSnapshot == null || !bleSnapshot.hostReady) {
            label = "Connect";
            contentDescription = "Connect gloves";
        } else {
            boolean bothConnected = calSnapshot != null && calSnapshot.pitchConnected && calSnapshot.volumeConnected;
            boolean oneConnected = calSnapshot != null && (calSnapshot.pitchConnected || calSnapshot.volumeConnected);
            boolean connecting = bleSnapshot.scanning
                    || BleUiText.isConnecting(bleSnapshot.pitchConnText)
                    || BleUiText.isConnecting(bleSnapshot.volumeConnText);

            if (!bleSnapshot.bluetoothEnabled) {
                label = "Bluetooth Off";
                contentDescription = "Bluetooth is off";
            } else if (bothConnected) {
                label = "Connected";
                contentDescription = "Both gloves are connected";
            } else if (connecting || oneConnected) {
                label = "Reconnect";
                contentDescription = "Reconnect missing gloves";
            } else {
                label = "Connect";
                contentDescription = "Connect gloves";
            }
        }

        btnScanConnect.setText("");
        btnScanConnect.setContentDescription(contentDescription);

        if (tvReconnectLabel != null) {
            tvReconnectLabel.setText(label);
        }
    }

    private void openCalibrationScreen() {
        Intent intent = new Intent(this, CalibrationActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
        );
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void showHelpDialog(String title) {
        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        StringBuilder sb = new StringBuilder();
        if (bleSnapshot == null || !bleSnapshot.hostReady) {
            sb.append("BLE host not ready.\n\n");
        } else {
            sb.append("Bluetooth: ").append(bleSnapshot.bluetoothEnabled ? "ON" : "OFF").append("\n");
            sb.append("Scanning: ").append(bleSnapshot.scanning ? "YES" : "NO").append("\n");
            sb.append("Status: ").append(bleSnapshot.statusText).append("\n\n");

            sb.append("Pitch: ").append(bleSnapshot.pitchConnText).append("\n");
            sb.append("Volume: ").append(bleSnapshot.volumeConnText).append("\n\n");
        }

        if (calSnapshot != null && calSnapshot.hostReady) {
            sb.append("Pitch connected: ").append(calSnapshot.pitchConnected).append("\n");
            sb.append("Volume connected: ").append(calSnapshot.volumeConnected).append("\n");
            sb.append(String.format(Locale.US, "Pitch Δ: %.2f°\n", calSnapshot.pitchActiveDeltaDeg));
            sb.append(String.format(Locale.US, "Volume Δ: %.2f°\n", calSnapshot.volumeActiveDeltaDeg));
            sb.append("Pitch direction: ").append(calSnapshot.pitchDirectionText).append("\n");
            sb.append("Volume direction: ").append(calSnapshot.volumeDirectionText).append("\n\n");
        }

        sb.append("Background audio: ").append(bgAudioEnabled ? "ON" : "OFF").append("\n");

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void reloadMappingSettingsFromRepository() {
        if (settingsRepo == null) settingsRepo = new AppSettingsRepository(this);

        AppSettings s = settingsRepo.load();
        if (s == null) {
            setDefaultMappingValues();
            persistSettings();
            return;
        }

        pitchAngleMinDeg = s.pitchAngleMinDeg;
        pitchAngleMaxDeg = s.pitchAngleMaxDeg;
        freqMinHz = s.freqMinHz;
        freqMaxHz = s.freqMaxHz;

        volumeAngleMinDeg = s.volumeAngleMinDeg;
        volumeAngleMaxDeg = s.volumeAngleMaxDeg;

        sanitizeMappingValues();
    }

    private void persistSettings() {
        if (settingsRepo == null) settingsRepo = new AppSettingsRepository(this);

        sanitizeMappingValues();

        AppSettings s = settingsRepo.load();
        if (s == null) s = new AppSettings();

        s.pitchAngleMinDeg = pitchAngleMinDeg;
        s.pitchAngleMaxDeg = pitchAngleMaxDeg;
        s.freqMinHz = freqMinHz;
        s.freqMaxHz = freqMaxHz;

        s.volumeAngleMinDeg = volumeAngleMinDeg;
        s.volumeAngleMaxDeg = volumeAngleMaxDeg;

        // IMPORTANT:
        // Do NOT touch direction flags here.
        // Direction is owned by Calibration + BleSessionManager.
        settingsRepo.save(s);
    }

    private void setDefaultMappingValues() {
        pitchAngleMinDeg = -15.0f;
        pitchAngleMaxDeg = 55.0f;
        freqMinHz = 880.0f;
        freqMaxHz = 2000.0f;

        volumeAngleMinDeg = -10.0f;
        volumeAngleMaxDeg = 55.0f;

        sanitizeMappingValues();
    }

    private void sanitizeMappingValues() {
        pitchAngleMinDeg = clamp(pitchAngleMinDeg, ANGLE_MIN, ANGLE_MAX);
        pitchAngleMaxDeg = clamp(pitchAngleMaxDeg, ANGLE_MIN, ANGLE_MAX);
        volumeAngleMinDeg = clamp(volumeAngleMinDeg, ANGLE_MIN, ANGLE_MAX);
        volumeAngleMaxDeg = clamp(volumeAngleMaxDeg, ANGLE_MIN, ANGLE_MAX);

        freqMinHz = clamp(freqMinHz, FREQ_MIN_UI, currentFreqMaxUi);
        freqMaxHz = clamp(freqMaxHz, FREQ_MIN_UI, currentFreqMaxUi);

        if (pitchAngleMaxDeg < pitchAngleMinDeg + ANGLE_STEP) {
            pitchAngleMaxDeg = Math.min(ANGLE_MAX, pitchAngleMinDeg + ANGLE_STEP);
        }
        if (volumeAngleMaxDeg < volumeAngleMinDeg + ANGLE_STEP) {
            volumeAngleMaxDeg = Math.min(ANGLE_MAX, volumeAngleMinDeg + ANGLE_STEP);
        }
        if (freqMaxHz < freqMinHz + 1f) {
            freqMaxHz = Math.min(currentFreqMaxUi, freqMinHz + 1f);
        }
    }

    private float clamp(float x, float lo, float hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private void setupSlidersAndClickNumbers() {
        reloadMappingSettingsFromRepository();

        sbPitchAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbPitchAngleMax.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMax.setMax(ANGLE_PROGRESS_MAX);

        sbFreqMin.setMax(getFreqProgressMax());
        sbFreqMax.setMax(getFreqProgressMax());

        SeekBar.OnSeekBarChangeListener commonAngleListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (suppressSliderCallbacks) return;

                float value = progressToAngle(progress);

                if (seekBar == sbPitchAngleMin) pitchAngleMinDeg = value;
                if (seekBar == sbPitchAngleMax) pitchAngleMaxDeg = value;
                if (seekBar == sbVolAngleMin) volumeAngleMinDeg = value;
                if (seekBar == sbVolAngleMax) volumeAngleMaxDeg = value;

                sanitizeMappingValues();
                syncAllMappingControlsFromState();
                recomputeMappedOutputs();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                persistSettings();
                appendLogSafe("Mapping updated");
            }
        };

        SeekBar.OnSeekBarChangeListener freqListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (suppressSliderCallbacks) return;

                float value = progressToFreq(progress);

                if (seekBar == sbFreqMin) freqMinHz = value;
                if (seekBar == sbFreqMax) freqMaxHz = value;

                sanitizeMappingValues();
                syncAllMappingControlsFromState();
                recomputeMappedOutputs();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                persistSettings();
                appendLogSafe("Mapping updated");
            }
        };

        sbPitchAngleMin.setOnSeekBarChangeListener(commonAngleListener);
        sbPitchAngleMax.setOnSeekBarChangeListener(commonAngleListener);
        sbVolAngleMin.setOnSeekBarChangeListener(commonAngleListener);
        sbVolAngleMax.setOnSeekBarChangeListener(commonAngleListener);

        sbFreqMin.setOnSeekBarChangeListener(freqListener);
        sbFreqMax.setOnSeekBarChangeListener(freqListener);

        attachNumberClick(tvPitchAngleMinVal, "Pitch angle min (deg)", ANGLE_MIN, ANGLE_MAX,
                () -> pitchAngleMinDeg,
                v -> pitchAngleMinDeg = v);

        attachNumberClick(tvPitchAngleMaxVal, "Pitch angle max (deg)", ANGLE_MIN, ANGLE_MAX,
                () -> pitchAngleMaxDeg,
                v -> pitchAngleMaxDeg = v);

        attachNumberClick(tvVolAngleMinVal, "Volume angle min (deg)", ANGLE_MIN, ANGLE_MAX,
                () -> volumeAngleMinDeg,
                v -> volumeAngleMinDeg = v);

        attachNumberClick(tvVolAngleMaxVal, "Volume angle max (deg)", ANGLE_MIN, ANGLE_MAX,
                () -> volumeAngleMaxDeg,
                v -> volumeAngleMaxDeg = v);

        attachNumberClick(tvFreqMinVal, "Frequency min (Hz)", FREQ_MIN_UI, currentFreqMaxUi,
                () -> freqMinHz,
                v -> freqMinHz = v);

        attachNumberClick(tvFreqMaxVal, "Frequency max (Hz)", FREQ_MIN_UI, currentFreqMaxUi,
                () -> freqMaxHz,
                v -> freqMaxHz = v);

        syncAllMappingControlsFromState();
    }

    private interface FloatGetter {
        float get();
    }

    private interface FloatSetter {
        void set(float v);
    }

    private void attachNumberClick(TextView tv,
                                   String title,
                                   float min,
                                   float max,
                                   FloatGetter getter,
                                   FloatSetter setter) {
        if (tv == null) return;
        tv.setOnClickListener(v -> showNumberEntryDialog(title, min, max, getter.get(), newVal -> {
            setter.set(newVal);
            sanitizeMappingValues();
            syncAllMappingControlsFromState();
            recomputeMappedOutputs();
            persistSettings();
            appendLogSafe("Manual entry: " + title);
        }));
    }

    private interface FloatConsumer {
        void accept(float v);
    }

    private void showNumberEntryDialog(String title,
                                       float min,
                                       float max,
                                       float currentValue,
                                       FloatConsumer onOk) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.format(Locale.US, "%.2f", currentValue));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(String.format(Locale.US, "Enter a value between %.1f and %.1f", min, max))
                .setView(input)
                .setPositiveButton("OK", (d, which) -> {
                    try {
                        float v = Float.parseFloat(input.getText().toString().trim());
                        if (v < min) v = min;
                        if (v > max) v = max;
                        onOk.accept(v);
                    } catch (Exception e) {
                        toastSafe("Invalid number");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private float angleToProgress(float angle) {
        return (angle - ANGLE_MIN) / ANGLE_STEP;
    }

    private float progressToAngle(int progress) {
        return ANGLE_MIN + progress * ANGLE_STEP;
    }

    private float freqToProgress(float hz) {
        return hz - FREQ_MIN_UI;
    }

    private float progressToFreq(int progress) {
        return FREQ_MIN_UI + progress;
    }

    private void syncAllMappingControlsFromState() {
        suppressSliderCallbacks = true;

        sanitizeMappingValues();

        sbPitchAngleMin.setProgress((int) angleToProgress(pitchAngleMinDeg));
        sbPitchAngleMax.setProgress((int) angleToProgress(pitchAngleMaxDeg));

        sbVolAngleMin.setProgress((int) angleToProgress(volumeAngleMinDeg));
        sbVolAngleMax.setProgress((int) angleToProgress(volumeAngleMaxDeg));

        sbFreqMin.setProgress((int) freqToProgress(freqMinHz));
        sbFreqMax.setProgress((int) freqToProgress(freqMaxHz));

        syncMappingValueTextsOnly();

        suppressSliderCallbacks = false;
    }

    private void syncMappingValueTextsOnly() {
        if (tvPitchAngleMinVal != null) tvPitchAngleMinVal.setText(String.format(Locale.US, "%.1f°", pitchAngleMinDeg));
        if (tvPitchAngleMaxVal != null) tvPitchAngleMaxVal.setText(String.format(Locale.US, "%.1f°", pitchAngleMaxDeg));

        if (tvVolAngleMinVal != null) tvVolAngleMinVal.setText(String.format(Locale.US, "%.1f°", volumeAngleMinDeg));
        if (tvVolAngleMaxVal != null) tvVolAngleMaxVal.setText(String.format(Locale.US, "%.1f°", volumeAngleMaxDeg));

        if (tvFreqMinVal != null) tvFreqMinVal.setText(String.format(Locale.US, "%.1f Hz", freqMinHz));
        if (tvFreqMaxVal != null) tvFreqMaxVal.setText(String.format(Locale.US, "%.1f Hz", freqMaxHz));
    }

    private void recomputeMappedOutputs() {
        sanitizeMappingValues();

        float freq = mapLinearClamped(
                pitchActiveDeltaDeg,
                pitchAngleMinDeg,
                pitchAngleMaxDeg,
                freqMinHz,
                freqMaxHz
        );

        float vol = mapLinearClamped(
                volActiveDeltaDeg,
                volumeAngleMinDeg,
                volumeAngleMaxDeg,
                0f,
                1f
        );

        if (!pitchHasAngle) freq = freqMinHz;
        if (!volHasAngle) vol = 0f;

        mappedFreqHz = freq;
        mappedVolumeLinear = vol;

        audioTargetFreqHz = mappedFreqHz;
        audioTargetVolumeLinear = mappedVolumeLinear;

        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        boolean btEnabled = bleSnapshot != null && bleSnapshot.hostReady && bleSnapshot.bluetoothEnabled;
        boolean bothConnected = calSnapshot != null && calSnapshot.hostReady
                && calSnapshot.pitchConnected && calSnapshot.volumeConnected;

        // Product behavior: only produce audible sound when BOTH gloves are connected.
        if (!btEnabled || !bothConnected) {
            audioTargetVolumeLinear = 0f;
            mappedVolumeLinear = 0f;
        }
    }

    private float mapLinearClamped(float x, float inMin, float inMax, float outMin, float outMax) {
        if (Math.abs(inMax - inMin) < 1e-6f) return outMin;
        float t = (x - inMin) / (inMax - inMin);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return outMin + t * (outMax - outMin);
    }

    private void refreshUiFast() {
        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        boolean hostReady = bleSnapshot != null && bleSnapshot.hostReady;
        boolean btEnabled = hostReady && bleSnapshot.bluetoothEnabled;

        boolean pitchConnected = calSnapshot != null && calSnapshot.hostReady && calSnapshot.pitchConnected;
        boolean volConnected = calSnapshot != null && calSnapshot.hostReady && calSnapshot.volumeConnected;

        boolean bothConnected = pitchConnected && volConnected;
        boolean oneConnected = pitchConnected || volConnected;
        boolean connecting = hostReady && (bleSnapshot.scanning
                || BleUiText.isConnecting(bleSnapshot.pitchConnText)
                || BleUiText.isConnecting(bleSnapshot.volumeConnText));

        if (hostReady && calSnapshot != null && calSnapshot.hostReady) {
            pitchActiveDeltaDeg = calSnapshot.pitchActiveDeltaDeg;
            volActiveDeltaDeg = calSnapshot.volumeActiveDeltaDeg;
            pitchHasAngle = pitchConnected;
            volHasAngle = volConnected;
        } else {
            pitchHasAngle = false;
            volHasAngle = false;
        }

        recomputeMappedOutputs();

        if (tvStatus != null) tvStatus.setText(buildPlayHeadline(bleSnapshot, bothConnected, oneConnected, connecting));
        if (tvAudio != null) tvAudio.setText(buildPlaySubtitle(bleSnapshot, bothConnected, oneConnected, connecting));

        if (tvPitchConn != null) {
            tvPitchConn.setText(buildFriendlyConnectionChip("Pitch glove", bleSnapshot, calSnapshot, true));
        }
        if (tvVolConn != null) {
            tvVolConn.setText(buildFriendlyConnectionChip("Volume glove", bleSnapshot, calSnapshot, false));
        }

        if (tvPitchValue != null) tvPitchValue.setText(buildFrequencyCardText(mappedFreqHz));
        if (tvVolValue != null) tvVolValue.setText(buildVolumeCardText(mappedVolumeLinear));

        if (tvToneValue != null) tvToneValue.setText(buildToneSummary(bothConnected, mappedFreqHz, mappedVolumeLinear));

        if (tvPitchLast != null && tvVolLast != null) {
            if (bothConnected || !hostReady) {
                tvPitchLast.setVisibility(View.GONE);
                tvVolLast.setVisibility(View.GONE);
            } else {
                tvPitchLast.setVisibility(View.VISIBLE);
                tvVolLast.setVisibility(View.VISIBLE);

                if (hostReady) {
                    tvPitchLast.setText(bleSnapshot.pitchLastText);
                    tvVolLast.setText(bleSnapshot.volumeLastText);
                } else {
                    tvPitchLast.setText("Pitch last: (none)");
                    tvVolLast.setText("Volume last: (none)");
                }
            }
        }

        updateCalibrationButtonGlow(bothConnected);

        updateAudioStatusText();
        updateBleButtonText();

        if (thereminVisualizerView != null) {
            thereminVisualizerView.setThereminState(mappedFreqHz, mappedVolumeLinear, freqMinHz, freqMaxHz);
        }

        syncMappingValueTextsOnly();

        // If Bluetooth is off, keep UI consistent (no sound).
        if (!btEnabled) {
            audioTargetVolumeLinear = 0f;
            mappedVolumeLinear = 0f;
        }
    }

    private String buildPlayHeadline(BleHostBridge.BleUiSnapshot bleSnapshot,
                                     boolean bothConnected,
                                     boolean oneConnected,
                                     boolean connecting) {
        if (bleSnapshot == null || !bleSnapshot.hostReady) {
            return "Preparing instrument";
        }
        if (!bleSnapshot.bluetoothEnabled) {
            return "Bluetooth is off";
        }
        if (bothConnected) {
            return "Ready to perform";
        }
        if (oneConnected) {
            return "Almost ready";
        }
        if (connecting) {
            return "Connecting your gloves";
        }
        return "Waiting for gloves";
    }

    private String buildPlaySubtitle(BleHostBridge.BleUiSnapshot bleSnapshot,
                                     boolean bothConnected,
                                     boolean oneConnected,
                                     boolean connecting) {
        if (bleSnapshot == null || !bleSnapshot.hostReady) {
            return "Setting up the live instrument experience.";
        }
        if (!bleSnapshot.bluetoothEnabled) {
            return "Turn Bluetooth on to reconnect your gloves.";
        }
        if (bothConnected) {
            return "Move your hands to shape pitch and volume. Calibrate anytime for a tighter response.";
        }
        if (oneConnected) {
            return "One glove is connected. Turn on the second glove to complete the instrument.";
        }
        if (connecting) {
            return "Keep both gloves awake and close to your phone.";
        }
        return "Turn on both gloves to begin playing.";
    }

    private String buildFriendlyConnectionChip(String label,
                                               BleHostBridge.BleUiSnapshot bleSnapshot,
                                               BleHostBridge.CalibrationUiSnapshot calSnapshot,
                                               boolean isPitch) {
        boolean hostReady = bleSnapshot != null && bleSnapshot.hostReady;
        boolean bluetoothEnabled = hostReady && bleSnapshot.bluetoothEnabled;
        boolean connected = calSnapshot != null && calSnapshot.hostReady
                && (isPitch ? calSnapshot.pitchConnected : calSnapshot.volumeConnected);
        String rawLine = hostReady
                ? (isPitch ? bleSnapshot.pitchConnText : bleSnapshot.volumeConnText)
                : null;
        boolean connecting = bluetoothEnabled
                && !connected
                && (BleUiText.isConnecting(rawLine) || bleSnapshot.scanning);

        if (!hostReady) {
            return label + "\nWaiting";
        }
        if (!bluetoothEnabled) {
            return label + "\nBluetooth off";
        }
        if (connected) {
            return label + "\nConnected";
        }
        if (connecting) {
            return label + "\nConnecting…";
        }
        return label + "\nWaiting";
    }

    private String buildFrequencyCardText(float freqHz) {
        float safeFreq = Math.max(FREQ_MIN_UI, freqHz);
        if (safeFreq >= 1000f) {
            return String.format(Locale.US, "Frequency • %.2f kHz", safeFreq / 1000f);
        }
        return String.format(Locale.US, "Frequency • %.0f Hz", safeFreq);
    }

    private String buildVolumeCardText(float volumeLinear) {
        int percent = Math.round(clamp(volumeLinear, 0f, 1f) * 100f);
        return String.format(Locale.US, "Volume level • %d%%", percent);
    }

    private String buildToneSummary(boolean bothConnected, float freqHz, float volumeLinear) {
        if (!bothConnected) {
            return "Connect both gloves to start shaping sound.";
        }
        if (volumeLinear <= 0.01f) {
            return String.format(Locale.US,
                    "Live tone ready • %.1f Hz • Raise your volume hand to bring it in",
                    freqHz);
        }
        return String.format(Locale.US,
                "Live tone • %.1f Hz • %.0f%% intensity",
                freqHz, volumeLinear * 100f);
    }

    private void updateCalibrationButtonGlow(boolean shouldGlow) {
        if (btnOpenCalibration == null) return;

        if (shouldGlow) {
            btnOpenCalibration.setText("✨ Calibrate Precisely");
            if (calibrateGlowAnimation == null) {
                AlphaAnimation pulse = new AlphaAnimation(1.0f, 0.55f);
                pulse.setDuration(850);
                pulse.setRepeatMode(Animation.REVERSE);
                pulse.setRepeatCount(Animation.INFINITE);
                calibrateGlowAnimation = pulse;
            }
            if (btnOpenCalibration.getAnimation() == null) {
                btnOpenCalibration.startAnimation(calibrateGlowAnimation);
            }
        } else {
            btnOpenCalibration.setText("Calibrate");
            btnOpenCalibration.clearAnimation();
        }
    }

    private void updateAudioStatusText() {
        boolean running = audioEngine != null && audioEngine.isRunning();

        if (btnAudioStart != null) {
            btnAudioStart.setText("");
            btnAudioStart.setIconResource(running
                    ? R.drawable.ic_pause_theremin
                    : R.drawable.ic_play_theremin);
            btnAudioStart.setContentDescription(running ? "Pause theremin" : "Play theremin");
        }

        if (tvPlayRemoteLabel != null) {
            tvPlayRemoteLabel.setText(running ? "Pause" : "Play");
        }

        if (btnAudioStop != null) {
            btnAudioStop.setText("");
            btnAudioStop.setIconResource(bgAudioEnabled
                    ? R.drawable.ic_background_on
                    : android.R.drawable.ic_menu_close_clear_cancel);
            btnAudioStop.setContentDescription(bgAudioEnabled
                    ? "Turn background audio off"
                    : "Turn background audio on");
        }

        if (tvBackgroundLabel != null) {
            tvBackgroundLabel.setText(bgAudioEnabled ? "Background On" : "Background Off");
        }
    }

    private void appendLogSafe(String msg) {
        final long now = SystemClock.elapsedRealtime();
        synchronized (logLines) {
            logLines.addLast(msg);
            while (logLines.size() > LOG_MAX_LINES) logLines.removeFirst();
            if (now - lastLogFlushMs < LOG_FLUSH_MIN_INTERVAL_MS) return;
            lastLogFlushMs = now;

            StringBuilder sb = new StringBuilder();
            for (String s : logLines) sb.append(s).append('\n');
            final String all = sb.toString();

            runOnUiThread(() -> {
                if (tvLog != null) tvLog.setText(all);
            });
        }
    }


    // ===== Audio engine =====
    private final class AudioEngine {
        private static final int SAMPLE_RATE = 48000;
        private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO;
        private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

        private AudioTrack track;
        private Thread audioThread;
        private volatile boolean running = false;

        private float phase = 0f;

        boolean isRunning() {
            return running;
        }

        void start() {
            if (running) return;

            int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
            int bufferSize = Math.max(minBuffer, 2048);

            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_MASK,
                    ENCODING,
                    bufferSize,
                    AudioTrack.MODE_STREAM
            );

            running = true;
            track.play();

            audioThread = new Thread(() -> {
                short[] buffer = new short[1024];

                while (running) {
                    float freq = audioTargetFreqHz;
                    float vol = audioTargetVolumeLinear;

                    for (int i = 0; i < buffer.length; i++) {
                        phase += (2f * (float) Math.PI * freq) / SAMPLE_RATE;
                        if (phase > 2f * Math.PI) phase -= 2f * (float) Math.PI;

                        float sample = (float) Math.sin(phase);
                        short pcm = (short) (sample * vol * Short.MAX_VALUE * 0.25f);
                        buffer[i] = pcm;
                    }

                    if (track != null) {
                        try {
                            track.write(buffer, 0, buffer.length);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, "ThereminAudioThread");

            audioThread.start();
        }

        void stop() {
            running = false;

            if (audioThread != null) {
                try {
                    audioThread.join(300);
                } catch (InterruptedException ignored) {
                }
                audioThread = null;
            }

            if (track != null) {
                try { track.pause(); } catch (Exception ignored) {}
                try { track.flush(); } catch (Exception ignored) {}
                try { track.release(); } catch (Exception ignored) {}
                track = null;
            }
        }

        void shutdown() {
            stop();
        }
    }
}
