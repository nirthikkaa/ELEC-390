package com.example.thereminglovestest2;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Instrumented logic and persistence tests covering sprint-core behavior without BLE hardware.
 */
@RunWith(AndroidJUnit4.class)
public class SprintCoreIntegrationTest {

    @Before
    public void setUp() {
        TestAppState.resetAll();
    }

    @Test
    public void settingsStore_roundTripsSprintOneAndSprintThreeFields() {
        Context context = TestAppState.targetContext();
        SettingsStore store = new SettingsStore(context);
        AppSettings settings = new AppSettings();
        settings.pitchAngleMinDeg = -32.5f;
        settings.pitchAngleMaxDeg = 47.5f;
        settings.freqMinHz = 55f;
        settings.freqMaxHz = 1_760f;
        settings.volumeAngleMinDeg = -44f;
        settings.volumeAngleMaxDeg = 88f;
        settings.pitchDirectionInverted = true;
        settings.volumeDirectionInverted = true;
        settings.toneType = AppSettings.TONE_CLARINET;
        settings.pitchEnabled = false;
        settings.volumeEnabled = true;
        settings.activeScale = AppSettings.SCALE_MINOR;
        settings.octaveShift = -2;
        settings.reverbEnabled = true;
        settings.reverbMix = 0.55f;
        settings.delayEnabled = true;
        settings.delayFeedback = 0.6f;
        settings.delayMix = 0.45f;
        settings.distortionEnabled = true;
        settings.distortionGain = 4.2f;
        settings.sensitivityResponseCurve = 0.4f;

        store.save(settings);
        AppSettings loaded = store.load();

        assertEquals(-32.5f, loaded.pitchAngleMinDeg, 0.0001f);
        assertEquals(47.5f, loaded.pitchAngleMaxDeg, 0.0001f);
        assertEquals(55f, loaded.freqMinHz, 0.0001f);
        assertEquals(1_760f, loaded.freqMaxHz, 0.0001f);
        assertEquals(-44f, loaded.volumeAngleMinDeg, 0.0001f);
        assertEquals(88f, loaded.volumeAngleMaxDeg, 0.0001f);
        assertTrue(loaded.pitchDirectionInverted);
        assertTrue(loaded.volumeDirectionInverted);
        assertEquals(AppSettings.TONE_CLARINET, loaded.toneType);
        assertFalse(loaded.pitchEnabled);
        assertTrue(loaded.volumeEnabled);
        assertEquals(AppSettings.SCALE_MINOR, loaded.activeScale);
        assertEquals(-2, loaded.octaveShift);
        assertTrue(loaded.reverbEnabled);
        assertEquals(0.55f, loaded.reverbMix, 0.0001f);
        assertTrue(loaded.delayEnabled);
        assertEquals(0.6f, loaded.delayFeedback, 0.0001f);
        assertEquals(0.45f, loaded.delayMix, 0.0001f);
        assertTrue(loaded.distortionEnabled);
        assertEquals(4.2f, loaded.distortionGain, 0.0001f);
        assertEquals(0.4f, loaded.sensitivityResponseCurve, 0.0001f);
        assertEquals(AppSettings.SENSITIVITY_HIGH, loaded.sensitivityLevel);
    }

    @Test
    public void settingsStore_normalizesInvalidValuesAndUiPrefs() {
        Context context = TestAppState.targetContext();
        SettingsStore store = new SettingsStore(context);
        AppSettings settings = new AppSettings();
        settings.toneType = "not_a_real_tone";
        settings.activeScale = "not_a_scale";
        settings.octaveShift = 10;
        settings.sensitivityResponseCurve = Float.POSITIVE_INFINITY;

        store.save(settings);
        AppSettings loaded = store.load();

        assertEquals(AppSettings.TONE_THEREMIN, loaded.toneType);
        assertEquals(AppSettings.SCALE_CHROMATIC, loaded.activeScale);
        assertEquals(2, loaded.octaveShift);
        assertEquals(AppSettings.DEFAULT_SENSITIVITY_RESPONSE_CURVE, loaded.sensitivityResponseCurve, 0.0001f);

        SettingsStore.setBgAudioEnabled(context, false);
        SettingsStore.setExtendedFreqRangeEnabled(context, true);
        SettingsStore.setCalibrationGuideLearned(context, true);
        SettingsStore.setAudioCompression(context, AppSettings.COMPRESSION_MEDIUM);
        SettingsStore.setRenameDialogEnabled(context, false);

        assertFalse(SettingsStore.isBgAudioEnabled(context));
        assertTrue(SettingsStore.isExtendedFreqRangeEnabled(context));
        assertTrue(SettingsStore.isCalibrationGuideLearned(context));
        assertEquals(AppSettings.COMPRESSION_MEDIUM, SettingsStore.getAudioCompression(context));
        assertFalse(SettingsStore.isRenameDialogEnabled(context));
    }

