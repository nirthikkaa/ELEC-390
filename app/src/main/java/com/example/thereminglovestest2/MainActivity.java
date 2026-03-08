package com.example.thereminglovestest2;
import android.content.Intent;
import android.graphics.Color;
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
 * BLE stays in BleSessionManager. This screen only maps live data to audio and UI.
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
    private boolean bgAudioEnabled = true, playUiVisible, suppressSliderCallbacks, pendingAutoStartAudio;
    private float currentFreqMaxUi = FREQ_STANDARD_MAX_UI;
    private float pitchAngleMinDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MIN_DEG;
    private float pitchAngleMaxDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MAX_DEG;
    private float freqMinHz = AppSettings.DEFAULT_PLAY_FREQ_MIN_HZ;
    private float freqMaxHz = AppSettings.DEFAULT_PLAY_FREQ_MAX_HZ;
    private float volumeAngleMinDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MIN_DEG;
    private float volumeAngleMaxDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MAX_DEG;
    private float pitchActiveDeltaDeg, volActiveDeltaDeg;
    private boolean pitchHasAngle, volHasAngle;
    private float mappedFreqHz = 880f, mappedVolumeLinear;
    private float audioTargetFreqHz = 880f, audioTargetVolumeLinear;
    private long lastLogFlushMs;
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
        onVisible();
        uiTicker.start();
    }
    @Override
    protected void onResume() {
        super.onResume();
        onVisible();
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
        if (!isChangingConfigurations() && !bgAudioEnabled && isAudioRunning()) audioEngine.stop();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        uiTicker.stop();
        if (audioEngine != null && (!bgAudioEnabled || isFinishing())) audioEngine.shutdown();
    }
    private void onVisible() {
        playUiVisible = true;
        maybeTakeAudioBackFromBackgroundService();
        refreshPlayUiState();
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
    private void syncFreqSeekRange() {
        int max = getFreqProgressMax();
        binding.sbFreqMin.setMax(max);
        binding.sbFreqMax.setMax(max);
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
        if (isAudioRunning()) return;
        pushAudioTargetsToEngine();
        audioEngine.start();
        appendLogSafe("Play visible -> audio returned from background service");
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
        binding.btnAudioStart.setOnClickListener(v -> toggleAudio());
        binding.btnAudioStop.setOnClickListener(v -> toggleBackgroundAudio());
        bindGloveButtons(true, binding.btnNeutralPitch, binding.btnDirectionPitch, binding.btnHelpPitch, "Pitch glove");
        bindGloveButtons(false, binding.btnNeutralVol, binding.btnDirectionVol, binding.btnHelpVol, "Volume glove");
        binding.btnDefaults.setOnClickListener(v -> {
            setDefaultMappingValues();
            applyMappingChange("Defaults restored", true);
        });
    }
    private void bindGloveButtons(boolean pitch, View neutral, View direction, View help, String title) {
        neutral.setOnClickListener(v -> BleSessionManager.requestCaptureNeutral(pitch));
        direction.setOnClickListener(v -> toggleDirection(pitch));
        help.setOnClickListener(v -> showHelpDialog(title));
    }
    private void toggleAudio() {
        BleSnapshot snapshot = getSnapshot();
        if (snapshot != null && !snapshot.isBluetoothOn()) {
            toastSafe("Bluetooth is OFF");
            return;
        }
        if (isAudioRunning()) audioEngine.stop(); else audioEngine.start();
        updateAudioStatusText();
    }
    private void toggleBackgroundAudio() {
        bgAudioEnabled = !bgAudioEnabled;
        saveBgAudioPref();
        appendLogSafe("BG audio toggled -> " + onOff(bgAudioEnabled));
        toastSafe("Background audio: " + onOff(bgAudioEnabled));
        if (bgAudioEnabled) {
            updateAudioStatusText();
            return;
        }
        if (!playUiVisible && isAudioRunning()) {
            audioEngine.stop();
            appendLogSafe("BG audio disabled while app in background -> audio stopped");
        }
        ThereminBackgroundAudioService.stopIfRunning(this);
        updateAudioStatusText();
    }
    private void toggleDirection(boolean pitch) {
        appendLogSafe((pitch ? "Pitch" : "Volume") + ": toggle direction");
        BleSessionManager.requestToggleDirection(pitch);
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
        String message;
        if (snapshot == null || !snapshot.hostReady) {
            message = "BLE host not ready.\n\n";
        } else {
            message = "Bluetooth: " + onOff(snapshot.bluetoothEnabled) + '\n'
                    + "Scanning: " + yesNo(snapshot.scanning) + '\n'
                    + "Status: " + snapshot.statusText + "\n\n"
                    + "Pitch: " + snapshot.connectionDetail(true) + '\n'
                    + "Volume: " + snapshot.connectionDetail(false) + "\n\n"
                    + "Pitch connected: " + snapshot.pitchConnected + '\n'
                    + "Volume connected: " + snapshot.volumeConnected + '\n'
                    + String.format(Locale.US, "Pitch Δ: %.2f°\n", snapshot.pitchActiveDeltaDeg)
                    + String.format(Locale.US, "Volume Δ: %.2f°\n", snapshot.volumeActiveDeltaDeg)
                    + "Pitch direction: " + snapshot.pitchDirectionText + '\n'
                    + "Volume direction: " + snapshot.volumeDirectionText + "\n\n";
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message + "Background audio: " + onOff(bgAudioEnabled))
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
        pitchAngleMaxDeg = enforceUpperBound(pitchAngleMinDeg, pitchAngleMaxDeg, ANGLE_STEP, ANGLE_MAX);
        volumeAngleMaxDeg = enforceUpperBound(volumeAngleMinDeg, volumeAngleMaxDeg, ANGLE_STEP, ANGLE_MAX);
        freqMaxHz = enforceUpperBound(freqMinHz, freqMaxHz, 1f, currentFreqMaxUi);
    }
    private float enforceUpperBound(float min, float max, float step, float cap) {
        return max < min + step ? Math.min(cap, min + step) : max;
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
        syncFreqSeekRange();
        SeekBar.OnSeekBarChangeListener sliderListener = new SliderListener();
        binding.sbPitchAngleMin.setOnSeekBarChangeListener(sliderListener);
        binding.sbPitchAngleMax.setOnSeekBarChangeListener(sliderListener);
        binding.sbVolAngleMin.setOnSeekBarChangeListener(sliderListener);
        binding.sbVolAngleMax.setOnSeekBarChangeListener(sliderListener);
        binding.sbFreqMin.setOnSeekBarChangeListener(sliderListener);
        binding.sbFreqMax.setOnSeekBarChangeListener(sliderListener);
        attachNumberClick(binding.tvPitchAngleMinVal, "Pitch angle min (deg)",
                () -> ANGLE_MIN, () -> ANGLE_MAX, () -> pitchAngleMinDeg, v -> pitchAngleMinDeg = v);
        attachNumberClick(binding.tvPitchAngleMaxVal, "Pitch angle max (deg)",
                () -> ANGLE_MIN, () -> ANGLE_MAX, () -> pitchAngleMaxDeg, v -> pitchAngleMaxDeg = v);
        attachNumberClick(binding.tvVolAngleMinVal, "Volume angle min (deg)",
                () -> ANGLE_MIN, () -> ANGLE_MAX, () -> volumeAngleMinDeg, v -> volumeAngleMinDeg = v);
        attachNumberClick(binding.tvVolAngleMaxVal, "Volume angle max (deg)",
                () -> ANGLE_MIN, () -> ANGLE_MAX, () -> volumeAngleMaxDeg, v -> volumeAngleMaxDeg = v);
        attachNumberClick(binding.tvFreqMinVal, "Frequency min (Hz)",
                () -> FREQ_MIN_UI, () -> currentFreqMaxUi, () -> freqMinHz, v -> freqMinHz = v);
        attachNumberClick(binding.tvFreqMaxVal, "Frequency max (Hz)",
                () -> FREQ_MIN_UI, () -> currentFreqMaxUi, () -> freqMaxHz, v -> freqMaxHz = v);
        syncAllMappingControlsFromState();
    }
    private final class SliderListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (suppressSliderCallbacks) return;
            float value = isFrequencySeekBar(seekBar) ? progressToFreq(progress) : progressToAngle(progress);
            if (seekBar == binding.sbPitchAngleMin) pitchAngleMinDeg = value;
            else if (seekBar == binding.sbPitchAngleMax) pitchAngleMaxDeg = value;
            else if (seekBar == binding.sbVolAngleMin) volumeAngleMinDeg = value;
            else if (seekBar == binding.sbVolAngleMax) volumeAngleMaxDeg = value;
            else if (seekBar == binding.sbFreqMin) freqMinHz = value;
            else if (seekBar == binding.sbFreqMax) freqMaxHz = value;
            applyMappingChange(null, false);
        }
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
            persistSettings();
            appendLogSafe("Mapping updated");
        }
    }
    private boolean isFrequencySeekBar(SeekBar seekBar) {
        return seekBar == binding.sbFreqMin || seekBar == binding.sbFreqMax;
    }
    private interface FloatGetter { float get(); }
    private interface FloatSetter { void set(float v); }
    private void attachNumberClick(TextView tv, String title, FloatGetter min, FloatGetter max,
                                   FloatGetter getter, FloatSetter setter) {
        tv.setOnClickListener(v -> showNumberEntryDialog(title, min.get(), max.get(), getter.get(), newValue -> {
            setter.set(newValue);
            applyMappingChange("Manual entry: " + title, true);
        }));
    }
    private void showNumberEntryDialog(String title, float min, float max, float currentValue, FloatSetter onOk) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.format(Locale.US, "%.2f", currentValue));
        input.setSelection(input.getText().length());
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(String.format(Locale.US, "Enter a value between %.1f and %.1f", min, max))
                .setView(input)
                .setPositiveButton("OK", (d, which) -> {
                    try {
                        onOk.set(clamp(Float.parseFloat(input.getText().toString().trim()), min, max));
                    } catch (Exception e) {
                        toastSafe("Invalid number");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    private void applyMappingChange(String logMessage, boolean persist) {
        sanitizeMappingValues();
        syncAllMappingControlsFromState();
        recomputeMappedOutputs();
        if (persist) persistSettings();
        if (logMessage != null) appendLogSafe(logMessage);
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
        syncFreqSeekRange();
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
        recomputeMappedOutputs(getSnapshot());
    }
    private void recomputeMappedOutputs(BleSnapshot snapshot) {
        sanitizeMappingValues();
        float freq = mapLinearClamped(pitchActiveDeltaDeg, pitchAngleMinDeg, pitchAngleMaxDeg, freqMinHz, freqMaxHz);
        float vol = mapLinearClamped(volActiveDeltaDeg, volumeAngleMinDeg, volumeAngleMaxDeg, 0f, 1f);
        if (!pitchHasAngle) freq = freqMinHz;
        if (!volHasAngle) vol = 0f;
        mappedFreqHz = freq;
        mappedVolumeLinear = vol;
        audioTargetFreqHz = freq;
        audioTargetVolumeLinear = isInstrumentReady(snapshot) ? vol : 0f;
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
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTOSTART_AUDIO, false)) return;
        pendingAutoStartAudio = true;
        intent.removeExtra(EXTRA_AUTOSTART_AUDIO);
    }
    private void syncAudioTargetsFromSharedBleState() {
        BleSnapshot snapshot = getSnapshot();
        applyLiveAnglesFromSnapshot(snapshot);
        recomputeMappedOutputs(snapshot);
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
            return;
        }
        pitchHasAngle = false;
        volHasAngle = false;
    }
    private boolean isInstrumentReady(BleSnapshot snapshot) {
        return snapshot != null && snapshot.isBluetoothOn() && snapshot.areBothGlovesConnected();
    }
    private void updateVisualizer() {
        ThereminAudioEngine.VisualizerSnapshot waveSnapshot = audioEngine.getVisualizerSnapshot();
        if (waveSnapshot == null) {
            binding.thereminVisualizerView.setAudioWave(null, mappedFreqHz, mappedVolumeLinear, freqMinHz, freqMaxHz);
            return;
        }
        binding.thereminVisualizerView.setAudioWave(
                waveSnapshot.samples,
                waveSnapshot.freqHz,
                waveSnapshot.volumeLinear,
                freqMinHz,
                freqMaxHz
        );
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
        recomputeMappedOutputs(snapshot);
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
    private String buildPlayHeadline(BleSnapshot snapshot, boolean bothConnected,
                                     boolean oneConnected, boolean connecting) {
        if (snapshot == null || !snapshot.hostReady) return "Preparing instrument";
        if (!snapshot.bluetoothEnabled) return "Bluetooth is off";
        if (bothConnected) return "Ready to perform";
        if (oneConnected) return "Almost ready";
        return connecting ? "Connecting your gloves" : "Waiting for gloves";
    }
    private String buildPlaySubtitle(BleSnapshot snapshot, boolean bothConnected,
                                     boolean oneConnected, boolean connecting) {
        if (snapshot == null || !snapshot.hostReady) return "Setting up the live instrument experience.";
        if (!snapshot.bluetoothEnabled) return "Turn Bluetooth on to reconnect your gloves.";
        if (bothConnected) return "Move your hands to shape pitch and volume. Calibrate anytime for a tighter response.";
        if (oneConnected) return "One glove is connected. Turn on the second glove to complete the instrument.";
        return connecting
                ? "Keep both gloves awake and close to your phone."
                : "Turn on both gloves to begin playing.";
    }
    private void applyConnectionChip(TextView view, BleSnapshot snapshot, boolean isPitch) {
        String label = isPitch ? "Pitch glove" : "Volume glove";
        view.setText(snapshot == null ? label + "\nWaiting" : snapshot.connectionChipText(label, isPitch));
        int bgColor = Color.parseColor("#351822");
        int strokeColor = Color.parseColor("#FF647D");
        int textColor = Color.parseColor("#FFF2F4");
        if (snapshot != null && snapshot.isGloveConnected(isPitch)) {
            bgColor = Color.parseColor("#173426");
            strokeColor = Color.parseColor("#49E37A");
            textColor = Color.parseColor("#F2FFF6");
        } else if (snapshot != null && snapshot.isAnyGloveConnecting()) {
            bgColor = Color.parseColor("#262041");
            strokeColor = Color.parseColor("#8A7DFF");
            textColor = Color.parseColor("#F3F0FF");
        }
        MaterialCardView card = (MaterialCardView) view.getParent();
        view.setTextColor(textColor);
        card.setCardBackgroundColor(bgColor);
        card.setStrokeColor(strokeColor);
        card.setCardElevation(0f);
    }
    private String buildFrequencyCardText(float freqHz) {
        float f = Math.max(FREQ_MIN_UI, freqHz);
        return f >= 1000f
                ? String.format(Locale.US, "Frequency • %.2f kHz", f / 1000f)
                : String.format(Locale.US, "Frequency • %.0f Hz", f);
    }
    private String buildVolumeCardText(float volumeLinear) {
        return String.format(Locale.US, "Volume level • %d%%", Math.round(clamp(volumeLinear, 0f, 1f) * 100f));
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
            StringBuilder sb = new StringBuilder(logLines.size() * 24);
            for (String line : logLines) sb.append(line).append('\n');
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
