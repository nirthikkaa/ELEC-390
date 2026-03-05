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

import java.util.Locale;

public class ConnectGlovesActivity extends AppCompatActivity {

    private static final long UI_POLL_MS = 150L;

    private static final int REQ_PERMS = 4101;
    private static final int REQ_ENABLE_BT = 4102;

    private TopNavBarView topNavBar;

    private TextView tvReadyHeadline;
    private TextView tvReadySubtext;
    private TextView tvBigStatus;
    private TextView tvPairSummary;
    private TextView tvHostNote;

    private TextView tvPitchConn;
    private TextView tvPitchLast;

    private TextView tvVolConn;
    private TextView tvVolLast;

    private Button btnReconnectPitch;
    private Button btnReconnectVolume;
    private Button btnConnectToggle;
    private Button btnRefreshInfo;
    private Button btnOpenPlay;
    private Button btnGoCalibration;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean autoConnectRequestedThisVisit = false;

    private final Runnable uiPollRunnable = new Runnable() {
        @Override
        public void run() {
            refreshFromBleHost();
            mainHandler.postDelayed(this, UI_POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BleHostBridge.initialize(getApplicationContext());
        setContentView(R.layout.activity_connect_gloves);

        bindViews();
        wireButtons();
        refreshFromBleHost();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BleHostBridge.initialize(getApplicationContext());

        mainHandler.removeCallbacks(uiPollRunnable);
        mainHandler.post(uiPollRunnable);

    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacks(uiPollRunnable);
    }

    private void bindViews() {
        topNavBar = findViewById(R.id.topNavBar);

        tvReadyHeadline = findViewById(R.id.tvReadyHeadline);
        tvReadySubtext = findViewById(R.id.tvReadySubtext);
        tvBigStatus = findViewById(R.id.tvBigStatus);
        tvPairSummary = findViewById(R.id.tvPairSummary);
        tvHostNote = findViewById(R.id.tvHostNote);

        tvPitchConn = findViewById(R.id.tvPitchConn);
        tvPitchLast = findViewById(R.id.tvPitchLast);

        tvVolConn = findViewById(R.id.tvVolConn);
        tvVolLast = findViewById(R.id.tvVolLast);

        btnReconnectPitch = findViewById(R.id.btnReconnectPitch);
        btnReconnectVolume = findViewById(R.id.btnReconnectVolume);
        btnConnectToggle = findViewById(R.id.btnConnectToggle);
        btnRefreshInfo = findViewById(R.id.btnRefreshInfo);
        btnOpenPlay = findViewById(R.id.btnOpenPlay);
        btnGoCalibration = findViewById(R.id.btnGoCalibration);

        if (topNavBar != null) {
            topNavBar.setTitleText("Connect Gloves");
        }
    }

    private void wireButtons() {
        btnReconnectPitch.setOnClickListener(v -> requestSingleGloveReconnect(true));
        btnReconnectVolume.setOnClickListener(v -> requestSingleGloveReconnect(false));

        btnConnectToggle.setOnClickListener(v -> {
            if (!BleHostBridge.hasRequiredPermissions(this)) {
                BluetoothRequirements.requestRequiredPermissions(this, REQ_PERMS);
                return;
            }

            if (!BluetoothRequirements.isBluetoothEnabled(this)) {
                BluetoothRequirements.requestEnableBluetoothPrompt(this, REQ_ENABLE_BT);
                return;
            }

            BleHostBridge.BleUiSnapshot snapshot = BleHostBridge.getBleUiSnapshot();
            boolean willDisconnect = snapshot != null && snapshot.hostReady && snapshot.busyOrConnected;

            // Manual disconnect from this screen should stay disconnected until the
            // user explicitly retries from this screen.
            autoConnectRequestedThisVisit = willDisconnect;
            if (!willDisconnect) {
                autoConnectRequestedThisVisit = false;
            }

            BleHostBridge.requestBleToggle();
            refreshFromBleHost();
        });

        btnRefreshInfo.setOnClickListener(v -> {
            if (!BleHostBridge.hasRequiredPermissions(this)) {
                BluetoothRequirements.requestRequiredPermissions(this, REQ_PERMS);
                return;
            }

            if (!BluetoothRequirements.isBluetoothEnabled(this)) {
                BluetoothRequirements.requestEnableBluetoothPrompt(this, REQ_ENABLE_BT);
                return;
            }

            autoConnectRequestedThisVisit = false;
            BleHostBridge.requestRefreshHandshake();
            refreshFromBleHost();
        });

        btnOpenPlay.setOnClickListener(v -> openPlay());

        btnGoCalibration.setOnClickListener(v -> {
            Intent intent = new Intent(this, CalibrationActivity.class);
            intent.addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION
            );
            startActivity(intent);
            overridePendingTransition(0, 0);
        });
    }

    private void maybeAutoConnectNow() {
        // Connect Gloves is the manual troubleshooting screen.
        // Keep the method compile-safe, but do not auto-start connections when
        // this screen opens or when the user navigates back to it.
    }

    private void requestSingleGloveReconnect(boolean isPitch) {
        if (!BleHostBridge.hasRequiredPermissions(this)) {
            BluetoothRequirements.requestRequiredPermissions(this, REQ_PERMS);
            return;
        }

        if (!BluetoothRequirements.isBluetoothEnabled(this)) {
            BluetoothRequirements.requestEnableBluetoothPrompt(this, REQ_ENABLE_BT);
            return;
        }

        autoConnectRequestedThisVisit = true;
        BleHostBridge.requestReconnectGlove(isPitch);
        refreshFromBleHost();
    }

    private void refreshFromBleHost() {
        BleHostBridge.BleUiSnapshot snapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        if (snapshot == null || !snapshot.hostReady) {
            tvReadyHeadline.setText("Preparing Bluetooth");
            tvReadySubtext.setText("Connection controls will appear when the Bluetooth session is ready.");
            tvBigStatus.setText("Preparing");
            tvPairSummary.setText("Waiting for connection engine");

            tvPitchConn.setText("Unavailable");
            tvPitchLast.setText("—");

            tvVolConn.setText("Unavailable");
            tvVolLast.setText("—");

            tvHostNote.setText("Waiting for connection events...");
            btnRefreshInfo.setVisibility(View.GONE);
            btnGoCalibration.setVisibility(View.GONE);
            btnReconnectPitch.setEnabled(false);
            btnReconnectVolume.setEnabled(false);
            btnConnectToggle.setText("Preparing...");
            btnConnectToggle.setEnabled(false);
            return;
        }

        boolean permissionsOk = BleHostBridge.hasRequiredPermissions(this);
        boolean bluetoothOn = BluetoothRequirements.isBluetoothEnabled(this);

        boolean pitchConnected = calSnapshot != null && calSnapshot.pitchConnected;
        boolean volumeConnected = calSnapshot != null && calSnapshot.volumeConnected;

        boolean pitchConnecting = isConnecting(snapshot.pitchConnText);
        boolean volumeConnecting = isConnecting(snapshot.volumeConnText);

        int connectedCount = 0;
        if (pitchConnected) connectedCount++;
        if (volumeConnected) connectedCount++;

        tvBigStatus.setText(stripStatusPrefix(snapshot.statusText));
        tvPairSummary.setText(buildPairSummary(connectedCount, pitchConnecting, volumeConnecting));

        tvPitchConn.setText(cleanConnectionText(snapshot.pitchConnText));
        tvPitchLast.setText(cleanLastValue(snapshot.pitchLastText, "Pitch last:"));

        tvVolConn.setText(cleanConnectionText(snapshot.volumeConnText));
        tvVolLast.setText(cleanLastValue(snapshot.volumeLastText, "Volume last:"));

        tvHostNote.setVisibility(View.VISIBLE);
        tvHostNote.setText(buildHostNote(snapshot));

        if (!permissionsOk) {
            tvReadyHeadline.setText("Bluetooth permissions needed");
            tvReadySubtext.setText("Allow Bluetooth permissions so the app can scan and connect your gloves.");
        } else if (!bluetoothOn || !snapshot.bluetoothEnabled) {
            tvReadyHeadline.setText("Bluetooth is off");
            tvReadySubtext.setText("Turn Bluetooth on, then the app will try to connect your gloves.");
        } else if (pitchConnected && volumeConnected) {
            tvReadyHeadline.setText("Ready to play");
            tvReadySubtext.setText("Both gloves are connected. Stay here to manage connections, or tap Play.");
        } else if (connectedCount == 1) {
            tvReadyHeadline.setText("One glove connected");
            tvReadySubtext.setText("Keep both gloves awake and nearby, then connect the remaining glove.");
        } else if (snapshot.scanning || pitchConnecting || volumeConnecting) {
            tvReadyHeadline.setText("Connecting gloves");
            tvReadySubtext.setText("Keep both gloves turned on and close to the phone.");
        } else {
            tvReadyHeadline.setText("Connection ready");
            tvReadySubtext.setText("Tap Connect to start, or retry if your gloves are not found.");
        }

        btnRefreshInfo.setVisibility(View.VISIBLE);
        btnGoCalibration.setVisibility(View.VISIBLE);

        btnReconnectPitch.setEnabled(permissionsOk && bluetoothOn);
        btnReconnectVolume.setEnabled(permissionsOk && bluetoothOn);

        boolean isBusyOrConnected = snapshot.busyOrConnected;
        btnConnectToggle.setEnabled(permissionsOk);
        btnConnectToggle.setText(isBusyOrConnected ? "Disconnect All" : "Connect All");

        btnOpenPlay.setEnabled(pitchConnected && volumeConnected);
    }

    private void openPlay() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
        );
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private boolean isConnecting(String text) {
        return text != null && text.contains("CONNECTING");
    }

