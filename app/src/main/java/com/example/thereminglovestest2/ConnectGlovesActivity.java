package com.example.thereminglovestest2;

/**
 * File guide:
 * Manual BLE control screen. It shows each glove clearly and lets the user connect, reconnect, or disconnect directly.
 */

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.thereminglovestest2.databinding.ActivityConnectGlovesBinding;

/**
 * Manual BLE control screen.
 * Keep it honest: show each glove clearly and let the user act directly.
 */
public class ConnectGlovesActivity extends AppCompatActivity {

    private static final long UI_POLL_MS = 150L;
    private static final int REQ_PERMS = 4101;
    private static final int REQ_ENABLE_BT = 4102;
    static final String EXTRA_SUPPRESS_AUTO_PLAY_REDIRECT =
            "com.example.thereminglovestest2.extra.SUPPRESS_AUTO_PLAY_REDIRECT";

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
    private boolean suppressAutoPlayRedirectThisVisit = false;

    // ── Onboarding hint ──────────────────────────────────────────────────────
    private static final int HINT_COLOR = 0xFF39F07A;
    private ObjectAnimator connectHintAnimator;
    private boolean connectHintDone;
    private final Runnable automaticBlePromptRunnable = () -> withBleReady(() -> {});
    // Set to true after the first startup auto-navigate to Play.
    // Prevents re-firing when the user navigates back to Connect from the Play screen.
    private static boolean startupAutoNavUsed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Warm Play dependencies while the user is on the manual connection screen.
        AppLaunchWarmup.begin(getApplicationContext());
        BleSessionManager.initialize(getApplicationContext());
        consumeLaunchIntent(getIntent());
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

