package com.example.thereminglovestest2;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;

public class CalibrationActivity extends AppCompatActivity {

    private static final long UI_POLL_MS = 150L;

    private static final float DEFAULT_PITCH_ANGLE_MIN = 0.0f;
    private static final float DEFAULT_PITCH_ANGLE_MAX = 90.0f;
    private static final float DEFAULT_FREQ_MIN = 20.0f;
    private static final float DEFAULT_FREQ_MAX = 2000.0f;
    private static final float DEFAULT_VOLUME_ANGLE_MIN = 0.0f;
    private static final float DEFAULT_VOLUME_ANGLE_MAX = 90.0f;

    private static final float ANGLE_MIN_LIMIT = -90.0f;
    private static final float ANGLE_MAX_LIMIT = 90.0f;
    private static final float FREQ_MIN_LIMIT = 20.0f;
    private static final float FREQ_STANDARD_MAX_LIMIT = 2000.0f;
    private static final float FREQ_EXTENDED_MAX_LIMIT = 20000.0f;

    private static final int CALIBRATION_PROGRESS_NOT_READY = 10;
    private static final int CALIBRATION_PROGRESS_CONNECTED = 35;
    private static final int CALIBRATION_PROGRESS_ONE_NEUTRAL = 65;
    private static final int CALIBRATION_PROGRESS_READY = 100;

    private static final String UI_PREFS_NAME = "calibration_ui_prefs";
    private static final String KEY_CALIBRATION_GUIDE_LEARNED = "calibration_guide_learned";
    private static final String APP_PREFS_NAME = "theremin_prefs";
    private static final String KEY_EXTENDED_FREQUENCY_RANGE_ENABLED = "extended_frequency_range_enabled";

