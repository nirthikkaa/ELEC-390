package com.example.thereminglovestest2;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "theremin_prefs";
    private static final String PREF_BG_AUDIO_ENABLED = "bg_audio_enabled";
    private static final String PREF_EXTENDED_FREQ_RANGE_ENABLED = "extended_frequency_range_enabled";

    private static final String UI_PREFS_NAME = "calibration_ui_prefs";
    private static final String KEY_CALIBRATION_GUIDE_LEARNED = "calibration_guide_learned";

    private static final float STANDARD_FREQ_MAX_HZ = 2000f;

    private SwitchCompat switchBackgroundAudio;
    private SwitchCompat switchExtendedFrequencyRange;
    private TextView tvBackgroundAudioState;
    private TextView tvTutorialState;
    private TextView tvFrequencyRangeState;

    private boolean suppressBackgroundAudioCallback = false;
    private boolean suppressFrequencyRangeCallback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        bindViews();
        wireActions();
        refreshUiFromPrefs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUiFromPrefs();
    }

    private void bindViews() {
        TopNavBarView topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) {
            topNavBar.setTitleText("Settings");
        }

        tvBackgroundAudioState = findViewById(R.id.tvBackgroundAudioState);
        tvTutorialState = findViewById(R.id.tvTutorialState);
        tvFrequencyRangeState = findViewById(R.id.tvFrequencyRangeState);

        switchBackgroundAudio = findViewById(R.id.switchBackgroundAudio);
        switchExtendedFrequencyRange = findViewById(R.id.switchExtendedFrequencyRange);
    }

    private void wireActions() {
        if (switchBackgroundAudio != null) {
            switchBackgroundAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressBackgroundAudioCallback) {
                    return;
                }

                setBackgroundAudioEnabled(isChecked);
                BleHostBridge.requestRefreshBackgroundAudioPreference();
                refreshUiFromPrefs();
                showToast(isChecked ? "Background audio enabled" : "Background audio disabled");
            });
        }

        if (switchExtendedFrequencyRange != null) {
            switchExtendedFrequencyRange.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressFrequencyRangeCallback) {
                    return;
                }

                setExtendedFrequencyRangeEnabled(isChecked);

                if (!isChecked) {
                    clampSavedFrequencyRangeToStandardCeiling();
                }

                refreshUiFromPrefs();
                showToast(isChecked
                        ? "Frequency ceiling raised to 20,000 Hz"
                        : "Frequency ceiling reset to 2,000 Hz");
            });
        }

        Button btnShowTutorialAgain = findViewById(R.id.btnShowTutorialAgain);
        if (btnShowTutorialAgain != null) {
            btnShowTutorialAgain.setOnClickListener(v -> {
                setCalibrationGuideLearned(false);
                refreshUiFromPrefs();
                showToast("Calibration guide will show again");
            });
        }

        Button btnOpenCalibration = findViewById(R.id.btnOpenCalibration);
        if (btnOpenCalibration != null) {
            btnOpenCalibration.setOnClickListener(v ->
                    NavigationUtils.openScreen(this, CalibrationActivity.class)
            );
        }
    }

    private void refreshUiFromPrefs() {
        boolean bgAudioEnabled = isBackgroundAudioEnabled();
        boolean tutorialLearned = isCalibrationGuideLearned();
        boolean extendedFrequencyRangeEnabled = isExtendedFrequencyRangeEnabled();

        if (tvBackgroundAudioState != null) {
            tvBackgroundAudioState.setText(
                    bgAudioEnabled
                            ? "Background audio is ON."
                            : "Background audio is OFF."
            );
        }

        if (switchBackgroundAudio != null) {
            suppressBackgroundAudioCallback = true;
            switchBackgroundAudio.setChecked(bgAudioEnabled);
            suppressBackgroundAudioCallback = false;
        }

        if (tvTutorialState != null) {
            tvTutorialState.setText(
                    tutorialLearned
                            ? "The calibration guide is hidden because it has already been completed once."
                            : "The calibration guide is enabled and will appear in Calibration."
            );
        }

        if (tvFrequencyRangeState != null) {
            tvFrequencyRangeState.setText(
                    extendedFrequencyRangeEnabled
                            ? "Calibration and Play can use 20 Hz to 20,000 Hz."
                            : "Calibration and Play currently use 20 Hz to 2,000 Hz."
            );
        }

        if (switchExtendedFrequencyRange != null) {
            suppressFrequencyRangeCallback = true;
            switchExtendedFrequencyRange.setChecked(extendedFrequencyRangeEnabled);
            suppressFrequencyRangeCallback = false;
        }
    }

    private SharedPreferences getAppPrefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private SharedPreferences getUiPrefs() {
        return getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE);
    }

    private boolean isBackgroundAudioEnabled() {
        return getAppPrefs().getBoolean(PREF_BG_AUDIO_ENABLED, true);
    }

    private void setBackgroundAudioEnabled(boolean enabled) {
        getAppPrefs().edit().putBoolean(PREF_BG_AUDIO_ENABLED, enabled).apply();
    }

    private boolean isExtendedFrequencyRangeEnabled() {
        return getAppPrefs().getBoolean(PREF_EXTENDED_FREQ_RANGE_ENABLED, false);
    }

    private void setExtendedFrequencyRangeEnabled(boolean enabled) {
        getAppPrefs().edit().putBoolean(PREF_EXTENDED_FREQ_RANGE_ENABLED, enabled).apply();
    }

    private boolean isCalibrationGuideLearned() {
        return getUiPrefs().getBoolean(KEY_CALIBRATION_GUIDE_LEARNED, false);
    }

    private void setCalibrationGuideLearned(boolean learned) {
        getUiPrefs().edit().putBoolean(KEY_CALIBRATION_GUIDE_LEARNED, learned).apply();
    }

    private void clampSavedFrequencyRangeToStandardCeiling() {
        AppSettingsRepository repo = new AppSettingsRepository(this);
        AppSettings settings = repo.load();
        if (settings == null) {
            return;
        }

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
            repo.save(settings);
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
