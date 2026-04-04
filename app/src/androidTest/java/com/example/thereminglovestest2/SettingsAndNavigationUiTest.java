package com.example.thereminglovestest2;

import android.content.Context;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDescendantOfA;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Espresso coverage for settings persistence and bottom-navigation flows. */
@RunWith(AndroidJUnit4.class)
public class SettingsAndNavigationUiTest {

    @Before
    public void setUp() {
        TestAppState.resetAll();
    }

    @Test
    public void settingsScreen_togglesPersistAndUpdateSummaries() {
        Context context = TestAppState.targetContext();
        SettingsStore.setCalibrationGuideLearned(context, true);
        ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class);
        try {
            onView(withId(R.id.tvBackgroundAudioState))
                    .check(matches(withText(containsString("ON"))));

            onView(withId(R.id.switchBackgroundAudio)).perform(click());
            onView(withId(R.id.tvBackgroundAudioState))
                    .check(matches(withText(containsString("OFF"))));

            onView(withId(R.id.switchExtendedFrequencyRange)).perform(scrollTo(), click());
            onView(withId(R.id.tvFrequencyRangeState))
                    .check(matches(withText(containsString("20,000 Hz"))));

            onView(withId(R.id.btnShowTutorialAgain)).perform(scrollTo(), click());
            onView(withId(R.id.tvTutorialState))
                    .check(matches(withText(containsString("enabled"))));
        } finally {
            scenario.close();
        }

        assertFalse(SettingsStore.isBgAudioEnabled(context));
        assertTrue(SettingsStore.isExtendedFreqRangeEnabled(context));
        assertFalse(SettingsStore.isCalibrationGuideLearned(context));
    }

    @Test
    public void settingsScreen_compressionAndSensitivityChangesPersist() {
        Context context = TestAppState.targetContext();
        ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class);
        try {
            onView(withId(R.id.chipCompressionMedium)).perform(scrollTo(), click());
            onView(withId(R.id.tvCompressionEstimate))
                    .check(matches(withText(containsString("1.4 MB"))));

            onView(withId(R.id.sliderSensitivity)).perform(scrollTo(), SliderViewActions.setValue(0.40f));
            // Commit through the activity's save path because the programmatic slider helper
            // does not mark the change as fromUser=true like a real drag gesture would.
            scenario.onActivity(activity -> invokeSaveSensitivityCurve(activity, 0.40f));
            onView(withId(R.id.tvSensitivityValue))
                    .check(matches(withText(containsString("0.40"))));
        } finally {
            scenario.close();
        }

        AppSettings loaded = new SettingsStore(context).load();
        assertEquals(AppSettings.COMPRESSION_MEDIUM, SettingsStore.getAudioCompression(context));
        assertEquals(0.40f, loaded.sensitivityResponseCurve, 0.0001f);
        assertEquals(AppSettings.SENSITIVITY_HIGH, loaded.sensitivityLevel);
    }

    @Test
    public void bottomNavigation_movesBetweenSettingsAndLibrary() {
        ActivityScenario<SettingsActivity> scenario = ActivityScenario.launch(SettingsActivity.class);
        try {
            onView(allOf(withText("Library"), isDescendantOfA(withId(R.id.bottomNavBar))))
                    .perform(click());
            onView(withId(R.id.btnDurationFilter)).check(matches(isDisplayed()));

            onView(allOf(withText("Settings"), isDescendantOfA(withId(R.id.bottomNavBar))))
                    .perform(click());
            onView(withId(R.id.switchBackgroundAudio)).check(matches(isDisplayed()));
        } finally {
            scenario.close();
        }
    }

    private static void invokeSaveSensitivityCurve(SettingsActivity activity, float curve) {
        try {
            Method method = SettingsActivity.class.getDeclaredMethod("saveSensitivityCurve", float.class);
            method.setAccessible(true);
            method.invoke(activity, curve);
        } catch (Exception e) {
            throw new AssertionError("Could not persist slider curve through SettingsActivity", e);
        }
    }
}
