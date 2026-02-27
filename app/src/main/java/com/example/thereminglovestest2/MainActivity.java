package com.example.thereminglovestest2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    // ===== BLE (MUST MATCH ARDUINO) =====
    private static final String PITCH_DEVICE_NAME = "ThereminGlove";
    private static final String VOLUME_DEVICE_NAME = "ThereminGloveVol";

    private static final UUID SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    private static final UUID TX_CHAR_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac");
    private static final UUID RX_CHAR_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ad");
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int REQ_PERMS = 1001;

    // Scan / reconnect
    private static final long SCAN_TIMEOUT_MS = 12000;
    private static final long AUTO_RECONNECT_DELAY_MS = 1500;
    private static final long CONNECT_ATTEMPT_TIMEOUT_MS = 12000;

    // Telemetry watchdog behavior
    private static final long PING_AFTER_MS = 3000;
    private static final long STALE_WARNING_MS = 4500;
    private static final long STALE_RECONNECT_MS = 20000;

    private static final long CONNECTION_WATCHDOG_PERIOD_MS = 1000;

    // UI tick: fast
    private static final long UI_TICK_MS = 80;

    // Log throttling: slow
    private static final int LOG_MAX_LINES = 200;
    private static final long LOG_FLUSH_MIN_INTERVAL_MS = 600;

    // ===== Background audio preference =====
    private static final String PREFS_NAME = "theremin_prefs";
    private static final String PREF_BG_AUDIO_ENABLED = "bg_audio_enabled";

    private volatile boolean bgAudioEnabled = true;

    // ===== Slider ranges =====
    private static final float ANGLE_MIN = -90f;
    private static final float ANGLE_MAX = 90f;
    private static final float ANGLE_STEP = 0.5f;
    private static final int ANGLE_PROGRESS_MAX = (int) ((ANGLE_MAX - ANGLE_MIN) / ANGLE_STEP);

    private static final float FREQ_MIN_UI = 20f;
    private static final float FREQ_MAX_UI = 2000f;
    private static final int FREQ_PROGRESS_MAX = (int) (FREQ_MAX_UI - FREQ_MIN_UI);

    // ===== UI =====
    private TextView tvStatus, tvPitchConn, tvVolConn, tvAudio;
    private TextView tvPitchValue, tvVolValue, tvToneValue;
    private TextView tvPitchLast, tvVolLast, tvLog;

    private TextView tvPitchAngleMinVal, tvPitchAngleMaxVal, tvFreqMinVal, tvFreqMaxVal;
    private TextView tvVolAngleMinVal, tvVolAngleMaxVal;

    private SeekBar sbPitchAngleMin, sbPitchAngleMax, sbFreqMin, sbFreqMax;
    private SeekBar sbVolAngleMin, sbVolAngleMax;

    private Button btnScanConnect, btnDisconnectAll, btnAudioStart, btnAudioStop;
    private Button btnNeutralPitch, btnNeutralVol, btnDirectionPitch, btnDirectionVol, btnHelpPitch, btnHelpVol;
    private Button btnDefaults;

    private ThereminVisualizerView thereminVisualizerView;

    // ===== Application context for BLE ownership =====
    private Context appContext;

    // ===== BLE core =====
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private boolean isScanning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable scanTimeoutRunnable = this::onScanTimeout;
    private final Runnable autoReconnectRunnable = this::runAutoReconnect;

    private final Runnable connectionTruthWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            runConnectionTruthWatchdog();
            mainHandler.postDelayed(this, CONNECTION_WATCHDOG_PERIOD_MS);
        }
    };

    // ===== Truth-state receiver =====
    private final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            onBluetoothAdapterStateChanged(state);
        }
    };

    private boolean bluetoothStateReceiverRegistered = false;

    private volatile boolean autoReconnectEnabled = true;
    private volatile boolean manualDisconnectRequested = false;
    private volatile boolean appShuttingDown = false;
    private volatile boolean startupAutoConnectAttempted = false;
    private volatile boolean keepBleAliveAcrossDestroy = false;

    // ===== App-side mapping settings =====
    private volatile float pitchAngleMinDeg = -15.0f;
    private volatile float pitchAngleMaxDeg = 55.0f;
    private volatile float freqMinHz = 880.0f;
    private volatile float freqMaxHz = 2000.0f;

    private volatile float volumeAngleMinDeg = -10.0f;
    private volatile float volumeAngleMaxDeg = 55.0f;

    // ===== Latest glove values =====
    private volatile float pitchActiveDeltaDeg = 0.0f;
    private volatile float volActiveDeltaDeg = 0.0f;
    private volatile boolean pitchHasAngle = false;
    private volatile boolean volHasAngle = false;

    private volatile float mappedFreqHz = 880.0f;
    private volatile float mappedVolumeLinear = 0.0f;

    private volatile float audioTargetFreqHz = 880.0f;
    private volatile float audioTargetVolumeLinear = 0.0f;

    // UI state
    private volatile String statusText = "Idle";
    private volatile boolean suppressSliderCallbacks = false;

    // Performance: bounded log buffer
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private volatile long lastLogFlushMs = 0;

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            refreshUiFast();
            mainHandler.postDelayed(this, UI_TICK_MS);
        }
    };

    private AudioEngine audioEngine;

    private final GloveClient pitchGlove = new GloveClient("PITCH", PITCH_DEVICE_NAME);
    private final GloveClient volumeGlove = new GloveClient("VOLUME", VOLUME_DEVICE_NAME);

    // ===== Persistence =====
    private AppSettingsRepository settingsRepo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appContext = getApplicationContext();

        bindViews();

        BluetoothManager bluetoothManager =
                (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) bluetoothAdapter = bluetoothManager.getAdapter();

        audioEngine = new AudioEngine();

        loadBgAudioPref();

        setupSlidersAndClickNumbers();
        syncAllMappingControlsFromState();
        recomputeMappedOutputs();

        wireButtons();

        setStatusText("Idle");
        appendLogSafe("App started");
        appendLogSafe("BG audio: " + (bgAudioEnabled ? "ON" : "OFF"));
        appendLogSafe("BLE GATT uses application context");
        updateStatusLineText();
        updateAudioStatusText();
        updateBleButtonText();
    }

    @Override
    protected void onStart() {
        super.onStart();
        autoReconnectEnabled = true;
        keepBleAliveAcrossDestroy = true;
        registerBluetoothStateReceiverIfNeeded();

        cleanupStaleDisconnectedGattState();
        reloadMappingSettingsFromRepository();

        mainHandler.post(uiTicker);
        mainHandler.removeCallbacks(connectionTruthWatchdogRunnable);
        mainHandler.post(connectionTruthWatchdogRunnable);

        maybeAutoConnectOnLaunchOrReturn();
    }

    private void cleanupStaleDisconnectedGattState() {
        cleanupStaleDisconnectedGattStateForGlove(pitchGlove);
        cleanupStaleDisconnectedGattStateForGlove(volumeGlove);
        updateBleButtonText();
    }

    private void cleanupStaleDisconnectedGattStateForGlove(GloveClient glove) {
        if (glove == null) return;
        if (glove.connected) return;
        if (glove.connecting) return;
        if (glove.gatt == null) return;

        appendLogSafe(glove.roleLabel + ": clearing stale disconnected GATT");
        safeCloseGloveConnection(glove);
    }

    @Override
    protected void onStop() {
        super.onStop();

        persistSettings();

        if (!bgAudioEnabled) {
            appendLogSafe("BG audio OFF -> stopping audio onStop");
            forceSilenceAndStopAudio();
        } else {
            appendLogSafe("BG audio ON -> keeping audio running in background");
        }

        // Keep BLE alive while navigating to internal screens.
        keepBleAliveAcrossDestroy = true;
        mainHandler.removeCallbacks(uiTicker);
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepBleAliveAcrossDestroy = true;
    }

    @Override
    protected void onDestroy() {
        boolean shouldFullyTearDownBle =
                isFinishing() && !keepBleAliveAcrossDestroy;

        appendLogSafe("onDestroy: finishing=" + isFinishing()
                + " keepBleAliveAcrossDestroy=" + keepBleAliveAcrossDestroy
                + " -> fullBleTeardown=" + shouldFullyTearDownBle);

        if (shouldFullyTearDownBle) {
            appShuttingDown = true;
            autoReconnectEnabled = false;
            cancelAutoReconnect();
            cancelScanTimeout();
            mainHandler.removeCallbacks(connectionTruthWatchdogRunnable);
            unregisterBluetoothStateReceiverIfNeeded();

            stopScanIfRunning();
            disconnectAllGlovesInternal(true);

            if (audioEngine != null) audioEngine.stop();
        }

        super.onDestroy();
    }

    // =========================
    // Background audio pref
    // =========================
    private void loadBgAudioPref() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bgAudioEnabled = sp.getBoolean(PREF_BG_AUDIO_ENABLED, true);
    }

    private void saveBgAudioPref() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putBoolean(PREF_BG_AUDIO_ENABLED, bgAudioEnabled).apply();
    }

    // =========================
    // Bluetooth OFF behavior
    // =========================
    private boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    private void onBluetoothAdapterStateChanged(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
            case BluetoothAdapter.STATE_TURNING_OFF:
                appendLogSafe("Bluetooth turning OFF -> stopping audio + disconnecting");
                handleBluetoothOffHard();
                break;

            case BluetoothAdapter.STATE_ON:
                appendLogSafe("Bluetooth ON");
                updateStatusLineText();
                updateBleButtonText();
                if (!manualDisconnectRequested) scheduleAutoReconnect("Bluetooth on");
                break;

            case BluetoothAdapter.STATE_TURNING_ON:
                setStatusText("Bluetooth turning on...");
                updateBleButtonText();
                break;

            default:
                appendLogSafe("Bluetooth state=" + state);
                break;
        }
    }

    private void handleBluetoothOffHard() {
        cancelAutoReconnect();
        stopScanIfRunning();

        forceSilenceAndStopAudio();

        safeCloseGloveConnection(pitchGlove);
        safeCloseGloveConnection(volumeGlove);

        pitchActiveDeltaDeg = 0f;
        volActiveDeltaDeg = 0f;
        pitchHasAngle = false;
        volHasAngle = false;
        pitchGlove.lastPacket = "(none)";
        volumeGlove.lastPacket = "(none)";

        setStatusText("⚠️ BLUETOOTH OFF — Turn Bluetooth ON to play");
        updateStatusLineText();
        updateAudioStatusText();
        updateBleButtonText();
    }

    private void forceSilenceAndStopAudio() {
        audioTargetVolumeLinear = 0f;
        mappedVolumeLinear = 0f;

        if (audioEngine != null && audioEngine.isRunning()) {
            audioEngine.stop();
        }
    }

    // =========================
    // Watchdog (truth-state + idle handling)
    // =========================
    private void runConnectionTruthWatchdog() {
        if (appShuttingDown) return;

        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            return;
        }

        long now = SystemClock.elapsedRealtime();

        maybeHandleGloveConnectTimeout(pitchGlove, now);
        maybeHandleGloveConnectTimeout(volumeGlove, now);

        maybeHandleGloveTelemetry(pitchGlove, now);
        maybeHandleGloveTelemetry(volumeGlove, now);
    }

    private void maybeHandleGloveConnectTimeout(GloveClient glove, long nowMs) {
        if (glove == null) return;
        if (!glove.connecting) return;
        if (glove.connectAttemptStartMs <= 0L) return;

        long age = nowMs - glove.connectAttemptStartMs;
        if (age < CONNECT_ATTEMPT_TIMEOUT_MS) return;

        appendLogSafe(glove.roleLabel + ": connect attempt timed out after " + age + "ms");
        safeCloseGloveConnection(glove);
        updateStatusLineText();
        updateBleButtonText();

        if (!manualDisconnectRequested) {
            scheduleAutoReconnect(glove.roleLabel + " connect timeout");
        }
    }

    private void maybeHandleGloveTelemetry(GloveClient glove, long nowMs) {
        if (glove == null) return;
        if (!glove.connected) return;
        if (!glove.notificationsEnabled) return;
        if (glove.lastTelemetryMs <= 0L) return;

        long age = nowMs - glove.lastTelemetryMs;

        glove.telemetryStale = (age > STALE_WARNING_MS);

        if (age > PING_AFTER_MS && (nowMs - glove.lastPingMs) > 2000) {
            glove.lastPingMs = nowMs;
            appendLogSafe(glove.roleLabel + ": idle ping (H), age=" + age + "ms");
            sendCommandToGlove(glove, "H");
        }

        if (age > STALE_RECONNECT_MS) {
            appendLogSafe(glove.roleLabel + ": no telemetry for " + age + "ms -> reconnecting");
            safeCloseGloveConnection(glove);
            updateStatusLineText();
            updateBleButtonText();
            if (!manualDisconnectRequested) scheduleAutoReconnect(glove.roleLabel + " telemetry stale");
        }
    }

    // =========================
    // Auto-connect on visibility
    // =========================
    private void maybeAutoConnectOnLaunchOrReturn() {
        if (appShuttingDown) return;
        if (manualDisconnectRequested) return;
        if (!isBluetoothEnabled()) return;
        if (isScanning) return;

        boolean missing = !pitchGlove.connected || !volumeGlove.connected;
        if (!missing) return;

        if (!startupAutoConnectAttempted) {
            startupAutoConnectAttempted = true;
            appendLogSafe("Auto-connect on launch");
        } else {
            appendLogSafe("Auto-connect (missing glove)");
        }
        startScanAndConnect();
    }

    // =========================
    // UI binding + buttons
    // =========================
    private void bindViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvPitchConn = findViewById(R.id.tvPitchConn);
        tvVolConn = findViewById(R.id.tvVolConn);
        tvAudio = findViewById(R.id.tvAudio);

        tvPitchValue = findViewById(R.id.tvPitchValue);
        tvVolValue = findViewById(R.id.tvVolValue);
        tvToneValue = findViewById(R.id.tvToneValue);

        tvPitchLast = findViewById(R.id.tvPitchLast);
        tvVolLast = findViewById(R.id.tvVolLast);
        tvLog = findViewById(R.id.tvLog);

        tvPitchAngleMinVal = findViewById(R.id.tvPitchAngleMinVal);
        tvPitchAngleMaxVal = findViewById(R.id.tvPitchAngleMaxVal);
        tvFreqMinVal = findViewById(R.id.tvFreqMinVal);
        tvFreqMaxVal = findViewById(R.id.tvFreqMaxVal);

        tvVolAngleMinVal = findViewById(R.id.tvVolAngleMinVal);
        tvVolAngleMaxVal = findViewById(R.id.tvVolAngleMaxVal);

        sbPitchAngleMin = findViewById(R.id.sbPitchAngleMin);
        sbPitchAngleMax = findViewById(R.id.sbPitchAngleMax);
        sbFreqMin = findViewById(R.id.sbFreqMin);
        sbFreqMax = findViewById(R.id.sbFreqMax);

        sbVolAngleMin = findViewById(R.id.sbVolAngleMin);
        sbVolAngleMax = findViewById(R.id.sbVolAngleMax);

        btnScanConnect = findViewById(R.id.btnScanConnect);
        btnDisconnectAll = findViewById(R.id.btnDisconnectAll);
        btnAudioStart = findViewById(R.id.btnAudioStart);
        btnAudioStop = findViewById(R.id.btnAudioStop);

        btnNeutralPitch = findViewById(R.id.btnNeutralPitch);
        btnNeutralVol = findViewById(R.id.btnNeutralVol);
        btnDirectionPitch = findViewById(R.id.btnDirectionPitch);
        btnDirectionVol = findViewById(R.id.btnDirectionVol);
        btnHelpPitch = findViewById(R.id.btnHelpPitch);
        btnHelpVol = findViewById(R.id.btnHelpVol);

        btnDefaults = findViewById(R.id.btnDefaults);

        thereminVisualizerView = findViewById(R.id.thereminVisualizerView);
    }

    private void wireButtons() {
        if (btnDisconnectAll != null) {
            btnDisconnectAll.setVisibility(View.GONE);
        }

        btnScanConnect.setOnClickListener(v -> onBleTogglePressed());

        btnAudioStart.setOnClickListener(v -> {
            if (audioEngine == null) return;

            if (!isBluetoothEnabled()) {
                handleBluetoothOffHard();
                toastSafe("Bluetooth is OFF");
                return;
            }

            if (audioEngine.isRunning()) {
                audioEngine.stop();
            } else {
                audioEngine.start();
            }

            updateAudioStatusText();
        });

        btnAudioStop.setOnClickListener(v -> {
            bgAudioEnabled = !bgAudioEnabled;
            saveBgAudioPref();
            appendLogSafe("BG audio toggled -> " + (bgAudioEnabled ? "ON" : "OFF"));
            toastSafe("Background audio: " + (bgAudioEnabled ? "ON" : "OFF"));
            updateAudioStatusText();
        });

        btnNeutralPitch.setOnClickListener(v -> sendCommandToGlove(pitchGlove, "N"));
        btnNeutralVol.setOnClickListener(v -> sendCommandToGlove(volumeGlove, "N"));

        btnDirectionPitch.setOnClickListener(v -> sendCommandToGlove(pitchGlove, "D"));
        btnDirectionVol.setOnClickListener(v -> sendCommandToGlove(volumeGlove, "D"));

        btnHelpPitch.setOnClickListener(v -> showHelpDialog("Pitch glove", pitchGlove));
        btnHelpVol.setOnClickListener(v -> showHelpDialog("Volume glove", volumeGlove));

        btnDefaults.setOnClickListener(v -> {
            setDefaultMappingValues();
            syncAllMappingControlsFromState();
            recomputeMappedOutputs();
            persistSettings();
            appendLogSafe("Defaults restored");
        });

        updateAudioStatusText();
        updateBleButtonText();
    }

    private void onBleTogglePressed() {
        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            toastSafe("Bluetooth is OFF");
            return;
        }

        if (isBleBusyOrConnected()) {
            manualDisconnectRequested = true;
            keepBleAliveAcrossDestroy = false;
            disconnectAllGlovesInternal(false);
            appendLogSafe("BLE button -> DISCONNECT");
        } else {
            manualDisconnectRequested = false;
            keepBleAliveAcrossDestroy = true;
            appendLogSafe("BLE button -> CONNECT");
            startScanAndConnect();
        }

        updateBleButtonText();
    }

    private boolean isBleBusyOrConnected() {
        return isScanning
                || pitchGlove.connecting || volumeGlove.connecting
                || pitchGlove.connected || volumeGlove.connected;
    }

    private void updateBleButtonText() {
        if (btnScanConnect == null) return;

        if (!isBluetoothEnabled()) {
            btnScanConnect.setText("BT OFF");
            return;
        }

        if (isBleBusyOrConnected()) {
            btnScanConnect.setText("DISCONNECT");
        } else {
            btnScanConnect.setText("CONNECT");
        }
    }

    private void showHelpDialog(String title, GloveClient glove) {
        StringBuilder sb = new StringBuilder();
        sb.append("Device: ").append(glove.targetDeviceName).append("\n");
        sb.append("Connected: ").append(glove.connected).append("\n");
        sb.append("Notify: ").append(glove.notificationsEnabled).append("\n");
        sb.append("Telemetry stale: ").append(glove.telemetryStale).append("\n\n");
        sb.append("Background audio: ").append(bgAudioEnabled ? "ON" : "OFF").append("\n");
        sb.append("BLE button: ").append(isBleBusyOrConnected() ? "DISCONNECT" : "CONNECT").append("\n");

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

    // =========================
    // Permissions
    // =========================
    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                    REQ_PERMS);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_PERMS);
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
        toastSafe(ok ? "Permissions granted" : "BLE permissions required");
    }

    // =========================
    // Scanning / connect
    // =========================
    private void startScanAndConnect() {
        cancelAutoReconnect();

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }

        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            return;
        }

        if (isScanning) stopScanIfRunning();

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            appendLogSafe("Scanner unavailable");
            setStatusText("Scanner unavailable");
            updateStatusLineText();
            updateBleButtonText();
            return;
        }

        pitchGlove.seenDuringCurrentScan = false;
        volumeGlove.seenDuringCurrentScan = false;

        isScanning = true;
        setStatusText("Scanning...");
        updateStatusLineText();
        updateBleButtonText();
        appendLogSafe("Scan started (" + SCAN_TIMEOUT_MS + "ms)");

        try {
            bleScanner.startScan(scanCallback);
        } catch (Exception e) {
            appendLogSafe("startScan exception: " + e.getClass().getSimpleName());
            isScanning = false;
            updateBleButtonText();
            scheduleAutoReconnect("scan exception");
            updateStatusLineText();
            return;
        }

        scheduleScanTimeout();
    }

    private void scheduleScanTimeout() {
        cancelScanTimeout();
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private void cancelScanTimeout() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
    }

    private void onScanTimeout() {
        if (!isScanning) return;
        appendLogSafe("Scan timeout");
        stopScanIfRunning();
        updateBleButtonText();
        if (!pitchGlove.connected || !volumeGlove.connected) {
            scheduleAutoReconnect("scan timeout missing glove");
        }
    }

    private void stopScanIfRunning() {
        cancelScanTimeout();
        if (!isScanning || bleScanner == null) return;
        try {
            bleScanner.stopScan(scanCallback);
        } catch (Exception ignored) {
        }
        isScanning = false;
        appendLogSafe("Scan stopped");
        updateStatusLineText();
        updateBleButtonText();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeDeviceName(device);
            if (name == null) return;

            if (PITCH_DEVICE_NAME.equals(name)) {
                pitchGlove.seenDuringCurrentScan = true;
                maybeConnectToGloveDevice(pitchGlove, device);
            } else if (VOLUME_DEVICE_NAME.equals(name)) {
                volumeGlove.seenDuringCurrentScan = true;
                maybeConnectToGloveDevice(volumeGlove, device);
            }

            if (pitchGlove.connected && volumeGlove.connected) stopScanIfRunning();
            updateBleButtonText();
        }
    };

    @SuppressLint("MissingPermission")
    private void maybeConnectToGloveDevice(GloveClient glove, BluetoothDevice device) {
        if (glove.connected || glove.connecting) return;

        if (glove.gatt != null) {
            appendLogSafe(glove.roleLabel + ": found stale GATT before connect, clearing it");
            safeCloseGloveConnection(glove);
        }

        glove.connecting = true;
        glove.connectAttemptStartMs = SystemClock.elapsedRealtime();
        try {
            glove.lastDeviceAddress = device.getAddress();
        } catch (Exception ignored) {
            glove.lastDeviceAddress = "";
        }

        appendLogSafe(glove.roleLabel + ": connecting to " + safeNameWithFallback(device));
        updateBleButtonText();

        BluetoothGattCallback callback = createGattCallback(glove);
        Context gattContext = (appContext != null) ? appContext : getApplicationContext();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                glove.gatt = device.connectGatt(gattContext, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                glove.gatt = device.connectGatt(gattContext, false, callback);
            }
        } catch (Exception e) {
            glove.connecting = false;
            glove.gatt = null;
            glove.connectAttemptStartMs = 0L;
            appendLogSafe(glove.roleLabel + ": connect exception " + e.getClass().getSimpleName());
            updateBleButtonText();
            scheduleAutoReconnect(glove.roleLabel + " connect exception");
        }
    }

    private void scheduleAutoReconnect(String reason) {
        if (appShuttingDown || !autoReconnectEnabled || manualDisconnectRequested) return;
        if (!isBluetoothEnabled()) return;
        if (pitchGlove.connected && volumeGlove.connected) return;

        cancelAutoReconnect();
        appendLogSafe("Auto-reconnect scheduled (" + reason + ")");
        mainHandler.postDelayed(autoReconnectRunnable, AUTO_RECONNECT_DELAY_MS);
    }

    private void runAutoReconnect() {
        if (appShuttingDown || !autoReconnectEnabled || manualDisconnectRequested) return;
        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            return;
        }
        if (pitchGlove.connected && volumeGlove.connected) return;
        if (isScanning) return;

        appendLogSafe("Auto-reconnect scanning...");
        startScanAndConnect();
    }

    private void cancelAutoReconnect() {
        mainHandler.removeCallbacks(autoReconnectRunnable);
    }

    private void disconnectAllGlovesInternal(boolean silent) {
        cancelAutoReconnect();
        stopScanIfRunning();
        safeCloseGloveConnection(pitchGlove);
        safeCloseGloveConnection(volumeGlove);
        updateStatusLineText();

        audioTargetVolumeLinear = 0f;
        mappedVolumeLinear = 0f;

        if (!silent) appendLogSafe("Manual disconnect all");
        updateAudioStatusText();
        updateBleButtonText();
    }

    // =========================
    // GATT callback guards
    // =========================
    private boolean isCurrentGattCallback(GloveClient glove, BluetoothGatt gatt) {
        return glove != null && gatt != null && glove.gatt == gatt;
    }

    @SuppressLint("MissingPermission")
    private void closeGattQuietly(BluetoothGatt gatt) {
        if (gatt == null) return;
        try {
            gatt.disconnect();
        } catch (Exception ignored) {
        }
        try {
            gatt.close();
        } catch (Exception ignored) {
        }
    }

    @SuppressLint("MissingPermission")
    private void safeCloseGloveConnection(GloveClient glove) {
        if (glove == null) return;

        glove.connecting = false;
        glove.connected = false;
        glove.notificationsEnabled = false;
        glove.telemetryStale = false;
        glove.lastPingMs = 0L;
        glove.connectAttemptStartMs = 0L;

        glove.txChar = null;
        glove.rxChar = null;

        BluetoothGatt g = glove.gatt;
        glove.gatt = null;

        if (g != null) {
            try {
                g.disconnect();
            } catch (Exception ignored) {
            }
            try {
                g.close();
            } catch (Exception ignored) {
            }
        }
    }

    private BluetoothGattCallback createGattCallback(GloveClient glove) {
        return new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (!isCurrentGattCallback(glove, gatt)) {
                    appendLogSafe(glove.roleLabel + ": ignoring connState from stale GATT");
                    closeGattQuietly(gatt);
                    return;
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    glove.connecting = false;
                    glove.connected = true;
                    glove.notificationsEnabled = false;
                    glove.telemetryStale = false;
                    glove.lastTelemetryMs = 0L;
                    glove.lastPingMs = 0L;
                    glove.connectAttemptStartMs = 0L;

                    appendLogSafe(glove.roleLabel + ": connected");
                    updateStatusLineText();
                    updateBleButtonText();

                    try {
                        gatt.discoverServices();
                    } catch (Exception e) {
                        appendLogSafe(glove.roleLabel + ": discoverServices exception");
                        scheduleAutoReconnect(glove.roleLabel + " discoverServices exception");
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    appendLogSafe(glove.roleLabel + ": disconnected (status=" + status + ")");

                    if (glove.gatt == gatt) {
                        safeCloseGloveConnection(glove);
                    } else {
                        closeGattQuietly(gatt);
                        return;
                    }

                    updateStatusLineText();
                    updateBleButtonText();

                    if (!pitchGlove.connected && !volumeGlove.connected) {
                        audioTargetVolumeLinear = 0f;
                        mappedVolumeLinear = 0f;
                    }

                    if (!manualDisconnectRequested) scheduleAutoReconnect(glove.roleLabel + " disconnected");
                    updateAudioStatusText();
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (!isCurrentGattCallback(glove, gatt)) {
                    closeGattQuietly(gatt);
                    return;
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    appendLogSafe(glove.roleLabel + ": service discovery failed " + status);
                    safeCloseGloveConnection(glove);
                    scheduleAutoReconnect(glove.roleLabel + " service discovery failed");
                    return;
                }

                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service == null) {
                    appendLogSafe(glove.roleLabel + ": service missing");
                    safeCloseGloveConnection(glove);
                    scheduleAutoReconnect(glove.roleLabel + " service missing");
                    return;
                }

                glove.txChar = service.getCharacteristic(TX_CHAR_UUID);
                glove.rxChar = service.getCharacteristic(RX_CHAR_UUID);

                if (glove.txChar != null) enableNotifications(glove, gatt, glove.txChar);

                sendCommandToGlove(glove, "H");
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                if (!isCurrentGattCallback(glove, gatt)) {
                    closeGattQuietly(gatt);
                    return;
                }
                glove.notificationsEnabled = (status == BluetoothGatt.GATT_SUCCESS);
                glove.lastTelemetryMs = SystemClock.elapsedRealtime();
                glove.telemetryStale = false;
                appendLogSafe(glove.roleLabel + ": notifications " + (glove.notificationsEnabled ? "ON" : "FAILED"));

                if (!glove.notificationsEnabled) {
                    safeCloseGloveConnection(glove);
                    scheduleAutoReconnect(glove.roleLabel + " notify failed");
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
                if (!isCurrentGattCallback(glove, gatt)) {
                    closeGattQuietly(gatt);
                    return;
                }
                handleGloveNotification(glove, characteristic, value);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                if (!isCurrentGattCallback(glove, gatt)) {
                    closeGattQuietly(gatt);
                    return;
                }
                handleGloveNotification(glove, characteristic, characteristic.getValue());
            }
        };
    }

    @SuppressLint("MissingPermission")
    private void enableNotifications(GloveClient glove, BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
        try {
            gatt.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                appendLogSafe(glove.roleLabel + ": CCCD missing");
                return;
            }
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(cccd);
        } catch (Exception e) {
            appendLogSafe(glove.roleLabel + ": enable notify exception");
        }
    }

    // =========================
    // Notification parsing
    // =========================
    private void handleGloveNotification(GloveClient glove, BluetoothGattCharacteristic ch, byte[] value) {
        if (ch == null || value == null) return;
        if (!TX_CHAR_UUID.equals(ch.getUuid())) return;

        String line = new String(value, StandardCharsets.UTF_8).trim();

        glove.lastPacket = line;
        glove.lastTelemetryMs = SystemClock.elapsedRealtime();
        glove.telemetryStale = false;

        if (line.startsWith("ACTIVE_DELTA_DEG:")) {
            Float v = parseTailFloat(line);
            if (v != null) {
                if (glove == pitchGlove) {
                    pitchActiveDeltaDeg = v;
                    pitchHasAngle = true;
                } else {
                    volActiveDeltaDeg = v;
                    volHasAngle = true;
                }
                recomputeMappedOutputs();
            }
            return;
        }

        if (line.startsWith("NEUTRAL_ROLL_DEG:")) {
            Float v = parseTailFloat(line);
            if (v != null) glove.neutralRollDeg = v;
        } else if (line.startsWith("DIRECTION:")) {
            glove.directionText = line.substring("DIRECTION:".length()).trim();
        } else if (line.startsWith("ROLE:")) {
            glove.roleTextFromDevice = line.substring("ROLE:".length()).trim();
        }

        appendLogSafe(glove.roleLabel + " <- " + line);
    }

    private Float parseTailFloat(String line) {
        int idx = line.indexOf(':');
        if (idx < 0 || idx >= line.length() - 1) return null;
        try {
            return Float.parseFloat(line.substring(idx + 1).trim());
        } catch (Exception e) {
            return null;
        }
    }

    // =========================
    // Mapping
    // =========================
    private void recomputeMappedOutputs() {
        sanitizeMappingValues();

        float freq = mapLinearClamped(
                pitchActiveDeltaDeg,
                pitchAngleMinDeg,
                pitchAngleMaxDeg,
                freqMinHz,
                freqMaxHz
        );

        float vol = mapLinearClamped(
                volActiveDeltaDeg,
                volumeAngleMinDeg,
                volumeAngleMaxDeg,
                0f,
                1f
        );

        if (!pitchHasAngle) freq = freqMinHz;
        if (!volHasAngle) vol = 0f;

        mappedFreqHz = freq;
        mappedVolumeLinear = vol;

        audioTargetFreqHz = mappedFreqHz;
        audioTargetVolumeLinear = mappedVolumeLinear;

        if (!isBluetoothEnabled() || (!pitchGlove.connected && !volumeGlove.connected)) {
            audioTargetVolumeLinear = 0f;
            mappedVolumeLinear = 0f;
        }
    }

    private float mapLinearClamped(float x, float inMin, float inMax, float outMin, float outMax) {
        if (Math.abs(inMax - inMin) < 1e-6f) return outMin;
        float t = (x - inMin) / (inMax - inMin);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return outMin + t * (outMax - outMin);
    }

    // =========================
    // Send commands
    // =========================
    @SuppressLint("MissingPermission")
    private void sendCommandToGlove(GloveClient glove, String cmd) {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }
        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            return;
        }

        if (glove.gatt == null || glove.rxChar == null || !glove.connected) return;

        try {
            glove.rxChar.setValue(cmd.getBytes(StandardCharsets.UTF_8));
            glove.gatt.writeCharacteristic(glove.rxChar);
        } catch (Exception ignored) {
        }
    }

    // =========================
    // UI refresh (fast)
    // =========================
    private void refreshUiFast() {
        if (!isBluetoothEnabled()) {
            tvStatus.setText("⚠️  BLUETOOTH OFF\nTurn Bluetooth ON to play");
        } else {
            tvStatus.setText("Status: " + statusText);
        }

        tvPitchConn.setText(connLine(pitchGlove, "Pitch (" + PITCH_DEVICE_NAME + ")"));
        tvVolConn.setText(connLine(volumeGlove, "Volume (" + VOLUME_DEVICE_NAME + ")"));

        updateAudioStatusText();
        updateBleButtonText();

        tvPitchValue.setText(String.format(Locale.US, "Pitch ACTIVE_DELTA_DEG = %.2f°", pitchActiveDeltaDeg));
        tvVolValue.setText(String.format(Locale.US, "Volume ACTIVE_DELTA_DEG = %.2f°", volActiveDeltaDeg));

        tvToneValue.setText(String.format(Locale.US, "Mapped: %.1f Hz | Volume %.0f%%",
                mappedFreqHz, mappedVolumeLinear * 100f));

        tvPitchLast.setText("Pitch last: " + pitchGlove.lastPacket);
        tvVolLast.setText("Volume last: " + volumeGlove.lastPacket);

        if (thereminVisualizerView != null) {
            thereminVisualizerView.setThereminState(mappedFreqHz, mappedVolumeLinear, freqMinHz, freqMaxHz);
        }

        syncMappingValueTextsOnly();
    }

    private String connLine(GloveClient g, String prefix) {
        if (g.connected) {
            if (g.telemetryStale) return prefix + ": CONNECTED ⚠️ (no data)";
            return prefix + ": CONNECTED ✅";
        }
        if (g.connecting) return prefix + ": CONNECTING…";
        return prefix + ": DISCONNECTED ❌";
    }

    private void updateAudioStatusText() {
        boolean running = audioEngine != null && audioEngine.isRunning();
        String bg = bgAudioEnabled ? "BG:ON" : "BG:OFF";
        tvAudio.setText((running ? "Audio: RUNNING ✅" : "Audio: STOPPED") + " | " + bg);

        if (btnAudioStart != null) btnAudioStart.setText(running ? "⏸ PAUSE" : "▶ PLAY");
        if (btnAudioStop != null) btnAudioStop.setText(bgAudioEnabled ? "BG AUDIO: ON" : "BG AUDIO: OFF");
    }

    private void setStatusText(String s) {
        statusText = s;
    }

    private void updateStatusLineText() {
        if (!isBluetoothEnabled()) {
            setStatusText("Bluetooth off");
            return;
        }

        if (pitchGlove.connected && volumeGlove.connected) {
            setStatusText("Both gloves connected");
        } else if (pitchGlove.connected || volumeGlove.connected) {
            setStatusText("One glove connected");
        } else if (isScanning) {
            setStatusText("Scanning...");
        } else {
            setStatusText("Idle / disconnected");
        }

        if (!pitchGlove.connected && !volumeGlove.connected) {
            audioTargetVolumeLinear = 0f;
            mappedVolumeLinear = 0f;
        }
    }

    // =========================
    // Logging (bounded + slow flush)
    // =========================
    private void appendLogSafe(String msg) {
        final long now = SystemClock.elapsedRealtime();
        synchronized (logLines) {
            logLines.addLast(msg);
            while (logLines.size() > LOG_MAX_LINES) logLines.removeFirst();
            if (now - lastLogFlushMs < LOG_FLUSH_MIN_INTERVAL_MS) return;
            lastLogFlushMs = now;

            StringBuilder sb = new StringBuilder();
            for (String s : logLines) sb.append(s).append('\n');
            final String out = sb.toString();

            mainHandler.post(() -> {
                if (tvLog != null) tvLog.setText(out);
            });
        }
    }

    private void toastSafe(String msg) {
        mainHandler.post(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    @SuppressLint("MissingPermission")
    private String safeDeviceName(BluetoothDevice d) {
        if (d == null) return null;
        try {
            return d.getName();
        } catch (Exception ignored) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private String safeNameWithFallback(BluetoothDevice d) {
        if (d == null) return "(null)";
        String n = safeDeviceName(d);
        if (n != null) return n;
        try {
            return d.getAddress();
        } catch (Exception ignored) {
            return "(unknown)";
        }
    }

    // =========================
    // Bluetooth receiver registration
    // =========================
    private void registerBluetoothStateReceiverIfNeeded() {
        if (bluetoothStateReceiverRegistered) return;
        try {
            registerReceiver(bluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            bluetoothStateReceiverRegistered = true;
        } catch (Exception e) {
            appendLogSafe("BT receiver register failed");
        }
    }

    private void unregisterBluetoothStateReceiverIfNeeded() {
        if (!bluetoothStateReceiverRegistered) return;
        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (Exception ignored) {
        }
        bluetoothStateReceiverRegistered = false;
    }

    // =========================
    // Settings / mapping UI
    // =========================
    private void setupSlidersAndClickNumbers() {
        settingsRepo = new AppSettingsRepository(this);

        sbPitchAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbPitchAngleMax.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMax.setMax(ANGLE_PROGRESS_MAX);
        sbFreqMin.setMax(FREQ_PROGRESS_MAX);
        sbFreqMax.setMax(FREQ_PROGRESS_MAX);

        AppSettings s = settingsRepo.load();
        if (s != null) {
            pitchAngleMinDeg = s.pitchAngleMinDeg;
            pitchAngleMaxDeg = s.pitchAngleMaxDeg;
            freqMinHz = s.freqMinHz;
            freqMaxHz = s.freqMaxHz;
            volumeAngleMinDeg = s.volumeAngleMinDeg;
            volumeAngleMaxDeg = s.volumeAngleMaxDeg;
        } else {
            setDefaultMappingValues();
        }

        SeekBar.OnSeekBarChangeListener l = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (suppressSliderCallbacks) return;
                updateMappingFromControls();
                recomputeMappedOutputs();
                persistSettings();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };

        sbPitchAngleMin.setOnSeekBarChangeListener(l);
        sbPitchAngleMax.setOnSeekBarChangeListener(l);
        sbVolAngleMin.setOnSeekBarChangeListener(l);
        sbVolAngleMax.setOnSeekBarChangeListener(l);
        sbFreqMin.setOnSeekBarChangeListener(l);
        sbFreqMax.setOnSeekBarChangeListener(l);

        setupNumberClickEdit(tvPitchAngleMinVal, () -> pitchAngleMinDeg, v -> pitchAngleMinDeg = v);
        setupNumberClickEdit(tvPitchAngleMaxVal, () -> pitchAngleMaxDeg, v -> pitchAngleMaxDeg = v);
        setupNumberClickEdit(tvFreqMinVal, () -> freqMinHz, v -> freqMinHz = v);
        setupNumberClickEdit(tvFreqMaxVal, () -> freqMaxHz, v -> freqMaxHz = v);
        setupNumberClickEdit(tvVolAngleMinVal, () -> volumeAngleMinDeg, v -> volumeAngleMinDeg = v);
        setupNumberClickEdit(tvVolAngleMaxVal, () -> volumeAngleMaxDeg, v -> volumeAngleMaxDeg = v);
    }

    private interface FloatGetter {
        float get();
    }

    private interface FloatSetter {
        void set(float v);
    }

    private void setupNumberClickEdit(TextView tv, FloatGetter getter, FloatSetter setter) {
        tv.setOnClickListener(v -> {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("Edit value");

            EditText et = new EditText(this);
            et.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
            et.setText(String.format(Locale.US, "%.2f", getter.get()));
            b.setView(et);

            b.setPositiveButton("OK", (d, which) -> {
                try {
                    float val = Float.parseFloat(et.getText().toString().trim());
                    setter.set(val);
                    sanitizeMappingValues();
                    syncAllMappingControlsFromState();
                    recomputeMappedOutputs();
                    persistSettings();
                } catch (Exception ignored) {
                    toastSafe("Invalid number");
                }
            });
            b.setNegativeButton("Cancel", null);
            b.show();
        });
    }

    private void persistSettings() {
        if (settingsRepo == null) return;
        AppSettings s = new AppSettings();
        s.pitchAngleMinDeg = pitchAngleMinDeg;
        s.pitchAngleMaxDeg = pitchAngleMaxDeg;
        s.freqMinHz = freqMinHz;
        s.freqMaxHz = freqMaxHz;
        s.volumeAngleMinDeg = volumeAngleMinDeg;
        s.volumeAngleMaxDeg = volumeAngleMaxDeg;
        settingsRepo.save(s);
    }

    private void reloadMappingSettingsFromRepository() {
        if (settingsRepo == null) return;

        AppSettings s = settingsRepo.load();
        if (s == null) return;

        pitchAngleMinDeg = s.pitchAngleMinDeg;
        pitchAngleMaxDeg = s.pitchAngleMaxDeg;
        freqMinHz = s.freqMinHz;
        freqMaxHz = s.freqMaxHz;
        volumeAngleMinDeg = s.volumeAngleMinDeg;
        volumeAngleMaxDeg = s.volumeAngleMaxDeg;

        sanitizeMappingValues();
        syncAllMappingControlsFromState();
        recomputeMappedOutputs();
    }

    private void updateMappingFromControls() {
        pitchAngleMinDeg = ANGLE_MIN + sbPitchAngleMin.getProgress() * ANGLE_STEP;
        pitchAngleMaxDeg = ANGLE_MIN + sbPitchAngleMax.getProgress() * ANGLE_STEP;

        volumeAngleMinDeg = ANGLE_MIN + sbVolAngleMin.getProgress() * ANGLE_STEP;
        volumeAngleMaxDeg = ANGLE_MIN + sbVolAngleMax.getProgress() * ANGLE_STEP;

        freqMinHz = FREQ_MIN_UI + sbFreqMin.getProgress();
        freqMaxHz = FREQ_MIN_UI + sbFreqMax.getProgress();

        sanitizeMappingValues();
        syncMappingValueTextsOnly();
    }

    private void syncMappingValueTextsOnly() {
        tvPitchAngleMinVal.setText(String.format(Locale.US, "%.2f°", pitchAngleMinDeg));
        tvPitchAngleMaxVal.setText(String.format(Locale.US, "%.2f°", pitchAngleMaxDeg));
        tvVolAngleMinVal.setText(String.format(Locale.US, "%.2f°", volumeAngleMinDeg));
        tvVolAngleMaxVal.setText(String.format(Locale.US, "%.2f°", volumeAngleMaxDeg));
        tvFreqMinVal.setText(String.format(Locale.US, "%.1f Hz", freqMinHz));
        tvFreqMaxVal.setText(String.format(Locale.US, "%.1f Hz", freqMaxHz));
    }

    private void syncAllMappingControlsFromState() {
        suppressSliderCallbacks = true;

        sbPitchAngleMin.setProgress(clampInt((int) ((pitchAngleMinDeg - ANGLE_MIN) / ANGLE_STEP), 0, ANGLE_PROGRESS_MAX));
        sbPitchAngleMax.setProgress(clampInt((int) ((pitchAngleMaxDeg - ANGLE_MIN) / ANGLE_STEP), 0, ANGLE_PROGRESS_MAX));

        sbVolAngleMin.setProgress(clampInt((int) ((volumeAngleMinDeg - ANGLE_MIN) / ANGLE_STEP), 0, ANGLE_PROGRESS_MAX));
        sbVolAngleMax.setProgress(clampInt((int) ((volumeAngleMaxDeg - ANGLE_MIN) / ANGLE_STEP), 0, ANGLE_PROGRESS_MAX));

        sbFreqMin.setProgress(clampInt((int) (freqMinHz - FREQ_MIN_UI), 0, FREQ_PROGRESS_MAX));
        sbFreqMax.setProgress(clampInt((int) (freqMaxHz - FREQ_MIN_UI), 0, FREQ_PROGRESS_MAX));

        syncMappingValueTextsOnly();
        suppressSliderCallbacks = false;
    }

    private void setDefaultMappingValues() {
        pitchAngleMinDeg = -15.0f;
        pitchAngleMaxDeg = 55.0f;
        freqMinHz = 880.0f;
        freqMaxHz = 2000.0f;

        volumeAngleMinDeg = -10.0f;
        volumeAngleMaxDeg = 55.0f;
    }

    private void sanitizeMappingValues() {
        if (pitchAngleMaxDeg < pitchAngleMinDeg + 0.5f) pitchAngleMaxDeg = pitchAngleMinDeg + 0.5f;
        if (volumeAngleMaxDeg < volumeAngleMinDeg + 0.5f) volumeAngleMaxDeg = volumeAngleMinDeg + 0.5f;
        if (freqMaxHz < freqMinHz + 1f) freqMaxHz = freqMinHz + 1f;

        pitchAngleMinDeg = clamp(pitchAngleMinDeg, ANGLE_MIN, ANGLE_MAX);
        pitchAngleMaxDeg = clamp(pitchAngleMaxDeg, ANGLE_MIN, ANGLE_MAX);
        volumeAngleMinDeg = clamp(volumeAngleMinDeg, ANGLE_MIN, ANGLE_MAX);
        volumeAngleMaxDeg = clamp(volumeAngleMaxDeg, ANGLE_MIN, ANGLE_MAX);
        freqMinHz = clamp(freqMinHz, FREQ_MIN_UI, FREQ_MAX_UI);
        freqMaxHz = clamp(freqMaxHz, FREQ_MIN_UI, FREQ_MAX_UI);
    }

    private float clamp(float v, float lo, float hi) {
        return (v < lo) ? lo : Math.min(v, hi);
    }

    private int clampInt(int v, int lo, int hi) {
        return (v < lo) ? lo : Math.min(v, hi);
    }

    private float clamp01(float v) {
        return (v < 0f) ? 0f : Math.min(v, 1f);
    }

    // =========================
    // Audio
    // =========================
    class AudioEngine {
        private static final int SAMPLE_RATE = 48000;
        private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
        private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

        private Thread thread;
        private volatile boolean running = false;

        boolean isRunning() {
            return running;
        }

        void start() {
            if (running) return;
            running = true;
            thread = new Thread(this::runAudio, "ThereminAudioThread");
            thread.start();
            appendLogSafe("Audio started");
        }

        void stop() {
            running = false;
            if (thread != null) {
                try {
                    thread.join(400);
                } catch (InterruptedException ignored) {
                }
                thread = null;
            }
            appendLogSafe("Audio stopped");
        }

        private void runAudio() {
            int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            if (minBuffer <= 0) minBuffer = 2048;
            int bufferSize = Math.max(minBuffer, 4096);

            AudioTrack track = null;
            try {
                track = new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                );

                short[] buffer = new short[512];
                double phase = 0.0;
                final double twoPi = 2.0 * Math.PI;

                float freqSmooth = Math.max(20f, mappedFreqHz);
                float volSmooth = 0f;

                track.play();

                while (running) {
                    if (!isBluetoothEnabled()) {
                        running = false;
                        break;
                    }

                    float targetFreq = Math.max(20f, audioTargetFreqHz);
                    float targetVol = clamp01(audioTargetVolumeLinear);

                    freqSmooth += 0.20f * (targetFreq - freqSmooth);
                    volSmooth += 0.20f * (targetVol - volSmooth);

                    double phaseInc = twoPi * freqSmooth / SAMPLE_RATE;

                    for (int i = 0; i < buffer.length; i++) {
                        double s = Math.sin(phase) * volSmooth;
                        buffer[i] = (short) (s * 32767.0);
                        phase += phaseInc;
                        if (phase > twoPi) phase -= twoPi;
                    }

                    track.write(buffer, 0, buffer.length);
                }
            } catch (Exception e) {
                appendLogSafe("Audio error: " + e.getClass().getSimpleName());
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                    } catch (Exception ignored) {
                    }
                    try {
                        track.release();
                    } catch (Exception ignored) {
                    }
                }
                mainHandler.post(MainActivity.this::updateAudioStatusText);
            }
        }
    }

    // =========================
    // Glove state container
    // =========================
    class GloveClient {
        final String roleLabel;
        final String targetDeviceName;

        BluetoothGatt gatt;
        BluetoothGattCharacteristic txChar;
        BluetoothGattCharacteristic rxChar;

        boolean connecting = false;
        boolean connected = false;
        boolean notificationsEnabled = false;
        boolean seenDuringCurrentScan = false;

        long lastTelemetryMs = 0L;
        long lastPingMs = 0L;
        long connectAttemptStartMs = 0L;
        boolean telemetryStale = false;

        String lastDeviceAddress = "";
        String lastPacket = "(none)";
        String directionText = "";
        String roleTextFromDevice = "";

        float neutralRollDeg = 0f;

        GloveClient(String roleLabel, String targetDeviceName) {
            this.roleLabel = roleLabel;
            this.targetDeviceName = targetDeviceName;
        }
    }
}