package com.example.thereminglovestest2;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.hasErrorText;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;

/** Espresso coverage for Library filtering and playback flows. */
@RunWith(AndroidJUnit4.class)
public class LibraryUiTest {

    @Before
    public void setUp() {
        TestAppState.resetAll();
    }

    @Test
    public void customDurationFilter_handlesInvalidRangeAndFiltersResults() throws IOException {
        long now = System.currentTimeMillis();
        TestAppState.saveRecording("Short Clip", 10_000L, now, AppSettings.COMPRESSION_HIGH);
        TestAppState.saveRecording("Medium Clip", 60_000L, now - 1_000L, AppSettings.COMPRESSION_HIGH);
        TestAppState.saveRecording("Long Clip", 180_000L, now - 2_000L, AppSettings.COMPRESSION_HIGH);

        ActivityScenario<LibraryActivity> scenario = ActivityScenario.launch(LibraryActivity.class);
        try {
            onView(withId(R.id.btnDurationFilter)).perform(click());
            onView(withText("Custom range...")).perform(click());

            onView(withHint("Min seconds")).perform(replaceText("120"), closeSoftKeyboard());
            onView(withHint("Max seconds")).perform(replaceText("30"), closeSoftKeyboard());
            onView(withText("Apply")).perform(click());
            onView(withHint("Max seconds"))
                    .check(matches(hasErrorText("Max must be greater than or equal to min")));

            onView(withHint("Min seconds")).perform(replaceText("30"), closeSoftKeyboard());
            onView(withHint("Max seconds")).perform(replaceText("120"), closeSoftKeyboard());
            onView(withText("Apply")).perform(click());

            onView(withText("Medium Clip")).check(matches(isDisplayed()));
            onView(withText("Short Clip")).check(doesNotExist());
            onView(withText("Long Clip")).check(doesNotExist());
            onView(withId(R.id.btnDurationFilter))
                    .check(matches(withText(containsString("30s-2m"))));
        } finally {
            scenario.close();
        }
    }

    @Test
    public void dateRangeFilter_limitsListToToday() throws IOException {
        long now = System.currentTimeMillis();
        long fiveDaysAgo = now - 5L * 24L * 60L * 60L * 1_000L;
        TestAppState.saveRecording("Today Clip", 12_000L, now, AppSettings.COMPRESSION_HIGH);
        TestAppState.saveRecording("Older Clip", 12_000L, fiveDaysAgo, AppSettings.COMPRESSION_HIGH);

        ActivityScenario<LibraryActivity> scenario = ActivityScenario.launch(LibraryActivity.class);
        try {
            onView(withId(R.id.btnDateFilter)).perform(click());
            onView(withText("Pick date range...")).perform(click());
            onView(withId(android.R.id.button1)).perform(click());
            onView(withId(android.R.id.button1)).perform(click());

            onView(withText("Today Clip")).check(matches(isDisplayed()));
            onView(withText("Older Clip")).check(doesNotExist());
        } finally {
            scenario.close();
        }
    }

    @Test
    public void validRecordingPlaybackShowsMiniPlayer() throws IOException {
        TestAppState.saveRecording("Playable Clip", 15_000L, System.currentTimeMillis(), AppSettings.COMPRESSION_LOSSLESS);

        ActivityScenario<LibraryActivity> scenario = ActivityScenario.launch(LibraryActivity.class);
        try {
            onView(withId(R.id.btnPlayPause)).perform(click());
            onView(withId(R.id.playerBar)).check(matches(isDisplayed()));
            onView(withId(R.id.tvPlayerName)).check(matches(withText("Playable Clip")));
        } finally {
            scenario.close();
        }
    }

    @Test
    public void brokenRecordingPlaybackFailsGracefully() {
        TestAppState.saveBrokenRecording("Broken Clip", 9_000L, System.currentTimeMillis(), AppSettings.COMPRESSION_HIGH);

        ActivityScenario<LibraryActivity> scenario = ActivityScenario.launch(LibraryActivity.class);
        try {
            onView(withId(R.id.btnPlayPause)).perform(click());
            onView(withId(R.id.playerBar)).check(matches(withEffectiveVisibility(GONE)));
        } finally {
            scenario.close();
        }
    }
}
