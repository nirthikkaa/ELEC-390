package com.example.thereminglovestest2;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Calibration regression coverage for full-setting persistence and hardware neutral-capture flow.
 */
@RunWith(AndroidJUnit4.class)
public class CalibrationRegressionUiTest {

    @Rule
    public GrantPermissionRule bluetoothPermissions = GrantPermissionRule.grant(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN);

    @Before
    public void setUp() {
        TestAppState.resetAll();
        TestAppState.setReturningUser(true);
        TestAppState.enableBluetooth();
        SettingsStore.setCalibrationGuideLearned(TestAppState.targetContext(), false);
    }

    @Test
    public void calibrationSettings_saveAllFieldsAndSummary() {
        Context context = TestAppState.targetContext();
        ActivityScenario<CalibrationActivity> scenario = ActivityScenario.launch(CalibrationActivity.class);
        try {
            // Drive the full draft directly so this regression checks every persisted field together.
            scenario.onActivity(activity -> updateDraft(activity,
                    -22f, 58f, 110f, 1_760f, -18f, 74f));

            onView(withId(R.id.tvSavedSummary)).check(matches(allOf(
                    withText(containsString("110")),
                    withText(containsString("1760")),
                    withText(containsString("-22.0")),
                    withText(containsString("74.0"))
            )));

            // Persist the draft directly here so the regression stays focused on calibration storage
            // instead of launching into Play, which adds unrelated startup writes during the test.
            scenario.onActivity(CalibrationRegressionUiTest::persistDraftWithoutNavigation);
        } finally {
            scenario.close();
        }

        AppSettings saved = new SettingsStore(context).load();
        assertEquals(-22f, saved.pitchAngleMinDeg, 0.0001f);
        assertEquals(58f, saved.pitchAngleMaxDeg, 0.0001f);
        assertEquals(110f, saved.freqMinHz, 0.0001f);
        assertEquals(1_760f, saved.freqMaxHz, 0.0001f);
        assertEquals(-18f, saved.volumeAngleMinDeg, 0.0001f);
        assertEquals(74f, saved.volumeAngleMaxDeg, 0.0001f);
    }

    @Test
    public void calibrationDefaultsAndReload_restoreExpectedValues() {
        Context context = TestAppState.targetContext();
        AppSettings seeded = new AppSettings();
        seeded.pitchAngleMinDeg = -12f;
        seeded.pitchAngleMaxDeg = 52f;
        seeded.freqMinHz = 140f;
        seeded.freqMaxHz = 1_480f;
        seeded.volumeAngleMinDeg = -28f;
        seeded.volumeAngleMaxDeg = 68f;
        new SettingsStore(context).save(seeded);

        ActivityScenario<CalibrationActivity> scenario = ActivityScenario.launch(CalibrationActivity.class);
        try {
            scenario.onActivity(activity -> updateDraft(activity,
                    -30f, 64f, 95f, 1_920f, -35f, 80f));

            // Defaults should replace the entire draft but remain unsaved until the user chooses Save & Play.
            onView(withId(R.id.btnDefaults)).perform(scrollTo(), click());
            scenario.onActivity(activity -> {
                CalibrationDraft draft = readDraft(activity);
                CalibrationDraft defaults = new CalibrationDraft();
                assertEquals(defaults.pitchAngleMinDeg, draft.pitchAngleMinDeg, 0.0001f);
                assertEquals(defaults.pitchAngleMaxDeg, draft.pitchAngleMaxDeg, 0.0001f);
                assertEquals(defaults.freqMinHz, draft.freqMinHz, 0.0001f);
                assertEquals(defaults.freqMaxHz, draft.freqMaxHz, 0.0001f);
                assertEquals(defaults.volumeAngleMinDeg, draft.volumeAngleMinDeg, 0.0001f);
                assertEquals(defaults.volumeAngleMaxDeg, draft.volumeAngleMaxDeg, 0.0001f);
                assertEquals("UNSAVED DRAFT", readText(activity, R.id.tvSavedSummaryLabel));
            });

            // Reload should discard the unsaved draft and recover the last saved calibration exactly.
            onView(withId(R.id.btnReload)).perform(scrollTo(), click());
            scenario.onActivity(activity -> {
                CalibrationDraft draft = readDraft(activity);
                assertEquals(seeded.pitchAngleMinDeg, draft.pitchAngleMinDeg, 0.0001f);
                assertEquals(seeded.pitchAngleMaxDeg, draft.pitchAngleMaxDeg, 0.0001f);
                assertEquals(seeded.freqMinHz, draft.freqMinHz, 0.0001f);
                assertEquals(seeded.freqMaxHz, draft.freqMaxHz, 0.0001f);
                assertEquals(seeded.volumeAngleMinDeg, draft.volumeAngleMinDeg, 0.0001f);
                assertEquals(seeded.volumeAngleMaxDeg, draft.volumeAngleMaxDeg, 0.0001f);
                assertFalse(readUnsavedFlag(activity));
                assertEquals("SAVED CALIBRATION", readText(activity, R.id.tvSavedSummaryLabel));
            });
        } finally {
            scenario.close();
        }
    }

