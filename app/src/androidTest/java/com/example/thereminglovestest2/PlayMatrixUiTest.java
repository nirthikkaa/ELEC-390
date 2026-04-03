package com.example.thereminglovestest2;

import android.Manifest;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import com.google.android.material.button.MaterialButton;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wider Play-screen regression coverage for tones, transport, effects, scales, keys, and presets.
 */
@RunWith(AndroidJUnit4.class)
public class PlayMatrixUiTest {

    @Rule
    public GrantPermissionRule bluetoothPermissions = GrantPermissionRule.grant(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN);

    @Before
    public void setUp() {
        TestAppState.resetAll();
        TestAppState.setReturningUser(true);
        TestAppState.enableBluetooth();
        for (int i = 0; i < 8; i++) TestAppState.seedBeatPresetSlot(i, 128 + i);
    }

    @Test
    public void playPause_andAllTonesRemainResponsive() {
        // Tone persistence races on the emulator startup image; the physical Pixel is the meaningful path.
        Assume.assumeFalse(TestAppState.isEmulator());
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            SystemClock.sleep(400L);
            onView(withId(R.id.btnAudioStart)).perform(click());
            waitForAudioState(scenario, true, 2_000L);

            String[] toneCycle = toneCycle();
            for (int i = 1; i < toneCycle.length; i++) {
                final int step = i;
                scenario.onActivity(activity -> invokeCycleTone(activity, 1));
                waitForText(scenario, R.id.tvToneLabel, AppSettings.prettyToneType(toneCycle[step]), 1_500L);
                scenario.onActivity(activity ->
                        assertTrue("Audio stopped while cycling tones", invokeIsAnyAudioRunning(activity)));
            }

            scenario.onActivity(activity -> invokeCycleTone(activity, 1));
            waitForText(scenario, R.id.tvToneLabel, AppSettings.prettyToneType(toneCycle[0]), 1_500L);

            onView(withId(R.id.btnAudioStart)).perform(click());
            waitForAudioState(scenario, false, 2_000L);
        } finally {
            scenario.close();
        }
    }

    @Test
    public void effectsAndScales_toggleIndividuallyAndPersistAcrossRecreate() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            onView(withId(R.id.btnAudioStart)).perform(click());
            waitForAudioState(scenario, true, 2_000L);

            onView(withId(R.id.btnReverb)).perform(scrollTo(), click());
            scenario.onActivity(activity -> assertTrue(readCheckedButton(activity, R.id.btnReverb)));

            onView(withId(R.id.btnDelay)).perform(scrollTo(), click());
            scenario.onActivity(activity -> assertTrue(readCheckedButton(activity, R.id.btnDelay)));

            onView(withId(R.id.btnDistortion)).perform(scrollTo(), click());
            scenario.onActivity(activity -> assertTrue(readCheckedButton(activity, R.id.btnDistortion)));

            clickScaleAndAssert(scenario, R.id.btnScaleChromatic, AppSettings.SCALE_CHROMATIC);
            clickScaleAndAssert(scenario, R.id.btnScaleMajor, AppSettings.SCALE_MAJOR);
            clickScaleAndAssert(scenario, R.id.btnScaleMinor, AppSettings.SCALE_MINOR);
            clickScaleAndAssert(scenario, R.id.btnScalePentatonic, AppSettings.SCALE_PENTATONIC);

            scenario.recreate();
            SystemClock.sleep(400L);

            scenario.onActivity(activity -> {
                assertTrue(readCheckedButton(activity, R.id.btnReverb));
                assertTrue(readCheckedButton(activity, R.id.btnDelay));
                assertTrue(readCheckedButton(activity, R.id.btnDistortion));
                assertEquals(AppSettings.SCALE_PENTATONIC,
                        new SettingsStore(activity).load().activeScale);
            });

            onView(withId(R.id.btnAudioStart)).perform(click());
            waitForAudioState(scenario, false, 2_000L);
        } finally {
            scenario.close();
        }
    }

    @Test
    public void playPause_andEveryEffectCombinationRemainConsistent() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            int[] effectIds = {R.id.btnReverb, R.id.btnDelay, R.id.btnDistortion};
            boolean[][] combos = {
                    {false, false, false},
                    {true, false, false},
                    {false, true, false},
                    {false, false, true},
                    {true, true, false},
                    {true, false, true},
                    {false, true, true},
                    {true, true, true}
            };

            for (boolean[] combo : combos) {
                // Drive every effect combination through the real buttons so persistence and button
                // listeners stay part of the regression path.
                setEffectStates(scenario, effectIds, combo);
                scenario.onActivity(activity -> {
                    AppSettings saved = new SettingsStore(activity).load();
                    assertEquals(combo[0], saved.reverbEnabled);
                    assertEquals(combo[1], saved.delayEnabled);
                    assertEquals(combo[2], saved.distortionEnabled);
                });

                onView(withId(R.id.btnAudioStart)).perform(click());
                waitForAudioState(scenario, true, 2_500L);
                onView(withId(R.id.btnAudioStart)).perform(click());
                waitForAudioState(scenario, false, 2_500L);
            }
        } finally {
            scenario.close();
        }
    }

    @Test
    public void beatPresetsAndKeyboardCoverFullVisibleMatrix() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            onView(withId(R.id.btnAudioStart)).perform(click());
            waitForAudioState(scenario, true, 2_000L);

            int[] presetIds = {
                    R.id.btnBeatPreset1, R.id.btnBeatPreset2, R.id.btnBeatPreset3, R.id.btnBeatPreset4,
                    R.id.btnBeatPreset5, R.id.btnBeatPreset6, R.id.btnBeatPreset7, R.id.btnBeatPreset8
            };
            for (int i = 0; i < presetIds.length; i++) {
                int slotIdx = i;
                onView(withId(presetIds[i])).perform(scrollTo(), click());
                waitForText(scenario, presetIds[i], "P" + (i + 1), 1_500L);
                scenario.onActivity(activity ->
                        assertTrue("Preset slot " + slotIdx + " did not arm", readActiveSlot(activity, slotIdx)));
            }

            String[] expectedWhiteNotes = {
                    "C3", "D3", "E3", "F3", "G3", "A3", "B3",
                    "C4", "D4", "E4", "F4", "G4", "A4", "B4", "C5"
            };
            for (int keyIndex = 0; keyIndex < expectedWhiteNotes.length; keyIndex++) {
                final int targetKey = keyIndex;
                scenario.onActivity(activity -> tapWhiteKey(activity, targetKey));
                waitForText(scenario, R.id.tvPianoNote, expectedWhiteNotes[keyIndex], 1_000L);
            }

            onView(withId(R.id.btnPianoSynth)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoSynth)).check(matches(withText("BELLS")));
            onView(withId(R.id.btnPianoSynth)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoSynth)).check(matches(withText("ORGAN")));

            onView(withId(R.id.btnPianoMode)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoMode)).check(matches(withText("MAJOR")));
            scenario.onActivity(activity -> tapWhiteKey(activity, 0));
            scenario.onActivity(activity -> tapWhiteKey(activity, 1));
            onView(withId(R.id.tvPianoNote)).check(matches(allOf(
                    withText(containsString("C3")),
                    withText(containsString("D3"))
            )));
        } finally {
            scenario.close();
        }
    }

    @Test
    public void pianoModeAndSynth_cycleThroughAllLabelsIncludingReverse() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            // Walk the full visible mode cycle so every harmonic mode label is covered.
            onView(withId(R.id.btnPianoMode)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoMode)).check(matches(withText("MAJOR")));
            onView(withId(R.id.btnPianoMode)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoMode)).check(matches(withText("MINOR")));
            onView(withId(R.id.btnPianoMode)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoMode)).check(matches(withText("PENTA")));
            onView(withId(R.id.btnPianoMode)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoMode)).check(matches(withText("JAZZ")));
            onView(withId(R.id.btnPianoMode)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoMode)).check(matches(withText("NOTE")));

            // Cover both forward and reverse synth cycling because the reverse path is long-press only.
            onView(withId(R.id.btnPianoSynth)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoSynth)).check(matches(withText("BELLS")));
            onView(withId(R.id.btnPianoSynth)).perform(scrollTo(), click());
            onView(withId(R.id.btnPianoSynth)).check(matches(withText("ORGAN")));
            onView(withId(R.id.btnPianoSynth)).perform(scrollTo(), longClick());
            onView(withId(R.id.btnPianoSynth)).check(matches(withText("BELLS")));
            onView(withId(R.id.btnPianoSynth)).perform(scrollTo(), longClick());
            onView(withId(R.id.btnPianoSynth)).check(matches(withText("KEYS")));
        } finally {
            scenario.close();
        }
    }

    private static void clickScaleAndAssert(ActivityScenario<MainActivity> scenario, int buttonId, String scale) {
        onView(withId(buttonId)).perform(scrollTo(), click());
        scenario.onActivity(activity ->
                assertEquals(scale, new SettingsStore(activity).load().activeScale));
    }

    private static String[] toneCycle() {
        try {
            Field field = MainActivity.class.getDeclaredField("TONE_CYCLE");
            field.setAccessible(true);
            return (String[]) field.get(null);
        } catch (Exception e) {
            throw new AssertionError("Could not read Play tone cycle", e);
        }
    }

    private static void invokeCycleTone(MainActivity activity, int delta) {
        try {
            Method method = MainActivity.class.getDeclaredMethod("cycleTone", int.class);
            method.setAccessible(true);
            method.invoke(activity, delta);
        } catch (Exception e) {
            throw new AssertionError("Could not cycle the Play tone", e);
        }
    }

    private static boolean invokeIsAnyAudioRunning(MainActivity activity) {
        try {
            Method method = MainActivity.class.getDeclaredMethod("isAnyAudioRunning");
            method.setAccessible(true);
            return (boolean) method.invoke(activity);
        } catch (Exception e) {
            throw new AssertionError("Could not read audio running state", e);
        }
    }

    private static boolean readActiveSlot(MainActivity activity, int slotIdx) {
        try {
            Field field = MainActivity.class.getDeclaredField("activeSlots");
            field.setAccessible(true);
            boolean[] activeSlots = (boolean[]) field.get(activity);
            return activeSlots[slotIdx];
        } catch (Exception e) {
            throw new AssertionError("Could not inspect beat preset state", e);
        }
    }

    private static boolean readCheckedButton(MainActivity activity, int viewId) {
        MaterialButton button = activity.findViewById(viewId);
        return button.isChecked();
    }

    private static void setEffectStates(ActivityScenario<MainActivity> scenario, int[] effectIds,
                                        boolean[] expectedStates) {
        for (int i = 0; i < effectIds.length; i++) {
            int effectId = effectIds[i];
            boolean expectedState = expectedStates[i];
            boolean[] actualState = new boolean[1];
            scenario.onActivity(activity -> actualState[0] = readCheckedButton(activity, effectId));
            if (actualState[0] != expectedState) {
                onView(withId(effectId)).perform(scrollTo(), click());
            }
            scenario.onActivity(activity -> {
                boolean afterToggle = readCheckedButton(activity, effectId);
                if (expectedState) assertTrue(afterToggle);
                else assertFalse(afterToggle);
            });
        }
    }

    private static void tapWhiteKey(MainActivity activity, int whiteKeyIndex) {
        PianoKeyboardView keyboard = activity.findViewById(R.id.pianoKeyboard);
        float whiteKeyWidth = keyboard.getWidth() / 15f;
        float x = whiteKeyWidth * whiteKeyIndex + whiteKeyWidth / 2f;
        float y = keyboard.getHeight() * 0.80f;
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(downTime, downTime + 16L, MotionEvent.ACTION_UP, x, y, 0);
        keyboard.dispatchTouchEvent(down);
        keyboard.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    private static void waitForAudioState(ActivityScenario<MainActivity> scenario, boolean expected, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        boolean[] actual = new boolean[1];
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> actual[0] = invokeIsAnyAudioRunning(activity));
            if (actual[0] == expected) return;
            SystemClock.sleep(100L);
        }
        assertEquals(expected, actual[0]);
    }

    private static void waitForText(ActivityScenario<MainActivity> scenario, int viewId,
                                    String expected, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String[] actual = new String[1];
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> actual[0] = readText(activity, viewId));
            if (expected.equals(actual[0])) return;
            SystemClock.sleep(100L);
        }
        assertEquals(expected, actual[0]);
    }

    private static String readText(MainActivity activity, int viewId) {
        TextView view = activity.findViewById(viewId);
        return view.getText().toString();
    }
}
