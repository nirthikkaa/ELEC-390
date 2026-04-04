package com.example.thereminglovestest2;

import android.Manifest;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE;
import static androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Espresso coverage for the Play stage/performance mode UI. */
@RunWith(AndroidJUnit4.class)
public class PlayStageModeUiTest {

    @Rule
    public GrantPermissionRule bluetoothPermissions = GrantPermissionRule.grant(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN);

    @Before
    public void setUp() {
        TestAppState.resetAll();
        TestAppState.enableBluetooth();
    }

    @Test
    public void stageView_togglePersistsAcrossRecreate() {
        ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
        try {
            onView(withText("Stage View")).perform(click());
            SystemClock.sleep(250L);
            onView(withId(R.id.btnExitStage)).check(matches(withEffectiveVisibility(VISIBLE)));
            onView(withId(R.id.bottomNavBar)).check(matches(withEffectiveVisibility(GONE)));

            scenario.recreate();
            SystemClock.sleep(250L);
            onView(withId(R.id.btnExitStage)).check(matches(withEffectiveVisibility(VISIBLE)));
            onView(withId(R.id.bottomNavBar)).check(matches(withEffectiveVisibility(GONE)));

            boolean[] prefAfterRecreate = new boolean[1];
            scenario.onActivity(activity -> prefAfterRecreate[0] = activity
                    .getSharedPreferences("theremin_prefs", MainActivity.MODE_PRIVATE)
                    .getBoolean("performance_mode_active", false));
            assertTrue(prefAfterRecreate[0]);

            onView(withId(R.id.btnExitStage)).perform(click());
            SystemClock.sleep(150L);
            onView(withId(R.id.bottomNavBar)).check(matches(withEffectiveVisibility(VISIBLE)));

            boolean[] prefAfterExit = new boolean[1];
            scenario.onActivity(activity -> prefAfterExit[0] = activity
                    .getSharedPreferences("theremin_prefs", MainActivity.MODE_PRIVATE)
                    .getBoolean("performance_mode_active", false));
            assertFalse(prefAfterExit[0]);
        } finally {
            scenario.close();
        }
    }
}
