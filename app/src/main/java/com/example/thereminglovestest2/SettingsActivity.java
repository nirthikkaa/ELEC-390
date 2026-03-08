package com.example.thereminglovestest2;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivitySettingsBinding;

public class SettingsActivity extends AppCompatActivity {

    private static final float STANDARD_FREQ_MAX_HZ = 2000f;

    private ActivitySettingsBinding binding;
    private SettingsStore store;
    private boolean quiet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        store = new SettingsStore(getApplicationContext());
        binding.topNavBar.setTitleText("Settings");
        wireUi();
        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void wireUi() {
        binding.switchBackgroundAudio.setOnCheckedChangeListener((v, on) ->
                onToggle(() -> SettingsStore.setBgAudioEnabled(this, on),
                        "Background audio " + (on ? "enabled" : "disabled")));

        binding.switchExtendedFrequencyRange.setOnCheckedChangeListener((v, on) ->
                onToggle(() -> {
                    SettingsStore.setExtendedFreqRangeEnabled(this, on);
                    if (!on) clampSavedFrequencyRange();
                }, on ? "Frequency ceiling raised to 20,000 Hz"
                        : "Frequency ceiling reset to 2,000 Hz"));

        binding.btnShowTutorialAgain.setOnClickListener(v -> {
            SettingsStore.setCalibrationGuideLearned(this, false);
            refreshUi();
            toast("Calibration guide will show again");
        });
    }

    private void onToggle(Runnable save, String message) {
        if (quiet) return;
        save.run();
        refreshUi();
        toast(message);
    }

    private void refreshUi() {
        boolean bg = SettingsStore.isBgAudioEnabled(this);
        boolean guide = SettingsStore.isCalibrationGuideLearned(this);
        boolean extended = SettingsStore.isExtendedFreqRangeEnabled(this);

        quietly(() -> {
            binding.switchBackgroundAudio.setChecked(bg);
            binding.switchExtendedFrequencyRange.setChecked(extended);
        });

        binding.tvBackgroundAudioState.setText("Background audio is " + (bg ? "ON." : "OFF."));
        binding.tvTutorialState.setText(guide
                ? "The calibration guide is hidden because you already finished it once."
                : "The calibration guide is enabled and will appear in Calibration.");
        binding.tvFrequencyRangeState.setText(extended
                ? "Calibration and Play can use 20 Hz to 20,000 Hz."
                : "Calibration and Play currently use 20 Hz to 2,000 Hz.");
    }

    private void quietly(Runnable work) {
        quiet = true;
        try {
            work.run();
        } finally {
            quiet = false;
        }
    }

    private void clampSavedFrequencyRange() {
        AppSettings s = store.load();
        float min = Math.min(s.freqMinHz, STANDARD_FREQ_MAX_HZ - 1f);
        float max = Math.min(s.freqMaxHz, STANDARD_FREQ_MAX_HZ);
        if (max < min + 1f) {
            min = STANDARD_FREQ_MAX_HZ - 1f;
            max = STANDARD_FREQ_MAX_HZ;
        }
        if (min != s.freqMinHz || max != s.freqMaxHz) {
            s.freqMinHz = min;
            s.freqMaxHz = max;
            store.save(s);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
