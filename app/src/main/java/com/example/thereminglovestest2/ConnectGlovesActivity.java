package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityConnectGlovesBinding;

/**
 * Manual BLE control screen.
 *
 * This page is intentionally boring and honest:
 * it should tell the user what each glove is doing right now,
 * and it should let them connect or disconnect each glove on purpose.
 */
public class ConnectGlovesActivity extends AppCompatActivity {

    private ActivityConnectGlovesBinding binding;

    private static final long UI_POLL_MS = 150L;
    private static final int REQ_PERMS = 4101;
    private static final int REQ_ENABLE_BT = 4102;

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

    private final UiPoller uiPoller = new UiPoller(UI_POLL_MS, this::refreshUi);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BleSessionManager.initialize(getApplicationContext());
        binding = ActivityConnectGlovesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        bindViews();
        wireButtons();
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

    private void bindViews() {
        binding.topNavBar.setTitleText("Connect Gloves");

        tvReadyHeadline = binding.tvReadyHeadline;
        tvReadySubtext = binding.tvReadySubtext;
        tvBigStatus = binding.tvBigStatus;
        tvPairSummary = binding.tvPairSummary;
        tvHostNote = binding.tvHostNote;

        tvPitchConn = binding.tvPitchConn;
        tvPitchLast = binding.tvPitchLast;
        tvVolConn = binding.tvVolConn;
        tvVolLast = binding.tvVolLast;

        btnReconnectPitch = binding.btnReconnectPitch;
        btnReconnectVolume = binding.btnReconnectVolume;
        btnConnectToggle = binding.btnConnectToggle;
        btnRefreshInfo = binding.btnRefreshInfo;

        if (btnRefreshInfo != null) {
            btnRefreshInfo.setText("Sync");
        }
    }

    private void wireButtons() {
        btnReconnectPitch.setOnClickListener(v -> onSingleGloveTogglePressed(true));
        btnReconnectVolume.setOnClickListener(v -> onSingleGloveTogglePressed(false));

        btnConnectToggle.setOnClickListener(v -> BleActionExecutor.runWhenReady(
                this,
                REQ_PERMS,
                REQ_ENABLE_BT,
                this::onAllGlovesTogglePressed
        ));

        btnRefreshInfo.setOnClickListener(v -> BleActionExecutor.runWhenReady(
                this,
                REQ_PERMS,
                REQ_ENABLE_BT,
                () -> {
                    // "Sync" does not reconnect anything.
                    // It only asks already-connected gloves to repeat their current role/direction/neutral info.
                    BleSessionManager.requestRefreshHandshake();
                    refreshUi();
                }
        ));
    }

    private void onSingleGloveTogglePressed(boolean isPitch) {
        BleActionExecutor.runWhenReady(this, REQ_PERMS, REQ_ENABLE_BT, () -> {
            if (isGloveConnected(isPitch)) {
                BleSessionManager.requestDisconnectGlove(isPitch);
            } else {
                BleSessionManager.requestConnectGlove(isPitch);
            }
            refreshUi();
        });
    }

    private void onAllGlovesTogglePressed() {
        BleSnapshot snapshot = BleSessionManager.getSnapshot();
        if (snapshot != null && snapshot.busyOrConnected) {
            BleSessionManager.requestBleToggle();
        } else {
            BleSessionManager.requestConnectMissingGloves();
        }
        refreshUi();
    }

    private void refreshUi() {
        // One shared snapshot is easier to reason about than separate BLE + calibration reads.
        BleSnapshot snapshot = BleSessionManager.getSnapshot();

        if (snapshot == null || !snapshot.hostReady) {
            showPreparingState();
            return;
        }

        boolean permissionsOk = BleSessionManager.hasRequiredPermissions(this);
        boolean bluetoothOn = BluetoothRequirements.isBluetoothEnabled(this) && snapshot.bluetoothEnabled;
        boolean pitchConnected = BleUiText.isPitchConnected(snapshot);
        boolean volumeConnected = BleUiText.isVolumeConnected(snapshot);
        boolean anyConnecting = BleUiText.isAnyGloveConnecting(snapshot);

        tvBigStatus.setText(BleUiText.stripStatusPrefix(snapshot.statusText));
        tvPairSummary.setText(BleUiText.pairSummary(snapshot));
        tvPitchConn.setText(BleUiText.cleanConnectionText(snapshot.pitchConnText));
        tvPitchLast.setText(BleUiText.cleanLastValue(snapshot.pitchLastText, "Pitch last:"));
        tvVolConn.setText(BleUiText.cleanConnectionText(snapshot.volumeConnText));
        tvVolLast.setText(BleUiText.cleanLastValue(snapshot.volumeLastText, "Volume last:"));
        tvHostNote.setText(snapshot.recentEventsText);

        updateHeadline(permissionsOk, bluetoothOn, pitchConnected, volumeConnected, anyConnecting);
        updateButtons(permissionsOk, bluetoothOn, pitchConnected, volumeConnected, anyConnecting);
    }

