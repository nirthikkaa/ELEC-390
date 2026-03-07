package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityHomeBinding;

/**
 * Friendly setup screen.
 *
 * The whole job of this screen is simple:
 * help the user get permissions/Bluetooth sorted out,
 * try auto-connect when it makes sense,
 * and get out of the way once both gloves are ready.
 */
public class HomeActivity extends AppCompatActivity {

    private ActivityHomeBinding binding;

    private static final long UI_POLL_MS = 200L;
    private static final int REQ_ENABLE_BT = 4201;
    private static final int REQ_BLE_PERMS = 4202;
    private static final int REQUIRED_CONNECTED_POLLS_BEFORE_AUTOPLAY = 2;

    private TextView tvHomeTitle;
    private TextView tvHomeSubtitle;
    private TextView tvSystemStatus;
    private TextView tvSetupHint;
    private TextView tvPitchStatus;
    private TextView tvVolumeStatus;

    private Button btnPrimaryAction;
    private Button btnSecondaryAction;
    private Button btnCalibrate;

    private final UiPoller uiPoller = new UiPoller(UI_POLL_MS, this::refreshHomeSnapshot);

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

        bindViews();
        wireButtons();
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

    private void bindViews() {
        binding.topNavBar.setTitleText("Setup");

        tvHomeTitle = binding.tvHomeTitle;
        tvHomeSubtitle = binding.tvHomeSubtitle;
        tvSystemStatus = binding.tvSystemStatus;
        tvSetupHint = binding.tvSetupHint;
        tvPitchStatus = binding.tvPitchStatus;
        tvVolumeStatus = binding.tvVolumeStatus;

        btnPrimaryAction = binding.btnPrimaryAction;
        btnSecondaryAction = binding.btnSecondaryAction;
        btnCalibrate = binding.btnCalibrate;

        tvHomeTitle.setText("Theremin Gloves");
        tvHomeSubtitle.setText("Turn on your gloves and the app will try to connect them automatically.");
    }

    private void wireButtons() {
        btnPrimaryAction.setOnClickListener(v -> handlePrimaryAction());
        btnSecondaryAction.setOnClickListener(v -> NavigationUtils.openScreen(this, ConnectGlovesActivity.class));
        btnCalibrate.setOnClickListener(v -> NavigationUtils.openScreen(this, CalibrationActivity.class));
    }

