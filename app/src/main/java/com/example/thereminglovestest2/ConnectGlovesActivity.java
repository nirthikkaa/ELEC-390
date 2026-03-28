package com.example.thereminglovestest2;

/**
 * File guide:
 * Manual BLE control screen. It shows each glove clearly and lets the user connect, reconnect, or disconnect directly.
 */

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityConnectGlovesBinding;

/**
 * Manual BLE control screen.
 * Keep it honest: show each glove clearly and let the user act directly.
 */
public class ConnectGlovesActivity extends AppCompatActivity {

    private static final long UI_POLL_MS = 150L;
    private static final int REQ_PERMS = 4101;
    private static final int REQ_ENABLE_BT = 4102;

    private ActivityConnectGlovesBinding binding;
    private final NavigationUtils.Poller uiPoller = new NavigationUtils.Poller(UI_POLL_MS, this::refreshUi);

    private boolean prevPitchConnected = false;
    private boolean prevPitchConnecting = false;
    private boolean prevVolumeConnected = false;
    private boolean prevVolumeConnecting = false;
    private long lastBleActionMs = 0L;
    private static final long BLE_DEBOUNCE_MS = 1200L;

    private int     consecutiveFullyConnectedPolls = 0;
    private boolean autoNavigatedToPlayThisVisit   = false;
    private boolean autoConnectRequestedThisVisit  = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BleSessionManager.initialize(getApplicationContext());
        binding = ActivityConnectGlovesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.topNavBar.setTitleText("Connect Gloves");
        binding.btnRefreshInfo.setText("Sync");
        binding.btnReconnectPitch.setOnClickListener(v -> withBleReady(() -> toggleGlove(true)));
        binding.btnReconnectVolume.setOnClickListener(v -> withBleReady(() -> toggleGlove(false)));
        binding.btnConnectToggle.setOnClickListener(v -> withBleReady(this::toggleAllGloves));
        binding.btnRefreshInfo.setOnClickListener(v -> withBleReady(() -> {
            BleSessionManager.requestRefreshHandshake();
            refreshUi();
        }));

        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BleSessionManager.initialize(getApplicationContext());
        uiPoller.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiPoller.stop();
    }

    private void toggleGlove(boolean isPitch) {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        if (snapshot != null && snapshot.isGloveConnected(isPitch)) {
            BleSessionManager.requestDisconnectGlove(isPitch);
        } else {
            BleSessionManager.requestConnectGlove(isPitch);
        }
        refreshUi();
    }

    private void toggleAllGloves() {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        if (snapshot != null && snapshot.isBusy()) BleSessionManager.requestBleToggle();
        else BleSessionManager.requestConnectMissingGloves();
        refreshUi();
    }

    private void withBleReady(Runnable action) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastBleActionMs < BLE_DEBOUNCE_MS) return;
        lastBleActionMs = now;
        BleSessionManager.runWhenReady(this, REQ_PERMS, REQ_ENABLE_BT, action);
    }

    private void refreshUi() {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        if (snapshot == null || !snapshot.hostReady) {
            showPreparingState();
            return;
        }

        boolean permissionsOk = BleSessionManager.hasRequiredPermissions(this);
        boolean bluetoothOn = snapshot.isBluetoothOn() && BleSessionManager.isBluetoothEnabled(this);

        binding.tvBigStatus.setText(BleSnapshot.stripStatusPrefix(snapshot.statusText));
        binding.tvPairSummary.setText(snapshot.pairSummary());
        setGlove(binding.tvPitchConn, binding.tvPitchLast,
                snapshot.connectionDetail(true), BleSnapshot.cleanLastValue(snapshot.pitchLastText, "Pitch last:"));
        setGlove(binding.tvVolConn, binding.tvVolLast,
                snapshot.connectionDetail(false), BleSnapshot.cleanLastValue(snapshot.volumeLastText, "Volume last:"));
        binding.tvHostNote.setText(snapshot.recentEventsText);

        updateReadyText(snapshot, permissionsOk, bluetoothOn);
        updateButtons(snapshot, permissionsOk, bluetoothOn);
        notifyReconnectTransitions(snapshot);

        // Auto-connect on arrival when BLE is ready and no gloves are connecting/connected.
        if (permissionsOk && bluetoothOn && !snapshot.isAnyGloveConnected()
                && !snapshot.isAnyGloveConnecting() && !autoConnectRequestedThisVisit) {
            autoConnectRequestedThisVisit = true;
            BleSessionManager.maybeStartAutoConnect();
        }

        // Auto-navigate to Play when both gloves are connected.
        if (snapshot.areBothGlovesConnected()) {
            consecutiveFullyConnectedPolls++;
            if (consecutiveFullyConnectedPolls >= 1) openPlay();
        } else {
            consecutiveFullyConnectedPolls = 0;
        }
    }

    private void notifyReconnectTransitions(BleSnapshot snapshot) {
        boolean pitchConnected = snapshot.isPitchConnected();
        boolean pitchConnecting = snapshot.hostReady && snapshot.pitchConnecting;
        boolean volumeConnected = snapshot.isVolumeConnected();
        boolean volumeConnecting = snapshot.hostReady && snapshot.volumeConnecting;

        if (!prevPitchConnected && pitchConnected) {
            Toast.makeText(this, "Pitch glove connected", Toast.LENGTH_SHORT).show();
        } else if (prevPitchConnecting && !pitchConnecting && !pitchConnected) {
            Toast.makeText(this, "Could not connect pitch glove", Toast.LENGTH_SHORT).show();
        }

        if (!prevVolumeConnected && volumeConnected) {
            Toast.makeText(this, "Volume glove connected", Toast.LENGTH_SHORT).show();
        } else if (prevVolumeConnecting && !volumeConnecting && !volumeConnected) {
            Toast.makeText(this, "Could not connect volume glove", Toast.LENGTH_SHORT).show();
        }

        prevPitchConnected = pitchConnected;
        prevPitchConnecting = pitchConnecting;
        prevVolumeConnected = volumeConnected;
        prevVolumeConnecting = volumeConnecting;
    }

    private void openPlay() {
        if (autoNavigatedToPlayThisVisit) return;
        autoNavigatedToPlayThisVisit = true;
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        overridePendingTransition(0, 0);
        finish();
    }

    private void showPreparingState() {
        setReady("Preparing Bluetooth", "The connection engine is still waking up.");
        binding.tvBigStatus.setText("Preparing");
        binding.tvPairSummary.setText("Waiting for connection engine");
        setGlove(binding.tvPitchConn, binding.tvPitchLast, "Unavailable", "—");
        setGlove(binding.tvVolConn, binding.tvVolLast, "Unavailable", "—");
        binding.tvHostNote.setText("Waiting for connection events...");

        binding.btnConnectToggle.setEnabled(false);
        binding.btnConnectToggle.setText("Preparing...");
        binding.btnReconnectPitch.setEnabled(false);
        binding.btnReconnectVolume.setEnabled(false);
        binding.btnRefreshInfo.setEnabled(false);
    }

    private void updateReadyText(BleSnapshot snapshot, boolean permissionsOk, boolean bluetoothOn) {
        if (!permissionsOk) {
            setReady("Bluetooth permissions needed",
                    "Allow Bluetooth permissions so the app can scan and connect your gloves.");
        } else if (!bluetoothOn) {
            setReady("Bluetooth is off", "Turn Bluetooth on, then connect whichever glove you want.");
        } else if (snapshot.areBothGlovesConnected()) {
            setReady("Ready to play", "Both gloves are connected. You can still disconnect either one here.");
        } else if (snapshot.isAnyGloveConnected()) {
            setReady("One glove connected",
                    "You can connect the other glove or disconnect the one that is already active.");
        } else if (snapshot.isAnyGloveConnecting()) {
            setReady("Connecting gloves", "Keep the gloves awake and close to the phone for a few seconds.");
        } else {
            setReady("Connection ready", "Connect either glove on its own, or connect both together.");
        }
    }

    private void updateButtons(BleSnapshot snapshot, boolean permissionsOk, boolean bluetoothOn) {
        boolean bleReady = permissionsOk && bluetoothOn;
        binding.btnConnectToggle.setEnabled(permissionsOk);
        binding.btnConnectToggle.setText(snapshot.isBusy() ? "Disconnect All" : "Connect All");
        setButton(binding.btnReconnectPitch, bleReady,
                snapshot.isPitchConnected() ? "Disconnect Pitch Glove" : "Connect Pitch Glove");
        setButton(binding.btnReconnectVolume, bleReady,
                snapshot.isVolumeConnected() ? "Disconnect Volume Glove" : "Connect Volume Glove");
        binding.btnRefreshInfo.setEnabled(bleReady && snapshot.isAnyGloveConnected());
    }

    private void setReady(String headline, String subtext) {
        binding.tvReadyHeadline.setText(headline);
        binding.tvReadySubtext.setText(subtext);
    }

    private void setGlove(TextView conn, TextView last, String connText, String lastText) {
        conn.setText(connText);
        last.setText(lastText);
    }

    private void setButton(TextView button, boolean enabled, String text) {
        button.setEnabled(enabled);
        button.setText(text);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) refreshUi();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && BleSessionManager.wereAllPermissionsGranted(grantResults)) refreshUi();
    }
}
