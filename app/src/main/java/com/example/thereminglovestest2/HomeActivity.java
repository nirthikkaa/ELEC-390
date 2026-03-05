package com.example.thereminglovestest2;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

public class HomeActivity extends AppCompatActivity {

    private static final long UI_POLL_MS = 200L;

    private static final int REQ_ENABLE_BT = 4201;
    private static final int REQ_BLE_PERMS = 4202;

    private static final int REQUIRED_CONNECTED_POLLS_BEFORE_AUTOPLAY = 2;

    private TopNavBarView topNavBar;

    private TextView tvHomeTitle;
    private TextView tvHomeSubtitle;
    private TextView tvSystemStatus;
    private TextView tvSetupHint;
    private TextView tvPitchStatus;
    private TextView tvVolumeStatus;

    private Button btnPrimaryAction;
    private Button btnSecondaryAction;
    private Button btnCalibrate;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean permissionPromptShownThisVisit = false;
    private boolean bluetoothPromptShownThisVisit = false;
    private boolean autoConnectRequestedThisVisit = false;
    private boolean autoNavigatedToPlayThisVisit = false;

    private int consecutiveFullyConnectedPolls = 0;

    private final Runnable uiPollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshHomeSnapshot();
            mainHandler.postDelayed(this, UI_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BleHostBridge.initialize(getApplicationContext());
        setContentView(R.layout.activity_home);

        bindViews();
        wireButtons();
        refreshHomeSnapshot();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BleHostBridge.initialize(getApplicationContext());

        autoNavigatedToPlayThisVisit = false;
        consecutiveFullyConnectedPolls = 0;

        mainHandler.removeCallbacks(uiPollRunnable);
        mainHandler.post(uiPollRunnable);

        kickAutomaticSetupFlow();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacks(uiPollRunnable);
    }

    private void bindViews() {
        topNavBar = findViewById(R.id.topNavBar);

        tvHomeTitle = findViewById(R.id.tvHomeTitle);
        tvHomeSubtitle = findViewById(R.id.tvHomeSubtitle);
        tvSystemStatus = findViewById(R.id.tvSystemStatus);
        tvSetupHint = findViewById(R.id.tvSetupHint);
        tvPitchStatus = findViewById(R.id.tvPitchStatus);
        tvVolumeStatus = findViewById(R.id.tvVolumeStatus);

        btnPrimaryAction = findViewById(R.id.btnPrimaryAction);
        btnSecondaryAction = findViewById(R.id.btnSecondaryAction);
        btnCalibrate = findViewById(R.id.btnCalibrate);

        if (topNavBar != null) {
            topNavBar.setTitleText("Setup");
        }

        tvHomeTitle.setText("Theremin Gloves");
        tvHomeSubtitle.setText("Turn on your gloves and the app will connect them automatically.");
    }

    private void wireButtons() {
        btnPrimaryAction.setOnClickListener(v -> handlePrimaryAction());
        btnSecondaryAction.setOnClickListener(v -> NavigationUtils.openScreen(this, ConnectGlovesActivity.class));
        btnCalibrate.setOnClickListener(v -> NavigationUtils.openScreen(this, CalibrationActivity.class));
    }

    private void handlePrimaryAction() {
        if (!BleHostBridge.hasRequiredPermissions(this)) {
            BluetoothRequirements.requestRequiredPermissions(this, REQ_BLE_PERMS);
            return;
        }

        if (!BluetoothRequirements.isBluetoothEnabled(this)) {
            BluetoothRequirements.requestEnableBluetoothPrompt(this, REQ_ENABLE_BT);
            return;
        }

        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();
        if (calSnapshot != null && calSnapshot.hostReady
                && calSnapshot.pitchConnected && calSnapshot.volumeConnected) {
            launchPlayAutomatically();
            return;
        }

        autoConnectRequestedThisVisit = false;
        kickAutomaticSetupFlow();
    }

