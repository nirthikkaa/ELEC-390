package com.example.thereminglovestest2;

/**
 * File guide:
 * Setup and onboarding screen. It helps the user get Bluetooth ready and guides them into the app flow.
 */

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityHomeBinding;

/**
 * Friendly setup screen.
 *
 * The job of this screen is simple:
 * get Bluetooth ready, try auto-connect, and get out of the way.
 */
public class HomeActivity extends AppCompatActivity {

    private static final long UI_POLL_MS = 200L;
    private static final int REQ_ENABLE_BT = 4201;
    private static final int REQ_BLE_PERMS = 4202;
    private static final int REQUIRED_CONNECTED_POLLS_BEFORE_AUTOPLAY = 1;

    private ActivityHomeBinding binding;
    private final NavigationUtils.Poller uiPoller = new NavigationUtils.Poller(UI_POLL_MS, this::refreshHomeSnapshot);

    private boolean permissionPromptShownThisVisit;
    private boolean bluetoothPromptShownThisVisit;
    private boolean autoConnectRequestedThisVisit;
    private boolean autoNavigatedToPlayThisVisit;
    private int consecutiveFullyConnectedPolls;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BleSessionManager.initialize(getApplicationContext());
        binding = ActivityHomeBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.topNavBar.setTitleText("Setup");
        binding.tvHomeTitle.setText("Theremin Gloves");
        binding.tvHomeSubtitle.setText("Turn on your gloves and the app will try to connect them automatically.");
        binding.btnPrimaryAction.setOnClickListener(v -> handlePrimaryAction());
        binding.btnSecondaryAction.setOnClickListener(v -> NavigationUtils.openScreen(this, ConnectGlovesActivity.class));
        binding.btnCalibrate.setOnClickListener(v -> NavigationUtils.openScreen(this, CalibrationActivity.class));
        refreshHomeSnapshot();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BleSessionManager.initialize(getApplicationContext());
        autoNavigatedToPlayThisVisit = false;
        consecutiveFullyConnectedPolls = 0;
        uiPoller.start();
        kickAutomaticSetupFlow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiPoller.stop();
    }

    private void handlePrimaryAction() {
        BleSessionManager.runWhenReady(this, REQ_BLE_PERMS, REQ_ENABLE_BT, () -> {
            BleSnapshot snapshot = BleSessionManager.getSnapshot();
            if (snapshot != null && snapshot.areBothGlovesConnected()) {
                openPlay();
                return;
            }
            autoConnectRequestedThisVisit = false;
            kickAutomaticSetupFlow();
        });
    }

    private void kickAutomaticSetupFlow() {
        if (!BleSessionManager.hasRequiredPermissions(this)) {
            if (!permissionPromptShownThisVisit) {
                permissionPromptShownThisVisit = true;
                BleSessionManager.requestRequiredPermissions(this, REQ_BLE_PERMS);
            }
            return;
        }
        if (!BleSessionManager.isBluetoothEnabled(this)) {
            if (!bluetoothPromptShownThisVisit) {
                bluetoothPromptShownThisVisit = true;
                BleSessionManager.requestEnableBluetoothPrompt(this, REQ_ENABLE_BT);
            }
            return;
        }
        maybeAutoConnectIfPossible();
    }

    private void maybeAutoConnectIfPossible() {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        if (autoConnectRequestedThisVisit) return;
        if (snapshot != null && snapshot.areBothGlovesConnected()) {
            maybeAutoOpenPlayNow();
            return;
        }
        autoConnectRequestedThisVisit = true;
        BleSessionManager.maybeStartAutoConnect();
    }

    private void maybeAutoOpenPlayNow() {
        if (autoNavigatedToPlayThisVisit) return;
        autoNavigatedToPlayThisVisit = true;
        openPlay();
    }

    private void openPlay() {
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        overridePendingTransition(0, 0);
        finish();
    }

    private void refreshHomeSnapshot() {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        renderPitchAndVolume(snapshot);

        if (snapshot == null || !snapshot.hostReady) {
            setWaitingState("Starting connection", "Getting Bluetooth ready...", "Please Wait", false, null, false);
            return;
        }

        boolean permissionsOk = BleSessionManager.hasRequiredPermissions(this);
        boolean bluetoothOn = snapshot.isBluetoothOn() && BleSessionManager.isBluetoothEnabled(this);

        if (!permissionsOk) {
            setWaitingState("Bluetooth permissions needed",
                    "Allow Bluetooth permissions so the app can find and connect your gloves.",
                    "Grant Bluetooth Permissions", true, null, false);
            return;
        }
        if (!bluetoothOn) {
            setWaitingState("Bluetooth is off",
                    "Turn Bluetooth on and the app will start looking for your gloves automatically.",
                    "Turn On Bluetooth", true, null, false);
            return;
        }
        if (snapshot.areBothGlovesConnected()) {
            consecutiveFullyConnectedPolls++;
            showState("Gloves connected", "Opening Play so you can start right away.", "Open Play Now", true, null, true);
            if (consecutiveFullyConnectedPolls >= REQUIRED_CONNECTED_POLLS_BEFORE_AUTOPLAY) maybeAutoOpenPlayNow();
            return;
        }

        consecutiveFullyConnectedPolls = 0;
        if (snapshot.isAnyGloveConnecting()) {
            showState(snapshot.isAnyGloveConnected() ? "One glove connected" : "Looking for gloves",
                    snapshot.isAnyGloveConnected()
                            ? "Keep both gloves turned on and close to the phone."
                            : "Make sure both gloves are turned on and close to your phone.",
                    "Retry Connection", true, "Connection Details", false);
            return;
        }

        showState("Looking for gloves", "The app is ready and trying to connect automatically.",
                "Retry Connection", true, "Connection Details", false);
        maybeAutoConnectIfPossible();
    }

    private void setWaitingState(String status, String hint, String button, boolean primaryEnabled,
                                 String secondary, boolean showCalibrate) {
        consecutiveFullyConnectedPolls = 0;
        showState(status, hint, button, primaryEnabled, secondary, showCalibrate);
    }

    private void renderPitchAndVolume(BleSnapshot snapshot) {
        binding.tvPitchStatus.setText("Pitch glove • " + (snapshot == null ? "—" : snapshot.connectionDetail(true)));
        binding.tvVolumeStatus.setText("Volume glove • " + (snapshot == null ? "—" : snapshot.connectionDetail(false)));
    }

    private void showState(String systemStatus, String setupHint, String primaryText,
                           boolean primaryEnabled, String secondaryText, boolean showCalibrate) {
        binding.tvSystemStatus.setText(systemStatus);
        binding.tvSetupHint.setText(setupHint);
        binding.btnPrimaryAction.setVisibility(View.VISIBLE);
        binding.btnPrimaryAction.setEnabled(primaryEnabled);
        binding.btnPrimaryAction.setText(primaryText);
        binding.btnSecondaryAction.setVisibility(secondaryText == null ? View.GONE : View.VISIBLE);
        if (secondaryText != null) binding.btnSecondaryAction.setText(secondaryText);
        binding.btnCalibrate.setVisibility(showCalibrate ? View.VISIBLE : View.GONE);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_ENABLE_BT) return;
        autoConnectRequestedThisVisit = false;
        if (BleSessionManager.isBluetoothEnabled(this)) maybeAutoConnectIfPossible();
        refreshHomeSnapshot();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLE_PERMS && BleSessionManager.wereAllPermissionsGranted(grantResults)) {
            autoConnectRequestedThisVisit = false;
            if (!BleSessionManager.isBluetoothEnabled(this)) {
                bluetoothPromptShownThisVisit = false;
                kickAutomaticSetupFlow();
            } else {
                maybeAutoConnectIfPossible();
            }
        }
        refreshHomeSnapshot();
    }
}
