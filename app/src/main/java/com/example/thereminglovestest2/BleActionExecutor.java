package com.example.thereminglovestest2;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Shared guard for BLE actions that require both runtime permissions
 * and Bluetooth to be enabled.
 */
public final class BleActionExecutor {

    private BleActionExecutor() {
    }

    public static void runWhenReady(AppCompatActivity activity,
                                    int permissionsRequestCode,
                                    int enableBluetoothRequestCode,
                                    Runnable action) {
        if (!BleSessionManager.hasRequiredPermissions(activity)) {
            BluetoothRequirements.requestRequiredPermissions(activity, permissionsRequestCode);
            return;
        }
        if (!BluetoothRequirements.isBluetoothEnabled(activity)) {
            BluetoothRequirements.requestEnableBluetoothPrompt(activity, enableBluetoothRequestCode);
            return;
        }
        if (action != null) {
            action.run();
        }
    }

    public static boolean wereAllPermissionsGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) {
            return false;
        }
        for (int result : grantResults) {
            if (result != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }
}