    private void kickAutomaticSetupFlow() {
        if (!BleHostBridge.hasRequiredPermissions(this)) {
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

        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();
        if (calSnapshot != null && calSnapshot.hostReady
                && calSnapshot.pitchConnected && calSnapshot.volumeConnected) {
            maybeAutoOpenPlayNow();
            return;
        }

        autoConnectRequestedThisVisit = true;
        BleHostBridge.maybeStartAutoConnect();
    }

    private void maybeAutoOpenPlayNow() {
        if (autoNavigatedToPlayThisVisit) {
            return;
        }

        autoNavigatedToPlayThisVisit = true;
        launchPlayAutomatically();
    }

    private void launchPlayAutomatically() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
        );
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    private void refreshHomeSnapshot() {
        BleHostBridge.BleUiSnapshot snapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        if (snapshot == null || !snapshot.hostReady) {
            consecutiveFullyConnectedPolls = 0;

            tvSystemStatus.setText("Starting connection");
            tvSetupHint.setText("Getting Bluetooth ready...");
            tvPitchStatus.setText("Pitch glove • —");
            tvVolumeStatus.setText("Volume glove • —");

            btnPrimaryAction.setVisibility(View.VISIBLE);
            btnPrimaryAction.setEnabled(false);
            btnPrimaryAction.setText("Please Wait");

            btnSecondaryAction.setVisibility(View.GONE);
            btnCalibrate.setVisibility(View.GONE);
            return;
        }

        boolean permissionsOk = BleHostBridge.hasRequiredPermissions(this);
        boolean bluetoothOn = BluetoothRequirements.isBluetoothEnabled(this);

        boolean pitchConnected = calSnapshot != null && calSnapshot.pitchConnected;
        boolean volumeConnected = calSnapshot != null && calSnapshot.volumeConnected;

        boolean pitchConnecting = BleUiText.isConnecting(snapshot.pitchConnText);
        boolean volumeConnecting = BleUiText.isConnecting(snapshot.volumeConnText);

        int connectedCount = 0;
        if (pitchConnected) connectedCount++;
        if (volumeConnected) connectedCount++;

        tvPitchStatus.setText("Pitch glove • " + BleUiText.cleanConnectionText(snapshot.pitchConnText));
        tvVolumeStatus.setText("Volume glove • " + BleUiText.cleanConnectionText(snapshot.volumeConnText));

        if (!permissionsOk) {
            consecutiveFullyConnectedPolls = 0;

            tvSystemStatus.setText("Bluetooth permissions needed");
            tvSetupHint.setText("Allow Bluetooth permissions so the app can find and connect your gloves.");

            btnPrimaryAction.setVisibility(View.VISIBLE);
            btnPrimaryAction.setEnabled(true);
            btnPrimaryAction.setText("Grant Bluetooth Permissions");

            btnSecondaryAction.setVisibility(View.GONE);
            btnCalibrate.setVisibility(View.GONE);
            return;
        }

        if (!bluetoothOn || !snapshot.bluetoothEnabled) {
            consecutiveFullyConnectedPolls = 0;

            tvSystemStatus.setText("Bluetooth is off");
            tvSetupHint.setText("Turn Bluetooth on and the app will start looking for your gloves automatically.");

            btnPrimaryAction.setVisibility(View.VISIBLE);
            btnPrimaryAction.setEnabled(true);
            btnPrimaryAction.setText("Turn On Bluetooth");

            btnSecondaryAction.setVisibility(View.GONE);
            btnCalibrate.setVisibility(View.GONE);
            return;
        }

        if (pitchConnected && volumeConnected) {
            consecutiveFullyConnectedPolls++;

            tvSystemStatus.setText("Gloves connected");
            tvSetupHint.setText("Opening Play so you can start right away.");

            btnPrimaryAction.setVisibility(View.VISIBLE);
            btnPrimaryAction.setEnabled(true);
            btnPrimaryAction.setText("Open Play Now");

            btnSecondaryAction.setVisibility(View.GONE);
            btnCalibrate.setVisibility(View.VISIBLE);

            if (consecutiveFullyConnectedPolls >= REQUIRED_CONNECTED_POLLS_BEFORE_AUTOPLAY) {
                maybeAutoOpenPlayNow();
            }
            return;
        }

        consecutiveFullyConnectedPolls = 0;

        if (snapshot.scanning || pitchConnecting || volumeConnecting) {
            if (connectedCount == 1) {
                tvSystemStatus.setText("One glove connected");
                tvSetupHint.setText("Keep both gloves turned on and close to the phone.");
            } else {
                tvSystemStatus.setText("Looking for gloves");
                tvSetupHint.setText("Make sure both gloves are turned on and close to your phone.");
            }

            btnPrimaryAction.setVisibility(View.VISIBLE);
            btnPrimaryAction.setEnabled(true);
            btnPrimaryAction.setText("Retry Connection");

            btnSecondaryAction.setVisibility(View.VISIBLE);
            btnSecondaryAction.setText("Connection Details");

            btnCalibrate.setVisibility(View.GONE);
            return;
        }

        tvSystemStatus.setText("Looking for gloves");
        tvSetupHint.setText("The app is ready and trying to connect automatically.");

        btnPrimaryAction.setVisibility(View.VISIBLE);
        btnPrimaryAction.setEnabled(true);
        btnPrimaryAction.setText("Retry Connection");

        btnSecondaryAction.setVisibility(View.VISIBLE);
        btnSecondaryAction.setText("Connection Details");

        btnCalibrate.setVisibility(View.GONE);

        maybeAutoConnectIfPossible();
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
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQ_BLE_PERMS) {
            return;
        }

        boolean allGranted = true;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
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