package com.example.thereminglovestest2;

import android.content.Context;

final class PlayMappingState {
    static final float ANGLE_MIN = -90f;
    static final float ANGLE_MAX = 90f;
    static final float ANGLE_STEP = 0.5f;
    static final int ANGLE_PROGRESS_MAX = (int) ((ANGLE_MAX - ANGLE_MIN) / ANGLE_STEP);
    static final float FREQ_MIN_UI = 20f;
    static final float FREQ_STANDARD_MAX_UI = 2000f;
    static final float FREQ_EXTENDED_MAX_UI = 20000f;

    float currentFreqMaxUi = FREQ_STANDARD_MAX_UI;
    float pitchAngleMinDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MIN_DEG;
    float pitchAngleMaxDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MAX_DEG;
    float freqMinHz = AppSettings.DEFAULT_PLAY_FREQ_MIN_HZ;
    float freqMaxHz = AppSettings.DEFAULT_PLAY_FREQ_MAX_HZ;
    float volumeAngleMinDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MIN_DEG;
    float volumeAngleMaxDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MAX_DEG;
    float pitchActiveDeltaDeg;
    float volActiveDeltaDeg;
    boolean pitchHasAngle;
    boolean volHasAngle;
    float mappedFreqHz = 880f;
    float mappedVolumeLinear;
    float audioTargetFreqHz = 880f;
    float audioTargetVolumeLinear;
    String currentToneType = AppSettings.TONE_SINE;
    private float sensitivityMultiplier = 1.0f;
    private int   octaveShift = 0;

    void setOctaveShift(int shift) {
        octaveShift = Math.max(-2, Math.min(2, shift));
    }

    void setSensitivityMultiplier(float mult) {
        sensitivityMultiplier = Math.max(0.1f, Math.min(3.0f, mult));
    }

    void refreshFreqRangeLimit(Context context) {
        currentFreqMaxUi = SettingsStore.isExtendedFreqRangeEnabled(context)
                ? FREQ_EXTENDED_MAX_UI : FREQ_STANDARD_MAX_UI;
        sanitize();
    }

    int getFreqProgressMax() {
        return Math.round(currentFreqMaxUi - FREQ_MIN_UI);
    }

    int angleToProgress(float angle) {
        return Math.round((angle - ANGLE_MIN) / ANGLE_STEP);
    }

    int freqToProgress(float hz) {
        return Math.round(hz - FREQ_MIN_UI);
    }

    float progressToAngle(int progress) {
        return ANGLE_MIN + progress * ANGLE_STEP;
    }

    float progressToFreq(int progress) {
        return FREQ_MIN_UI + progress;
    }

    void load(AppSettings settings) {
        pitchAngleMinDeg = settings.pitchAngleMinDeg;
        pitchAngleMaxDeg = settings.pitchAngleMaxDeg;
        freqMinHz = settings.freqMinHz;
        freqMaxHz = settings.freqMaxHz;
        volumeAngleMinDeg = settings.volumeAngleMinDeg;
        volumeAngleMaxDeg = settings.volumeAngleMaxDeg;
        currentToneType = AppSettings.normalizeToneType(settings.toneType);
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
        settings.toneType = AppSettings.normalizeToneType(currentToneType);
    }

    void restoreDefaults() {
        pitchAngleMinDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MIN_DEG;
        pitchAngleMaxDeg = AppSettings.DEFAULT_PLAY_PITCH_ANGLE_MAX_DEG;
        freqMinHz = AppSettings.DEFAULT_PLAY_FREQ_MIN_HZ;
        freqMaxHz = AppSettings.DEFAULT_PLAY_FREQ_MAX_HZ;
        volumeAngleMinDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MIN_DEG;
        volumeAngleMaxDeg = AppSettings.DEFAULT_PLAY_VOLUME_ANGLE_MAX_DEG;
        sanitize();
    }

    void syncLive(BleSnapshot snapshot) {
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

    void recompute(BleSnapshot snapshot) {
        sanitize();
        float pitchMid  = (pitchAngleMinDeg + pitchAngleMaxDeg) / 2f;
        float pitchSpan = (pitchAngleMaxDeg - pitchAngleMinDeg) * sensitivityMultiplier;
        float effPitchMin = pitchMid - pitchSpan / 2f;
        float effPitchMax = pitchMid + pitchSpan / 2f;

        float volMid  = (volumeAngleMinDeg + volumeAngleMaxDeg) / 2f;
        float volSpan = (volumeAngleMaxDeg - volumeAngleMinDeg) * sensitivityMultiplier;
        float effVolMin = volMid - volSpan / 2f;
        float effVolMax = volMid + volSpan / 2f;

        float freq = mapLinearClamped(pitchActiveDeltaDeg, effPitchMin, effPitchMax, freqMinHz, freqMaxHz);
        if (octaveShift != 0) freq = clamp(freq * (float) Math.pow(2.0, octaveShift), 20f, 20000f);
        float vol = mapLinearClamped(volActiveDeltaDeg, effVolMin, effVolMax, 0f, 1f);
        if (!pitchHasAngle) freq = freqMinHz;
        if (!volHasAngle) vol = 0f;
        mappedFreqHz = freq;
        mappedVolumeLinear = vol;
        audioTargetFreqHz = freq;
        audioTargetVolumeLinear = isInstrumentReady(snapshot) ? vol : 0f;
        if (audioTargetVolumeLinear == 0f) mappedVolumeLinear = 0f;
    }

    private boolean isInstrumentReady(BleSnapshot snapshot) {
        return snapshot != null && snapshot.isBluetoothOn() && snapshot.areBothGlovesConnected();
    }

    private void sanitize() {
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

    private float mapLinearClamped(float x, float inMin, float inMax, float outMin, float outMax) {
        if (Math.abs(inMax - inMin) < 1e-6f) return outMin;
        float t = clamp((x - inMin) / (inMax - inMin), 0f, 1f);
        return outMin + t * (outMax - outMin);
    }

    private float enforceUpperBound(float min, float max, float step, float cap) {
        return max < min + step ? Math.min(cap, min + step) : max;
    }

    private float clamp(float x, float lo, float hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
