package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityCalibrationBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

public class CalibrationActivity extends AppCompatActivity {

    private static final long UI_POLL_MS = 150L;
    private static final float ANGLE_MIN_LIMIT = -90f, ANGLE_MAX_LIMIT = 90f, ANGLE_STEP = 0.5f;
    private static final float FREQ_MIN_LIMIT = 20f, FREQ_STANDARD_MAX_LIMIT = 2000f, FREQ_EXTENDED_MAX_LIMIT = 20000f, FREQ_STEP = 1f;

    private ActivityCalibrationBinding binding;
    private final NavigationUtils.Poller uiPoller = new NavigationUtils.Poller(UI_POLL_MS, this::refreshLiveCalibration);
    private SettingsStore settingsRepo;

    private float pitchAngleMinDeg = AppSettings.DEFAULT_PITCH_ANGLE_MIN_DEG;
    private float pitchAngleMaxDeg = AppSettings.DEFAULT_PITCH_ANGLE_MAX_DEG;
    private float freqMinHz = AppSettings.DEFAULT_FREQ_MIN_HZ;
    private float freqMaxHz = AppSettings.DEFAULT_FREQ_MAX_HZ;
    private float volumeAngleMinDeg = AppSettings.DEFAULT_VOLUME_ANGLE_MIN_DEG;
    private float volumeAngleMaxDeg = AppSettings.DEFAULT_VOLUME_ANGLE_MAX_DEG;
    private boolean pitchDirectionInverted = AppSettings.DEFAULT_PITCH_DIRECTION_INVERTED;
    private boolean volumeDirectionInverted = AppSettings.DEFAULT_VOLUME_DIRECTION_INVERTED;
    private boolean hasUnsavedChanges, pitchNeutralCapturedThisVisit, volumeNeutralCapturedThisVisit, calibrationGuideLearned;
    private float freqMaxLimitHz = FREQ_STANDARD_MAX_LIMIT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsRepo = new SettingsStore(this);
        refreshFrequencyRangeLimit();
        calibrationGuideLearned = SettingsStore.isCalibrationGuideLearned(this);
        loadAllSettings();
        binding = ActivityCalibrationBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.topNavBar.setTitleText("Calibration");
        setupControls();
        syncAllViewsFromState();
        refreshLiveCalibration();
    }

    @Override
    protected void onStart() {
        super.onStart();
        boolean wasGuideLearned = calibrationGuideLearned;
        refreshFrequencyRangeLimit();
        refreshDirectionSettings();
        calibrationGuideLearned = SettingsStore.isCalibrationGuideLearned(this);
        if (wasGuideLearned && !calibrationGuideLearned) {
            resetNeutralCaptureProgress();
            setHostNote("Calibration tutorial restored. Start from step 1.");
        }
        syncAllViewsFromState();
        uiPoller.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiPoller.stop();
    }

    private void setupControls() {
        setupAngleKnob(binding.knobPitchAngleMin, "Pitch Angle Min", () -> pitchAngleMinDeg, value -> pitchAngleMinDeg = value);
        setupAngleKnob(binding.knobPitchAngleMax, "Pitch Angle Max", () -> pitchAngleMaxDeg, value -> pitchAngleMaxDeg = value);
        setupFreqKnob(binding.knobFreqMin, "Freq Min", () -> freqMinHz, value -> freqMinHz = value);
        setupFreqKnob(binding.knobFreqMax, "Freq Max", () -> freqMaxHz, value -> freqMaxHz = value);
        setupAngleKnob(binding.knobVolumeAngleMin, "Volume Angle Min", () -> volumeAngleMinDeg, value -> volumeAngleMinDeg = value);
        setupAngleKnob(binding.knobVolumeAngleMax, "Volume Angle Max", () -> volumeAngleMaxDeg, value -> volumeAngleMaxDeg = value);
        refreshFrequencyKnobRanges();

        binding.btnPing.setOnClickListener(v -> runHostAction(BleSessionManager::requestRefreshHandshake, "Requested BLE refresh from the Play host."));
        binding.btnPitchNeutral.setOnClickListener(v -> handleNeutralCapture(true));
        binding.btnVolumeNeutral.setOnClickListener(v -> handleNeutralCapture(false));
        binding.btnDefaults.setOnClickListener(v -> restoreDefaults());
        binding.btnReload.setOnClickListener(v -> reloadSavedSettings());
        binding.btnSaveAndPlay.setOnClickListener(v -> saveAndPlay());
    }

    private void setupAngleKnob(KnobControlView knob, String label, FloatGetter getter, FloatSetter setter) {
        setupKnob(knob, label, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT, ANGLE_STEP, true, getter, setter);
    }

    private void setupFreqKnob(KnobControlView knob, String label, FloatGetter getter, FloatSetter setter) {
        setupKnob(knob, label, FREQ_MIN_LIMIT, freqMaxLimitHz, FREQ_STEP, false, getter, setter);
    }

    private void setupKnob(KnobControlView knob, String label, float min, float max, float step,
                           boolean isAngle, FloatGetter getter, FloatSetter setter) {
        knob.setLabelText(label);
        knob.setRange(min, max);
        knob.setStepSize(step);
        knob.setValue(getter.get());
        knob.setValueText(formatValue(getter.get(), isAngle));
        knob.setOnKnobValueChangedListener((view, value, fromUser) -> {
            if (!fromUser) return;
            setter.set(value);
            markChanged();
            syncAllViewsFromState();
        });
        knob.setOnKnobCommitListener((view, value) -> {
            sanitizeState();
            markChanged();
            updateSummaryText();
        });
        knob.setOnClickListener(v -> showNumberEditDialog(label, getter.get(), setter, isAngle));
    }

    private void loadAllSettings() {
        AppSettings settings = settingsRepo.load();
        applyCalibrationSettings(settings);
        applyDirectionSettings(settings);
    }

    private void refreshDirectionSettings() {
        applyDirectionSettings(settingsRepo.load());
    }

    private void applyCalibrationSettings(AppSettings settings) {
        pitchAngleMinDeg = settings.pitchAngleMinDeg;
        pitchAngleMaxDeg = settings.pitchAngleMaxDeg;
        freqMinHz = settings.freqMinHz;
        freqMaxHz = settings.freqMaxHz;
        volumeAngleMinDeg = settings.volumeAngleMinDeg;
        volumeAngleMaxDeg = settings.volumeAngleMaxDeg;
        sanitizeState();
    }

    private void applyDirectionSettings(AppSettings settings) {
        pitchDirectionInverted = settings.pitchDirectionInverted;
        volumeDirectionInverted = settings.volumeDirectionInverted;
    }

    private void applyCalibrationDefaults() {
        pitchAngleMinDeg = AppSettings.DEFAULT_PITCH_ANGLE_MIN_DEG;
        pitchAngleMaxDeg = AppSettings.DEFAULT_PITCH_ANGLE_MAX_DEG;
        freqMinHz = AppSettings.DEFAULT_FREQ_MIN_HZ;
        freqMaxHz = Math.min(AppSettings.DEFAULT_FREQ_MAX_HZ, freqMaxLimitHz);
        volumeAngleMinDeg = AppSettings.DEFAULT_VOLUME_ANGLE_MIN_DEG;
        volumeAngleMaxDeg = AppSettings.DEFAULT_VOLUME_ANGLE_MAX_DEG;
        sanitizeState();
    }

    private void markChanged() {
        hasUnsavedChanges = true;
    }

    private void runHostAction(Runnable action, String note) {
        action.run();
        setHostNote(note);
    }

    private void restoreDefaults() {
        applyCalibrationDefaults();
        markChanged();
        resetNeutralCaptureProgress();
        syncAllViewsFromState();
        updateCalibrationProgress(BleSessionManager.getSnapshot());
        setHostNote("Calibration defaults loaded. Direction settings stay in Settings.");
    }

    private void reloadSavedSettings() {
        loadAllSettings();
        hasUnsavedChanges = false;
        resetNeutralCaptureProgress();
        syncAllViewsFromState();
        updateCalibrationProgress(BleSessionManager.getSnapshot());
        setHostNote("Loaded the last saved calibration from local storage.");
    }

    private void saveAndPlay() {
        sanitizeState();
        settingsRepo.save(buildSettings());
        hasUnsavedChanges = false;
        setHostNote("Calibration saved. Returning to Play.");
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        intent.putExtra(MainActivity.EXTRA_AUTOSTART_AUDIO, true);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    private AppSettings buildSettings() {
        AppSettings settings = settingsRepo.load();
        settings.pitchAngleMinDeg = pitchAngleMinDeg;
        settings.pitchAngleMaxDeg = pitchAngleMaxDeg;
        settings.freqMinHz = freqMinHz;
        settings.freqMaxHz = freqMaxHz;
        settings.volumeAngleMinDeg = volumeAngleMinDeg;
        settings.volumeAngleMaxDeg = volumeAngleMaxDeg;
        return settings;
    }

    private void showNumberEditDialog(String label, float current, FloatSetter setter, boolean isAngle) {
        float min = isAngle ? ANGLE_MIN_LIMIT : FREQ_MIN_LIMIT;
        float max = isAngle ? ANGLE_MAX_LIMIT : freqMaxLimitHz;
        String unit = isAngle ? "°" : " Hz";

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(8), dp(8), 0);

        TextView helper = new TextView(this);
        helper.setText(String.format(Locale.US, "Enter a value from %s to %s%s", formatPlainValue(min, isAngle), formatPlainValue(max, isAngle), unit));
        helper.setTextSize(14f);
        helper.setPadding(dp(4), 0, dp(4), dp(10));

        TextInputLayout inputLayout = new TextInputLayout(this);
        inputLayout.setHint(label);
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setHelperText(isAngle ? "Decimals allowed" : "Whole numbers recommended");

        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        input.setText(String.format(Locale.US, isAngle ? "%.2f" : "%.0f", current));
        input.setSelectAllOnFocus(true);
        input.setGravity(Gravity.START);
        input.setTextSize(20f);
        input.setSingleLine(true);
        inputLayout.addView(input, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        container.addView(helper);
        container.addView(inputLayout);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", null)
                .create();

        dialog.setOnShowListener(d -> {
            selectAll(input);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v ->
                    applyTypedValue(inputLayout, input, setter, min, max, unit, isAngle, dialog));
        });
        dialog.show();
    }

    private void selectAll(TextInputEditText input) {
        input.requestFocus();
        input.post(() -> {
            input.requestFocus();
            if (input.getText() != null) input.setSelection(0, input.getText().length());
        });
    }

    private void applyTypedValue(TextInputLayout inputLayout, TextInputEditText input, FloatSetter setter,
                                 float min, float max, String unit, boolean isAngle, AlertDialog dialog) {
        String raw = input.getText() == null ? "" : input.getText().toString().trim();
        if (raw.isEmpty()) {
            inputLayout.setError("Enter a value.");
            return;
        }
        try {
            float value = Float.parseFloat(raw);
            if (value < min || value > max) {
                inputLayout.setError(String.format(Locale.US, "Use %s to %s%s", formatPlainValue(min, isAngle), formatPlainValue(max, isAngle), unit));
                return;
            }
            inputLayout.setError(null);
            setter.set(value);
            sanitizeState();
            markChanged();
            syncAllViewsFromState();
            dialog.dismiss();
        } catch (Exception ignored) {
            inputLayout.setError("Enter a valid number.");
        }
    }

    private void refreshLiveCalibration() {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        if (snapshot == null || !snapshot.hostReady) {
            showDisconnectedLiveState();
            return;
        }

        binding.tvLiveStatus.setText(snapshot.bluetoothEnabled
                ? String.format(Locale.US, "Pitch: %s | Volume: %s", connectedText(snapshot.pitchConnected), connectedText(snapshot.volumeConnected))
                : "BLUETOOTH OFF");

        if (!hasUnsavedChanges) {
            setHostNote("Tap a value to type. Turn a knob to adjust. Save & Play stores the current values.");
        }

        applyLiveSection(binding.tvPitchLive, binding.tvPitchMeta, "Pitch", snapshot.pitchActiveDeltaDeg,
                snapshot.pitchConnected, directionText(snapshot.pitchDirectionText, pitchDirectionInverted), snapshot.pitchNeutralRollDeg);
        applyLiveSection(binding.tvVolumeLive, binding.tvVolumeMeta, "Volume", snapshot.volumeActiveDeltaDeg,
                snapshot.volumeConnected, directionText(snapshot.volumeDirectionText, volumeDirectionInverted), snapshot.volumeNeutralRollDeg);
        updateCalibrationProgress(snapshot);
    }

    private void showDisconnectedLiveState() {
        binding.tvLiveStatus.setText("Live status: host unavailable");
        if (!hasUnsavedChanges) setHostNote("Open Play once to initialize BLE, then come back here.");
        applyLiveSection(binding.tvPitchLive, binding.tvPitchMeta, "Pitch", null, false,
                directionText(null, pitchDirectionInverted), null);
        applyLiveSection(binding.tvVolumeLive, binding.tvVolumeMeta, "Volume", null, false,
                directionText(null, volumeDirectionInverted), null);
        updateCalibrationProgress(null);
    }

    private void applyLiveSection(TextView live, TextView meta, String name, Float delta, boolean connected, String direction, Float neutral) {
        live.setText(delta == null ? name + " ACTIVE_DELTA_DEG: —" : String.format(Locale.US, "%s ACTIVE_DELTA_DEG: %.2f°", name, delta));
        meta.setText(neutral == null
                ? String.format(Locale.US, "Conn: %s | Dir: %s | Neutral: —", connected ? "ON" : "OFF", direction)
                : String.format(Locale.US, "Conn: %s | Dir: %s | Neutral: %.2f°", connected ? "ON" : "OFF", direction, neutral));
    }

    private String directionText(String reported, boolean fallbackInverted) {
        if (reported != null) {
            String direction = reported.trim().toUpperCase(Locale.US);
            if (direction.contains("NEG")) return "NEGATIVE";
            if (direction.contains("POS")) return "POSITIVE";
        }
        return fallbackInverted ? "NEGATIVE" : "POSITIVE";
    }

    private void updateCalibrationProgress(BleSnapshot snapshot) {
        if (calibrationGuideLearned) {
            setProgressVisible(false);
            return;
        }
        int progress = 0;
        String label = "Progress 0/3 • Open Play once so the BLE host becomes available.";
        boolean hostReady = snapshot != null && snapshot.hostReady;
        boolean bothConnected = hostReady && snapshot.pitchConnected && snapshot.volumeConnected;
        if (bothConnected) {
            int steps = 1 + (pitchNeutralCapturedThisVisit ? 1 : 0) + (volumeNeutralCapturedThisVisit ? 1 : 0);
            if (steps >= 3) {
                calibrationGuideLearned = true;
                SettingsStore.setCalibrationGuideLearned(this, true);
                setProgressVisible(false);
                return;
            }
            progress = steps == 1 ? 35 : 65;
            label = steps == 1
                    ? "Progress 1/3 • Both gloves connected. Capture pitch neutral first."
                    : "Progress 2/3 • One neutral captured. Capture the remaining glove.";
        } else if (hostReady) {
            progress = 10;
            label = "Progress 0/3 • Connect both gloves before calibrating.";
        }
        setProgressVisible(true);
        binding.progressCalibration.setProgress(progress);
        binding.tvCalibrationProgress.setText(label);
    }

    private void setProgressVisible(boolean visible) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        binding.progressCalibration.setVisibility(visibility);
        binding.tvCalibrationProgress.setVisibility(visibility);
    }

    private void syncAllViewsFromState() {
        sanitizeState();
        refreshFrequencyKnobRanges();
        applyKnob(binding.knobPitchAngleMin, pitchAngleMinDeg, true);
        applyKnob(binding.knobPitchAngleMax, pitchAngleMaxDeg, true);
        applyKnob(binding.knobFreqMin, freqMinHz, false);
        applyKnob(binding.knobFreqMax, freqMaxHz, false);
        applyKnob(binding.knobVolumeAngleMin, volumeAngleMinDeg, true);
        applyKnob(binding.knobVolumeAngleMax, volumeAngleMaxDeg, true);
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        updateSummaryText();
        updateCalibrationProgress(snapshot);
    }

    private void applyKnob(KnobControlView knob, float value, boolean isAngle) {
        knob.setValue(value);
        knob.setValueText(formatValue(value, isAngle));
    }

    private void updateSummaryText() {
        binding.tvSavedSummary.setText((hasUnsavedChanges ? "Unsaved • " : "Saved • ") + String.format(Locale.US,
                "Pitch %.1f°→%.1f° | Freq %.0f→%.0f Hz | Volume %.1f°→%.1f°",
                pitchAngleMinDeg, pitchAngleMaxDeg, freqMinHz, freqMaxHz, volumeAngleMinDeg, volumeAngleMaxDeg));
    }

    private void refreshFrequencyRangeLimit() {
        freqMaxLimitHz = SettingsStore.isExtendedFreqRangeEnabled(this) ? FREQ_EXTENDED_MAX_LIMIT : FREQ_STANDARD_MAX_LIMIT;
    }

    private void refreshFrequencyKnobRanges() {
        binding.knobFreqMin.setRange(FREQ_MIN_LIMIT, freqMaxLimitHz);
        binding.knobFreqMax.setRange(FREQ_MIN_LIMIT, freqMaxLimitHz);
    }

    private void handleNeutralCapture(boolean isPitch) {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        if (snapshot == null || !snapshot.hostReady) {
            setHostNote("Open Play first so the BLE host becomes available.");
            return;
        }
        if (isPitch && !snapshot.pitchConnected) {
            setHostNote("Connect the pitch glove first.");
            return;
        }
        if (!isPitch && !snapshot.volumeConnected) {
            setHostNote("Connect the volume glove first.");
            return;
        }
        if (isPitch) pitchNeutralCapturedThisVisit = true;
        else volumeNeutralCapturedThisVisit = true;
        BleSessionManager.requestCaptureNeutral(isPitch);
        setHostNote(isPitch ? "Pitch neutral captured. Now capture the volume glove."
                : "Volume neutral captured. If both steps are done, SAVE & PLAY will finish calibration.");
        updateCalibrationProgress(snapshot);
    }

    private void sanitizeState() {
        pitchAngleMinDeg = clamp(pitchAngleMinDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        pitchAngleMaxDeg = clamp(pitchAngleMaxDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        volumeAngleMinDeg = clamp(volumeAngleMinDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        volumeAngleMaxDeg = clamp(volumeAngleMaxDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        freqMinHz = clamp(freqMinHz, FREQ_MIN_LIMIT, freqMaxLimitHz);
        freqMaxHz = clamp(freqMaxHz, FREQ_MIN_LIMIT, freqMaxLimitHz);
        if (pitchAngleMaxDeg < pitchAngleMinDeg + ANGLE_STEP) pitchAngleMaxDeg = Math.min(ANGLE_MAX_LIMIT, pitchAngleMinDeg + ANGLE_STEP);
        if (volumeAngleMaxDeg < volumeAngleMinDeg + ANGLE_STEP) volumeAngleMaxDeg = Math.min(ANGLE_MAX_LIMIT, volumeAngleMinDeg + ANGLE_STEP);
        if (freqMaxHz < freqMinHz + FREQ_STEP) freqMaxHz = Math.min(freqMaxLimitHz, freqMinHz + FREQ_STEP);
    }

    private String connectedText(boolean connected) {
        return connected ? "CONNECTED" : "DISCONNECTED";
    }

    private void resetNeutralCaptureProgress() {
        pitchNeutralCapturedThisVisit = false;
        volumeNeutralCapturedThisVisit = false;
    }

    private void setHostNote(String text) {
        binding.tvHostNote.setText(text);
    }

    private String formatValue(float value, boolean isAngle) {
        return String.format(Locale.US, isAngle ? "%.1f°" : "%.0f Hz", value);
    }

    private String formatPlainValue(float value, boolean isAngle) {
        return String.format(Locale.US, isAngle ? "%.1f" : "%.0f", value);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface FloatGetter { float get(); }
    private interface FloatSetter { void set(float value); }
}
