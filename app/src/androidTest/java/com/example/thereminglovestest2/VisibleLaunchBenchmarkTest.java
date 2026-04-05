package com.example.thereminglovestest2;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;

/**
 * Visible on-device launch benchmark suite.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.example.thereminglovestest2.VisibleLaunchBenchmarkTest
 *
 * This class intentionally launches visible Activities so the phone shows the app opening.
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class VisibleLaunchBenchmarkTest {

    private static final String TAG = "LATENCY_BENCH";
    private static final int RUNS = 5;
    private static final long VISIBLE_HOLD_MS = 350L;
    private static final long LAUNCH_SCREEN_HOLD_MS = 120L;
    private static final StringBuilder REPORT = new StringBuilder();
    private static final String PRIVACY_POLICY_ACCEPTED_KEY = "privacy_policy_accepted";

    private UiDevice device;

    @BeforeClass
    public static void suiteSetup() {
        REPORT.setLength(0);
        REPORT.append("# Theremin Gloves — Visible Launch Benchmark Raw Results\n");
        REPORT.append("# Device  : ").append(android.os.Build.MODEL).append("\n");
        REPORT.append("# Android : ").append(android.os.Build.VERSION.RELEASE)
                .append("  API ").append(android.os.Build.VERSION.SDK_INT).append("\n");
        REPORT.append("# Format  : METRIC  min_us  avg_us  max_us  stddev_us  n\n\n");
    }

    @Before
    public void setup() {
        TestAppState.resetAll();
        TestAppState.grantBlePermissions();
        TestAppState.enableBluetooth();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        device.pressHome();
        device.waitForIdle();
    }

    @After
    public void teardown() {
        TestAppState.resetAll();
        TestAppState.grantBlePermissions();
        TestAppState.enableBluetooth();
        device.pressHome();
        device.waitForIdle();
    }

    @AfterClass
    public static void writeReport() {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File out = new File(c.getFilesDir(), "visible_launch_benchmark.txt");
        try (FileWriter w = new FileWriter(out, false)) {
            w.write(REPORT.toString());
        } catch (IOException e) {
            Log.e(TAG, "Failed to write visible launch report file", e);
        }
        Log.i(TAG, "=== FULL VISIBLE LAUNCH REPORT ===\n" + REPORT);
    }

    @Test
    public void test_01_mainActivityVisibleLaunch() {
        Assume.assumeFalse(TestAppState.isEmulator());

        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            prepareReturningUserLaunchState();
            device.pressHome();
            device.waitForIdle();

            long t0 = System.nanoTime();
            ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class);
            times[i] = (System.nanoTime() - t0) / 1_000;

            SystemClock.sleep(VISIBLE_HOLD_MS);
            scenario.close();
            SystemClock.sleep(120L);
        }
        record("VISIBLE_MAIN_ACTIVITY_LAUNCH_US", times);
    }

    @Test
    public void test_02_launchActivityVisibleLaunch() {
        Assume.assumeFalse(TestAppState.isEmulator());

        long[] times = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            prepareReturningUserLaunchState();
            device.pressHome();
            device.waitForIdle();

            long t0 = System.nanoTime();
            ActivityScenario<LaunchActivity> scenario = ActivityScenario.launch(LaunchActivity.class);
            times[i] = (System.nanoTime() - t0) / 1_000;

            SystemClock.sleep(LAUNCH_SCREEN_HOLD_MS);
            scenario.close();
            SystemClock.sleep(120L);
        }
        record("VISIBLE_LAUNCH_ACTIVITY_US", times);
    }

    private void prepareReturningUserLaunchState() {
        TestAppState.resetAll();
        TestAppState.grantBlePermissions();
        TestAppState.enableBluetooth();
        TestAppState.setReturningUser(true);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.getSharedPreferences(LaunchActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PRIVACY_POLICY_ACCEPTED_KEY, true)
                .commit();
    }

    private static void record(String metric, long[] usValues) {
        long[] sorted = Arrays.copyOf(usValues, usValues.length);
        Arrays.sort(sorted);

        double sum = 0;
        for (long v : sorted) sum += v;
        double avg = sum / sorted.length;

        double variance = 0;
        for (long v : sorted) variance += (v - avg) * (v - avg);
        long stddev = (long) Math.sqrt(variance / sorted.length);

        long p50 = sorted[sorted.length / 2];
        long p95 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))];
        long p99 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))];

        String line = String.format(
                "%-42s  min=%6d  avg=%6d  p50=%6d  p95=%6d  p99=%6d  max=%6d  stddev=%5d  n=%d",
                metric,
                sorted[0], (long) avg, p50, p95, p99, sorted[sorted.length - 1], stddev,
                sorted.length);

        Log.i(TAG, line);
        REPORT.append(line).append("\n");
    }
}
