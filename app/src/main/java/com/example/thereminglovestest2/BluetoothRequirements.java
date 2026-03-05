package com.example.thereminglovestest2;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

/**
 * Tiny utility to keep Activities clean.
 * Centralizes Bluetooth enabled check + permission request + BT enable prompt.
 *
 * IMPORTANT: This does NOT change BLE contracts or session logic.
 * It only removes duplicated boilerplate from Activities.
 */
final class BluetoothRequirements {

    private BluetoothRequirements() {
        // Utility class
    }

    static boolean isBluetoothEnabled(Context context) {
        if (context == null) return false;

        BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        return adapter != null && adapter.isEnabled();
    }

    @SuppressWarnings("deprecation")
    static void requestEnableBluetoothPrompt(AppCompatActivity activity, int requestCode) {
        if (activity == null) return;

        try {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(intent, requestCode);
        } catch (Exception ignored) {
            // If the system refuses the intent, the Activity will keep showing the "Bluetooth off" UI.
        }
    }

    static void requestRequiredPermissions(AppCompatActivity activity, int requestCode) {
        if (activity == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    requestCode
            );
        } else {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    requestCode
            );
        }
    }
}