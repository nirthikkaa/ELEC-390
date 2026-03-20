package com.example.thereminglovestest2;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.thereminglovestest2.databinding.ActivityMainBinding;
import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Play screen.
 *
 * BLE stays in BleSessionManager.
 * Audio stays in ThereminAudioEngine / ThereminBackgroundAudioService.
 * The Play screen mostly does three things:
 * 1) read the latest BLE snapshot,
 * 2) map glove angles to frequency/volume,
 * 3) update the visible UI.
 */
public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_AUTOSTART_AUDIO =
            "com.example.thereminglovestest2.extra.AUTOSTART_AUDIO";

    private static final long UI_TICK_MS = 80;
    private static final int LOG_MAX_LINES = 200;
    private static final long LOG_FLUSH_MIN_INTERVAL_MS = 600;
    private static final int REQUEST_RECORD_AUDIO = 4109;

    private ActivityMainBinding binding;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final NavigationUtils.Poller uiTicker =
            new NavigationUtils.Poller(UI_TICK_MS, this::refreshUiFast);
    private final PlayMappingState play = new PlayMappingState();

    private ThereminAudioEngine audioEngine;
    private SettingsStore settingsRepo;
    private RecordingManager recordingManager;
    private RecordingRepository recordingRepository;
    private final Handler recordingTimerHandler = new Handler(Looper.getMainLooper());

    private boolean bgAudioEnabled = true;
    private boolean isRecordingUiActive;
    private boolean playUiVisible;
    private boolean suppressSliderCallbacks;
    private boolean pendingAutoStartAudio;
    private boolean waitingForServiceToStop;
    private long lastLogFlushMs;
    private long recordingStartElapsedMs;
    private ObjectAnimator recordBlinkAnimator;

    private final Runnable recordingTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecordingUiActive) return;
            long elapsedMs = SystemClock.elapsedRealtime() - recordingStartElapsedMs;
            binding.tvRecordingTimer.setText(formatRecordingDuration(elapsedMs));
            recordingTimerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.topNavBar.setTitleText("Play");

        BleSessionManager.initialize(getApplicationContext());
        audioEngine = new ThereminAudioEngine();
        recordingManager = new RecordingManager(this);
        recordingRepository = new RecordingRepository(this);
        setupRecordingCallbacks();
        ensureRecordAudioPermission();

        consumeIntent(getIntent());
        loadBgAudioPref();
        play.refreshFreqRangeLimit(this);
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
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        stopRecordBlink();
        if (recordingManager != null) recordingManager.release();
        super.onDestroy();
        uiTicker.stop();
        if (audioEngine != null && (!bgAudioEnabled || isFinishing())) audioEngine.shutdown();
    }

    private void onVisible() {
        playUiVisible = true;
        requestAudioBackFromBackgroundService();
        refreshPlayUiState();
    }

    private void refreshPlayUiState() {
        loadBgAudioPref();
        play.refreshFreqRangeLimit(this);
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

    private boolean isServiceOwningAudio() {
        return bgAudioEnabled && ThereminBackgroundAudioService.isServiceActive();
    }

    private boolean isAnyAudioRunning() {
        return isAudioRunning() || isServiceOwningAudio();
    }

    private void loadBgAudioPref() {
        bgAudioEnabled = SettingsStore.isBgAudioEnabled(this);
    }

    private void saveBgAudioPref() {
        SettingsStore.setBgAudioEnabled(this, bgAudioEnabled);
    }

    private void syncFreqSeekRange() {
        int max = play.getFreqProgressMax();
        binding.sbFreqMin.setMax(max);
        binding.sbFreqMax.setMax(max);
    }

    private void maybeMoveAudioToBackgroundService() {
        if (!bgAudioEnabled || isChangingConfigurations()) return;
        if (ThereminBackgroundAudioService.isServiceActive()) {
            waitingForServiceToStop = false;
            return;
        }
        if (!isAudioRunning()) return;
        try {
            pushAudioTargetsToEngine();
            ThereminBackgroundAudioService.startIfNeeded(this);
            audioEngine.stop();
            waitingForServiceToStop = false;
            appendLogSafe("Play hidden -> audio handed to background service");
        } catch (Exception e) {
            appendLogSafe("Background service failed: " + e.getClass().getSimpleName());
        }
    }

    private void requestAudioBackFromBackgroundService() {
        if (bgAudioEnabled || !ThereminBackgroundAudioService.isServiceActive()) {
            waitingForServiceToStop = false;
            return;
        }
        waitingForServiceToStop = true;
        ThereminBackgroundAudioService.stopIfRunning(this);
        appendLogSafe("Play visible -> reclaiming audio from background service");
    }

    private void finishAudioTakebackIfReady() {
        if (!waitingForServiceToStop || ThereminBackgroundAudioService.isServiceActive()) return;
        waitingForServiceToStop = false;
        if (isAudioRunning()) return;
        pushAudioTargetsToEngine();
        audioEngine.start();
        appendLogSafe("Play visible -> audio returned from background service");
    }

    private void wireButtons() {
        binding.btnDisconnectAll.setVisibility(View.GONE);

        binding.btnAudioStart.setOnClickListener(v -> toggleAudio());
        binding.btnRecord.setOnClickListener(v -> onRecordButtonPressed());
        bindGloveButtons(true, binding.btnNeutralPitch, binding.btnDirectionPitch, binding.btnHelpPitch, "Pitch glove");
        bindGloveButtons(false, binding.btnNeutralVol, binding.btnDirectionVol, binding.btnHelpVol, "Volume glove");
        binding.btnDefaults.setOnClickListener(v -> {
            play.restoreDefaults();
            applyMappingChange("Defaults restored", true);
        });
    }

    private void bindGloveButtons(boolean pitch, View neutral, View direction, View help, String title) {
        neutral.setOnClickListener(v -> BleSessionManager.requestCaptureNeutral(pitch));
        direction.setOnClickListener(v -> toggleDirection(pitch));
        help.setOnClickListener(v -> showHelpDialog(title));
    }

    private void setupRecordingCallbacks() {
        recordingManager.setListener(new RecordingManager.RecordingListener() {
            @Override
            public void onRecordingStarted() {
                runOnUiThread(() -> {
                    if (!isRecordingUiActive) startRecordingUi();
                });
            }

            @Override
            public void onRecordingStopped(String filePath, long durationMs) {
                runOnUiThread(() -> handleSavedRecording(filePath, durationMs));
            }

            @Override
            public void onRecordingError(String error) {
                runOnUiThread(() -> {
                    stopRecordingUi();
                    toastSafe(error == null || error.trim().isEmpty()
                            ? getString(R.string.recording_unavailable)
                            : error);
                    appendLogSafe("Recording error: " + error);
                });
            }
        });
    }

    private void ensureRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void onRecordButtonPressed() {
        if (isRecordingUiActive) {
            recordingManager.stopRecording();
            stopRecordingUi();
            toastSafe(getString(R.string.recording_saved));
            appendLogSafe("Recording stopped");
            return;
        }

        if (!hasRecordAudioPermission()) {
            ensureRecordAudioPermission();
            if (!hasRecordAudioPermission()) {
                toastSafe(getString(R.string.recording_unavailable));
                return;
            }
        }

        recordingManager.startRecording();
        startRecordingUi();
        appendLogSafe("Recording started");
    }

    private void startRecordingUi() {
        isRecordingUiActive = true;
        recordingStartElapsedMs = SystemClock.elapsedRealtime();
        binding.btnRecord.setText(getString(R.string.stop_recording));
        binding.tvRecordingTimer.setText(getString(R.string.recording_timer_zero));
        binding.tvRecordingTimer.setVisibility(View.VISIBLE);
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        recordingTimerHandler.postDelayed(recordingTimerRunnable, 1000);
        startRecordBlink();
    }

    private void stopRecordingUi() {
        isRecordingUiActive = false;
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        stopRecordBlink();
        binding.btnRecord.setText(getString(R.string.start_recording));
        binding.tvRecordingTimer.setText(getString(R.string.recording_timer_zero));
        binding.tvRecordingTimer.setVisibility(View.GONE);
    }

    private void startRecordBlink() {
        stopRecordBlink();
        recordBlinkAnimator = ObjectAnimator.ofFloat(binding.btnRecord, "alpha", 1f, 0.3f);
        recordBlinkAnimator.setDuration(600);
        recordBlinkAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        recordBlinkAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        recordBlinkAnimator.start();
    }

    private void stopRecordBlink() {
        if (recordBlinkAnimator != null) {
            recordBlinkAnimator.cancel();
            recordBlinkAnimator = null;
        }
        if (binding != null) binding.btnRecord.setAlpha(1f);
    }

    private void handleSavedRecording(String filePath, long durationMs) {
        if (filePath == null || filePath.trim().isEmpty()) {
            toastSafe(getString(R.string.recording_failed_empty));
            appendLogSafe("Recording failed: empty file path");
            return;
        }

        File file = new File(filePath);
        if (!file.exists() || file.length() == 0) {
            toastSafe(getString(R.string.recording_failed_empty));
            appendLogSafe("Recording failed: file missing or empty");
            return;
        }

        String name = "Recording " + new SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
                .format(new Date());
        recordingRepository.saveRecording(filePath, name, durationMs);
        appendLogSafe("Recording saved: " + name);
    }

    private String formatRecordingDuration(long elapsedMs) {
        long totalSeconds = Math.max(0L, elapsedMs / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            toastSafe(getString(R.string.recording_unavailable));
            appendLogSafe("RECORD_AUDIO denied");
        }
    }

    private void toggleAudio() {
        BleSnapshot snapshot = getSnapshot();
        if (snapshot != null && !snapshot.isBluetoothOn()) {
            toastSafe("Bluetooth is OFF");
            return;
        }
        if (bgAudioEnabled) {
            if (ThereminBackgroundAudioService.isServiceActive()) {
                waitingForServiceToStop = false;
                ThereminBackgroundAudioService.stopIfRunning(this);
                appendLogSafe("Background audio stopped from Play");
            } else {
                persistSettings();
                ThereminBackgroundAudioService.startIfNeeded(this);
                if (isAudioRunning()) audioEngine.stop();
                appendLogSafe("Background audio started from Play");
            }
        } else if (isAudioRunning()) {
            audioEngine.stop();
        } else if (ThereminBackgroundAudioService.isServiceActive()) {
            waitingForServiceToStop = true;
            ThereminBackgroundAudioService.stopIfRunning(this);
            appendLogSafe("Play tapped while background service was still stopping");
        } else {
            audioEngine.start();
        }
        updateAudioStatusText();
    }

    private void toggleBackgroundAudio() {
        bgAudioEnabled = !bgAudioEnabled;
        saveBgAudioPref();
        appendLogSafe("BG audio toggled -> " + onOff(bgAudioEnabled));
        toastSafe("Background audio: " + onOff(bgAudioEnabled));

        if (bgAudioEnabled) {
            if (isAudioRunning()) {
                persistSettings();
                ThereminBackgroundAudioService.startIfNeeded(this);
                audioEngine.stop();
                appendLogSafe("Play audio moved to background owner");
            }
            updateAudioStatusText();
            return;
        }

        if (!playUiVisible && isAudioRunning()) {
            audioEngine.stop();
            appendLogSafe("BG audio disabled while app in background -> audio stopped");
        }
        waitingForServiceToStop = false;
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
        AppSettings settings = store().load();
        play.load(settings);
        audioEngine.setToneType(play.currentToneType);
    }

    private void persistSettings() {
        AppSettings settings = store().load();
        play.saveTo(settings);
        store().save(settings);
    }

    private void setupSlidersAndClickNumbers() {
        reloadMappingSettingsFromRepository();
        binding.sbPitchAngleMin.setMax(PlayMappingState.ANGLE_PROGRESS_MAX);
        binding.sbPitchAngleMax.setMax(PlayMappingState.ANGLE_PROGRESS_MAX);
        binding.sbVolAngleMin.setMax(PlayMappingState.ANGLE_PROGRESS_MAX);
        binding.sbVolAngleMax.setMax(PlayMappingState.ANGLE_PROGRESS_MAX);
        syncFreqSeekRange();

        SeekBar.OnSeekBarChangeListener sliderListener = new SliderListener();
        binding.sbPitchAngleMin.setOnSeekBarChangeListener(sliderListener);
        binding.sbPitchAngleMax.setOnSeekBarChangeListener(sliderListener);
        binding.sbVolAngleMin.setOnSeekBarChangeListener(sliderListener);
        binding.sbVolAngleMax.setOnSeekBarChangeListener(sliderListener);
        binding.sbFreqMin.setOnSeekBarChangeListener(sliderListener);
        binding.sbFreqMax.setOnSeekBarChangeListener(sliderListener);

        attachNumberClick(binding.tvPitchAngleMinVal, "Pitch angle min (deg)",
                () -> PlayMappingState.ANGLE_MIN, () -> PlayMappingState.ANGLE_MAX,
                () -> play.pitchAngleMinDeg, v -> play.pitchAngleMinDeg = v);
        attachNumberClick(binding.tvPitchAngleMaxVal, "Pitch angle max (deg)",
                () -> PlayMappingState.ANGLE_MIN, () -> PlayMappingState.ANGLE_MAX,
                () -> play.pitchAngleMaxDeg, v -> play.pitchAngleMaxDeg = v);
        attachNumberClick(binding.tvVolAngleMinVal, "Volume angle min (deg)",
                () -> PlayMappingState.ANGLE_MIN, () -> PlayMappingState.ANGLE_MAX,
                () -> play.volumeAngleMinDeg, v -> play.volumeAngleMinDeg = v);
        attachNumberClick(binding.tvVolAngleMaxVal, "Volume angle max (deg)",
                () -> PlayMappingState.ANGLE_MIN, () -> PlayMappingState.ANGLE_MAX,
                () -> play.volumeAngleMaxDeg, v -> play.volumeAngleMaxDeg = v);
        attachNumberClick(binding.tvFreqMinVal, "Frequency min (Hz)",
                () -> PlayMappingState.FREQ_MIN_UI, () -> play.currentFreqMaxUi,
                () -> play.freqMinHz, v -> play.freqMinHz = v);
        attachNumberClick(binding.tvFreqMaxVal, "Frequency max (Hz)",
                () -> PlayMappingState.FREQ_MIN_UI, () -> play.currentFreqMaxUi,
                () -> play.freqMaxHz, v -> play.freqMaxHz = v);

        syncAllMappingControlsFromState();
    }

    private final class SliderListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            if (suppressSliderCallbacks) return;
            float value = isFrequencySeekBar(seekBar)
                    ? play.progressToFreq(progress)
                    : play.progressToAngle(progress);
            if (seekBar == binding.sbPitchAngleMin) play.pitchAngleMinDeg = value;
            else if (seekBar == binding.sbPitchAngleMax) play.pitchAngleMaxDeg = value;
            else if (seekBar == binding.sbVolAngleMin) play.volumeAngleMinDeg = value;
            else if (seekBar == binding.sbVolAngleMax) play.volumeAngleMaxDeg = value;
            else if (seekBar == binding.sbFreqMin) play.freqMinHz = value;
            else if (seekBar == binding.sbFreqMax) play.freqMaxHz = value;
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
                        float value = Float.parseFloat(String.valueOf(input.getText()).trim());
                        onOk.set(Math.max(min, Math.min(max, value)));
                    } catch (Exception e) {
                        toastSafe("Invalid number");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applyMappingChange(String logMessage, boolean persist) {
        syncAllMappingControlsFromState();
        recomputeMappedOutputs();
        if (persist) persistSettings();
        if (logMessage != null) appendLogSafe(logMessage);
    }

    private void syncAllMappingControlsFromState() {
        suppressSliderCallbacks = true;
        syncFreqSeekRange();
        binding.sbPitchAngleMin.setProgress(play.angleToProgress(play.pitchAngleMinDeg));
        binding.sbPitchAngleMax.setProgress(play.angleToProgress(play.pitchAngleMaxDeg));
        binding.sbVolAngleMin.setProgress(play.angleToProgress(play.volumeAngleMinDeg));
        binding.sbVolAngleMax.setProgress(play.angleToProgress(play.volumeAngleMaxDeg));
        binding.sbFreqMin.setProgress(play.freqToProgress(play.freqMinHz));
        binding.sbFreqMax.setProgress(play.freqToProgress(play.freqMaxHz));
        syncMappingValueTextsOnly();
        suppressSliderCallbacks = false;
    }

    private void syncMappingValueTextsOnly() {
        binding.tvPitchAngleMinVal.setText(String.format(Locale.US, "%.1f°", play.pitchAngleMinDeg));
        binding.tvPitchAngleMaxVal.setText(String.format(Locale.US, "%.1f°", play.pitchAngleMaxDeg));
        binding.tvVolAngleMinVal.setText(String.format(Locale.US, "%.1f°", play.volumeAngleMinDeg));
        binding.tvVolAngleMaxVal.setText(String.format(Locale.US, "%.1f°", play.volumeAngleMaxDeg));
        binding.tvFreqMinVal.setText(String.format(Locale.US, "%.1f Hz", play.freqMinHz));
        binding.tvFreqMaxVal.setText(String.format(Locale.US, "%.1f Hz", play.freqMaxHz));
    }

    private void recomputeMappedOutputs() {
        recomputeMappedOutputs(getSnapshot());
    }

    private void recomputeMappedOutputs(BleSnapshot snapshot) {
        play.recompute(snapshot);
        pushAudioTargetsToEngine();
    }

    private void pushAudioTargetsToEngine() {
        audioEngine.setToneType(play.currentToneType);
        audioEngine.setTargets(play.audioTargetFreqHz, play.audioTargetVolumeLinear);
    }

    private void consumeIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTOSTART_AUDIO, false)) return;
        pendingAutoStartAudio = true;
        intent.removeExtra(EXTRA_AUTOSTART_AUDIO);
    }

    private void syncAudioTargetsFromSharedBleState() {
        BleSnapshot snapshot = getSnapshot();
        play.syncLive(snapshot);
        play.recompute(snapshot);
    }

    private BleSnapshot getSnapshot() {
        return BleSessionManager.getSnapshot();
    }

    private void updateVisualizer() {
        ThereminAudioEngine.VisualizerSnapshot waveSnapshot =
                isServiceOwningAudio() && !isAudioRunning()
                        ? ThereminBackgroundAudioService.getVisualizerSnapshot()
                        : audioEngine.getVisualizerSnapshot();
        if (waveSnapshot == null) {
            binding.thereminVisualizerView.setAudioWave(
                    null, play.mappedFreqHz, play.mappedVolumeLinear, play.freqMinHz, play.freqMaxHz);
            return;
        }
        binding.thereminVisualizerView.setAudioWave(
                waveSnapshot.samples,
                waveSnapshot.freqHz,
                waveSnapshot.volumeLinear,
                play.freqMinHz,
                play.freqMaxHz
        );
    }

    private void maybeStartAudioAfterCalibration(boolean bothConnected) {
        if (!pendingAutoStartAudio || !bothConnected || waitingForServiceToStop) return;
        if (bgAudioEnabled) {
            if (!ThereminBackgroundAudioService.isServiceActive()) {
                persistSettings();
                ThereminBackgroundAudioService.startIfNeeded(this);
                appendLogSafe("Calibration returned to Play -> background audio kept alive");
            }
            pendingAutoStartAudio = false;
            return;
        }
        if (isAudioRunning() || ThereminBackgroundAudioService.isServiceActive()) return;
        pushAudioTargetsToEngine();
        audioEngine.start();
        pendingAutoStartAudio = false;
        appendLogSafe("Calibration returned to Play -> audio started");
    }

    private void refreshUiFast() {
        finishAudioTakebackIfReady();
        BleSnapshot snapshot = getSnapshot();
        boolean bothConnected = snapshot != null && snapshot.areBothGlovesConnected();
        boolean oneConnected = snapshot != null && snapshot.isAnyGloveConnected();
        boolean connecting = snapshot != null && snapshot.isAnyGloveConnecting();

        play.syncLive(snapshot);
        play.recompute(snapshot);

        binding.tvStatus.setText(PlayUiText.headline(snapshot, bothConnected, oneConnected, connecting));
        binding.tvAudio.setText(PlayUiText.subtitle(snapshot, bothConnected, oneConnected, connecting));
        applyConnectionChip(binding.tvPitchConn, snapshot, true);
        applyConnectionChip(binding.tvVolConn, snapshot, false);
        binding.tvPitchValue.setText(PlayUiText.frequency(play.mappedFreqHz));
        binding.tvVolValue.setText(PlayUiText.volume(play.mappedVolumeLinear));
        binding.tvToneValue.setText(PlayUiText.tone(bothConnected, play.mappedFreqHz, play.mappedVolumeLinear));

        maybeStartAudioAfterCalibration(bothConnected);
        updateAudioStatusText();
        updateBleButtonText();
        updateVisualizer();
        syncMappingValueTextsOnly();
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




    private void updateAudioStatusText() {
        boolean running = isAnyAudioRunning();
        binding.btnAudioStart.setText("");
        binding.btnAudioStart.setIconResource(running ? R.drawable.ic_pause_theremin : R.drawable.ic_play_theremin);
        binding.btnAudioStart.setContentDescription(running ? "Pause theremin" : "Play theremin");
        binding.tvPlayRemoteLabel.setText(running ? "Pause" : "Play");

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

    private void toastSafe(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }
}