    private void handlePrimaryAction() {
        BleActionExecutor.runWhenReady(this, REQ_BLE_PERMS, REQ_ENABLE_BT, () -> {
            if (areBothGlovesConnected()) {
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
                BluetoothRequirements.requestRequiredPermissions(this, REQ_BLE_PERMS);
            }
            return;
        }

        if (!BluetoothRequirements.isBluetoothEnabled(this)) {
            if (!bluetoothPromptShownThisVisit) {
                bluetoothPromptShownThisVisit = true;
                BluetoothRequirements.requestEnableBluetoothPrompt(this, REQ_ENABLE_BT);
            }
            return;
        }

        maybeAutoConnectIfPossible();
    }

    private void maybeAutoConnectIfPossible() {
        if (autoConnectRequestedThisVisit) {
            return;
        }

        if (areBothGlovesConnected()) {
            maybeAutoOpenPlayNow();
            return;
        }

        autoConnectRequestedThisVisit = true;
        BleSessionManager.maybeStartAutoConnect();
    }

    private void maybeAutoOpenPlayNow() {
        if (autoNavigatedToPlayThisVisit) {
            return;
        }
        autoNavigatedToPlayThisVisit = true;
        openPlay();
    }

    private void openPlay() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    private void refreshHomeSnapshot() {
        // This screen only reads one combined snapshot now.
        // Less plumbing here means fewer ways for the UI to disagree with itself.
        BleSnapshot snapshot = BleSessionManager.getSnapshot();

        if (snapshot == null || !snapshot.hostReady) {
            consecutiveFullyConnectedPolls = 0;
            renderPitchAndVolume(snapshot);
            showState(
                    "Starting connection",
                    "Getting Bluetooth ready...",
                    "Please Wait",
                    false,
                    false,
                    null,
                    false
            );
            return;
        }

        boolean permissionsOk = BleSessionManager.hasRequiredPermissions(this);
        boolean bluetoothOn = BluetoothRequirements.isBluetoothEnabled(this) && snapshot.bluetoothEnabled;
        boolean pitchConnected = BleUiText.isPitchConnected(snapshot);
        boolean volumeConnected = BleUiText.isVolumeConnected(snapshot);
        boolean anyConnecting = BleUiText.isAnyGloveConnecting(snapshot);

        renderPitchAndVolume(snapshot);

        if (!permissionsOk) {
            consecutiveFullyConnectedPolls = 0;
            showState(
                    "Bluetooth permissions needed",
                    "Allow Bluetooth permissions so the app can find and connect your gloves.",
                    "Grant Bluetooth Permissions",
                    true,
                    false,
                    null,
                    false
            );
            return;
        }

        if (!bluetoothOn) {
            consecutiveFullyConnectedPolls = 0;
            showState(
                    "Bluetooth is off",
                    "Turn Bluetooth on and the app will start looking for your gloves automatically.",
                    "Turn On Bluetooth",
                    true,
                    false,
                    null,
                    false
            );
            return;
        }

        if (BleUiText.areBothGlovesConnected(snapshot)) {
            consecutiveFullyConnectedPolls++;
            showState(
                    "Gloves connected",
                    "Opening Play so you can start right away.",
                    "Open Play Now",
                    true,
                    false,
                    null,
                    true
            );

            if (consecutiveFullyConnectedPolls >= REQUIRED_CONNECTED_POLLS_BEFORE_AUTOPLAY) {
                maybeAutoOpenPlayNow();
            }
            return;
        }

        consecutiveFullyConnectedPolls = 0;

        if (anyConnecting || snapshot.scanning) {
            String systemText = (pitchConnected || volumeConnected)
                    ? "One glove connected"
                    : "Looking for gloves";
            String hintText = (pitchConnected || volumeConnected)
                    ? "Keep both gloves turned on and close to the phone."
                    : "Make sure both gloves are turned on and close to your phone.";

            showState(systemText, hintText, "Retry Connection", true, true, "Connection Details", false);
            return;
        }

        showState(
                "Looking for gloves",
                "The app is ready and trying to connect automatically.",
                "Retry Connection",
                true,
                true,
                "Connection Details",
                false
        );
        maybeAutoConnectIfPossible();
    }

    private void renderPitchAndVolume(BleSnapshot snapshot) {
        String pitchText = snapshot == null ? "—" : BleUiText.cleanConnectionText(snapshot.pitchConnText);
        String volumeText = snapshot == null ? "—" : BleUiText.cleanConnectionText(snapshot.volumeConnText);

        tvPitchStatus.setText("Pitch glove • " + pitchText);
        tvVolumeStatus.setText("Volume glove • " + volumeText);
    }

    private void showState(String systemStatus,
                           String setupHint,
                           String primaryText,
                           boolean primaryEnabled,
                           boolean showSecondary,
                           String secondaryText,
                           boolean showCalibrate) {
        tvSystemStatus.setText(systemStatus);
        tvSetupHint.setText(setupHint);

        btnPrimaryAction.setVisibility(View.VISIBLE);
        btnPrimaryAction.setEnabled(primaryEnabled);
        btnPrimaryAction.setText(primaryText);

        btnSecondaryAction.setVisibility(showSecondary ? View.VISIBLE : View.GONE);
        if (showSecondary && secondaryText != null) {
            btnSecondaryAction.setText(secondaryText);
        }

        btnCalibrate.setVisibility(showCalibrate ? View.VISIBLE : View.GONE);
    }

    private boolean areBothGlovesConnected() {
        return BleUiText.areBothGlovesConnected(BleSessionManager.getSnapshot());
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ENABLE_BT) {
            autoConnectRequestedThisVisit = false;
            if (BluetoothRequirements.isBluetoothEnabled(this)) {
                maybeAutoConnectIfPossible();
            }
            refreshHomeSnapshot();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQ_BLE_PERMS) {
            return;
        }

        if (BleActionExecutor.wereAllPermissionsGranted(grantResults)) {
            autoConnectRequestedThisVisit = false;
            if (!BluetoothRequirements.isBluetoothEnabled(this)) {
                bluetoothPromptShownThisVisit = false;
                kickAutomaticSetupFlow();
            } else {
                maybeAutoConnectIfPossible();
            }
        }

        refreshHomeSnapshot();
    }
}
