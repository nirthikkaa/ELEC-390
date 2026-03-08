package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityMainBinding;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * Play screen.
 * BLE lives in BleSessionManager. This screen just renders state and drives audio.
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_AUTOSTART_AUDIO =
            "com.example.thereminglovestest2.extra.AUTOSTART_AUDIO";

    private static final long UI_TICK_MS = 80;
    private static final int LOG_MAX_LINES = 200;
    private static final long LOG_FLUSH_MIN_INTERVAL_MS = 600;

    private static final float ANGLE_MIN = -90f;
    private static final float ANGLE_MAX = 90f;
    private static final float ANGLE_STEP = 0.5f;
    private static final int ANGLE_PROGRESS_MAX = (int) ((ANGLE_MAX - ANGLE_MIN) / ANGLE_STEP);

    private static final float FREQ_MIN_UI = 20f;
    private static final float FREQ_STANDARD_MAX_UI = 2000f;
    private static final float FREQ_EXTENDED_MAX_UI = 20000f;

    private ActivityMainBinding binding;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final NavigationUtils.Poller uiTicker =
            new NavigationUtils.Poller(UI_TICK_MS, this::refreshUiFast);

    private ThereminAudioEngine audioEngine;
    private SettingsStore settingsRepo;

    private volatile boolean bgAudioEnabled = true;
    private volatile boolean playUiVisible = false;
    private volatile boolean suppressSliderCallbacks = false;
    private volatile boolean pendingAutoStartAudio = false;

    private volatile float currentFreqMaxUi = FREQ_STANDARD_MAX_UI;
    private volatile float pitchAngleMinDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MIN_DEG;
    private volatile float pitchAngleMaxDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MAX_DEG;
    private volatile float freqMinHz = AppSettings.DEFAULT_PLAY_FREQ_MIN_HZ;
    private volatile float freqMaxHz = AppSettings.DEFAULT_PLAY_FREQ_MAX_HZ;
    private volatile float volumeAngleMinDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MIN_DEG;
    private volatile float volumeAngleMaxDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MAX_DEG;

    private volatile float pitchActiveDeltaDeg = 0f;
    private volatile float volActiveDeltaDeg = 0f;
    private volatile boolean pitchHasAngle = false;
    private volatile boolean volHasAngle = false;

    private volatile float mappedFreqHz = 880f;
    private volatile float mappedVolumeLinear = 0f;
    private volatile float audioTargetFreqHz = 880f;
    private volatile float audioTargetVolumeLinear = 0f;

    private volatile long lastLogFlushMs = 0;
    private String currentToneType = AppSettings.TONE_SINE;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.topNavBar.setTitleText("Play");

        BleSessionManager.initialize(getApplicationContext());
        audioEngine = new ThereminAudioEngine();

        consumeIntent(getIntent());
        loadBgAudioPref();
        refreshFrequencyRangeLimit();
        setupSlidersAndClickNumbers();
        syncAllMappingControlsFromState();
        recomputeMappedOutputs();
        wireButtons();

        appendLogSafe("Play opened");
        appendLogSafe("BG audio: " + onOff(bgAudioEnabled));
        updateAudioStatusText();
        updateBleButtonText();
    }

    @Override
    protected void onStart() {
        super.onStart();
        playUiVisible = true;
        maybeTakeAudioBackFromBackgroundService();
        refreshPlayUiState();
        uiTicker.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        playUiVisible = true;
        maybeTakeAudioBackFromBackgroundService();
        refreshPlayUiState();
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

        if (!bgAudioEnabled && !isChangingConfigurations() && isAudioRunning()) {
            audioEngine.stop();
            appendLogSafe("onPause -> audio stopped (background off)");
        } else if (bgAudioEnabled && isAudioRunning()) {
            appendLogSafe("onPause -> audio kept running in background");
        }

        maybeMoveAudioToBackgroundService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        playUiVisible = false;
        uiTicker.stop();
        if (!isChangingConfigurations() && !bgAudioEnabled && isAudioRunning()) {
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

    private void refreshPlayUiState() {
        loadBgAudioPref();
        refreshFrequencyRangeLimit();
        reloadMappingSettingsFromRepository();
        syncAudioTargetsFromSharedBleState();
        syncAllMappingControlsFromState();
        updateAudioStatusText();
        updateBleButtonText();
    }

    private SettingsStore store() {
        if (settingsRepo == null) settingsRepo = new SettingsStore(this);
        return settingsRepo;
    }

    private boolean isAudioRunning() {
        return audioEngine != null && audioEngine.isRunning();
    }

    private void loadBgAudioPref() {
        bgAudioEnabled = SettingsStore.isBgAudioEnabled(this);
    }

    private void saveBgAudioPref() {
        SettingsStore.setBgAudioEnabled(this, bgAudioEnabled);
    }

    private void refreshFrequencyRangeLimit() {
        currentFreqMaxUi = SettingsStore.isExtendedFreqRangeEnabled(this)
                ? FREQ_EXTENDED_MAX_UI : FREQ_STANDARD_MAX_UI;
    }

    private int getFreqProgressMax() {
        return Math.round(currentFreqMaxUi - FREQ_MIN_UI);
    }

    private void maybeMoveAudioToBackgroundService() {
        if (!isAudioRunning() || !bgAudioEnabled || isChangingConfigurations()) return;

        try {
            pushAudioTargetsToEngine();
            ThereminBackgroundAudioService.startIfNeeded(this);
            audioEngine.stop();
            appendLogSafe("Play hidden -> audio handed to background service");
        } catch (Exception e) {
            appendLogSafe("Background service failed: " + e.getClass().getSimpleName());
        }
    }

    private void maybeTakeAudioBackFromBackgroundService() {
        if (!ThereminBackgroundAudioService.isServiceActive()) return;
        ThereminBackgroundAudioService.stopIfRunning(this);
        if (!isAudioRunning()) {
            pushAudioTargetsToEngine();
            audioEngine.start();
            appendLogSafe("Play visible -> audio returned from background service");
        }
    }

    private void toastSafe(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void wireButtons() {
        binding.btnDisconnectAll.setVisibility(View.GONE);

        View.OnClickListener reconnectClick = v -> {
            appendLogSafe(v == binding.tvReconnectLabel ? "Reconnect label pressed" : "Reconnect pressed");
            onBleTogglePressed();
        };
        binding.btnScanConnect.setOnClickListener(reconnectClick);
        binding.tvReconnectLabel.setClickable(true);
        binding.tvReconnectLabel.setFocusable(true);
        binding.tvReconnectLabel.setOnClickListener(reconnectClick);

        binding.btnAudioStart.setOnClickListener(v -> {
            BleSnapshot snapshot = getSnapshot();
            if (snapshot != null && !snapshot.isBluetoothOn()) {
                toastSafe("Bluetooth is OFF");
                return;
            }
            if (isAudioRunning()) audioEngine.stop(); else audioEngine.start();
            updateAudioStatusText();
        });

        binding.btnAudioStop.setOnClickListener(v -> toggleBackgroundAudio());
        binding.btnNeutralPitch.setOnClickListener(v -> BleSessionManager.requestCaptureNeutral(true));
        binding.btnNeutralVol.setOnClickListener(v -> BleSessionManager.requestCaptureNeutral(false));
        binding.btnDirectionPitch.setOnClickListener(v -> toggleDirection(true));
        binding.btnDirectionVol.setOnClickListener(v -> toggleDirection(false));
        binding.btnHelpPitch.setOnClickListener(v -> showHelpDialog("Pitch glove"));
        binding.btnHelpVol.setOnClickListener(v -> showHelpDialog("Volume glove"));
        binding.btnDefaults.setOnClickListener(v -> restoreDefaults());
    }

    private void toggleBackgroundAudio() {
        bgAudioEnabled = !bgAudioEnabled;
        saveBgAudioPref();
        appendLogSafe("BG audio toggled -> " + onOff(bgAudioEnabled));
        toastSafe("Background audio: " + onOff(bgAudioEnabled));

        if (!bgAudioEnabled) {
            if (!playUiVisible && isAudioRunning()) {
                audioEngine.stop();
                appendLogSafe("BG audio disabled while app in background -> audio stopped");
            }
            ThereminBackgroundAudioService.stopIfRunning(this);
        }
        updateAudioStatusText();
    }

    private void toggleDirection(boolean pitch) {
        appendLogSafe((pitch ? "Pitch" : "Volume") + ": toggle direction");
        BleSessionManager.requestToggleDirection(pitch);
    }

    private void restoreDefaults() {
        setDefaultMappingValues();
        syncAllMappingControlsFromState();
        recomputeMappedOutputs();
        persistSettings();
        appendLogSafe("Defaults restored");
    }

    private void onBleTogglePressed() {
        BleSnapshot snapshot = getSnapshot();
        if (snapshot == null || !snapshot.isBluetoothOn()) {
            toastSafe("Bluetooth is OFF");
            return;
        }
        if (snapshot.areBothGlovesConnected()) {
            toastSafe("Both gloves are already connected");
            return;
        }

        toastSafe("Connecting missing glove(s)...");
        BleSessionManager.requestConnectMissingGloves();
        refreshUiFast();
        updateBleButtonText();
    }

    private void updateBleButtonText() {
        BleSnapshot snapshot = getSnapshot();
        String label = "Connect";
        String description = "Connect gloves";

        if (snapshot != null && snapshot.hostReady) {
            if (!snapshot.bluetoothEnabled) {
                label = "Bluetooth Off";
                description = "Bluetooth is off";
            } else if (snapshot.areBothGlovesConnected()) {
                label = "Connected";
                description = "Both gloves are connected";
            } else if (snapshot.isAnyGloveConnecting() || snapshot.isAnyGloveConnected()) {
                label = "Reconnect";
                description = "Reconnect missing gloves";
            }
        }

        binding.btnScanConnect.setText("");
        binding.btnScanConnect.setContentDescription(description);
        binding.tvReconnectLabel.setText(label);
    }

    private void showHelpDialog(String title) {
        BleSnapshot snapshot = getSnapshot();
        StringBuilder sb = new StringBuilder();

        if (snapshot == null || !snapshot.hostReady) {
            sb.append("BLE host not ready.\n\n");
        } else {
            sb.append("Bluetooth: ").append(onOff(snapshot.bluetoothEnabled)).append("\n")
                    .append("Scanning: ").append(yesNo(snapshot.scanning)).append("\n")
                    .append("Status: ").append(snapshot.statusText).append("\n\n")
                    .append("Pitch: ").append(snapshot.connectionDetail(true)).append("\n")
                    .append("Volume: ").append(snapshot.connectionDetail(false)).append("\n\n")
                    .append("Pitch connected: ").append(snapshot.pitchConnected).append("\n")
                    .append("Volume connected: ").append(snapshot.volumeConnected).append("\n")
                    .append(String.format(Locale.US, "Pitch Δ: %.2f°\n", snapshot.pitchActiveDeltaDeg))
                    .append(String.format(Locale.US, "Volume Δ: %.2f°\n", snapshot.volumeActiveDeltaDeg))
                    .append("Pitch direction: ").append(snapshot.pitchDirectionText).append("\n")
                    .append("Volume direction: ").append(snapshot.volumeDirectionText).append("\n\n");
        }

        sb.append("Background audio: ").append(onOff(bgAudioEnabled));
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    private void reloadMappingSettingsFromRepository() {
        AppSettings s = store().load();
        pitchAngleMinDeg = s.pitchAngleMinDeg;
        pitchAngleMaxDeg = s.pitchAngleMaxDeg;
        freqMinHz = s.freqMinHz;
        freqMaxHz = s.freqMaxHz;
        volumeAngleMinDeg = s.volumeAngleMinDeg;
        volumeAngleMaxDeg = s.volumeAngleMaxDeg;
        currentToneType = AppSettings.normalizeToneType(s.toneType);
        sanitizeMappingValues();
        audioEngine.setToneType(currentToneType);
    }

    private void persistSettings() {
        sanitizeMappingValues();
        AppSettings s = store().load();
        s.pitchAngleMinDeg = pitchAngleMinDeg;
        s.pitchAngleMaxDeg = pitchAngleMaxDeg;
        s.freqMinHz = freqMinHz;
        s.freqMaxHz = freqMaxHz;
        s.volumeAngleMinDeg = volumeAngleMinDeg;
        s.volumeAngleMaxDeg = volumeAngleMaxDeg;
        store().save(s);
    }

    private void setDefaultMappingValues() {
        pitchAngleMinDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MIN_DEG;
        pitchAngleMaxDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MAX_DEG;
        freqMinHz = AppSettings.DEFAULT_PLAY_FREQ_MIN_HZ;
        freqMaxHz = AppSettings.DEFAULT_PLAY_FREQ_MAX_HZ;
        volumeAngleMinDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MIN_DEG;
        volumeAngleMaxDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MAX_DEG;
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

        binding.sbPitchAngleMin.setMax(ANGLE_PROGRESS_MAX);
        binding.sbPitchAngleMax.setMax(ANGLE_PROGRESS_MAX);
        binding.sbVolAngleMin.setMax(ANGLE_PROGRESS_MAX);
        binding.sbVolAngleMax.setMax(ANGLE_PROGRESS_MAX);
        binding.sbFreqMin.setMax(getFreqProgressMax());
        binding.sbFreqMax.setMax(getFreqProgressMax());

        SeekBar.OnSeekBarChangeListener angleListener = new SliderListener(false);
        SeekBar.OnSeekBarChangeListener freqListener = new SliderListener(true);

        binding.sbPitchAngleMin.setOnSeekBarChangeListener(angleListener);
        binding.sbPitchAngleMax.setOnSeekBarChangeListener(angleListener);
        binding.sbVolAngleMin.setOnSeekBarChangeListener(angleListener);
        binding.sbVolAngleMax.setOnSeekBarChangeListener(angleListener);
        binding.sbFreqMin.setOnSeekBarChangeListener(freqListener);
        binding.sbFreqMax.setOnSeekBarChangeListener(freqListener);

        attachNumberClick(binding.tvPitchAngleMinVal, "Pitch angle min (deg)", ANGLE_MIN, ANGLE_MAX,
                () -> pitchAngleMinDeg, v -> pitchAngleMinDeg = v);
        attachNumberClick(binding.tvPitchAngleMaxVal, "Pitch angle max (deg)", ANGLE_MIN, ANGLE_MAX,
                () -> pitchAngleMaxDeg, v -> pitchAngleMaxDeg = v);
        attachNumberClick(binding.tvVolAngleMinVal, "Volume angle min (deg)", ANGLE_MIN, ANGLE_MAX,
                () -> volumeAngleMinDeg, v -> volumeAngleMinDeg = v);
        attachNumberClick(binding.tvVolAngleMaxVal, "Volume angle max (deg)", ANGLE_MIN, ANGLE_MAX,
                () -> volumeAngleMaxDeg, v -> volumeAngleMaxDeg = v);
        attachNumberClick(binding.tvFreqMinVal, "Frequency min (Hz)", FREQ_MIN_UI, currentFreqMaxUi,
                () -> freqMinHz, v -> freqMinHz = v);
        attachNumberClick(binding.tvFreqMaxVal, "Frequency max (Hz)", FREQ_MIN_UI, currentFreqMaxUi,
                () -> freqMaxHz, v -> freqMaxHz = v);

        syncAllMappingControlsFromState();
    }

    private final class SliderListener implements SeekBar.OnSeekBarChangeListener {
        private final boolean frequency;

        private SliderListener(boolean frequency) {
            this.frequency = frequency;
        }

        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (suppressSliderCallbacks) return;

            if (frequency) {
                float value = progressToFreq(progress);
                if (seekBar == binding.sbFreqMin) freqMinHz = value;
                if (seekBar == binding.sbFreqMax) freqMaxHz = value;
            } else {
                float value = progressToAngle(progress);
                if (seekBar == binding.sbPitchAngleMin) pitchAngleMinDeg = value;
                if (seekBar == binding.sbPitchAngleMax) pitchAngleMaxDeg = value;
                if (seekBar == binding.sbVolAngleMin) volumeAngleMinDeg = value;
                if (seekBar == binding.sbVolAngleMax) volumeAngleMaxDeg = value;
            }

            sanitizeMappingValues();
            syncAllMappingControlsFromState();
            recomputeMappedOutputs();
        }

        @Override public void onStartTrackingTouch(SeekBar seekBar) {}

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            persistSettings();
            appendLogSafe("Mapping updated");
        }
    }

    private interface FloatGetter { float get(); }
    private interface FloatSetter { void set(float v); }
    private interface FloatConsumer { void accept(float v); }

    private void attachNumberClick(TextView tv, String title, float min, float max,
                                   FloatGetter getter, FloatSetter setter) {
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
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.format(Locale.US, "%.2f", currentValue));
        input.setSelection(input.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(String.format(Locale.US,
                        "Enter a value between %.1f and %.1f", min, max))
                .setView(input)
                .setPositiveButton("OK", (d, which) -> {
                    try {
                        onOk.accept(clamp(Float.parseFloat(input.getText().toString().trim()), min, max));
                    } catch (Exception e) {
                        toastSafe("Invalid number");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private float angleToProgress(float angle) { return (angle - ANGLE_MIN) / ANGLE_STEP; }
    private float progressToAngle(int progress) { return ANGLE_MIN + progress * ANGLE_STEP; }
    private float freqToProgress(float hz) { return hz - FREQ_MIN_UI; }
    private float progressToFreq(int progress) { return FREQ_MIN_UI + progress; }

    private void syncAllMappingControlsFromState() {
        suppressSliderCallbacks = true;
        sanitizeMappingValues();

        binding.sbPitchAngleMin.setProgress((int) angleToProgress(pitchAngleMinDeg));
        binding.sbPitchAngleMax.setProgress((int) angleToProgress(pitchAngleMaxDeg));
        binding.sbVolAngleMin.setProgress((int) angleToProgress(volumeAngleMinDeg));
        binding.sbVolAngleMax.setProgress((int) angleToProgress(volumeAngleMaxDeg));
        binding.sbFreqMin.setProgress((int) freqToProgress(freqMinHz));
        binding.sbFreqMax.setProgress((int) freqToProgress(freqMaxHz));
        syncMappingValueTextsOnly();
        suppressSliderCallbacks = false;
    }

    private void syncMappingValueTextsOnly() {
        binding.tvPitchAngleMinVal.setText(String.format(Locale.US, "%.1f°", pitchAngleMinDeg));
        binding.tvPitchAngleMaxVal.setText(String.format(Locale.US, "%.1f°", pitchAngleMaxDeg));
        binding.tvVolAngleMinVal.setText(String.format(Locale.US, "%.1f°", volumeAngleMinDeg));
        binding.tvVolAngleMaxVal.setText(String.format(Locale.US, "%.1f°", volumeAngleMaxDeg));
        binding.tvFreqMinVal.setText(String.format(Locale.US, "%.1f Hz", freqMinHz));
        binding.tvFreqMaxVal.setText(String.format(Locale.US, "%.1f Hz", freqMaxHz));
    }

    private void recomputeMappedOutputs() {
        sanitizeMappingValues();

        float freq = mapLinearClamped(pitchActiveDeltaDeg, pitchAngleMinDeg, pitchAngleMaxDeg, freqMinHz, freqMaxHz);
        float vol = mapLinearClamped(volActiveDeltaDeg, volumeAngleMinDeg, volumeAngleMaxDeg, 0f, 1f);

        if (!pitchHasAngle) freq = freqMinHz;
        if (!volHasAngle) vol = 0f;

        mappedFreqHz = freq;
        mappedVolumeLinear = vol;
        audioTargetFreqHz = freq;
        audioTargetVolumeLinear = isInstrumentReady(getSnapshot()) ? vol : 0f;
        if (audioTargetVolumeLinear == 0f) mappedVolumeLinear = 0f;

        pushAudioTargetsToEngine();
    }

    private float mapLinearClamped(float x, float inMin, float inMax, float outMin, float outMax) {
        if (Math.abs(inMax - inMin) < 1e-6f) return outMin;
        float t = clamp((x - inMin) / (inMax - inMin), 0f, 1f);
        return outMin + t * (outMax - outMin);
    }

    private void pushAudioTargetsToEngine() {
        audioEngine.setToneType(currentToneType);
        audioEngine.setTargets(audioTargetFreqHz, audioTargetVolumeLinear);
    }

    private void consumeIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra(EXTRA_AUTOSTART_AUDIO, false)) {
            pendingAutoStartAudio = true;
            intent.removeExtra(EXTRA_AUTOSTART_AUDIO);
        }
    }

    private void syncAudioTargetsFromSharedBleState() {
        applyLiveAnglesFromSnapshot(getSnapshot());
        recomputeMappedOutputs();
    }

    private BleSnapshot getSnapshot() {
        return BleSessionManager.getSnapshot();
    }

    private void applyLiveAnglesFromSnapshot(BleSnapshot snapshot) {
        if (snapshot != null && snapshot.hostReady) {
            pitchActiveDeltaDeg = snapshot.pitchActiveDeltaDeg;
            volActiveDeltaDeg = snapshot.volumeActiveDeltaDeg;
            pitchHasAngle = snapshot.pitchConnected;
            volHasAngle = snapshot.volumeConnected;
        } else {
            pitchHasAngle = false;
            volHasAngle = false;
        }
    }

    private boolean isInstrumentReady(BleSnapshot snapshot) {
        return snapshot != null && snapshot.isBluetoothOn() && snapshot.areBothGlovesConnected();
    }

    private void updateVisualizer() {
        ThereminAudioEngine.VisualizerSnapshot waveSnapshot = audioEngine.getVisualizerSnapshot();
        if (waveSnapshot != null) {
            binding.thereminVisualizerView.setAudioWave(
                    waveSnapshot.samples,
                    waveSnapshot.freqHz,
                    waveSnapshot.volumeLinear,
                    freqMinHz,
                    freqMaxHz
            );
        } else {
            binding.thereminVisualizerView.setAudioWave(null, mappedFreqHz, mappedVolumeLinear, freqMinHz, freqMaxHz);
        }
    }

    private void maybeStartAudioAfterCalibration(boolean bothConnected) {
        if (!pendingAutoStartAudio || !bothConnected || isAudioRunning()) return;
        pushAudioTargetsToEngine();
        audioEngine.start();
        pendingAutoStartAudio = false;
        appendLogSafe("Calibration returned to Play -> audio started");
    }

    private void refreshUiFast() {
        BleSnapshot snapshot = getSnapshot();
        boolean bothConnected = snapshot != null && snapshot.areBothGlovesConnected();
        boolean oneConnected = snapshot != null && snapshot.isAnyGloveConnected();
        boolean connecting = snapshot != null && snapshot.isAnyGloveConnecting();

        applyLiveAnglesFromSnapshot(snapshot);
        recomputeMappedOutputs();

        binding.tvStatus.setText(buildPlayHeadline(snapshot, bothConnected, oneConnected, connecting));
        binding.tvAudio.setText(buildPlaySubtitle(snapshot, bothConnected, oneConnected, connecting));
        applyConnectionChip(binding.tvPitchConn, snapshot, true);
        applyConnectionChip(binding.tvVolConn, snapshot, false);
        binding.tvPitchValue.setText(buildFrequencyCardText(mappedFreqHz));
        binding.tvVolValue.setText(buildVolumeCardText(mappedVolumeLinear));
        binding.tvToneValue.setText(buildToneSummary(bothConnected));

        maybeStartAudioAfterCalibration(bothConnected);
        updateAudioStatusText();
        updateBleButtonText();
        updateVisualizer();
        syncMappingValueTextsOnly();
    }

    private String buildPlayHeadline(BleSnapshot s, boolean bothConnected, boolean oneConnected, boolean connecting) {
        if (s == null || !s.hostReady) return "Preparing instrument";
        if (!s.bluetoothEnabled) return "Bluetooth is off";
        if (bothConnected) return "Ready to perform";
        if (oneConnected) return "Almost ready";
        return connecting ? "Connecting your gloves" : "Waiting for gloves";
    }

    private String buildPlaySubtitle(BleSnapshot s, boolean bothConnected, boolean oneConnected, boolean connecting) {
        if (s == null || !s.hostReady) return "Setting up the live instrument experience.";
        if (!s.bluetoothEnabled) return "Turn Bluetooth on to reconnect your gloves.";
        if (bothConnected) return "Move your hands to shape pitch and volume. Calibrate anytime for a tighter response.";
        if (oneConnected) return "One glove is connected. Turn on the second glove to complete the instrument.";
        return connecting
                ? "Keep both gloves awake and close to your phone."
                : "Turn on both gloves to begin playing.";
    }

    private void applyConnectionChip(TextView view, BleSnapshot snapshot, boolean isPitch) {
        String label = isPitch ? "Pitch glove" : "Volume glove";
        view.setText(snapshot == null ? label + "\nWaiting" : snapshot.connectionChipText(label, isPitch));

        int bgColor;
        int strokeColor;
        int textColor;
        if (snapshot != null && snapshot.isGloveConnected(isPitch)) {
            bgColor = android.graphics.Color.parseColor("#173426");
            strokeColor = android.graphics.Color.parseColor("#49E37A");
            textColor = android.graphics.Color.parseColor("#F2FFF6");
        } else if (snapshot != null && snapshot.isAnyGloveConnecting()) {
            bgColor = android.graphics.Color.parseColor("#262041");
            strokeColor = android.graphics.Color.parseColor("#8A7DFF");
            textColor = android.graphics.Color.parseColor("#F3F0FF");
        } else {
            bgColor = android.graphics.Color.parseColor("#351822");
            strokeColor = android.graphics.Color.parseColor("#FF647D");
            textColor = android.graphics.Color.parseColor("#FFF2F4");
        }

        view.setTextColor(textColor);
        ((MaterialCardView) view.getParent()).setCardBackgroundColor(bgColor);
        ((MaterialCardView) view.getParent()).setStrokeColor(strokeColor);
        ((MaterialCardView) view.getParent()).setCardElevation(0f);
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

    private String buildToneSummary(boolean bothConnected) {
        if (!bothConnected) return "Connect both gloves to start shaping sound.";
        if (mappedVolumeLinear <= 0.01f) {
            return String.format(Locale.US,
                    "Live tone ready • %.1f Hz • Raise your volume hand to bring it in", mappedFreqHz);
        }
        return String.format(Locale.US,
                "Live tone • %.1f Hz • %.0f%% intensity", mappedFreqHz, mappedVolumeLinear * 100f);
    }

    private void updateAudioStatusText() {
        boolean running = isAudioRunning();
        binding.btnAudioStart.setText("");
        binding.btnAudioStart.setIconResource(running ? R.drawable.ic_pause_theremin : R.drawable.ic_play_theremin);
        binding.btnAudioStart.setContentDescription(running ? "Pause theremin" : "Play theremin");
        binding.tvPlayRemoteLabel.setText(running ? "Pause" : "Play");

        binding.btnAudioStop.setText("");
        binding.btnAudioStop.setIconResource(bgAudioEnabled
                ? R.drawable.ic_background_on
                : android.R.drawable.ic_menu_close_clear_cancel);
        binding.btnAudioStop.setContentDescription(bgAudioEnabled
                ? "Turn background audio off"
                : "Turn background audio on");
        binding.tvBackgroundLabel.setText(bgAudioEnabled ? "Background On" : "Background Off");
    }

    private void appendLogSafe(String msg) {
        long now = SystemClock.elapsedRealtime();
        synchronized (logLines) {
            logLines.addLast(msg);
            while (logLines.size() > LOG_MAX_LINES) logLines.removeFirst();
            if (now - lastLogFlushMs < LOG_FLUSH_MIN_INTERVAL_MS) return;
            lastLogFlushMs = now;

            StringBuilder sb = new StringBuilder();
            for (String s : logLines) sb.append(s).append('\n');
            String all = sb.toString();
            runOnUiThread(() -> binding.tvLog.setText(all));
        }
    }

    private String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }
}