    private String stripStatusPrefix(String text) {
        if (text == null) return "—";
        return text.trim().replace("Status: ", "");
    }

    private String cleanConnectionText(String text) {
        if (text == null || text.trim().isEmpty()) return "—";
        return text.trim()
                .replace("Pitch (ThereminGlove): ", "")
                .replace("Volume (ThereminGloveVol): ", "");
    }

    private String cleanLastValue(String text, String prefix) {
        if (text == null || text.trim().isEmpty()) return "—";
        String cleaned = text.trim();
        if (prefix != null && cleaned.startsWith(prefix)) {
            cleaned = cleaned.substring(prefix.length()).trim();
        }
        return cleaned.isEmpty() ? "—" : cleaned;
    }

    private String buildPairSummary(int connectedCount, boolean pitchConnecting, boolean volumeConnecting) {
        if (connectedCount == 2) return "Both gloves connected";
        if (connectedCount == 1) return "One glove connected";
        if (pitchConnecting || volumeConnecting) return "Connecting...";
        return "No gloves connected";
    }

    private String buildHostNote(BleHostBridge.BleUiSnapshot snapshot) {
        if (snapshot == null) return "—";
        if (snapshot.scanning) return "Scanning nearby devices...";
        if (snapshot.busyOrConnected) return "Connection session active.";
        return "Ready to connect.";
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ENABLE_BT) {
            autoConnectRequestedThisVisit = false;
            if (BluetoothRequirements.isBluetoothEnabled(this)) {
            }
            refreshFromBleHost();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            @NonNull String[] permissions,
            @NonNull int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQ_PERMS) {
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
        }

        refreshFromBleHost();
    }
}