    private final android.os.Handler mainHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable uiPollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshLiveCalibration();
            mainHandler.postDelayed(this, UI_POLL_MS);
        }
    };

    private AppSettingsRepository settingsRepo;

    private float pitchAngleMinDeg = DEFAULT_PITCH_ANGLE_MIN;
    private float pitchAngleMaxDeg = DEFAULT_PITCH_ANGLE_MAX;
    private float freqMinHz = DEFAULT_FREQ_MIN;
    private float freqMaxHz = DEFAULT_FREQ_MAX;
    private float volumeAngleMinDeg = DEFAULT_VOLUME_ANGLE_MIN;
    private float volumeAngleMaxDeg = DEFAULT_VOLUME_ANGLE_MAX;

    private boolean pitchDirectionInverted = false;
    private boolean volumeDirectionInverted = true;
    private boolean suppressDirectionSwitchCallbacks = false;
    private float freqMaxLimitHz = FREQ_STANDARD_MAX_LIMIT;

    /**
     * Calibration changes are local-only until SAVE & PLAY.
     */
    private boolean hasUnsavedChanges = false;
    private boolean pitchNeutralCapturedThisVisit = false;
    private boolean volumeNeutralCapturedThisVisit = false;
    private boolean calibrationGuideLearned = false;

    private TextView tvLiveStatus;
    private TextView tvCalibrationProgress;
    private TextView tvHostNote;
    private TextView tvSavedSummary;
    private TextView tvPitchLive;
    private TextView tvPitchMeta;
    private TextView tvVolumeLive;
    private TextView tvVolumeMeta;

    private ProgressBar progressCalibration;

    private SwitchCompat switchPitchDirection;
    private SwitchCompat switchVolumeDirection;

    private KnobControlView knobPitchAngleMin;
    private KnobControlView knobPitchAngleMax;
    private KnobControlView knobFreqMin;
    private KnobControlView knobFreqMax;
    private KnobControlView knobVolumeAngleMin;
    private KnobControlView knobVolumeAngleMax;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsRepo = new AppSettingsRepository(this);
        refreshFrequencyRangeLimit();
        loadCalibrationUiPrefs();
        loadStateFromRepositoryOrDefaults();
        setContentView(R.layout.activity_calibration);
        bindViews();
        setupControls();
        syncAllViewsFromState();
        refreshLiveCalibration();
    }

    @Override
    protected void onStart() {
        super.onStart();

        boolean wasGuideLearned = calibrationGuideLearned;
        refreshFrequencyRangeLimit();
        loadCalibrationUiPrefs();

        if (wasGuideLearned && !calibrationGuideLearned) {
            pitchNeutralCapturedThisVisit = false;
            volumeNeutralCapturedThisVisit = false;
            setHostNote("Calibration tutorial restored. Start from step 1.");
        }

        syncAllViewsFromState();

        mainHandler.removeCallbacks(uiPollRunnable);
        mainHandler.post(uiPollRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacks(uiPollRunnable);
    }

    private void bindViews() {
        TopNavBarView topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) {
            topNavBar.setTitleText("Calibration");
        }

        tvLiveStatus = findViewById(R.id.tvLiveStatus);
        tvCalibrationProgress = findViewById(R.id.tvCalibrationProgress);
        tvHostNote = findViewById(R.id.tvHostNote);
        tvSavedSummary = findViewById(R.id.tvSavedSummary);
        tvPitchLive = findViewById(R.id.tvPitchLive);
        tvPitchMeta = findViewById(R.id.tvPitchMeta);
        tvVolumeLive = findViewById(R.id.tvVolumeLive);
        tvVolumeMeta = findViewById(R.id.tvVolumeMeta);

        progressCalibration = findViewById(R.id.progressCalibration);

        switchPitchDirection = findViewById(R.id.switchPitchDirection);
        switchVolumeDirection = findViewById(R.id.switchVolumeDirection);

        knobPitchAngleMin = findViewById(R.id.knobPitchAngleMin);
        knobPitchAngleMax = findViewById(R.id.knobPitchAngleMax);
        knobFreqMin = findViewById(R.id.knobFreqMin);
        knobFreqMax = findViewById(R.id.knobFreqMax);
        knobVolumeAngleMin = findViewById(R.id.knobVolumeAngleMin);
        knobVolumeAngleMax = findViewById(R.id.knobVolumeAngleMax);
    }

    private boolean isExtendedFrequencyRangeEnabled() {
        SharedPreferences prefs = getSharedPreferences(APP_PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_EXTENDED_FREQUENCY_RANGE_ENABLED, false);
    }

    private void refreshFrequencyRangeLimit() {
        freqMaxLimitHz = isExtendedFrequencyRangeEnabled()
                ? FREQ_EXTENDED_MAX_LIMIT
                : FREQ_STANDARD_MAX_LIMIT;
    }

    private void refreshFrequencyKnobRanges() {
        if (knobFreqMin != null) {
            knobFreqMin.setRange(FREQ_MIN_LIMIT, freqMaxLimitHz);
        }
        if (knobFreqMax != null) {
            knobFreqMax.setRange(FREQ_MIN_LIMIT, freqMaxLimitHz);
        }
    }

    private void setupControls() {
        setupKnob(
                knobPitchAngleMin,
                "Pitch Angle Min",
                ANGLE_MIN_LIMIT,
                ANGLE_MAX_LIMIT,
                0.5f,
                true,
                () -> pitchAngleMinDeg,
                value -> pitchAngleMinDeg = value
        );

        setupKnob(
                knobPitchAngleMax,
                "Pitch Angle Max",
                ANGLE_MIN_LIMIT,
                ANGLE_MAX_LIMIT,
                0.5f,
                true,
                () -> pitchAngleMaxDeg,
                value -> pitchAngleMaxDeg = value
        );

        setupKnob(
                knobFreqMin,
                "Freq Min",
                FREQ_MIN_LIMIT,
                freqMaxLimitHz,
                1.0f,
                false,
                () -> freqMinHz,
                value -> freqMinHz = value
        );

        setupKnob(
                knobFreqMax,
                "Freq Max",
                FREQ_MIN_LIMIT,
                freqMaxLimitHz,
                1.0f,
                false,
                () -> freqMaxHz,
                value -> freqMaxHz = value
        );

        setupKnob(
                knobVolumeAngleMin,
                "Volume Angle Min",
                ANGLE_MIN_LIMIT,
                ANGLE_MAX_LIMIT,
                0.5f,
                true,
                () -> volumeAngleMinDeg,
                value -> volumeAngleMinDeg = value
        );

        setupKnob(
                knobVolumeAngleMax,
                "Volume Angle Max",
                ANGLE_MIN_LIMIT,
                ANGLE_MAX_LIMIT,
                0.5f,
                true,
                () -> volumeAngleMaxDeg,
                value -> volumeAngleMaxDeg = value
        );

        refreshFrequencyKnobRanges();

        Button btnPing = findViewById(R.id.btnPing);
        btnPing.setOnClickListener(v -> {
            BleHostBridge.requestRefreshHandshake();
            setHostNote("Requested BLE refresh from the Play host.");
        });

        Button btnOpenPlay = findViewById(R.id.btnOpenPlay);
        btnOpenPlay.setOnClickListener(v -> openPlayHost());

        Button btnPitchNeutral = findViewById(R.id.btnPitchNeutral);
        btnPitchNeutral.setOnClickListener(v -> handleNeutralCapture(true));

        switchPitchDirection.setOnCheckedChangeListener((buttonView, isChecked) ->
                handleDirectionToggle(true, isChecked)
        );

        Button btnVolumeNeutral = findViewById(R.id.btnVolumeNeutral);
        btnVolumeNeutral.setOnClickListener(v -> handleNeutralCapture(false));

        switchVolumeDirection.setOnCheckedChangeListener((buttonView, isChecked) ->
                handleDirectionToggle(false, isChecked)
        );

        Button btnDefaults = findViewById(R.id.btnDefaults);
        btnDefaults.setOnClickListener(v -> {
            applyDefaultsToState();
            hasUnsavedChanges = true;
            resetNeutralCaptureProgress();
            syncAllViewsFromState();
            updateCalibrationProgress(getCalibrationSnapshot());
            setHostNote("Defaults loaded locally.");
        });

        Button btnReload = findViewById(R.id.btnReload);
        btnReload.setOnClickListener(v -> {
            loadStateFromRepositoryOrDefaults();
            hasUnsavedChanges = false;
            resetNeutralCaptureProgress();
            syncAllViewsFromState();
            updateCalibrationProgress(getCalibrationSnapshot());
            setHostNote("Loaded the last saved calibration from local storage.");
        });

        Button btnSaveAndPlay = findViewById(R.id.btnSaveAndPlay);
        btnSaveAndPlay.setOnClickListener(v -> {
            sanitizeState();
            persistSettings();
            setHostNote("Calibration saved. Returning to Play.");
            returnToExistingPlay();
        });
    }

    private void setupKnob(
            KnobControlView knob,
            String label,
            float rangeMin,
            float rangeMax,
            float step,
            boolean isAngle,
            FloatGetter getter,
            FloatSetter setter
    ) {
        knob.setLabelText(label);
        knob.setRange(rangeMin, rangeMax);
        knob.setStepSize(step);
        knob.setValue(getter.get());
        knob.setValueText(formatValue(getter.get(), isAngle));

        knob.setOnKnobValueChangedListener((view, value, fromUser) -> {
            if (!fromUser) return;
            setter.set(value);
            sanitizeState();
            hasUnsavedChanges = true;
            syncAllViewsFromState();
        });

        knob.setOnKnobCommitListener((view, value) -> {
            sanitizeState();
            hasUnsavedChanges = true;
            updateSummaryText();
        });

        knob.setOnClickListener(v -> showNumberEditDialog(label, getter.get(), setter, isAngle));
    }

    private interface FloatGetter {
        float get();
    }

    private interface FloatSetter {
        void set(float value);
    }

    private void showNumberEditDialog(String label, float current, FloatSetter setter, boolean isAngle) {
        final float min = isAngle ? ANGLE_MIN_LIMIT : FREQ_MIN_LIMIT;
        final float max = isAngle ? ANGLE_MAX_LIMIT : freqMaxLimitHz;
        final String unit = isAngle ? "°" : " Hz";
        final String initialText = String.format(Locale.US, isAngle ? "%.2f" : "%.0f", current);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(8), dp(8), dp(8), 0);

        TextView helper = new TextView(this);
        helper.setText(String.format(
                Locale.US,
                "Enter a value from %s to %s%s",
                formatRangeValue(min, isAngle),
                formatRangeValue(max, isAngle),
                unit
        ));
        helper.setTextSize(14f);
        helper.setPadding(dp(4), 0, dp(4), dp(10));

        TextInputLayout inputLayout = new TextInputLayout(this);
        inputLayout.setHint(label);
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setHelperText(isAngle ? "Decimals allowed" : "Whole numbers recommended");

        TextInputEditText input = new TextInputEditText(inputLayout.getContext());
        input.setInputType(
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED
        );
        input.setText(initialText);
        input.setSelectAllOnFocus(true);
        input.setGravity(Gravity.START);
        input.setTextSize(20f);
        input.setSingleLine(true);

        inputLayout.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        container.addView(helper, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        container.addView(inputLayout, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        final AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(label)
                .setView(container)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Apply", null)
                .create();

        dialog.setOnShowListener(d -> {
            input.requestFocus();
            input.post(() -> {
                input.requestFocus();
                if (input.getText() != null) {
                    input.setSelection(0, input.getText().length());
                }
            });

            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            positive.setOnClickListener(v -> {
                String raw = input.getText() == null ? "" : input.getText().toString().trim();
                if (raw.isEmpty()) {
                    inputLayout.setError("Enter a value.");
                    return;
                }

                try {
                    float value = Float.parseFloat(raw);
                    if (value < min || value > max) {
                        inputLayout.setError(String.format(
                                Locale.US,
                                "Use %s to %s%s",
                                formatRangeValue(min, isAngle),
                                formatRangeValue(max, isAngle),
                                unit
                        ));
                        return;
                    }

                    inputLayout.setError(null);
                    setter.set(value);
                    sanitizeState();
                    hasUnsavedChanges = true;
                    syncAllViewsFromState();
                    dialog.dismiss();
                } catch (Exception ignored) {
                    inputLayout.setError("Enter a valid number.");
                }
            });
        });

        dialog.show();
    }

    private String formatRangeValue(float value, boolean isAngle) {
        return String.format(Locale.US, isAngle ? "%.1f" : "%.0f", value);
    }

    private void refreshLiveCalibration() {
        BleHostBridge.CalibrationUiSnapshot snapshot = getCalibrationSnapshot();

        if (snapshot == null || !snapshot.hostReady) {
            tvLiveStatus.setText("Live status: host unavailable");

            if (!hasUnsavedChanges) {
                setHostNote("Open Play once to initialize BLE, then come back here.");
            }

            tvPitchLive.setText("Pitch ACTIVE_DELTA_DEG: —");
            tvPitchMeta.setText(String.format(
                    Locale.US,
                    "Conn: — | Dir: %s | Neutral: —",
                    pitchDirectionInverted ? "NEGATIVE" : "POSITIVE"
            ));

            tvVolumeLive.setText("Volume ACTIVE_DELTA_DEG: —");
            tvVolumeMeta.setText(String.format(
                    Locale.US,
                    "Conn: — | Dir: %s | Neutral: —",
                    volumeDirectionInverted ? "NEGATIVE" : "POSITIVE"
            ));

            syncDirectionToggleViews(false);
            updateCalibrationProgress(null);
            return;
        }

        String liveStatusText = snapshot.bluetoothEnabled
                ? ("Pitch: " + (snapshot.pitchConnected ? "CONNECTED" : "DISCONNECTED")
                + " | Volume: " + (snapshot.volumeConnected ? "CONNECTED" : "DISCONNECTED"))
                : "BLUETOOTH OFF";

        tvLiveStatus.setText(liveStatusText);

        if (!hasUnsavedChanges) {
            setHostNote("Tap a value to type. Rotate a knob to adjust calibration. SAVE & PLAY stores the current values.");
        }

        tvPitchLive.setText(String.format(Locale.US, "Pitch ACTIVE_DELTA_DEG: %.2f°", snapshot.pitchActiveDeltaDeg));
        tvPitchMeta.setText(String.format(
                Locale.US,
                "Conn: %s | Dir: %s | Neutral: %.2f°",
                snapshot.pitchConnected ? "ON" : "OFF",
                snapshot.pitchDirectionText,
                snapshot.pitchNeutralRollDeg
        ));

        tvVolumeLive.setText(String.format(Locale.US, "Volume ACTIVE_DELTA_DEG: %.2f°", snapshot.volumeActiveDeltaDeg));
        tvVolumeMeta.setText(String.format(
                Locale.US,
                "Conn: %s | Dir: %s | Neutral: %.2f°",
                snapshot.volumeConnected ? "ON" : "OFF",
                snapshot.volumeDirectionText,
                snapshot.volumeNeutralRollDeg
        ));

        if (directionTextIsKnown(snapshot.pitchDirectionText)) {
            pitchDirectionInverted = directionTextMeansInverted(snapshot.pitchDirectionText);
        }
        if (directionTextIsKnown(snapshot.volumeDirectionText)) {
            volumeDirectionInverted = directionTextMeansInverted(snapshot.volumeDirectionText);
        }

        syncDirectionToggleViews(snapshot.bluetoothEnabled);
        updateCalibrationProgress(snapshot);
    }

    private void updateCalibrationProgress(BleHostBridge.CalibrationUiSnapshot snapshot) {
        if (progressCalibration == null || tvCalibrationProgress == null) {
            return;
        }

        if (calibrationGuideLearned) {
            progressCalibration.setVisibility(android.view.View.GONE);
            tvCalibrationProgress.setVisibility(android.view.View.GONE);
            return;
        }

        boolean hostReady = snapshot != null && snapshot.hostReady;
        boolean bothConnected = hostReady && snapshot.pitchConnected && snapshot.volumeConnected;

        int progress;
        String label;

        if (!hostReady) {
            progress = 0;
            label = "Progress 0/3 • Open Play once so the BLE host becomes available.";
        } else if (!bothConnected) {
            progress = CALIBRATION_PROGRESS_NOT_READY;
            label = "Progress 0/3 • Connect both gloves before calibrating.";
        } else if (!pitchNeutralCapturedThisVisit && !volumeNeutralCapturedThisVisit) {
            progress = CALIBRATION_PROGRESS_CONNECTED;
            label = "Progress 1/3 • Both gloves connected. Capture pitch neutral first.";
        } else if (pitchNeutralCapturedThisVisit ^ volumeNeutralCapturedThisVisit) {
            progress = CALIBRATION_PROGRESS_ONE_NEUTRAL;
            label = "Progress 2/3 • One neutral captured. Capture the remaining glove.";
        } else {
            progress = CALIBRATION_PROGRESS_READY;
            label = "Progress 3/3 • Calibration steps complete.";
            calibrationGuideLearned = true;
            saveCalibrationUiPrefs();
            progressCalibration.setVisibility(android.view.View.GONE);
            tvCalibrationProgress.setVisibility(android.view.View.GONE);
            return;
        }

        progressCalibration.setVisibility(android.view.View.VISIBLE);
        tvCalibrationProgress.setVisibility(android.view.View.VISIBLE);
        progressCalibration.setProgress(progress);
        tvCalibrationProgress.setText(label);
    }

    private boolean directionTextIsKnown(String directionText) {
        if (directionText == null) return false;
        String d = directionText.trim().toUpperCase(Locale.US);
        return d.contains("POS") || d.contains("NEG");
    }

    private boolean directionTextMeansInverted(String directionText) {
        return directionText != null
                && directionText.trim().toUpperCase(Locale.US).contains("NEG");
    }

    private void syncDirectionToggleViews(boolean enabled) {
        suppressDirectionSwitchCallbacks = true;
        switchPitchDirection.setChecked(pitchDirectionInverted);
        switchVolumeDirection.setChecked(volumeDirectionInverted);
        suppressDirectionSwitchCallbacks = false;

        switchPitchDirection.setEnabled(enabled);
        switchVolumeDirection.setEnabled(enabled);
    }

    private void loadStateFromRepositoryOrDefaults() {
        AppSettings s = settingsRepo.load();
        if (s == null) {
            applyDefaultsToState();
            pitchDirectionInverted = false;
            volumeDirectionInverted = true;
            return;
        }

        pitchAngleMinDeg = s.pitchAngleMinDeg;
        pitchAngleMaxDeg = s.pitchAngleMaxDeg;
        freqMinHz = s.freqMinHz;
        freqMaxHz = s.freqMaxHz;
        volumeAngleMinDeg = s.volumeAngleMinDeg;
        volumeAngleMaxDeg = s.volumeAngleMaxDeg;
        pitchDirectionInverted = s.pitchDirectionInverted;
        volumeDirectionInverted = s.volumeDirectionInverted;

        sanitizeState();
    }

    private void applyDefaultsToState() {
        pitchAngleMinDeg = DEFAULT_PITCH_ANGLE_MIN;
        pitchAngleMaxDeg = DEFAULT_PITCH_ANGLE_MAX;
        freqMinHz = DEFAULT_FREQ_MIN;
        freqMaxHz = DEFAULT_FREQ_MAX;
        volumeAngleMinDeg = DEFAULT_VOLUME_ANGLE_MIN;
        volumeAngleMaxDeg = DEFAULT_VOLUME_ANGLE_MAX;

        sanitizeState();
    }

    private void syncAllViewsFromState() {
        sanitizeState();

        refreshFrequencyKnobRanges();

        knobPitchAngleMin.setValue(pitchAngleMinDeg);
        knobPitchAngleMax.setValue(pitchAngleMaxDeg);
        knobFreqMin.setValue(freqMinHz);
        knobFreqMax.setValue(freqMaxHz);
        knobVolumeAngleMin.setValue(volumeAngleMinDeg);
        knobVolumeAngleMax.setValue(volumeAngleMaxDeg);

        knobPitchAngleMin.setValueText(formatValue(pitchAngleMinDeg, true));
        knobPitchAngleMax.setValueText(formatValue(pitchAngleMaxDeg, true));
        knobFreqMin.setValueText(formatValue(freqMinHz, false));
        knobFreqMax.setValueText(formatValue(freqMaxHz, false));
        knobVolumeAngleMin.setValueText(formatValue(volumeAngleMinDeg, true));
        knobVolumeAngleMax.setValueText(formatValue(volumeAngleMaxDeg, true));

        syncDirectionToggleViews(BleHostBridge.isBleHostAvailable());
        updateSummaryText();
        updateCalibrationProgress(getCalibrationSnapshot());
    }

    private String formatValue(float value, boolean isAngle) {
        return isAngle
                ? String.format(Locale.US, "%.1f°", value)
                : String.format(Locale.US, "%.0f Hz", value);
    }

    private void persistSettings() {
        sanitizeState();

        AppSettings s = settingsRepo.load();
        if (s == null) {
            s = new AppSettings();
        }

        s.pitchAngleMinDeg = pitchAngleMinDeg;
        s.pitchAngleMaxDeg = pitchAngleMaxDeg;
        s.freqMinHz = freqMinHz;
        s.freqMaxHz = freqMaxHz;
        s.volumeAngleMinDeg = volumeAngleMinDeg;
        s.volumeAngleMaxDeg = volumeAngleMaxDeg;
        s.pitchDirectionInverted = pitchDirectionInverted;
        s.volumeDirectionInverted = volumeDirectionInverted;

        settingsRepo.save(s);
        hasUnsavedChanges = false;
        updateSummaryText();
        setHostNote("Calibration saved.");
    }

    private void updateSummaryText() {
        String summary = String.format(
                Locale.US,
                "Pitch %.1f°→%.1f° | Freq %.0f→%.0f Hz | Volume %.1f°→%.1f°",
                pitchAngleMinDeg,
                pitchAngleMaxDeg,
                freqMinHz,
                freqMaxHz,
                volumeAngleMinDeg,
                volumeAngleMaxDeg
        );

        if (hasUnsavedChanges) {
            tvSavedSummary.setText("Unsaved • " + summary);
        } else {
            tvSavedSummary.setText("Saved • " + summary);
        }
    }

    private void loadCalibrationUiPrefs() {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE);
        calibrationGuideLearned = prefs.getBoolean(KEY_CALIBRATION_GUIDE_LEARNED, false);
    }

    private void saveCalibrationUiPrefs() {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_CALIBRATION_GUIDE_LEARNED, calibrationGuideLearned).apply();
    }

    private void sanitizeState() {
        pitchAngleMinDeg = clamp(pitchAngleMinDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        pitchAngleMaxDeg = clamp(pitchAngleMaxDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        volumeAngleMinDeg = clamp(volumeAngleMinDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        volumeAngleMaxDeg = clamp(volumeAngleMaxDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        freqMinHz = clamp(freqMinHz, FREQ_MIN_LIMIT, freqMaxLimitHz);
        freqMaxHz = clamp(freqMaxHz, FREQ_MIN_LIMIT, freqMaxLimitHz);

        if (pitchAngleMaxDeg < pitchAngleMinDeg + 0.5f) {
            pitchAngleMaxDeg = Math.min(ANGLE_MAX_LIMIT, pitchAngleMinDeg + 0.5f);
        }

        if (volumeAngleMaxDeg < volumeAngleMinDeg + 0.5f) {
            volumeAngleMaxDeg = Math.min(ANGLE_MAX_LIMIT, volumeAngleMinDeg + 0.5f);
        }

        if (freqMaxHz < freqMinHz + 1.0f) {
            freqMaxHz = Math.min(freqMaxLimitHz, freqMinHz + 1.0f);
        }
    }

    private void handleNeutralCapture(boolean isPitch) {
        BleHostBridge.CalibrationUiSnapshot snapshot = getCalibrationSnapshot();
        markNeutralCapturedIfConnected(isPitch, snapshot);
        BleHostBridge.requestCaptureNeutral(isPitch);

        if (isPitch) {
            setHostNote("Pitch neutral captured. Now capture the volume glove.");
        } else {
            setHostNote("Volume neutral captured. If both steps are done, SAVE & PLAY will finish calibration.");
        }

        updateCalibrationProgress(snapshot);
    }

    private void handleDirectionToggle(boolean isPitch, boolean isChecked) {
        if (suppressDirectionSwitchCallbacks) return;

        if (!BleHostBridge.isBleHostAvailable()) {
            setHostNote("Open Play first so the BLE host can apply direction changes.");
            syncDirectionToggleViews(false);
            return;
        }

        if (isPitch) {
            pitchDirectionInverted = isChecked;
        } else {
            volumeDirectionInverted = isChecked;
        }

        BleHostBridge.requestToggleDirection(isPitch);
        hasUnsavedChanges = true;
        setHostNote(isPitch ? "Pitch direction toggle requested." : "Volume direction toggle requested.");
        updateSummaryText();
    }

    private BleHostBridge.CalibrationUiSnapshot getCalibrationSnapshot() {
        return BleHostBridge.getCalibrationUiSnapshot();
    }

    private void markNeutralCapturedIfConnected(boolean isPitch, BleHostBridge.CalibrationUiSnapshot snapshot) {
        if (snapshot == null || !snapshot.hostReady) {
            return;
        }

        if (isPitch && snapshot.pitchConnected) {
            pitchNeutralCapturedThisVisit = true;
        } else if (!isPitch && snapshot.volumeConnected) {
            volumeNeutralCapturedThisVisit = true;
        }
    }

    private void resetNeutralCaptureProgress() {
        pitchNeutralCapturedThisVisit = false;
        volumeNeutralCapturedThisVisit = false;
    }

    private void openPlayHost() {
        NavigationUtils.openScreen(this, MainActivity.class);
    }

    private void returnToExistingPlay() {
        openPlayHost();
        finish();
    }

    private void setHostNote(String text) {
        if (tvHostNote != null) {
            tvHostNote.setText(text);
        }
    }

    private float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
