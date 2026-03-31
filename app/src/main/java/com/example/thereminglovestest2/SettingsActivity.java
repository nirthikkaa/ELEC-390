package com.example.thereminglovestest2;

import android.os.Bundle;
import android.os.StatFs;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.slider.Slider;

import java.io.File;
import java.util.Locale;

import com.example.thereminglovestest2.databinding.ActivitySettingsBinding;

public class SettingsActivity extends AppCompatActivity {

    private static final float STANDARD_FREQ_MAX_HZ = 2000f;

    private ActivitySettingsBinding binding;
    private SettingsStore store;
    private boolean quiet;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        store = new SettingsStore(getApplicationContext());
        binding.topNavBar.setTitleText("Settings");
        bindActions();
        refreshUi();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void bindActions() {
        binding.switchBackgroundAudio.setOnCheckedChangeListener((v, on) ->
                onToggle(() -> SettingsStore.setBgAudioEnabled(this, on), "Background audio " + (on ? "enabled" : "disabled")));

        binding.switchExtendedFrequencyRange.setOnCheckedChangeListener((v, on) -> onToggle(() -> {
            SettingsStore.setExtendedFreqRangeEnabled(this, on);
            if (!on) clampSavedFrequencyRange();
        }, on ? "Frequency ceiling raised to 20,000 Hz" : "Frequency ceiling reset to 2,000 Hz"));

        binding.switchPitchDirection.setOnCheckedChangeListener((v, on) ->
                onToggle(() -> saveDirection(true, on), "Pitch direction set to " + directionLabel(on)));
        binding.switchVolumeDirection.setOnCheckedChangeListener((v, on) ->
                onToggle(() -> saveDirection(false, on), "Volume direction set to " + directionLabel(on)));

        binding.btnShowTutorialAgain.setOnClickListener(v -> {
            SettingsStore.setCalibrationGuideLearned(this, false);
            refreshUi();
            toast("Calibration guide will show again");
        });

        binding.switchRenameDialog.setOnCheckedChangeListener((v, on) ->
                onToggle(() -> SettingsStore.setRenameDialogEnabled(this, on),
                        "Rename dialog " + (on ? "enabled" : "disabled")));

        binding.sliderSensitivity.addOnChangeListener((slider, value, fromUser) -> {
            float curve = AppSettings.clampSensitivityResponseCurve(value);
            updateSensitivityUi(curve);
            if (!quiet && fromUser) saveSensitivityCurve(curve);
        });
        binding.sliderSensitivity.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override public void onStartTrackingTouch(Slider slider) {}
            @Override public void onStopTrackingTouch(Slider slider) {
                if (quiet) return;
                float curve = AppSettings.clampSensitivityResponseCurve(slider.getValue());
                toast("Sensitivity: " + AppSettings.prettySensitivityCurve(curve)
                        + " (" + formatSensitivityCurve(curve) + ")");
            }
        });

        binding.chipGroupCompression.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (quiet || checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            String q;
            if (id == R.id.chipCompressionLossless)    q = AppSettings.COMPRESSION_LOSSLESS;
            else if (id == R.id.chipCompressionMedium) q = AppSettings.COMPRESSION_MEDIUM;
            else if (id == R.id.chipCompressionLow)    q = AppSettings.COMPRESSION_LOW;
            else                                        q = AppSettings.COMPRESSION_HIGH;
            SettingsStore.setAudioCompression(this, q);
            updateCompressionEstimate(q);
            toast("Recording quality: " + compressionLabel(q));
        });
    }

    private void saveDirection(boolean isPitch, boolean inverted) {
        AppSettings settings = store.load();
        if (isPitch) settings.pitchDirectionInverted = inverted;
        else settings.volumeDirectionInverted = inverted;
        store.save(settings);
        BleSessionManager.setDesiredDirection(isPitch, inverted);
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
        AppSettings settings = store.load();

        String compression = SettingsStore.getAudioCompression(this);
        boolean renameDialog = SettingsStore.isRenameDialogEnabled(this);

        quietly(() -> {
            binding.switchBackgroundAudio.setChecked(bg);
            binding.switchExtendedFrequencyRange.setChecked(extended);
            binding.switchPitchDirection.setChecked(settings.pitchDirectionInverted);
            binding.switchVolumeDirection.setChecked(settings.volumeDirectionInverted);
            binding.switchRenameDialog.setChecked(renameDialog);
            int chipId;
            switch (compression) {
                case AppSettings.COMPRESSION_LOSSLESS: chipId = R.id.chipCompressionLossless; break;
                case AppSettings.COMPRESSION_MEDIUM:   chipId = R.id.chipCompressionMedium;   break;
                case AppSettings.COMPRESSION_LOW:      chipId = R.id.chipCompressionLow;      break;
                default:                               chipId = R.id.chipCompressionHigh;     break;
            }
            binding.chipGroupCompression.check(chipId);
            binding.sliderSensitivity.setValue(AppSettings.clampSensitivityResponseCurve(settings.sensitivityResponseCurve));
        });

        binding.tvBackgroundAudioState.setText("Background audio is " + (bg ? "ON." : "OFF."));
        binding.tvTutorialState.setText(guide
                ? "The calibration guide is hidden because you already finished it once."
                : "The calibration guide is enabled and will appear in Calibration.");
        binding.tvFrequencyRangeState.setText(extended
                ? "Calibration and Play can use 20 Hz to 20,000 Hz."
                : "Calibration and Play currently use 20 Hz to 2,000 Hz.");
        binding.tvDirectionState.setText("Pitch: " + directionLabel(settings.pitchDirectionInverted)
                + " | Volume: " + directionLabel(settings.volumeDirectionInverted));
        updateSensitivityUi(settings.sensitivityResponseCurve);

        updateCompressionEstimate(compression);
        refreshStorageUi();
    }

    private void updateCompressionEstimate(String q) {
        String size, detail;
        switch (q) {
            case AppSettings.COMPRESSION_LOSSLESS:
                size = "~11 MB / min"; detail = "WAV · 48 kHz · stereo · lossless (PCM)"; break;
            case AppSettings.COMPRESSION_MEDIUM:
                size = "~1.4 MB / min"; detail = "AAC · 48 kHz · stereo · 192 kbps"; break;
            case AppSettings.COMPRESSION_LOW:
                size = "~0.9 MB / min"; detail = "AAC · 48 kHz · stereo · 128 kbps"; break;
            default: // HIGH
                size = "~2.4 MB / min"; detail = "AAC · 48 kHz · stereo · 320 kbps"; break;
        }
        binding.tvCompressionEstimate.setText(size);
        binding.tvCompressionDetail.setText(detail);
    }

    private static String compressionLabel(String q) {
        switch (q) {
            case AppSettings.COMPRESSION_LOSSLESS: return "Lossless";
            case AppSettings.COMPRESSION_MEDIUM:   return "Medium";
            case AppSettings.COMPRESSION_LOW:      return "Low";
            default:                               return "High";
        }
    }

    private void refreshStorageUi() {
        File recordingsDir = new File(getFilesDir(), "recordings");
        long usedBytes = 0;
        if (recordingsDir.exists()) {
            File[] files = recordingsDir.listFiles();
            if (files != null) {
                for (File f : files) usedBytes += f.length();
            }
        }

        StatFs stat = new StatFs(getFilesDir().getPath());
        long freeBytes = stat.getAvailableBytes();
        long totalBytes = stat.getTotalBytes();

        int progressPercent = totalBytes > 0 ? (int) ((totalBytes - freeBytes) * 100 / totalBytes) : 0;
        binding.pbStorageUsed.setProgress(progressPercent);
        binding.tvRecordingsStorageUsed.setText("Recordings: " + formatSize(usedBytes));
        binding.tvDeviceStorageFree.setText("Device free: " + formatSize(freeBytes) + " of " + formatSize(totalBytes));
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024));
        return String.format(Locale.getDefault(), "%.2f GB", bytes / (1024f * 1024 * 1024));
    }

    private static String directionLabel(boolean inverted) { return inverted ? "NEGATIVE" : "POSITIVE"; }

    private void saveSensitivityCurve(float curve) {
        float clamped = AppSettings.clampSensitivityResponseCurve(curve);
        AppSettings settings = store.load();
        if (Math.abs(settings.sensitivityResponseCurve - clamped) < 0.0001f) return;
        settings.sensitivityResponseCurve = clamped;
        settings.sensitivityLevel = AppSettings.curveToLegacySensitivityLevel(clamped);
        store.save(settings);
        ThereminBackgroundAudioService.setSensitivityResponseCurve(clamped);
    }

    private void updateSensitivityUi(float curve) {
        float clamped = AppSettings.clampSensitivityResponseCurve(curve);
        binding.tvSensitivityValue.setText(AppSettings.prettySensitivityCurve(clamped)
                + " | " + formatSensitivityCurve(clamped));
        binding.tvSensitivityDetail.setText(clamped < 1.0f
                ? "Smaller hand motions create bigger pitch and volume changes."
                : clamped > 1.0f
                ? "Larger hand motions are needed, which gives you finer control."
                : "Balanced response across the full calibrated range.");
    }

    private String formatSensitivityCurve(float curve) {
        return String.format(Locale.getDefault(), "%.2f", AppSettings.clampSensitivityResponseCurve(curve));
    }

    private void quietly(Runnable work) {
        quiet = true;
        try { work.run(); } finally { quiet = false; }
    }

    private void clampSavedFrequencyRange() {
        AppSettings s = store.load();
        float min = Math.min(s.freqMinHz, STANDARD_FREQ_MAX_HZ - 1f);
        float max = Math.min(s.freqMaxHz, STANDARD_FREQ_MAX_HZ);
        if (max < min + 1f) {
            min = STANDARD_FREQ_MAX_HZ - 1f;
            max = STANDARD_FREQ_MAX_HZ;
        }
        if (min == s.freqMinHz && max == s.freqMaxHz) return;
        s.freqMinHz = min;
        s.freqMaxHz = max;
        store.save(s);
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
