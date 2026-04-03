package com.example.thereminglovestest2;

/**
 * File guide:
 * Launcher router. It sends first-time users into Setup and returning users straight to Play.
 */

import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityLaunchBinding;

public class LaunchActivity extends AppCompatActivity {
    private static final long MIN_LOADING_VISIBILITY_MS = 180L;
    private static final int REQUEST_BLE_PERMISSIONS = 3101;
    private static final int REQUEST_ENABLE_BLUETOOTH = 3102;

    static final String PREFS_NAME = "theremin_prefs";
    static final String KEY_FIRST_LAUNCH_DONE = "first_launch_done";
    static final String KEY_GRID_HINT_PENDING = "grid_hint_pending";

    private ActivityLaunchBinding binding;
    private boolean started;
    private boolean launchChecksStarted;
    private long launchStartedElapsedMs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLaunchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        launchStartedElapsedMs = SystemClock.elapsedRealtime();

        // Start warming Play dependencies immediately so the first Play open is not cold.
        AppLaunchWarmup.begin(getApplicationContext());
        // Bring up the BLE session host while the lightweight launch screen is already visible.
        BleSessionManager.initialize(getApplicationContext());

        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean returningUser = prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false);
        binding.tvLaunchStatus.setText(returningUser ? "Loading Play" : "Preparing Setup");
        binding.tvLaunchDetail.setText(returningUser
                ? "Preparing audio, Bluetooth, and saved settings..."
                : "Preparing Bluetooth, setup, and calibration tools...");

        // Wait for the first frame, then resolve launch-time Bluetooth blockers from this loader screen.
        binding.launchRoot.post(this::beginLaunchChecks);
    }

    private void beginLaunchChecks() {
        if (launchChecksStarted || isFinishing() || isDestroyed()) return;
        launchChecksStarted = true;
        binding.tvLaunchStatus.setText("Checking Bluetooth");
        binding.tvLaunchDetail.setText("Resolving permissions and Bluetooth state before opening the app...");

        if (!BleSessionManager.hasRequiredPermissions(this)) {
            // Ask from the loader screen so the user sees the app before the prompt appears.
            BleSessionManager.requestRequiredPermissions(this, REQUEST_BLE_PERMISSIONS);
            return;
        }
        if (!BleSessionManager.isBluetoothEnabled(this)) {
            // Show the enable-Bluetooth system dialog while the loader remains visible underneath.
            BleSessionManager.requestEnableBluetoothPrompt(this, REQUEST_ENABLE_BLUETOOTH);
            return;
        }
        routeAfterFirstFrame();
    }

    private void routeAfterFirstFrame() {
        binding.tvLaunchStatus.setText("Opening App");
        long elapsedMs = SystemClock.elapsedRealtime() - launchStartedElapsedMs;
        long remainingMs = Math.max(0L, MIN_LOADING_VISIBILITY_MS - elapsedMs);
        binding.launchRoot.postDelayed(this::routeNext, remainingMs);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_BLE_PERMISSIONS) return;
        // Whether granted or denied, continue the flow so the user does not get stuck on the loader.
        if (BleSessionManager.hasRequiredPermissions(this) && !BleSessionManager.isBluetoothEnabled(this)) {
            BleSessionManager.requestEnableBluetoothPrompt(this, REQUEST_ENABLE_BLUETOOTH);
            return;
        }
        routeAfterFirstFrame();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
            // Continue into the app whether Bluetooth was enabled or dismissed.
            routeAfterFirstFrame();
        }
    }

    private void routeNext() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) {
            openPlay();
            return;
        }
        openSetup();
    }

    private void openPlay() {
        if (started) return;
        started = true;
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_QUICK_START_LAUNCH, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        overridePendingTransition(0, 0);
        finish();
    }

    private void openSetup() {
        if (started) return;
        started = true;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FIRST_LAUNCH_DONE, true)
                .putBoolean(KEY_GRID_HINT_PENDING, true)
                .apply();
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        overridePendingTransition(0, 0);
        finish();
    }
}