        // Seed prev-state before the first refreshUi() call so we never fire a
        // "glove connected" toast for gloves that were already connected on entry.
        seedPrevConnectionState();
        refreshUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        BleSessionManager.initialize(getApplicationContext());
        autoNavigatedToPlayThisVisit = false;
        consecutiveFullyConnectedPolls = 0;
        autoConnectRequestedThisVisit = false;
        seedPrevConnectionState();
        uiPoller.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Show system BLE dialogs only after the Connect screen is already visible.
        binding.getRoot().removeCallbacks(automaticBlePromptRunnable);
        binding.getRoot().postDelayed(automaticBlePromptRunnable, 32L);
        // Onboarding hint: glow Connect All if user hasn't connected yet.
        connectHintDone = getSharedPreferences(LaunchActivity.PREFS_NAME, MODE_PRIVATE)
                .getBoolean(LaunchActivity.KEY_HINT_CONNECT_DONE, false);
        if (!connectHintDone) startConnectHint();
    }

    @Override
    protected void onStop() {
        super.onStop();
        binding.getRoot().removeCallbacks(automaticBlePromptRunnable);
        if (connectHintAnimator != null) { connectHintAnimator.cancel(); connectHintAnimator = null; }
        uiPoller.stop();
        // Manual "stay on Connect" applies only to the current visit, not future auto-open flows.
        suppressAutoPlayRedirectThisVisit = false;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeLaunchIntent(intent);
    }

    /** Initialise prevXxx fields from the live snapshot so the first poll never fires spurious toasts. */
    private void seedPrevConnectionState() {
        BleSnapshot seed = BleSessionManager.getSnapshot();
        if (seed != null) {
            prevPitchConnected   = seed.isPitchConnected();
            prevPitchConnecting  = seed.hostReady && seed.pitchConnecting;
            prevVolumeConnected  = seed.isVolumeConnected();
            prevVolumeConnecting = seed.hostReady && seed.volumeConnecting;
        }
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
        boolean permissionsOk = BleSessionManager.hasRequiredPermissions(this);
        boolean bluetoothOn = BleSessionManager.isBluetoothEnabled(this);

        // Show permission/Bluetooth blockers immediately, even if the BLE host snapshot has not caught up yet.
        if (!permissionsOk || !bluetoothOn) {
            if (snapshot == null || !snapshot.hostReady) {
                snapshot = BleSessionManager.getSnapshot();
            }
        }
        if (snapshot == null || !snapshot.hostReady) {
            if (!permissionsOk) {
                showPreparingState();
                setReady("Bluetooth permissions needed",
                        "Allow Bluetooth permissions so the app can scan and connect your gloves.");
            } else if (!bluetoothOn) {
                showPreparingState();
                setReady("Bluetooth is off", "Turn Bluetooth on, then connect whichever glove you want.");
            } else {
                showPreparingState();
            }
            return;
        }

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
            clearConnectHint();
            consecutiveFullyConnectedPolls++;
            if (!suppressAutoPlayRedirectThisVisit && consecutiveFullyConnectedPolls >= 1) openPlay();
        } else {
            consecutiveFullyConnectedPolls = 0;
        }
    }

    private void consumeLaunchIntent(Intent intent) {
        if (intent == null) return;
        // Bottom-nav Connect should stay on Connect even if startup auto-connect already succeeded.
        suppressAutoPlayRedirectThisVisit =
                intent.getBooleanExtra(EXTRA_SUPPRESS_AUTO_PLAY_REDIRECT, false);
        intent.removeExtra(EXTRA_SUPPRESS_AUTO_PLAY_REDIRECT);
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
        if (startupAutoNavUsed) return;
        autoNavigatedToPlayThisVisit = true;
        startupAutoNavUsed = true;
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
            setReady("Connection ready", "Connect the right pitch glove, the left volume glove, or both together.");
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

    // ── Onboarding hint helpers ───────────────────────────────────────────────

    private void startConnectHint() {
        if (connectHintAnimator != null && connectHintAnimator.isStarted()) return;
        com.google.android.material.button.MaterialButton btn = binding.btnConnectToggle;
        btn.setStrokeWidth(dp(3));
        btn.setStrokeColor(ColorStateList.valueOf(HINT_COLOR));
        int base = ContextCompat.getColor(this, R.color.app_primary);
        btn.setBackgroundTintList(ColorStateList.valueOf(blendColor(base, HINT_COLOR, 0.45f)));
        btn.setTextColor(ContextCompat.getColor(this, R.color.app_on_primary));
        connectHintAnimator = ObjectAnimator.ofFloat(btn, View.ALPHA, 1f, 0.42f, 1f);
        connectHintAnimator.setDuration(900L);
        connectHintAnimator.setRepeatCount(ValueAnimator.INFINITE);
        connectHintAnimator.setRepeatMode(ValueAnimator.RESTART);
        connectHintAnimator.start();
    }

    private void clearConnectHint() {
        if (connectHintDone) return;
        connectHintDone = true;
        getSharedPreferences(LaunchActivity.PREFS_NAME, MODE_PRIVATE)
                .edit().putBoolean(LaunchActivity.KEY_HINT_CONNECT_DONE, true).apply();
        if (connectHintAnimator != null) { connectHintAnimator.cancel(); connectHintAnimator = null; }
        com.google.android.material.button.MaterialButton btn = binding.btnConnectToggle;
        btn.setAlpha(1f);
        btn.setStrokeWidth(0);
        btn.setStrokeColor(ColorStateList.valueOf(Color.TRANSPARENT));
        btn.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.app_primary)));
        btn.setTextColor(ContextCompat.getColor(this, R.color.app_on_primary));
    }

    private int blendColor(int from, int to, float amount) {
        float inv = 1f - amount;
        return Color.argb(
                Math.round(Color.alpha(from) * inv + Color.alpha(to) * amount),
                Math.round(Color.red(from)   * inv + Color.red(to)   * amount),
                Math.round(Color.green(from) * inv + Color.green(to) * amount),
                Math.round(Color.blue(from)  * inv + Color.blue(to)  * amount));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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