    private void showPreparingState() {
        tvReadyHeadline.setText("Preparing Bluetooth");
        tvReadySubtext.setText("The connection engine is still waking up.");
        tvBigStatus.setText("Preparing");
        tvPairSummary.setText("Waiting for connection engine");
        tvPitchConn.setText("Unavailable");
        tvPitchLast.setText("—");
        tvVolConn.setText("Unavailable");
        tvVolLast.setText("—");
        tvHostNote.setText("Waiting for connection events...");

        btnConnectToggle.setEnabled(false);
        btnConnectToggle.setText("Preparing...");
        btnReconnectPitch.setEnabled(false);
        btnReconnectVolume.setEnabled(false);
        btnRefreshInfo.setEnabled(false);
    }

    private void updateHeadline(boolean permissionsOk,
                                boolean bluetoothOn,
                                boolean pitchConnected,
                                boolean volumeConnected,
                                boolean anyConnecting) {
        int connectedCount = (pitchConnected ? 1 : 0) + (volumeConnected ? 1 : 0);

        if (!permissionsOk) {
            tvReadyHeadline.setText("Bluetooth permissions needed");
            tvReadySubtext.setText("Allow Bluetooth permissions so the app can scan and connect your gloves.");
            return;
        }

        if (!bluetoothOn) {
            tvReadyHeadline.setText("Bluetooth is off");
            tvReadySubtext.setText("Turn Bluetooth on, then connect whichever glove you want.");
            return;
        }

        if (connectedCount == 2) {
            tvReadyHeadline.setText("Ready to play");
            tvReadySubtext.setText("Both gloves are connected. You can still disconnect either one here.");
            return;
        }

        if (connectedCount == 1) {
            tvReadyHeadline.setText("One glove connected");
            tvReadySubtext.setText("You can connect the other glove or disconnect the one that is already active.");
            return;
        }

        if (anyConnecting) {
            tvReadyHeadline.setText("Connecting gloves");
            tvReadySubtext.setText("Keep the gloves awake and close to the phone for a few seconds.");
            return;
        }

        tvReadyHeadline.setText("Connection ready");
        tvReadySubtext.setText("Connect either glove on its own, or connect both together.");
    }

    private void updateButtons(boolean permissionsOk,
                               boolean bluetoothOn,
                               boolean pitchConnected,
                               boolean volumeConnected,
                               boolean anyConnecting) {
        boolean bleReady = permissionsOk && bluetoothOn;

        btnConnectToggle.setEnabled(permissionsOk);
        boolean anyActive = pitchConnected || volumeConnected || anyConnecting;
        btnConnectToggle.setText(anyActive ? "Disconnect All" : "Connect All");

        btnReconnectPitch.setEnabled(bleReady);
        btnReconnectPitch.setText(pitchConnected ? "Disconnect Pitch Glove" : "Connect Pitch Glove");

        btnReconnectVolume.setEnabled(bleReady);
        btnReconnectVolume.setText(volumeConnected ? "Disconnect Volume Glove" : "Connect Volume Glove");

        btnRefreshInfo.setEnabled(bleReady && (pitchConnected || volumeConnected));
    }

    private boolean isGloveConnected(boolean isPitch) {
        return BleUiText.isGloveConnected(BleSessionManager.getSnapshot(), isPitch);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ENABLE_BT) {
            refreshUi();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) {
            return;
        }

        if (BleActionExecutor.wereAllPermissionsGranted(grantResults)) {
            refreshUi();
        }
    }
}
