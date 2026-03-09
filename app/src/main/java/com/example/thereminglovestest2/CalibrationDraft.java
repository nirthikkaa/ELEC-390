package com.example.thereminglovestest2;

import android.content.Context;

import com.example.thereminglovestest2.databinding.ActivityCalibrationBinding;

import java.util.Locale;

final class CalibrationDraft {
    static final float ANGLE_MIN = -90f;
    static final float ANGLE_MAX = 90f;
    static final float ANGLE_STEP = 0.5f;
    static final float FREQ_MIN = 20f;
    static final float FREQ_STANDARD_MAX = 2000f;
    static final float FREQ_EXTENDED_MAX = 20000f;
    static final float FREQ_STEP = 1f;

    float pitchAngleMinDeg = AppSettings.DEFAULT_PITCH_ANGLE_MIN_DEG;
    float pitchAngleMaxDeg = AppSettings.DEFAULT_PITCH_ANGLE_MAX_DEG;
    float freqMinHz = AppSettings.DEFAULT_FREQ_MIN_HZ;
    float freqMaxHz = AppSettings.DEFAULT_FREQ_MAX_HZ;
    float volumeAngleMinDeg = AppSettings.DEFAULT_VOLUME_ANGLE_MIN_DEG;
    float volumeAngleMaxDeg = AppSettings.DEFAULT_VOLUME_ANGLE_MAX_DEG;
    float freqMaxLimitHz = FREQ_STANDARD_MAX;

    void refreshFreqRangeLimit(Context context) {
        freqMaxLimitHz = SettingsStore.isExtendedFreqRangeEnabled(context)
                ? FREQ_EXTENDED_MAX : FREQ_STANDARD_MAX;
        sanitize();
    }

    void load(AppSettings settings) {
        pitchAngleMinDeg = settings.pitchAngleMinDeg;
        pitchAngleMaxDeg = settings.pitchAngleMaxDeg;
        freqMinHz = settings.freqMinHz;
        freqMaxHz = settings.freqMaxHz;
        volumeAngleMinDeg = settings.volumeAngleMinDeg;
        volumeAngleMaxDeg = settings.volumeAngleMaxDeg;
        sanitize();
    }

    void saveTo(AppSettings settings) {
        sanitize();
        settings.pitchAngleMinDeg = pitchAngleMinDeg;
        settings.pitchAngleMaxDeg = pitchAngleMaxDeg;
        settings.freqMinHz = freqMinHz;
        settings.freqMaxHz = freqMaxHz;
        settings.volumeAngleMinDeg = volumeAngleMinDeg;
        settings.volumeAngleMaxDeg = volumeAngleMaxDeg;
    }

    void restoreDefaults() {
        pitchAngleMinDeg = AppSettings.DEFAULT_PITCH_ANGLE_MIN_DEG;
        pitchAngleMaxDeg = AppSettings.DEFAULT_PITCH_ANGLE_MAX_DEG;
        freqMinHz = AppSettings.DEFAULT_FREQ_MIN_HZ;
        freqMaxHz = Math.min(AppSettings.DEFAULT_FREQ_MAX_HZ, freqMaxLimitHz);
        volumeAngleMinDeg = AppSettings.DEFAULT_VOLUME_ANGLE_MIN_DEG;
        volumeAngleMaxDeg = AppSettings.DEFAULT_VOLUME_ANGLE_MAX_DEG;
        sanitize();
    }

    void syncKnobs(ActivityCalibrationBinding binding) {
        sanitize();
        binding.knobFreqMin.setRange(FREQ_MIN, freqMaxLimitHz);
        binding.knobFreqMax.setRange(FREQ_MIN, freqMaxLimitHz);
        apply(binding.knobPitchAngleMin, pitchAngleMinDeg, true);
        apply(binding.knobPitchAngleMax, pitchAngleMaxDeg, true);
        apply(binding.knobFreqMin, freqMinHz, false);
        apply(binding.knobFreqMax, freqMaxHz, false);
        apply(binding.knobVolumeAngleMin, volumeAngleMinDeg, true);
        apply(binding.knobVolumeAngleMax, volumeAngleMaxDeg, true);
    }

    String summaryText(boolean unsaved) {
        return (unsaved ? "Unsaved • " : "Saved • ") + String.format(Locale.US,
                "Pitch %.1f°→%.1f° | Freq %.0f→%.0f Hz | Volume %.1f°→%.1f°",
                pitchAngleMinDeg, pitchAngleMaxDeg, freqMinHz, freqMaxHz, volumeAngleMinDeg, volumeAngleMaxDeg);
    }

    String formatValue(float value, boolean isAngle) {
        return String.format(Locale.US, isAngle ? "%.1f°" : "%.0f Hz", value);
    }

    String formatPlainValue(float value, boolean isAngle) {
        return String.format(Locale.US, isAngle ? "%.1f" : "%.0f", value);
    }

    private void apply(KnobControlView knob, float value, boolean isAngle) {
        knob.setValue(value);
        knob.setValueText(formatValue(value, isAngle));
    }

    private void sanitize() {
        pitchAngleMinDeg = clamp(pitchAngleMinDeg, ANGLE_MIN, ANGLE_MAX);
        pitchAngleMaxDeg = clamp(pitchAngleMaxDeg, ANGLE_MIN, ANGLE_MAX);
        volumeAngleMinDeg = clamp(volumeAngleMinDeg, ANGLE_MIN, ANGLE_MAX);
        volumeAngleMaxDeg = clamp(volumeAngleMaxDeg, ANGLE_MIN, ANGLE_MAX);
        freqMinHz = clamp(freqMinHz, FREQ_MIN, freqMaxLimitHz);
        freqMaxHz = clamp(freqMaxHz, FREQ_MIN, freqMaxLimitHz);
        pitchAngleMaxDeg = enforceUpper(pitchAngleMinDeg, pitchAngleMaxDeg, ANGLE_STEP, ANGLE_MAX);
        volumeAngleMaxDeg = enforceUpper(volumeAngleMinDeg, volumeAngleMaxDeg, ANGLE_STEP, ANGLE_MAX);
        freqMaxHz = enforceUpper(freqMinHz, freqMaxHz, FREQ_STEP, freqMaxLimitHz);
    }

    private float enforceUpper(float min, float max, float step, float cap) {
        return max < min + step ? Math.min(cap, min + step) : max;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