    @Test
    public void calibrationGuide_completesWhenBothGlovesCaptureNeutral() {
        Assume.assumeFalse(TestAppState.isEmulator());
        ensureBothGlovesConnectedOrSkip();

        ActivityScenario<CalibrationActivity> scenario = ActivityScenario.launch(CalibrationActivity.class);
        try {
            TestAppState.waitForBleSnapshot(snapshot -> snapshot != null && snapshot.areBothGlovesConnected(), 5_000L);
            onView(withId(R.id.tvLiveStatus)).check(matches(withText(containsString("CONNECTED"))));

            onView(withId(R.id.btnPitchNeutral)).perform(scrollTo(), click());
            onView(withId(R.id.tvCalibrationProgress)).check(matches(withText(containsString("Step 2/3"))));

            onView(withId(R.id.tabVolume)).perform(scrollTo(), click());
            onView(withId(R.id.btnVolumeNeutral)).perform(scrollTo(), click());

            waitForCalibrationText(scenario, "Calibration complete", 2_000L);
            SystemClock.sleep(1_600L);
            onView(withId(R.id.cardCalibrationStatus)).check(matches(withEffectiveVisibility(GONE)));
        } finally {
            scenario.close();
        }
    }

    private static void ensureBothGlovesConnectedOrSkip() {
        Intent intent = new Intent(TestAppState.targetContext(), ConnectGlovesActivity.class)
                .putExtra(ConnectGlovesActivity.EXTRA_SUPPRESS_AUTO_PLAY_REDIRECT, true);
        ActivityScenario<ConnectGlovesActivity> scenario = ActivityScenario.launch(intent);
        try {
            TestAppState.waitForBleSnapshot(snapshot ->
                    snapshot != null && snapshot.hostReady && snapshot.isBluetoothOn(), 12_000L);

            BleSnapshot connected = TestAppState.waitForBleSnapshotOrNull(
                    snapshot -> snapshot != null && snapshot.areBothGlovesConnected(), 2_000L);
            if (connected == null) {
                onView(withId(R.id.btnConnectToggle)).perform(scrollTo(), click());
                SystemClock.sleep(1_300L);
                connected = TestAppState.waitForBleSnapshotOrNull(
                        snapshot -> snapshot != null && snapshot.areBothGlovesConnected(), 35_000L);
            }
            Assume.assumeTrue("Pitch/Volume gloves were not available for calibration regression",
                    connected != null);
        } finally {
            scenario.close();
        }
    }

    private static void updateDraft(CalibrationActivity activity, float pitchMin, float pitchMax,
                                    float freqMin, float freqMax, float volumeMin, float volumeMax) {
        try {
            CalibrationDraft draft = readDraft(activity);
            draft.pitchAngleMinDeg = pitchMin;
            draft.pitchAngleMaxDeg = pitchMax;
            draft.freqMinHz = freqMin;
            draft.freqMaxHz = freqMax;
            draft.volumeAngleMinDeg = volumeMin;
            draft.volumeAngleMaxDeg = volumeMax;

            Field changedField = CalibrationActivity.class.getDeclaredField("hasUnsavedChanges");
            changedField.setAccessible(true);
            changedField.setBoolean(activity, true);

            Method syncMethod = CalibrationActivity.class.getDeclaredMethod("syncAllViewsFromState");
            syncMethod.setAccessible(true);
            syncMethod.invoke(activity);
        } catch (Exception e) {
            throw new AssertionError("Could not update calibration draft", e);
        }
    }

    private static void persistDraftWithoutNavigation(CalibrationActivity activity) {
        AppSettings settings = new SettingsStore(activity).load();
        readDraft(activity).saveTo(settings);
        new SettingsStore(activity).save(settings);
    }

    private static CalibrationDraft readDraft(CalibrationActivity activity) {
        try {
            Field draftField = CalibrationActivity.class.getDeclaredField("draft");
            draftField.setAccessible(true);
            return (CalibrationDraft) draftField.get(activity);
        } catch (Exception e) {
            throw new AssertionError("Could not read calibration draft", e);
        }
    }

    private static boolean readUnsavedFlag(CalibrationActivity activity) {
        try {
            Field changedField = CalibrationActivity.class.getDeclaredField("hasUnsavedChanges");
            changedField.setAccessible(true);
            return changedField.getBoolean(activity);
        } catch (Exception e) {
            throw new AssertionError("Could not read calibration dirty flag", e);
        }
    }

    private static void waitForCalibrationText(ActivityScenario<CalibrationActivity> scenario,
                                               String expected, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String[] actual = new String[1];
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> actual[0] = readText(activity, R.id.tvCalibrationProgress));
            if (expected.equals(actual[0])) return;
            SystemClock.sleep(100L);
        }
        assertEquals(expected, actual[0]);
    }

    private static String readText(CalibrationActivity activity, int viewId) {
        TextView view = activity.findViewById(viewId);
        return view.getText().toString();
    }
}
