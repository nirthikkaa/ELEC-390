package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityConnectGlovesBinding;

public class ConnectGlovesActivity extends AppCompatActivity {

    private static final long UI_POLL_MS = 150L;
    private static final int REQ_PERMS = 4101;
    private static final int REQ_ENABLE_BT = 4102;

    private ActivityConnectGlovesBinding binding;
    private final NavigationUtils.Poller uiPoller = new NavigationUtils.Poller(UI_POLL_MS, this::refreshUi);

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

    @Override protected void onStart() {
        super.onStart();
        BleSessionManager.initialize(getApplicationContext());
        uiPoller.start();
    }

    @Override protected void onStop() {
        super.onStop();
        uiPoller.stop();
    }

    private void toggleGlove(boolean isPitch) {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        if (snapshot != null && snapshot.isGloveConnected(isPitch)) BleSessionManager.requestDisconnectGlove(isPitch);
        else BleSessionManager.requestConnectGlove(isPitch);
        refreshUi();
    }

    private void toggleAllGloves() {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        if (snapshot != null && snapshot.isBusy()) BleSessionManager.requestBleToggle();
        else BleSessionManager.requestConnectMissingGloves();
        refreshUi();
    }

    private void withBleReady(Runnable action) {
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
        binding.tvPitchConn.setText(snapshot.connectionDetail(true));
        binding.tvPitchLast.setText(BleSnapshot.cleanLastValue(snapshot.pitchLastText, "Pitch last:"));
        binding.tvVolConn.setText(snapshot.connectionDetail(false));
        binding.tvVolLast.setText(BleSnapshot.cleanLastValue(snapshot.volumeLastText, "Volume last:"));
        binding.tvHostNote.setText(snapshot.recentEventsText);

        if (!permissionsOk) {
            binding.tvReadyHeadline.setText("Bluetooth permissions needed");
            binding.tvReadySubtext.setText("Allow Bluetooth permissions so the app can scan and connect your gloves.");
        } else if (!bluetoothOn) {
            binding.tvReadyHeadline.setText("Bluetooth is off");
            binding.tvReadySubtext.setText("Turn Bluetooth on, then connect whichever glove you want.");
        } else if (snapshot.areBothGlovesConnected()) {
            binding.tvReadyHeadline.setText("Ready to play");
            binding.tvReadySubtext.setText("Both gloves are connected. You can still disconnect either one here.");
        } else if (snapshot.isAnyGloveConnected()) {
            binding.tvReadyHeadline.setText("One glove connected");
            binding.tvReadySubtext.setText("You can connect the other glove or disconnect the one that is already active.");
        } else if (snapshot.isAnyGloveConnecting()) {
            binding.tvReadyHeadline.setText("Connecting gloves");
            binding.tvReadySubtext.setText("Keep the gloves awake and close to the phone for a few seconds.");
        } else {
            binding.tvReadyHeadline.setText("Connection ready");
            binding.tvReadySubtext.setText("Connect either glove on its own, or connect both together.");
        }

        boolean bleReady = permissionsOk && bluetoothOn;
        binding.btnConnectToggle.setEnabled(permissionsOk);
        binding.btnConnectToggle.setText(snapshot.isBusy() ? "Disconnect All" : "Connect All");
        binding.btnReconnectPitch.setEnabled(bleReady);
        binding.btnReconnectPitch.setText(snapshot.isPitchConnected() ? "Disconnect Pitch Glove" : "Connect Pitch Glove");
        binding.btnReconnectVolume.setEnabled(bleReady);
        binding.btnReconnectVolume.setText(snapshot.isVolumeConnected() ? "Disconnect Volume Glove" : "Connect Volume Glove");
        binding.btnRefreshInfo.setEnabled(bleReady && snapshot.isAnyGloveConnected());
    }

    private void showPreparingState() {
        binding.tvReadyHeadline.setText("Preparing Bluetooth");
        binding.tvReadySubtext.setText("The connection engine is still waking up.");
        binding.tvBigStatus.setText("Preparing");
        binding.tvPairSummary.setText("Waiting for connection engine");
        binding.tvPitchConn.setText("Unavailable");
        binding.tvPitchLast.setText("—");
        binding.tvVolConn.setText("Unavailable");
        binding.tvVolLast.setText("—");
        binding.tvHostNote.setText("Waiting for connection events...");
        binding.btnConnectToggle.setEnabled(false);
        binding.btnConnectToggle.setText("Preparing...");
        binding.btnReconnectPitch.setEnabled(false);
        binding.btnReconnectVolume.setEnabled(false);
        binding.btnRefreshInfo.setEnabled(false);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) refreshUi();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS && BleSessionManager.wereAllPermissionsGranted(grantResults)) refreshUi();
    }
}