    @Test
    public void calibrationDraft_clampsAndRespectsExtendedRangeToggle() {
        Context context = TestAppState.targetContext();
        CalibrationDraft draft = new CalibrationDraft();
        draft.pitchAngleMinDeg = 80f;
        draft.pitchAngleMaxDeg = 70f;
        draft.volumeAngleMinDeg = -95f;
        draft.volumeAngleMaxDeg = 120f;
        draft.freqMinHz = 5f;
        draft.freqMaxHz = 25_000f;

        SettingsStore.setExtendedFreqRangeEnabled(context, false);
        draft.refreshFreqRangeLimit(context);

        assertEquals(20f, draft.freqMinHz, 0.0001f);
        assertEquals(CalibrationDraft.FREQ_STANDARD_MAX, draft.freqMaxHz, 0.0001f);
        assertEquals(80.5f, draft.pitchAngleMaxDeg, 0.0001f);
        assertEquals(-90f, draft.volumeAngleMinDeg, 0.0001f);
        assertEquals(90f, draft.volumeAngleMaxDeg, 0.0001f);

        SettingsStore.setExtendedFreqRangeEnabled(context, true);
        draft.freqMaxHz = 30_000f;
        draft.refreshFreqRangeLimit(context);
        assertEquals(CalibrationDraft.FREQ_EXTENDED_MAX, draft.freqMaxHz, 0.0001f);
    }

    @Test
    public void playMappingState_readySnapshotMapsPitchVolumeSensitivityAndOctave() {
        PlayMappingState state = new PlayMappingState();
        state.pitchAngleMinDeg = 0f;
        state.pitchAngleMaxDeg = 90f;
        state.freqMinHz = 100f;
        state.freqMaxHz = 1_000f;
        state.volumeAngleMinDeg = 0f;
        state.volumeAngleMaxDeg = 90f;
        state.setSensitivityResponseCurve(1.0f);
        state.setOctaveShift(1);

        BleSnapshot snapshot = new BleSnapshot(
                true, true, false,
                "Ready", "pitch", "volume", "events",
                true, true, false, false,
                false, false, false, false,
                45f, 45f,
                0f, 0f,
                "POSITIVE", "POSITIVE");

        state.syncLive(snapshot);
        state.recompute(snapshot);

        assertEquals(1_100f, state.mappedFreqHz, 0.5f);
        assertEquals(0.5f, state.mappedVolumeLinear, 0.0001f);
        assertEquals(1_100f, state.audioTargetFreqHz, 0.5f);
        assertEquals(0.5f, state.audioTargetVolumeLinear, 0.0001f);
    }

    @Test
    public void playMappingState_mutesWhenInstrumentIsNotReady() {
        PlayMappingState state = new PlayMappingState();
        state.pitchAngleMinDeg = 0f;
        state.pitchAngleMaxDeg = 90f;
        state.freqMinHz = 100f;
        state.freqMaxHz = 1_000f;
        state.volumeAngleMinDeg = 0f;
        state.volumeAngleMaxDeg = 90f;

        BleSnapshot snapshot = new BleSnapshot(
                true, false, false,
                "Bluetooth off", "pitch", "volume", "events",
                true, true, false, false,
                false, false, false, false,
                45f, 45f,
                0f, 0f,
                "POSITIVE", "POSITIVE");

        state.syncLive(snapshot);
        state.recompute(snapshot);

        assertEquals(550f, state.audioTargetFreqHz, 0.5f);
        assertEquals(0f, state.audioTargetVolumeLinear, 0.0001f);
        assertEquals(0f, state.mappedVolumeLinear, 0.0001f);
    }

    @Test
    public void backgroundServiceStopPathImmediatelyMutesTheremin() {
        Context context = TestAppState.targetContext();
        ThereminBackgroundAudioService.setThereminMuted(false);

        ThereminBackgroundAudioService.stopIfRunning(context);

        assertTrue(ThereminBackgroundAudioService.isThereminMuted());
    }
}
