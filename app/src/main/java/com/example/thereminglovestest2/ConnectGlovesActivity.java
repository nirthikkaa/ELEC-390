package com.example.thereminglovestest2;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

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

        maybeAutoConnectNow();
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
                requestRequiredPermissions();
                return;
            }

            if (!isBluetoothEnabled()) {
                requestEnableBluetoothPrompt();
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
                requestRequiredPermissions();
                return;
            }

            if (!isBluetoothEnabled()) {
                requestEnableBluetoothPrompt();
                return;
            }

            autoConnectRequestedThisVisit = false;
            maybeAutoConnectNow();
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
        if (autoConnectRequestedThisVisit) {
            return;
        }

        if (!BleHostBridge.hasRequiredPermissions(this)) {
            return;
        }

        if (!isBluetoothEnabled()) {
            return;
        }

        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();
        if (calSnapshot != null && calSnapshot.hostReady
                && calSnapshot.pitchConnected && calSnapshot.volumeConnected) {
            return;
        }

        autoConnectRequestedThisVisit = true;
        BleHostBridge.maybeStartAutoConnect();
    }

    private void requestSingleGloveReconnect(boolean isPitch) {
        if (!BleHostBridge.hasRequiredPermissions(this)) {
            requestRequiredPermissions();
            return;
        }

        if (!isBluetoothEnabled()) {
            requestEnableBluetoothPrompt();
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
        boolean bluetoothOn = isBluetoothEnabled();

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
            tvReadyHeadline.setText("Searching for gloves");
            tvReadySubtext.setText("The app is scanning and pairing. Keep the gloves close to the phone.");
        } else {
            tvReadyHeadline.setText("Looking for gloves");
            tvReadySubtext.setText("The app is ready. Tap Connect if you want to try again.");
            maybeAutoConnectNow();
        }

        btnConnectToggle.setEnabled(true);
        btnRefreshInfo.setEnabled(true);
        btnOpenPlay.setVisibility(View.VISIBLE);
        btnGoCalibration.setVisibility((pitchConnected && volumeConnected) ? View.VISIBLE : View.GONE);
        btnRefreshInfo.setVisibility(View.VISIBLE);

        boolean canUseSingleReconnect = permissionsOk && bluetoothOn && snapshot.hostReady;
        btnReconnectPitch.setEnabled(canUseSingleReconnect);
        btnReconnectVolume.setEnabled(canUseSingleReconnect);

        if (pitchConnecting) {
            btnReconnectPitch.setText("Pitch Connecting...");
        } else if (pitchConnected) {
            btnReconnectPitch.setText("Reconnect Pitch Glove");
        } else {
            btnReconnectPitch.setText("Retry Pitch Glove");
        }

        if (volumeConnecting) {
            btnReconnectVolume.setText("Volume Connecting...");
        } else if (volumeConnected) {
            btnReconnectVolume.setText("Reconnect Volume Glove");
        } else {
            btnReconnectVolume.setText("Retry Volume Glove");
        }

        if (!permissionsOk) {
            btnConnectToggle.setText("Grant Bluetooth Permissions");
        } else if (!bluetoothOn || !snapshot.bluetoothEnabled) {
            btnConnectToggle.setText("Turn On Bluetooth");
        } else if (snapshot.busyOrConnected) {
            btnConnectToggle.setText("Disconnect Gloves");
        } else {
            btnConnectToggle.setText("Connect Gloves");
        }
    }

    private String buildHostNote(BleHostBridge.BleUiSnapshot snapshot) {
        if (snapshot == null || snapshot.recentEventsText == null || snapshot.recentEventsText.trim().isEmpty()) {
            return "No connection events yet.";
        }
        return snapshot.recentEventsText.trim();
    }

    private String buildPairSummary(int connectedCount, boolean pitchConnecting, boolean volumeConnecting) {
        StringBuilder sb = new StringBuilder();
        sb.append("Connected gloves: ").append(connectedCount).append(" / 2");

        if (pitchConnecting || volumeConnecting) {
            sb.append("  •  Pairing in progress");
        } else if (connectedCount == 2) {
            sb.append("  •  Ready");
        } else if (connectedCount == 1) {
            sb.append("  •  Waiting for second glove");
        } else {
            sb.append("  •  Not connected");
        }

        return sb.toString();
    }

    private String stripStatusPrefix(String raw) {
        if (raw == null) return "—";
        String trimmed = raw.trim();
        if (trimmed.startsWith("Status:")) {
            trimmed = trimmed.substring("Status:".length()).trim();
        }
        return trimmed.isEmpty() ? "—" : trimmed;
    }

    private String cleanConnectionText(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "Unavailable";
        }

        String upper = raw.toUpperCase(Locale.US);

        if (upper.contains("CONNECTING")) {
            return "Connecting";
        }
        if (upper.contains("DISCONNECTED")) {
            return "Disconnected";
        }
        if (upper.contains("CONNECTED") && upper.contains("NO DATA")) {
            return "Connected • No data";
        }
        if (upper.contains("CONNECTED")) {
            return "Connected";
        }

        return raw.trim();
    }

    private String cleanLastValue(String raw, String prefix) {
        if (raw == null || raw.trim().isEmpty()) {
            return "—";
        }

        String cleaned = raw.trim();
        if (cleaned.startsWith(prefix)) {
            cleaned = cleaned.substring(prefix.length()).trim();
        }

        return cleaned.isEmpty() ? "—" : cleaned;
    }

    private boolean isBluetoothEnabled() {
        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;
        return adapter != null && adapter.isEnabled();
    }

    @SuppressWarnings("deprecation")
    private void requestEnableBluetoothPrompt() {
        try {
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(intent, REQ_ENABLE_BT);
        } catch (Exception ignored) {
        }
    }

    private boolean isConnecting(String text) {
        return text != null && text.contains("CONNECTING");
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_PERMS
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_PERMS
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQ_PERMS) return;

        boolean ok = true;
        for (int g : grantResults) {
            if (g != PackageManager.PERMISSION_GRANTED) {
                ok = false;
                break;
            }
        }

        if (ok) {
            autoConnectRequestedThisVisit = false;
            if (!isBluetoothEnabled()) {
                requestEnableBluetoothPrompt();
            } else {
                maybeAutoConnectNow();
            }
        }

        refreshFromBleHost();
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_ENABLE_BT) {
            autoConnectRequestedThisVisit = false;
            if (isBluetoothEnabled()) {
                maybeAutoConnectNow();
            }
            refreshFromBleHost();
        }
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
}