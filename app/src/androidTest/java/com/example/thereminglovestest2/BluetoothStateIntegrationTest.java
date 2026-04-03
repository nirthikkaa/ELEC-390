package com.example.thereminglovestest2;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Integration coverage for real Bluetooth adapter state transitions on the attached device.
 */
@RunWith(AndroidJUnit4.class)
public class BluetoothStateIntegrationTest {

    @Before
    public void setUp() {
        // The real adapter toggle path is meaningful only on the physical Pixel.
        Assume.assumeFalse(TestAppState.isEmulator());
        TestAppState.resetAll();
        TestAppState.grantBlePermissions();
        TestAppState.enableBluetooth();
    }

    @After
    public void tearDown() {
        TestAppState.grantBlePermissions();
        TestAppState.enableBluetooth();
    }

    @Test
    public void bluetoothAdapter_togglesOffAndBackOn() {
        assertTrue(BleSessionManager.isBluetoothEnabled(TestAppState.targetContext()));

        TestAppState.disableBluetooth();
        TestAppState.waitForBluetoothEnabled(false, 12_000L);
        assertFalse(BleSessionManager.isBluetoothEnabled(TestAppState.targetContext()));

        TestAppState.enableBluetooth();
        TestAppState.waitForBluetoothEnabled(true, 30_000L);
        assertTrue(BleSessionManager.isBluetoothEnabled(TestAppState.targetContext()));
    }
}
