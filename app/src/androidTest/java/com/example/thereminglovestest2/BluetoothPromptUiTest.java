package com.example.thereminglovestest2;

import android.os.Build;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.BySelector;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

/**
 * Device-level launch tests for the Bluetooth permission prompt.
 */
@RunWith(AndroidJUnit4.class)
public class BluetoothPromptUiTest {

    private static final long UI_TIMEOUT_MS = 12_000L;
    private UiDevice device;

    @Before
    public void setUp() {
        TestAppState.resetAll();
        TestAppState.setReturningUser(true);
        TestAppState.grantBlePermissions();
        TestAppState.enableBluetooth();
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // Start from a neutral launcher state so launch-time system dialogs are easy to detect.
        device.pressHome();
    }

    @After
    public void tearDown() {
        TestAppState.grantBlePermissions();
        TestAppState.enableBluetooth();
        device.pressHome();
    }

    @Test
    public void launch_promptsForBluetoothPermissions_whenMissing() {
        Assume.assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
        // The real Nearby Devices permission dialog is stable on the attached Pixel, not the emulator.
        Assume.assumeFalse(TestAppState.isEmulator());
        TestAppState.revokeBlePermissions();

        ActivityScenario<LaunchActivity> scenario = ActivityScenario.launch(LaunchActivity.class);
        try {
            assertTrue("Bluetooth permission prompt did not appear", waitForPermissionPrompt());
            // Back out once the dialog is confirmed so the suite does not depend on OEM-specific
            // permission-sheet continuation behaviour.
            device.pressBack();
            device.waitForIdle();
        } finally {
            scenario.close();
        }
    }

    private boolean waitForPermissionPrompt() {
        long deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            UiObject2 promptAction = findFirst(
                    By.res("com.android.permissioncontroller:id/permission_allow_button"),
                    By.res("com.android.permissioncontroller:id/permission_allow_foreground_only_button"),
                    By.res("com.android.permissioncontroller:id/permission_allow_selected_button"),
                    By.res("com.android.packageinstaller:id/permission_allow_button"),
                    By.text("Allow"),
                    By.text("While using the app"),
                    By.text("Continue")
            );
            if (promptAction != null) return true;
            SystemClock.sleep(150L);
        }
        return false;
    }

    private UiObject2 findFirst(BySelector... selectors) {
        for (BySelector selector : selectors) {
            UiObject2 object = device.findObject(selector);
            if (object != null) return object;
        }
        return null;
    }
}
