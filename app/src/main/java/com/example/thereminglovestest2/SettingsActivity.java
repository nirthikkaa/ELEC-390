package com.example.thereminglovestest2;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.example.thereminglovestest2.databinding.ActivitySettingsBinding;

/**
 * Small settings screen for the demo app.
 *
 * Kept intentionally plain: read current prefs, show them clearly,
 * and write them back without hidden side effects.
 */
public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;

    private static final float STANDARD_FREQ_MAX_HZ = 2000f;

    private SettingsStore settingsStore;

    private SwitchCompat switchBackgroundAudio;
    private SwitchCompat switchExtendedFrequencyRange;
    private TextView tvBackgroundAudioState;
    private TextView tvTutorialState;
    private TextView tvFrequencyRangeState;

    private boolean suppressBackgroundAudioCallback;
    private boolean suppressFrequencyRangeCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        settingsStore = new SettingsStore(this);

        bindViews();
        wireActions();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void bindViews() {
        binding.topNavBar.setTitleText("Settings");

        tvBackgroundAudioState = binding.tvBackgroundAudioState;
        tvTutorialState = binding.tvTutorialState;
        tvFrequencyRangeState = binding.tvFrequencyRangeState;

        switchBackgroundAudio = binding.switchBackgroundAudio;
        switchExtendedFrequencyRange = binding.switchExtendedFrequencyRange;
    }

    private void wireActions() {
        if (switchBackgroundAudio != null) {
            switchBackgroundAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressBackgroundAudioCallback) {
                    return;
                }

                AppPrefs.setBgAudioEnabled(this, isChecked);
                refreshUi();
                showToast(isChecked ? "Background audio enabled" : "Background audio disabled");
            });
        }

        if (switchExtendedFrequencyRange != null) {
            switchExtendedFrequencyRange.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressFrequencyRangeCallback) {
                    return;
                }

                AppPrefs.setExtendedFreqRangeEnabled(this, isChecked);
                if (!isChecked) {
                    clampSavedFrequencyRangeToStandardCeiling();
                }

                refreshUi();
                showToast(isChecked
                        ? "Frequency ceiling raised to 20,000 Hz"
                        : "Frequency ceiling reset to 2,000 Hz");
            });
        }

        Button btnShowTutorialAgain = binding.btnShowTutorialAgain;
        if (btnShowTutorialAgain != null) {
            btnShowTutorialAgain.setOnClickListener(v -> {
                AppPrefs.setCalibrationGuideLearned(this, false);
                refreshUi();
                showToast("Calibration guide will show again");
            });
        }

        Button btnOpenCalibration = binding.btnOpenCalibration;
        if (btnOpenCalibration != null) {
            btnOpenCalibration.setOnClickListener(v ->
                    NavigationUtils.openScreen(this, CalibrationActivity.class)
            );
        }
    }

    private void refreshUi() {
        boolean bgAudioEnabled = AppPrefs.isBgAudioEnabled(this);
        boolean tutorialLearned = AppPrefs.isCalibrationGuideLearned(this);
        boolean extendedRangeEnabled = AppPrefs.isExtendedFreqRangeEnabled(this);

        setSwitchState(switchBackgroundAudio, bgAudioEnabled, true);
        setSwitchState(switchExtendedFrequencyRange, extendedRangeEnabled, false);

        if (tvBackgroundAudioState != null) {
            tvBackgroundAudioState.setText(bgAudioEnabled
                    ? "Background audio is ON."
                    : "Background audio is OFF.");
        }

        if (tvTutorialState != null) {
            tvTutorialState.setText(tutorialLearned
                    ? "The calibration guide is hidden because you already finished it once."
                    : "The calibration guide is enabled and will appear in Calibration.");
        }

        if (tvFrequencyRangeState != null) {
            tvFrequencyRangeState.setText(extendedRangeEnabled
                    ? "Calibration and Play can use 20 Hz to 20,000 Hz."
                    : "Calibration and Play currently use 20 Hz to 2,000 Hz.");
        }
    }

    private void setSwitchState(SwitchCompat switchView, boolean checked, boolean isBackgroundAudio) {
        if (switchView == null) {
            return;
        }

        if (isBackgroundAudio) {
            suppressBackgroundAudioCallback = true;
            switchView.setChecked(checked);
            suppressBackgroundAudioCallback = false;
        } else {
            suppressFrequencyRangeCallback = true;
            switchView.setChecked(checked);
            suppressFrequencyRangeCallback = false;
        }
    }

    private void clampSavedFrequencyRangeToStandardCeiling() {
        AppSettings settings = settingsStore.load();

        boolean changed = false;
        if (settings.freqMinHz > STANDARD_FREQ_MAX_HZ - 1f) {
            settings.freqMinHz = STANDARD_FREQ_MAX_HZ - 1f;
            changed = true;
        }
        if (settings.freqMaxHz > STANDARD_FREQ_MAX_HZ) {
            settings.freqMaxHz = STANDARD_FREQ_MAX_HZ;
            changed = true;
        }
        if (settings.freqMaxHz < settings.freqMinHz + 1f) {
            settings.freqMinHz = STANDARD_FREQ_MAX_HZ - 1f;
            settings.freqMaxHz = STANDARD_FREQ_MAX_HZ;
            changed = true;
        }

        if (changed) {
            settingsStore.save(settings);
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
