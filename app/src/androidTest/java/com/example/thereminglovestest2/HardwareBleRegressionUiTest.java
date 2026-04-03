package com.example.thereminglovestest2;

import android.Manifest;
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

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.hamcrest.Matchers.containsString;

/**
 * Real-device BLE regression coverage for repeated connect/disconnect and per-glove reconnect flows.
 */
@RunWith(AndroidJUnit4.class)
public class HardwareBleRegressionUiTest {

    @Rule
    public GrantPermissionRule bluetoothPermissions = GrantPermissionRule.grant(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN);

    @Before
    public void setUp() {
        Assume.assumeFalse(TestAppState.isEmulator());
        TestAppState.resetAll();
        TestAppState.setReturningUser(true);
        TestAppState.enableBluetooth();
        TestAppState.grantBlePermissions();
    }

    @Test
    public void connectScreen_repeatsConnectAndDisconnectAll() {
        ActivityScenario<ConnectGlovesActivity> scenario = ActivityScenario.launch(connectIntent());
        try {
            TestAppState.waitForBleSnapshot(snapshot ->
                    snapshot != null && snapshot.hostReady && snapshot.isBluetoothOn(), 12_000L);
            waitForActionDebounce();

            // Loop more than once so the suite catches stale reconnect state after the first success.
            for (int cycle = 0; cycle < 2; cycle++) {
                ensureBothGlovesConnectedOrSkip();
                scenario.onActivity(HardwareBleRegressionUiTest::disableAutoConnectForVisit);
                waitForText(scenario, R.id.tvPairSummary, "Both gloves connected", 2_500L);

                waitForActionDebounce();
                onView(withId(R.id.btnConnectToggle)).perform(scrollTo(), click());
                TestAppState.waitForBleSnapshot(snapshot ->
                        snapshot != null && snapshot.hostReady
                                && !snapshot.isAnyGloveConnected()
                                && !snapshot.isAnyGloveConnecting(), 25_000L);
                onView(withId(R.id.tvPairSummary)).check(matches(withText(containsString("No gloves connected"))));

                waitForActionDebounce();
                onView(withId(R.id.btnConnectToggle)).perform(scrollTo(), click());
                TestAppState.waitForBleSnapshot(snapshot ->
                        snapshot != null && snapshot.areBothGlovesConnected(), 35_000L);
            }
        } finally {
            scenario.close();
        }
    }

    @Test
    public void connectScreen_individualReconnectButtonsRecoverEachGlove() {
        ActivityScenario<ConnectGlovesActivity> scenario = ActivityScenario.launch(connectIntent());
        try {
            TestAppState.waitForBleSnapshot(snapshot ->
                    snapshot != null && snapshot.hostReady && snapshot.isBluetoothOn(), 12_000L);
            waitForActionDebounce();
            ensureBothGlovesConnectedOrSkip();
            scenario.onActivity(HardwareBleRegressionUiTest::disableAutoConnectForVisit);
            waitForText(scenario, R.id.tvPairSummary, "Both gloves connected", 2_500L);

            // Repeat per-glove reconnects so the suite covers multiple successive drop/recover cycles.
            for (int cycle = 0; cycle < 2; cycle++) {
                waitForActionDebounce();
                onView(withId(R.id.btnReconnectPitch)).perform(scrollTo(), click());
                TestAppState.waitForBleSnapshot(snapshot ->
                        snapshot != null && !snapshot.isPitchConnected() && snapshot.isVolumeConnected(), 35_000L);
                onView(withId(R.id.tvPitchConn)).check(matches(withText(containsString("Disconnected"))));

                waitForActionDebounce();
                onView(withId(R.id.btnReconnectPitch)).perform(scrollTo(), click());
                TestAppState.waitForBleSnapshot(snapshot ->
                        snapshot != null && snapshot.areBothGlovesConnected(), 35_000L);
                waitForText(scenario, R.id.tvPairSummary, "Both gloves connected", 2_500L);

                waitForActionDebounce();
                onView(withId(R.id.btnReconnectVolume)).perform(scrollTo(), click());
                TestAppState.waitForBleSnapshot(snapshot ->
                        snapshot != null && snapshot.isPitchConnected() && !snapshot.isVolumeConnected(), 35_000L);
                onView(withId(R.id.tvVolConn)).check(matches(withText(containsString("Disconnected"))));

                waitForActionDebounce();
                onView(withId(R.id.btnReconnectVolume)).perform(scrollTo(), click());
                TestAppState.waitForBleSnapshot(snapshot ->
                        snapshot != null && snapshot.areBothGlovesConnected(), 35_000L);
            }
            waitForText(scenario, R.id.tvPairSummary, "Both gloves connected", 2_500L);
        } finally {
            scenario.close();
        }
    }

    private static Intent connectIntent() {
        return new Intent(TestAppState.targetContext(), ConnectGlovesActivity.class)
                .putExtra(ConnectGlovesActivity.EXTRA_SUPPRESS_AUTO_PLAY_REDIRECT, true);
    }

    private static void ensureBothGlovesConnectedOrSkip() {
        BleSnapshot connected = TestAppState.waitForBleSnapshotOrNull(
                snapshot -> snapshot != null && snapshot.areBothGlovesConnected(), 2_000L);
        if (connected == null) {
            waitForActionDebounce();
            onView(withId(R.id.btnConnectToggle)).perform(scrollTo(), click());
            connected = TestAppState.waitForBleSnapshotOrNull(
                    snapshot -> snapshot != null && snapshot.areBothGlovesConnected(), 35_000L);
        }
        Assume.assumeTrue("Pitch/Volume gloves were not available for BLE reconnect regression",
                connected != null);
    }

    private static void waitForActionDebounce() {
        // ConnectGlovesActivity records a last-action timestamp on resume, so the first user tap
        // must wait out that debounce window to exercise the real connect/disconnect buttons.
        SystemClock.sleep(1_350L);
    }

    private static void disableAutoConnectForVisit(ConnectGlovesActivity activity) {
        try {
            // Keep the manual disconnect assertions stable by preventing the screen from immediately
            // auto-reconnecting after it reaches an all-disconnected state.
            Field field = ConnectGlovesActivity.class.getDeclaredField("autoConnectRequestedThisVisit");
            field.setAccessible(true);
            field.setBoolean(activity, true);
        } catch (Exception e) {
            throw new AssertionError("Could not disable Connect auto-connect for this visit", e);
        }
    }

    private static void waitForText(ActivityScenario<ConnectGlovesActivity> scenario, int viewId,
                                    String expected, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        String[] actual = new String[1];
        while (SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity(activity -> actual[0] = readText(activity, viewId));
            if (expected.equals(actual[0])) return;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("Expected text '" + expected + "' but was '" + actual[0] + "'");
    }

    private static String readText(ConnectGlovesActivity activity, int viewId) {
        TextView view = activity.findViewById(viewId);
        return view.getText().toString();
    }
}
