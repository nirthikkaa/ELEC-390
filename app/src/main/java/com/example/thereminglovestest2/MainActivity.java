package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;
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

import com.example.thereminglovestest2.databinding.ActivityMainBinding;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Play screen (instrument).
 * BLE is owned by BleSessionManager.
 * This activity only renders snapshots + plays audio.
 */
public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    public static final String EXTRA_AUTOSTART_AUDIO = "com.example.thereminglovestest2.extra.AUTOSTART_AUDIO";

    // ===== Preferences =====
    // ===== UI tick =====
    private static final long UI_TICK_MS = 80;

    // ===== Log throttling =====
    private static final int  LOG_MAX_LINES           = 200;
    private static final long LOG_FLUSH_MIN_INTERVAL_MS = 600;

    // ===== Slider ranges =====
    private static final float ANGLE_MIN = -90f;
    private static final float ANGLE_MAX =  90f;
    private static final float ANGLE_STEP = 0.5f;
    private static final int   ANGLE_PROGRESS_MAX = (int) ((ANGLE_MAX - ANGLE_MIN) / ANGLE_STEP);

    private static final float FREQ_MIN_UI           = 20f;
    private static final float FREQ_STANDARD_MAX_UI  = 2000f;
    private static final float FREQ_EXTENDED_MAX_UI  = 20000f;

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
    private volatile boolean bgAudioEnabled    = true;
    private volatile boolean playUiVisible     = false;
    private volatile float currentFreqMaxUi    = FREQ_STANDARD_MAX_UI;

    // ===== Mapping settings =====
    private volatile float pitchAngleMinDeg  = Defaults.PLAY_PITCH_ANGLE_MIN_DEG;
    private volatile float pitchAngleMaxDeg  = Defaults.PLAY_PITCH_ANGLE_MAX_DEG;
    private volatile float freqMinHz         = Defaults.PLAY_FREQ_MIN_HZ;
    private volatile float freqMaxHz         = Defaults.PLAY_FREQ_MAX_HZ;
    private volatile float volumeAngleMinDeg = Defaults.PLAY_VOLUME_ANGLE_MIN_DEG;
    private volatile float volumeAngleMaxDeg = Defaults.PLAY_VOLUME_ANGLE_MAX_DEG;

    // ===== Latest glove values =====
    private volatile float pitchActiveDeltaDeg = 0.0f;
    private volatile float volActiveDeltaDeg   = 0.0f;
    private volatile boolean pitchHasAngle     = false;
    private volatile boolean volHasAngle       = false;

    private volatile float mappedFreqHz          = 880.0f;
    private volatile float mappedVolumeLinear    = 0.0f;
    private volatile float audioTargetFreqHz     = 880.0f;
    private volatile float audioTargetVolumeLinear = 0.0f;

    private volatile boolean suppressSliderCallbacks = false;

    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private volatile long lastLogFlushMs = 0;

    private final UiPoller uiTicker = new UiPoller(UI_TICK_MS, this::refreshUiFast);

    private ThereminAudioEngine audioEngine;
    private SettingsStore settingsRepo;
    private String currentToneType = AppSettings.TONE_SINE;
    private boolean pendingAutoStartAudio = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        BleSessionManager.initialize(getApplicationContext());

        bindViews();
        audioEngine = new ThereminAudioEngine();
        consumeIntent(getIntent());

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
        playUiVisible = true;

        loadBgAudioPref();
        refreshFrequencyRangeLimit();
        reloadMappingSettingsFromRepository();
        syncAudioTargetsFromSharedBleState();

        maybeTakeAudioBackFromBackgroundService();
        uiTicker.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        playUiVisible = true;

        maybeTakeAudioBackFromBackgroundService();

        loadBgAudioPref();
        refreshFrequencyRangeLimit();
        updateAudioStatusText();
        reloadMappingSettingsFromRepository();
        syncAudioTargetsFromSharedBleState();
        syncAllMappingControlsFromState();
        updateBleButtonText();
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent);
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
        maybeMoveAudioToBackgroundService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        playUiVisible = false;
        uiTicker.stop();

        if (isChangingConfigurations()) {
            return;
        }

        if (!bgAudioEnabled && audioEngine != null && audioEngine.isRunning()) {
            audioEngine.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiTicker.stop();

        if (audioEngine != null && (!bgAudioEnabled || isFinishing())) {
            audioEngine.shutdown();
        }
    }

    // ===== Preferences =====

    private void loadBgAudioPref() {
        bgAudioEnabled = AppPrefs.isBgAudioEnabled(this);
    }

    private void saveBgAudioPref() {
        AppPrefs.setBgAudioEnabled(this, bgAudioEnabled);
    }

    private boolean isExtendedFrequencyRangeEnabled() {
        return AppPrefs.isExtendedFreqRangeEnabled(this);
    }

    private void refreshFrequencyRangeLimit() {
        currentFreqMaxUi = isExtendedFrequencyRangeEnabled()
                ? FREQ_EXTENDED_MAX_UI : FREQ_STANDARD_MAX_UI;
    }

    private int getFreqProgressMax() {
        return Math.round(currentFreqMaxUi - FREQ_MIN_UI);
    }

    private void applyBackgroundAudioPreferenceNow() {
        boolean wasEnabled = bgAudioEnabled;
        loadBgAudioPref();
        updateAudioStatusText();

        if (!bgAudioEnabled && wasEnabled && !playUiVisible
                && audioEngine != null && audioEngine.isRunning()) {
            audioEngine.stop();
            appendLogSafe("BG audio pref changed -> audio stopped");
        }

        if (!bgAudioEnabled) {
            ThereminBackgroundAudioService.stopIfRunning(this);
        }
    }
    private void maybeMoveAudioToBackgroundService() {
        if (audioEngine == null || !audioEngine.isRunning()) return;
        if (!bgAudioEnabled || isChangingConfigurations()) return;

        try {
            pushAudioTargetsToEngine();
            ThereminBackgroundAudioService.startIfNeeded(this);
            audioEngine.stop();
            appendLogSafe("Play hidden -> audio handed to background service");
        } catch (Exception e) {
            // If the service fails to start, keep the local engine alive.
            appendLogSafe("Background service failed: " + e.getClass().getSimpleName());
        }
    }

    private void maybeTakeAudioBackFromBackgroundService() {
        if (!ThereminBackgroundAudioService.isServiceActive()) return;

        ThereminBackgroundAudioService.stopIfRunning(this);
        if (audioEngine != null && !audioEngine.isRunning()) {
            pushAudioTargetsToEngine();
            audioEngine.start();
            appendLogSafe("Play visible -> audio returned from background service");
        }
    }

    private void toastSafe(String msg) {

        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ===== View binding =====

    private void bindViews() {
        binding.topNavBar.setTitleText("Play");

        tvStatus    = binding.tvStatus;
        tvPitchConn = binding.tvPitchConn;
        tvVolConn   = binding.tvVolConn;
        tvAudio     = binding.tvAudio;

        tvPitchValue = binding.tvPitchValue;
        tvVolValue   = binding.tvVolValue;
        tvToneValue  = binding.tvToneValue;

        tvPlayRemoteLabel  = binding.tvPlayRemoteLabel;
        tvReconnectLabel   = binding.tvReconnectLabel;
        tvBackgroundLabel  = binding.tvBackgroundLabel;

        // These optional text slots are not part of the compact Play layout anymore.
        tvPitchLast = null;
        tvVolLast   = null;
        tvLog       = binding.tvLog;

        tvPitchAngleMinVal = binding.tvPitchAngleMinVal;
        tvPitchAngleMaxVal = binding.tvPitchAngleMaxVal;
        tvFreqMinVal       = binding.tvFreqMinVal;
        tvFreqMaxVal       = binding.tvFreqMaxVal;
        tvVolAngleMinVal   = binding.tvVolAngleMinVal;
        tvVolAngleMaxVal   = binding.tvVolAngleMaxVal;

        sbPitchAngleMin = binding.sbPitchAngleMin;
        sbPitchAngleMax = binding.sbPitchAngleMax;
        sbFreqMin       = binding.sbFreqMin;
        sbFreqMax       = binding.sbFreqMax;
        sbVolAngleMin   = binding.sbVolAngleMin;
        sbVolAngleMax   = binding.sbVolAngleMax;

        btnScanConnect    = binding.btnScanConnect;
        btnDisconnectAll  = binding.btnDisconnectAll;
        btnAudioStart     = binding.btnAudioStart;
        btnAudioStop      = binding.btnAudioStop;
        btnOpenCalibration = null;

        btnNeutralPitch   = binding.btnNeutralPitch;
        btnNeutralVol     = binding.btnNeutralVol;
        btnDirectionPitch = binding.btnDirectionPitch;
        btnDirectionVol   = binding.btnDirectionVol;
        btnHelpPitch      = binding.btnHelpPitch;
        btnHelpVol        = binding.btnHelpVol;

        btnDefaults = binding.btnDefaults;

        thereminVisualizerView = binding.thereminVisualizerView;
    }

    // ===== Button wiring =====

    private void wireButtons() {
        if (btnDisconnectAll != null) btnDisconnectAll.setVisibility(View.GONE);

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
            BleSessionManager.BleUiSnapshot snapshot = BleSessionManager.getBleUiSnapshot();
            if (snapshot != null && snapshot.hostReady && !snapshot.bluetoothEnabled) {
                toastSafe("Bluetooth is OFF");
                return;
            }
            if (audioEngine.isRunning()) audioEngine.stop();
            else                         audioEngine.start();
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

            if (!bgAudioEnabled) {
            ThereminBackgroundAudioService.stopIfRunning(this);
        }
            updateAudioStatusText();
        });

        if (btnOpenCalibration != null) {
            btnOpenCalibration.setText("✨ Calibrate Precisely");
            btnOpenCalibration.setOnClickListener(v -> openCalibrationScreen());
        }

        btnNeutralPitch.setOnClickListener(v -> BleSessionManager.requestCaptureNeutral(true));
        btnNeutralVol.setOnClickListener(v   -> BleSessionManager.requestCaptureNeutral(false));

        btnDirectionPitch.setOnClickListener(v -> {
            appendLogSafe("Pitch: toggle direction");
            BleSessionManager.requestToggleDirection(true);
        });
        btnDirectionVol.setOnClickListener(v -> {
            appendLogSafe("Volume: toggle direction");
            BleSessionManager.requestToggleDirection(false);
        });

        btnHelpPitch.setOnClickListener(v -> showHelpDialog("Pitch glove"));
        btnHelpVol.setOnClickListener(v   -> showHelpDialog("Volume glove"));

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
        BleSnapshot snapshot = getSnapshot();

        if (!BleUiText.isBluetoothOn(snapshot)) {
            toastSafe("Bluetooth is OFF");
            return;
        }
        if (BleUiText.areBothGlovesConnected(snapshot)) {
            toastSafe("Both gloves are already connected");
            return;
        }

        toastSafe("Connecting missing glove(s)...");
        BleSessionManager.requestConnectMissingGloves();
        refreshUiFast();
        updateBleButtonText();
    }

    private void updateBleButtonText() {
        if (btnScanConnect == null) return;

        BleSnapshot snapshot = getSnapshot();
        String label = "Connect";
        String contentDescription = "Connect gloves";

        if (snapshot != null && snapshot.hostReady) {
            if (!snapshot.bluetoothEnabled) {
                label = "Bluetooth Off";
                contentDescription = "Bluetooth is off";
            } else if (BleUiText.areBothGlovesConnected(snapshot)) {
                label = "Connected";
                contentDescription = "Both gloves are connected";
            } else if (BleUiText.isAnyGloveConnecting(snapshot) || BleUiText.isAnyGloveConnected(snapshot)) {
                label = "Reconnect";
                contentDescription = "Reconnect missing gloves";
            }
        }

        btnScanConnect.setText("");
        btnScanConnect.setContentDescription(contentDescription);
        if (tvReconnectLabel != null) tvReconnectLabel.setText(label);
    }

    private void openCalibrationScreen() {
        Intent intent = new Intent(this, CalibrationActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private void showHelpDialog(String title) {
        BleSnapshot snapshot = getSnapshot();
        StringBuilder sb = new StringBuilder();

        if (snapshot == null || !snapshot.hostReady) {
            sb.append("BLE host not ready.\n\n");
        } else {
            sb.append("Bluetooth: ").append(snapshot.bluetoothEnabled ? "ON" : "OFF").append("\n");
            sb.append("Scanning: ").append(snapshot.scanning ? "YES" : "NO").append("\n");
            sb.append("Status: ").append(snapshot.statusText).append("\n\n");
            sb.append("Pitch: ").append(snapshot.pitchConnText).append("\n");
            sb.append("Volume: ").append(snapshot.volumeConnText).append("\n\n");
            sb.append("Pitch connected: ").append(snapshot.pitchConnected).append("\n");
            sb.append("Volume connected: ").append(snapshot.volumeConnected).append("\n");
            sb.append(String.format(Locale.US, "Pitch Δ: %.2f°\n", snapshot.pitchActiveDeltaDeg));
            sb.append(String.format(Locale.US, "Volume Δ: %.2f°\n", snapshot.volumeActiveDeltaDeg));
            sb.append("Pitch direction: ").append(snapshot.pitchDirectionText).append("\n");
            sb.append("Volume direction: ").append(snapshot.volumeDirectionText).append("\n\n");
        }

        sb.append("Background audio: ").append(bgAudioEnabled ? "ON" : "OFF").append("\n");

        new AlertDialog.Builder(this)
                .setTitle(title).setMessage(sb.toString())
                .setPositiveButton("OK", null).show();
    }

    // ===== Settings persistence =====

    private void reloadMappingSettingsFromRepository() {
        if (settingsRepo == null) settingsRepo = new SettingsStore(this);
        AppSettings s = settingsRepo.load();

        pitchAngleMinDeg  = s.pitchAngleMinDeg;
        pitchAngleMaxDeg  = s.pitchAngleMaxDeg;
        freqMinHz         = s.freqMinHz;
        freqMaxHz         = s.freqMaxHz;
        volumeAngleMinDeg = s.volumeAngleMinDeg;
        volumeAngleMaxDeg = s.volumeAngleMaxDeg;
        currentToneType   = AppSettings.normalizeToneType(s.toneType);
        sanitizeMappingValues();

        if (audioEngine != null) {
            audioEngine.setToneType(currentToneType);
        }
    }

    private void persistSettings() {
        if (settingsRepo == null) settingsRepo = new SettingsStore(this);
        sanitizeMappingValues();

        AppSettings s = settingsRepo.load();

        s.pitchAngleMinDeg  = pitchAngleMinDeg;
        s.pitchAngleMaxDeg  = pitchAngleMaxDeg;
        s.freqMinHz         = freqMinHz;
        s.freqMaxHz         = freqMaxHz;
        s.volumeAngleMinDeg = volumeAngleMinDeg;
        s.volumeAngleMaxDeg = volumeAngleMaxDeg;
        // Direction flags are owned by CalibrationActivity + BleSessionManager — not touched here
        settingsRepo.save(s);
    }

    private void setDefaultMappingValues() {
        pitchAngleMinDeg  = Defaults.PLAY_PITCH_ANGLE_MIN_DEG;
        pitchAngleMaxDeg  = Defaults.PLAY_PITCH_ANGLE_MAX_DEG;
        freqMinHz         = Defaults.PLAY_FREQ_MIN_HZ;
        freqMaxHz         = Defaults.PLAY_FREQ_MAX_HZ;
        volumeAngleMinDeg = Defaults.PLAY_VOLUME_ANGLE_MIN_DEG;
        volumeAngleMaxDeg = Defaults.PLAY_VOLUME_ANGLE_MAX_DEG;
        sanitizeMappingValues();
    }

    private void sanitizeMappingValues() {
        pitchAngleMinDeg  = clamp(pitchAngleMinDeg,  ANGLE_MIN, ANGLE_MAX);
        pitchAngleMaxDeg  = clamp(pitchAngleMaxDeg,  ANGLE_MIN, ANGLE_MAX);
        volumeAngleMinDeg = clamp(volumeAngleMinDeg, ANGLE_MIN, ANGLE_MAX);
        volumeAngleMaxDeg = clamp(volumeAngleMaxDeg, ANGLE_MIN, ANGLE_MAX);
        freqMinHz = clamp(freqMinHz, FREQ_MIN_UI, currentFreqMaxUi);
        freqMaxHz = clamp(freqMaxHz, FREQ_MIN_UI, currentFreqMaxUi);

        if (pitchAngleMaxDeg  < pitchAngleMinDeg  + ANGLE_STEP) pitchAngleMaxDeg  = Math.min(ANGLE_MAX, pitchAngleMinDeg  + ANGLE_STEP);
        if (volumeAngleMaxDeg < volumeAngleMinDeg + ANGLE_STEP) volumeAngleMaxDeg = Math.min(ANGLE_MAX, volumeAngleMinDeg + ANGLE_STEP);
        if (freqMaxHz < freqMinHz + 1f) freqMaxHz = Math.min(currentFreqMaxUi, freqMinHz + 1f);
    }

    private float clamp(float x, float lo, float hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    // ===== Sliders =====

    private void setupSlidersAndClickNumbers() {
        reloadMappingSettingsFromRepository();

        sbPitchAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbPitchAngleMax.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMax.setMax(ANGLE_PROGRESS_MAX);
        sbFreqMin.setMax(getFreqProgressMax());
        sbFreqMax.setMax(getFreqProgressMax());

        SeekBar.OnSeekBarChangeListener angleListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (suppressSliderCallbacks) return;
                float value = progressToAngle(progress);
                if (seekBar == sbPitchAngleMin) pitchAngleMinDeg  = value;
                if (seekBar == sbPitchAngleMax) pitchAngleMaxDeg  = value;
                if (seekBar == sbVolAngleMin)   volumeAngleMinDeg = value;
                if (seekBar == sbVolAngleMax)   volumeAngleMaxDeg = value;
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

        sbPitchAngleMin.setOnSeekBarChangeListener(angleListener);
        sbPitchAngleMax.setOnSeekBarChangeListener(angleListener);
        sbVolAngleMin.setOnSeekBarChangeListener(angleListener);
        sbVolAngleMax.setOnSeekBarChangeListener(angleListener);
        sbFreqMin.setOnSeekBarChangeListener(freqListener);
        sbFreqMax.setOnSeekBarChangeListener(freqListener);

        attachNumberClick(tvPitchAngleMinVal, "Pitch angle min (deg)", ANGLE_MIN, ANGLE_MAX, () -> pitchAngleMinDeg, v -> pitchAngleMinDeg = v);
        attachNumberClick(tvPitchAngleMaxVal, "Pitch angle max (deg)", ANGLE_MIN, ANGLE_MAX, () -> pitchAngleMaxDeg, v -> pitchAngleMaxDeg = v);
        attachNumberClick(tvVolAngleMinVal,   "Volume angle min (deg)", ANGLE_MIN, ANGLE_MAX, () -> volumeAngleMinDeg, v -> volumeAngleMinDeg = v);
        attachNumberClick(tvVolAngleMaxVal,   "Volume angle max (deg)", ANGLE_MIN, ANGLE_MAX, () -> volumeAngleMaxDeg, v -> volumeAngleMaxDeg = v);
        attachNumberClick(tvFreqMinVal, "Frequency min (Hz)", FREQ_MIN_UI, currentFreqMaxUi, () -> freqMinHz, v -> freqMinHz = v);
        attachNumberClick(tvFreqMaxVal, "Frequency max (Hz)", FREQ_MIN_UI, currentFreqMaxUi, () -> freqMaxHz, v -> freqMaxHz = v);

        syncAllMappingControlsFromState();
    }

    private interface FloatGetter { float get(); }
    private interface FloatSetter { void set(float v); }
    private interface FloatConsumer { void accept(float v); }

    private void attachNumberClick(TextView tv, String title, float min, float max,
                                   FloatGetter getter, FloatSetter setter) {
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

    private void showNumberEntryDialog(String title, float min, float max,
                                       float currentValue, FloatConsumer onOk) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.format(Locale.US, "%.2f", currentValue));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(String.format(Locale.US, "Enter a value between %.1f and %.1f", min, max))
                .setView(input)
                .setPositiveButton("OK", (d, which) -> {
                    try {
                        float v = Float.parseFloat(input.getText().toString().trim());
                        v = clamp(v, min, max);
                        onOk.accept(v);
                    } catch (Exception e) {
                        toastSafe("Invalid number");
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private float angleToProgress(float angle) { return (angle - ANGLE_MIN) / ANGLE_STEP; }
    private float progressToAngle(int progress) { return ANGLE_MIN + progress * ANGLE_STEP; }
    private float freqToProgress(float hz)      { return hz - FREQ_MIN_UI; }
    private float progressToFreq(int progress)  { return FREQ_MIN_UI + progress; }

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
        if (tvVolAngleMinVal   != null) tvVolAngleMinVal.setText(String.format(Locale.US, "%.1f°", volumeAngleMinDeg));
        if (tvVolAngleMaxVal   != null) tvVolAngleMaxVal.setText(String.format(Locale.US, "%.1f°", volumeAngleMaxDeg));
        if (tvFreqMinVal != null) tvFreqMinVal.setText(String.format(Locale.US, "%.1f Hz", freqMinHz));
        if (tvFreqMaxVal != null) tvFreqMaxVal.setText(String.format(Locale.US, "%.1f Hz", freqMaxHz));
    }

    // ===== Mapping =====

    private void recomputeMappedOutputs() {
        sanitizeMappingValues();

        float freq = mapLinearClamped(pitchActiveDeltaDeg, pitchAngleMinDeg, pitchAngleMaxDeg, freqMinHz, freqMaxHz);
        float vol  = mapLinearClamped(volActiveDeltaDeg,   volumeAngleMinDeg, volumeAngleMaxDeg, 0f, 1f);

        if (!pitchHasAngle) freq = freqMinHz;
        if (!volHasAngle)   vol  = 0f;

        mappedFreqHz = freq;
        mappedVolumeLinear = vol;
        audioTargetFreqHz = mappedFreqHz;
        audioTargetVolumeLinear = mappedVolumeLinear;

        if (!isInstrumentReady(getSnapshot())) {
            audioTargetVolumeLinear = 0f;
            mappedVolumeLinear = 0f;
        }

        pushAudioTargetsToEngine();
    }

    private float mapLinearClamped(float x, float inMin, float inMax, float outMin, float outMax) {
        if (Math.abs(inMax - inMin) < 1e-6f) return outMin;
        float t = clamp((x - inMin) / (inMax - inMin), 0f, 1f);
        return outMin + t * (outMax - outMin);
    }

    private void pushAudioTargetsToEngine() {
        if (audioEngine == null) return;
        audioEngine.setToneType(currentToneType);
        audioEngine.setTargets(audioTargetFreqHz, audioTargetVolumeLinear);
    }

    private void consumeIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra(EXTRA_AUTOSTART_AUDIO, false)) {
            pendingAutoStartAudio = true;
            intent.removeExtra(EXTRA_AUTOSTART_AUDIO);
        }
    }

    private void syncAudioTargetsFromSharedBleState() {
        applyLiveAnglesFromSnapshot(getSnapshot());

        // Keep the synth targets synced even before the first UI poll tick lands.
        recomputeMappedOutputs();
    }

    // MainActivity used to pull two different BLE snapshot objects all over the place.
    // Keeping one combined snapshot here makes the screen logic easier to follow.
    private BleSnapshot getSnapshot() {
        return BleSessionManager.getSnapshot();
    }

    private void applyLiveAnglesFromSnapshot(BleSnapshot snapshot) {
        if (snapshot != null && snapshot.hostReady) {
            pitchActiveDeltaDeg = snapshot.pitchActiveDeltaDeg;
            volActiveDeltaDeg = snapshot.volumeActiveDeltaDeg;
            pitchHasAngle = snapshot.pitchConnected;
            volHasAngle = snapshot.volumeConnected;
            return;
        }

        pitchHasAngle = false;
        volHasAngle = false;
    }

    private boolean isInstrumentReady(BleSnapshot snapshot) {
        return BleUiText.isBluetoothOn(snapshot) && BleUiText.areBothGlovesConnected(snapshot);
    }

    // The visualizer is just a view of the current synth output.
    // Keeping this in one helper avoids repeating the same null checks every UI tick.
    private void updateVisualizer() {
        if (thereminVisualizerView == null) return;

        ThereminAudioEngine.VisualizerSnapshot waveSnapshot = audioEngine != null
                ? audioEngine.getVisualizerSnapshot()
                : null;

        if (waveSnapshot != null) {
            thereminVisualizerView.setAudioWave(
                    waveSnapshot.samples,
                    waveSnapshot.freqHz,
                    waveSnapshot.volumeLinear,
                    freqMinHz,
                    freqMaxHz
            );
        } else {
            thereminVisualizerView.setAudioWave(null, mappedFreqHz, mappedVolumeLinear, freqMinHz, freqMaxHz);
        }
    }

    private void maybeStartAudioAfterCalibration(boolean bothConnected) {
        if (!pendingAutoStartAudio || !bothConnected || audioEngine == null || audioEngine.isRunning()) {
            return;
        }

        pushAudioTargetsToEngine();
        audioEngine.start();
        pendingAutoStartAudio = false;
        appendLogSafe("Calibration returned to Play -> audio started");
    }

    // ===== UI refresh =====

    private void refreshUiFast() {
        BleSnapshot snapshot = getSnapshot();

        boolean hostReady = snapshot != null && snapshot.hostReady;
        boolean btEnabled = BleUiText.isBluetoothOn(snapshot);
        boolean bothConnected = BleUiText.areBothGlovesConnected(snapshot);
        boolean oneConnected = BleUiText.isAnyGloveConnected(snapshot);
        boolean connecting = BleUiText.isAnyGloveConnecting(snapshot);

        applyLiveAnglesFromSnapshot(snapshot);
        recomputeMappedOutputs();

        if (tvStatus != null) tvStatus.setText(buildPlayHeadline(snapshot, bothConnected, oneConnected, connecting));
        if (tvAudio  != null) tvAudio.setText(buildPlaySubtitle(snapshot, bothConnected, oneConnected, connecting));

        if (tvPitchConn != null) tvPitchConn.setText(buildFriendlyConnectionChip("Pitch glove", snapshot, true));
        if (tvVolConn   != null) tvVolConn.setText(buildFriendlyConnectionChip("Volume glove", snapshot, false));

        if (tvPitchValue != null) tvPitchValue.setText(buildFrequencyCardText(mappedFreqHz));
        if (tvVolValue   != null) tvVolValue.setText(buildVolumeCardText(mappedVolumeLinear));
        if (tvToneValue  != null) tvToneValue.setText(buildToneSummary(bothConnected, mappedFreqHz, mappedVolumeLinear));

        if (tvPitchLast != null && tvVolLast != null) {
            if (bothConnected || !hostReady) {
                tvPitchLast.setVisibility(View.GONE);
                tvVolLast.setVisibility(View.GONE);
            } else {
                tvPitchLast.setVisibility(View.VISIBLE);
                tvVolLast.setVisibility(View.VISIBLE);
                tvPitchLast.setText(hostReady ? snapshot.pitchLastText : "Pitch last: (none)");
                tvVolLast.setText(hostReady ? snapshot.volumeLastText : "Volume last: (none)");
            }
        }

        updateCalibrationButtonGlow(bothConnected);
        maybeStartAudioAfterCalibration(bothConnected);
        updateAudioStatusText();
        updateBleButtonText();
        updateVisualizer();
        syncMappingValueTextsOnly();

        if (!btEnabled) {
            audioTargetVolumeLinear = 0f;
            mappedVolumeLinear = 0f;
        }
    }

    private String buildPlayHeadline(BleSnapshot s,
                                     boolean bothConnected, boolean oneConnected, boolean connecting) {
        if (s == null || !s.hostReady)  return "Preparing instrument";
        if (!s.bluetoothEnabled)        return "Bluetooth is off";
        if (bothConnected)              return "Ready to perform";
        if (oneConnected)               return "Almost ready";
        if (connecting)                 return "Connecting your gloves";
        return "Waiting for gloves";
    }

    private String buildPlaySubtitle(BleSnapshot s,
                                     boolean bothConnected, boolean oneConnected, boolean connecting) {
        if (s == null || !s.hostReady)  return "Setting up the live instrument experience.";
        if (!s.bluetoothEnabled)        return "Turn Bluetooth on to reconnect your gloves.";
        if (bothConnected)              return "Move your hands to shape pitch and volume. Calibrate anytime for a tighter response.";
        if (oneConnected)               return "One glove is connected. Turn on the second glove to complete the instrument.";
        if (connecting)                 return "Keep both gloves awake and close to your phone.";
        return "Turn on both gloves to begin playing.";
    }

    private String buildFriendlyConnectionChip(String label, BleSnapshot snapshot, boolean isPitch) {
        return BleUiText.buildPlayChipText(label, snapshot, isPitch);
    }

    private String buildFrequencyCardText(float freqHz) {
        float f = Math.max(FREQ_MIN_UI, freqHz);
        return f >= 1000f
                ? String.format(Locale.US, "Frequency • %.2f kHz", f / 1000f)
                : String.format(Locale.US, "Frequency • %.0f Hz", f);
    }

    private String buildVolumeCardText(float volumeLinear) {
        return String.format(Locale.US, "Volume level • %d%%",
                Math.round(clamp(volumeLinear, 0f, 1f) * 100f));
    }

    private String buildToneSummary(boolean bothConnected, float freqHz, float volumeLinear) {
        if (!bothConnected) return "Connect both gloves to start shaping sound.";
        if (volumeLinear <= 0.01f)
            return String.format(Locale.US, "Live tone ready • %.1f Hz • Raise your volume hand to bring it in", freqHz);
        return String.format(Locale.US, "Live tone • %.1f Hz • %.0f%% intensity", freqHz, volumeLinear * 100f);
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
            btnAudioStart.setIconResource(running ? R.drawable.ic_pause_theremin : R.drawable.ic_play_theremin);
            btnAudioStart.setContentDescription(running ? "Pause theremin" : "Play theremin");
        }
        if (tvPlayRemoteLabel != null) tvPlayRemoteLabel.setText(running ? "Pause" : "Play");

        if (btnAudioStop != null) {
            btnAudioStop.setText("");
            btnAudioStop.setIconResource(bgAudioEnabled
                    ? R.drawable.ic_background_on
                    : android.R.drawable.ic_menu_close_clear_cancel);
            btnAudioStop.setContentDescription(bgAudioEnabled
                    ? "Turn background audio off" : "Turn background audio on");
        }
        if (tvBackgroundLabel != null) tvBackgroundLabel.setText(bgAudioEnabled ? "Background On" : "Background Off");
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
            runOnUiThread(() -> { if (tvLog != null) tvLog.setText(all); });
        }
    }

}
