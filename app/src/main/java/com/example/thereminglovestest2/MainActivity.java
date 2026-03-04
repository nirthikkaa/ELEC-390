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
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    private static WeakReference<MainActivity> activeHostRef = new WeakReference<>(null);

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
    private static final String PREF_DIRECTION_DEFAULTS_MIGRATED_V2 = "direction_defaults_migrated_v2";

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

    private Button btnScanConnect, btnDisconnectAll, btnAudioStart, btnAudioStop, btnOpenCalibration;
    private Button btnNeutralPitch, btnNeutralVol, btnDirectionPitch, btnDirectionVol, btnHelpPitch, btnHelpVol;
    private Button btnDefaults;

    private ThereminVisualizerView thereminVisualizerView;
    private Animation calibrateGlowAnimation;

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

    private volatile boolean pitchDirectionInverted = false;
    private volatile boolean volumeDirectionInverted = true;

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
    private volatile String statusText = "Connect both gloves to start playing";
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

    public static final class BleUiSnapshot {
        public final boolean hostReady;
        public final boolean bluetoothEnabled;
        public final boolean scanning;
        public final boolean busyOrConnected;
        public final String statusText;
        public final String pitchConnText;
        public final String volumeConnText;
        public final String pitchLastText;
        public final String volumeLastText;

        public BleUiSnapshot(
                boolean hostReady,
                boolean bluetoothEnabled,
                boolean scanning,
                boolean busyOrConnected,
                String statusText,
                String pitchConnText,
                String volumeConnText,
                String pitchLastText,
                String volumeLastText
        ) {
            this.hostReady = hostReady;
            this.bluetoothEnabled = bluetoothEnabled;
            this.scanning = scanning;
            this.busyOrConnected = busyOrConnected;
            this.statusText = statusText;
            this.pitchConnText = pitchConnText;
            this.volumeConnText = volumeConnText;
            this.pitchLastText = pitchLastText;
            this.volumeLastText = volumeLastText;
        }
    }

    public static final class CalibrationUiSnapshot {
        public final boolean hostReady;
        public final boolean bluetoothEnabled;
        public final boolean pitchConnected;
        public final boolean volumeConnected;
        public final float pitchActiveDeltaDeg;
        public final float volumeActiveDeltaDeg;
        public final float pitchNeutralRollDeg;
        public final float volumeNeutralRollDeg;
        public final String pitchDirectionText;
        public final String volumeDirectionText;

        public CalibrationUiSnapshot(
                boolean hostReady,
                boolean bluetoothEnabled,
                boolean pitchConnected,
                boolean volumeConnected,
                float pitchActiveDeltaDeg,
                float volumeActiveDeltaDeg,
                float pitchNeutralRollDeg,
                float volumeNeutralRollDeg,
                String pitchDirectionText,
                String volumeDirectionText
        ) {
            this.hostReady = hostReady;
            this.bluetoothEnabled = bluetoothEnabled;
            this.pitchConnected = pitchConnected;
            this.volumeConnected = volumeConnected;
            this.pitchActiveDeltaDeg = pitchActiveDeltaDeg;
            this.volumeActiveDeltaDeg = volumeActiveDeltaDeg;
            this.pitchNeutralRollDeg = pitchNeutralRollDeg;
            this.volumeNeutralRollDeg = volumeNeutralRollDeg;
            this.pitchDirectionText = pitchDirectionText;
            this.volumeDirectionText = volumeDirectionText;
        }
    }

    private static MainActivity getActiveHost() {
        return activeHostRef.get();
    }

    public static boolean isBleHostAvailable() {
        MainActivity host = getActiveHost();
        return host != null && !host.isFinishing();
    }

    public static BleUiSnapshot getBleUiSnapshot() {
        MainActivity host = getActiveHost();
        if (host == null) return null;
        return host.buildBleUiSnapshot();
    }

    public static CalibrationUiSnapshot getCalibrationUiSnapshot() {
        MainActivity host = getActiveHost();
        if (host == null) return null;
        return host.buildCalibrationUiSnapshot();
    }

    public static void requestBleToggleFromFacade() {
        MainActivity host = getActiveHost();
        if (host == null) return;
        host.mainHandler.post(host::onBleTogglePressed);
    }

    public static void requestCaptureNeutralFromFacade(boolean isPitch) {
        MainActivity host = getActiveHost();
        if (host == null) return;
        host.mainHandler.post(() ->
                host.sendCommandToGlove(isPitch ? host.pitchGlove : host.volumeGlove, "N")
        );
    }

    public static void requestToggleDirectionFromFacade(boolean isPitch) {
        MainActivity host = getActiveHost();
        if (host == null) return;
        host.mainHandler.post(() -> host.toggleDirectionPreferenceAndSend(isPitch));
    }

    public static void requestRefreshHandshakeFromFacade() {
        MainActivity host = getActiveHost();
        if (host == null) return;
        host.mainHandler.post(() -> {
            host.sendCommandToGlove(host.pitchGlove, "H");
            host.sendCommandToGlove(host.volumeGlove, "H");
        });
    }

    private BleUiSnapshot buildBleUiSnapshot() {
        boolean btEnabled = isBluetoothEnabled();
        String bigStatus = btEnabled
                ? ("Status: " + statusText)
                : "⚠️ BLUETOOTH OFF — Turn Bluetooth ON to play";

        return new BleUiSnapshot(
                true,
                btEnabled,
                isScanning,
                isBleBusyOrConnected(),
                bigStatus,
                connLine(pitchGlove, "Pitch (" + PITCH_DEVICE_NAME + ")"),
                connLine(volumeGlove, "Volume (" + VOLUME_DEVICE_NAME + ")"),
                "Pitch last: " + pitchGlove.lastPacket,
                "Volume last: " + volumeGlove.lastPacket
        );
    }

    private CalibrationUiSnapshot buildCalibrationUiSnapshot() {
        return new CalibrationUiSnapshot(
                true,
                isBluetoothEnabled(),
                pitchGlove.connected,
                volumeGlove.connected,
                pitchActiveDeltaDeg,
                volActiveDeltaDeg,
                pitchGlove.neutralRollDeg,
                volumeGlove.neutralRollDeg,
                safeDirectionText(pitchGlove),
                safeDirectionText(volumeGlove)
        );
    }

    private String safeDirectionText(GloveClient glove) {
        if (glove == null) return "UNKNOWN";
        if (glove.directionText == null) return "UNKNOWN";
        String trimmed = glove.directionText.trim();
        return trimmed.isEmpty() ? "UNKNOWN" : trimmed;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appContext = getApplicationContext();
        BleHostBridge.initialize(appContext);
        activeHostRef = new WeakReference<>(this);

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

        setStatusText("Connect both gloves to start playing");
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
        activeHostRef = new WeakReference<>(this);
        keepBleAliveAcrossDestroy = true;
        registerBluetoothStateReceiverIfNeeded();

        cleanupStaleDisconnectedGattState();
        reloadMappingSettingsFromRepository();

        mainHandler.post(uiTicker);
        mainHandler.removeCallbacks(connectionTruthWatchdogRunnable);
        mainHandler.post(connectionTruthWatchdogRunnable);

        BleHostBridge.maybeStartAutoConnect();
    }

    private void cleanupStaleDisconnectedGattState() {
        cleanupStaleDisconnectedGattStateForGlove(pitchGlove);
        cleanupStaleDisconnectedGattStateForGlove(volumeGlove);
    }

    private void cleanupStaleDisconnectedGattStateForGlove(GloveClient glove) {
        if (glove == null) return;
        if (glove.connected) return;
        if (glove.connecting) return;
        if (glove.gatt == null) return;

        appendLogSafe(glove.roleLabel + ": cleaning stale GATT reference");
        safeCloseGloveConnection(glove);
    }

    @Override
    protected void onResume() {
        super.onResume();
        keepBleAliveAcrossDestroy = true;

        activeHostRef = new WeakReference<>(this);

        // Preserve the user's manual play/pause choice when returning to Play.
        // Do not auto-start audio here.
        updateAudioStatusText();

        reloadMappingSettingsFromRepository();
        syncAllMappingControlsFromState();
        updateStatusLineText();
        updateBleButtonText();
    }
    @Override
    protected void onPause() {
        super.onPause();

        if (audioEngine != null) {
            if (!bgAudioEnabled && !isChangingConfigurations()) {
                audioEngine.stop();
                appendLogSafe("onPause -> audio stopped (background off)");
            } else if (bgAudioEnabled && audioEngine.isRunning()) {
                appendLogSafe("onPause -> audio kept running in background");
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacks(uiTicker);

        if (isChangingConfigurations()) {
            keepBleAliveAcrossDestroy = true;
            return;
        }

        if (bgAudioEnabled) {
            keepBleAliveAcrossDestroy = true;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        appShuttingDown = isFinishing();
        activeHostRef = new WeakReference<>(null);

        mainHandler.removeCallbacks(uiTicker);
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        mainHandler.removeCallbacks(autoReconnectRunnable);
        mainHandler.removeCallbacks(connectionTruthWatchdogRunnable);

        unregisterBluetoothStateReceiverIfNeeded();

        boolean shouldKeepBleAlive = keepBleAliveAcrossDestroy && !appShuttingDown;
        if (!shouldKeepBleAlive) {
            disconnectAllGlovesInternal(true);
        }

        if (audioEngine != null) {
            if (!bgAudioEnabled || appShuttingDown) {
                audioEngine.shutdown();
            }
        }
    }

    private void loadBgAudioPref() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        bgAudioEnabled = sp.getBoolean(PREF_BG_AUDIO_ENABLED, true);
    }

    private void saveBgAudioPref() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        sp.edit().putBoolean(PREF_BG_AUDIO_ENABLED, bgAudioEnabled).apply();
    }

    private void onBluetoothAdapterStateChanged(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
            case BluetoothAdapter.STATE_TURNING_OFF:
                appendLogSafe("Bluetooth OFF");
                handleBluetoothOffHard();
                break;

            case BluetoothAdapter.STATE_ON:
                appendLogSafe("Bluetooth ON");
                updateStatusLineText();
                updateBleButtonText();
                if (!manualDisconnectRequested) {
                    scheduleAutoReconnect("Bluetooth on");
                }
                break;

            case BluetoothAdapter.STATE_TURNING_ON:
                appendLogSafe("Bluetooth turning on");
                setStatusText("Bluetooth is turning on...");
                updateBleButtonText();
                break;

            default:
                break;
        }
    }

    private void handleBluetoothOffHard() {
        cancelAutoReconnect();
        stopScanIfRunning();

        safeCloseGloveConnection(pitchGlove);
        safeCloseGloveConnection(volumeGlove);

        pitchActiveDeltaDeg = 0f;
        volActiveDeltaDeg = 0f;
        pitchHasAngle = false;
        volHasAngle = false;

        pitchGlove.lastPacket = "(none)";
        volumeGlove.lastPacket = "(none)";

        setStatusText("⚠️ BLUETOOTH OFF — Turn Bluetooth ON to play");
        mappedVolumeLinear = 0f;
        audioTargetVolumeLinear = 0f;

        updateStatusLineText();
        updateBleButtonText();
        updateAudioStatusText();
    }

    private boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

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

        appendLogSafe(glove.roleLabel + ": connect timeout");
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
            appendLogSafe(glove.roleLabel + ": stale-ish -> request handshake");
            sendCommandToGlove(glove, "H");
        }

        if (age > STALE_RECONNECT_MS) {
            appendLogSafe(glove.roleLabel + ": telemetry stale too long -> reconnect");
            safeCloseGloveConnection(glove);
            updateStatusLineText();
            updateBleButtonText();

            if (!manualDisconnectRequested) {
                scheduleAutoReconnect(glove.roleLabel + " telemetry stale");
            }
        }
    }

    private void maybeAutoConnectOnLaunchOrReturn() {
        BleHostBridge.maybeStartAutoConnect();
    }

    private void bindViews() {
        TopNavBarView topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) {
            topNavBar.setTitleText("Play");
        }

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
        btnOpenCalibration = findViewById(R.id.btnOpenCalibration);

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

        if (btnOpenCalibration != null) {
            btnOpenCalibration.setText("✨ Calibrate Precisely");
            btnOpenCalibration.setOnClickListener(v -> openCalibrationScreen());
        }

        btnNeutralPitch.setOnClickListener(v -> BleHostBridge.requestCaptureNeutral(true));
        btnNeutralVol.setOnClickListener(v -> BleHostBridge.requestCaptureNeutral(false));

        btnDirectionPitch.setOnClickListener(v -> toggleDirectionPreferenceAndSend(true));
        btnDirectionVol.setOnClickListener(v -> toggleDirectionPreferenceAndSend(false));

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
        BleHostBridge.requestBleToggle();
        updateBleButtonText();
    }

    private boolean isBleBusyOrConnected() {
        return isScanning
                || pitchGlove.connecting || volumeGlove.connecting
                || pitchGlove.connected || volumeGlove.connected;
    }

    private void updateBleButtonText() {
        if (btnScanConnect == null) return;

        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        if (bleSnapshot == null || !bleSnapshot.hostReady) {
            btnScanConnect.setText("Connect Gloves");
            return;
        }

        boolean bothConnected = calSnapshot != null && calSnapshot.pitchConnected && calSnapshot.volumeConnected;
        boolean oneConnected = calSnapshot != null && (calSnapshot.pitchConnected || calSnapshot.volumeConnected);
        boolean connecting = bleSnapshot.scanning
                || isConnecting(bleSnapshot.pitchConnText)
                || isConnecting(bleSnapshot.volumeConnText);

        if (!bleSnapshot.bluetoothEnabled) {
            btnScanConnect.setText("Bluetooth Off");
            return;
        }

        if (bothConnected) {
            btnScanConnect.setText("Disconnect Gloves");
        } else if (connecting || oneConnected) {
            btnScanConnect.setText("Reconnect Gloves");
        } else {
            btnScanConnect.setText("Connect Gloves");
        }
    }

    private void openCalibrationScreen() {
        keepBleAliveAcrossDestroy = true;
        Intent intent = new Intent(this, CalibrationActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
        );
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    private boolean desiredDirectionInvertedForGlove(GloveClient glove) {
        return glove == volumeGlove ? volumeDirectionInverted : pitchDirectionInverted;
    }

    private String desiredDirectionTextForGlove(GloveClient glove) {
        return desiredDirectionInvertedForGlove(glove) ? "NEGATIVE" : "POSITIVE";
    }

    private boolean reportedDirectionMatchesDesired(GloveClient glove) {
        String reported = safeDirectionText(glove).trim().toUpperCase(Locale.US);
        if (reported.isEmpty() || "UNKNOWN".equals(reported)) return false;
        return reported.contains(desiredDirectionTextForGlove(glove));
    }

    private void syncDirectionPreferenceIfNeeded(GloveClient glove) {
        if (glove == null || !glove.connected) return;

        String reported = safeDirectionText(glove).trim().toUpperCase(Locale.US);
        if (!reported.contains("POS") && !reported.contains("NEG")) return;
        if (reportedDirectionMatchesDesired(glove)) return;

        long now = SystemClock.elapsedRealtime();
        if (now - glove.lastDirectionSyncCommandMs < 1000L) return;

        glove.lastDirectionSyncCommandMs = now;
        appendLogSafe(glove.roleLabel + ": applying saved direction -> " + desiredDirectionTextForGlove(glove));
        sendCommandToGlove(glove, "D");
    }

    private void toggleDirectionPreferenceAndSend(boolean isPitch) {
        GloveClient glove = isPitch ? pitchGlove : volumeGlove;

        if (isPitch) {
            pitchDirectionInverted = !pitchDirectionInverted;
        } else {
            volumeDirectionInverted = !volumeDirectionInverted;
        }

        persistSettings();
        appendLogSafe(glove.roleLabel + ": direction preference saved -> " + desiredDirectionTextForGlove(glove));
        BleHostBridge.requestToggleDirection(isPitch);
    }

    private void applyDirectionDefaultsMigrationIfNeeded() {
        SharedPreferences sp = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean migrated = sp.getBoolean(PREF_DIRECTION_DEFAULTS_MIGRATED_V2, false);
        if (migrated) return;

        pitchDirectionInverted = false;
        volumeDirectionInverted = true;
        sp.edit().putBoolean(PREF_DIRECTION_DEFAULTS_MIGRATED_V2, true).apply();
        persistSettings();
        appendLogSafe("Applied direction defaults: volume glove inverted by default");
    }

    private void showHelpDialog(String title, GloveClient glove) {
        StringBuilder sb = new StringBuilder();
        sb.append("Device: ").append(glove.targetDeviceName).append("\n");
        sb.append("Connected: ").append(glove.connected).append("\n");
        sb.append("Notify: ").append(glove.notificationsEnabled).append("\n");
        sb.append("Telemetry stale: ").append(glove.telemetryStale).append("\n\n");
        sb.append("Background audio: ").append(bgAudioEnabled ? "ON" : "OFF").append("\n");
        sb.append("BLE button: ").append(isBleBusyOrConnected() ? "DISCONNECT" : "CONNECT").append("\n");
        sb.append("Saved default direction: ").append(desiredDirectionTextForGlove(glove)).append("\n");
        sb.append("Reported device direction: ").append(safeDirectionText(glove)).append("\n");

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(sb.toString())
                .setPositiveButton("OK", null)
                .show();
    }

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
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
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
            appendLogSafe("Permissions granted -> auto connect");
            startScanAndConnect();
        } else {
            appendLogSafe("Permissions denied");
            toastSafe("Bluetooth permissions are required");
        }
    }

    private void toastSafe(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void reloadMappingSettingsFromRepository() {
        if (settingsRepo == null) settingsRepo = new AppSettingsRepository(this);

        AppSettings s = settingsRepo.load();
        if (s == null) {
            setDefaultMappingValues();
            applyDirectionDefaultsMigrationIfNeeded();
            persistSettings();
            return;
        }

        pitchAngleMinDeg = s.pitchAngleMinDeg;
        pitchAngleMaxDeg = s.pitchAngleMaxDeg;
        freqMinHz = s.freqMinHz;
        freqMaxHz = s.freqMaxHz;

        volumeAngleMinDeg = s.volumeAngleMinDeg;
        volumeAngleMaxDeg = s.volumeAngleMaxDeg;

        pitchDirectionInverted = s.pitchDirectionInverted;
        volumeDirectionInverted = s.volumeDirectionInverted;

        sanitizeMappingValues();
    }

    private void persistSettings() {
        if (settingsRepo == null) settingsRepo = new AppSettingsRepository(this);

        sanitizeMappingValues();

        AppSettings s = settingsRepo.load();
        if (s == null) s = new AppSettings();

        s.pitchAngleMinDeg = pitchAngleMinDeg;
        s.pitchAngleMaxDeg = pitchAngleMaxDeg;
        s.freqMinHz = freqMinHz;
        s.freqMaxHz = freqMaxHz;

        s.volumeAngleMinDeg = volumeAngleMinDeg;
        s.volumeAngleMaxDeg = volumeAngleMaxDeg;

        s.pitchDirectionInverted = pitchDirectionInverted;
        s.volumeDirectionInverted = volumeDirectionInverted;

        settingsRepo.save(s);
    }

    private void setDefaultMappingValues() {
        pitchAngleMinDeg = -15.0f;
        pitchAngleMaxDeg = 55.0f;
        freqMinHz = 880.0f;
        freqMaxHz = 2000.0f;

        volumeAngleMinDeg = -10.0f;
        volumeAngleMaxDeg = 55.0f;

        pitchDirectionInverted = false;
        volumeDirectionInverted = true;

        sanitizeMappingValues();
    }

    private void sanitizeMappingValues() {
        pitchAngleMinDeg = clamp(pitchAngleMinDeg, ANGLE_MIN, ANGLE_MAX);
        pitchAngleMaxDeg = clamp(pitchAngleMaxDeg, ANGLE_MIN, ANGLE_MAX);
        volumeAngleMinDeg = clamp(volumeAngleMinDeg, ANGLE_MIN, ANGLE_MAX);
        volumeAngleMaxDeg = clamp(volumeAngleMaxDeg, ANGLE_MIN, ANGLE_MAX);

        freqMinHz = clamp(freqMinHz, FREQ_MIN_UI, FREQ_MAX_UI);
        freqMaxHz = clamp(freqMaxHz, FREQ_MIN_UI, FREQ_MAX_UI);

        if (pitchAngleMaxDeg < pitchAngleMinDeg + ANGLE_STEP) {
            pitchAngleMaxDeg = Math.min(ANGLE_MAX, pitchAngleMinDeg + ANGLE_STEP);
        }
        if (volumeAngleMaxDeg < volumeAngleMinDeg + ANGLE_STEP) {
            volumeAngleMaxDeg = Math.min(ANGLE_MAX, volumeAngleMinDeg + ANGLE_STEP);
        }
        if (freqMaxHz < freqMinHz + 1f) {
            freqMaxHz = Math.min(FREQ_MAX_UI, freqMinHz + 1f);
        }
    }

    private float clamp(float x, float lo, float hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private void setupSlidersAndClickNumbers() {
        reloadMappingSettingsFromRepository();

        sbPitchAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbPitchAngleMax.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMax.setMax(ANGLE_PROGRESS_MAX);

        sbFreqMin.setMax(FREQ_PROGRESS_MAX);
        sbFreqMax.setMax(FREQ_PROGRESS_MAX);

        SeekBar.OnSeekBarChangeListener commonAngleListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (suppressSliderCallbacks) return;

                float value = progressToAngle(progress);

                if (seekBar == sbPitchAngleMin) pitchAngleMinDeg = value;
                if (seekBar == sbPitchAngleMax) pitchAngleMaxDeg = value;
                if (seekBar == sbVolAngleMin) volumeAngleMinDeg = value;
                if (seekBar == sbVolAngleMax) volumeAngleMaxDeg = value;

                sanitizeMappingValues();
                syncAllMappingControlsFromState();
                recomputeMappedOutputs();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persistSettings();
            }
        };

        SeekBar.OnSeekBarChangeListener commonFreqListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (suppressSliderCallbacks) return;

                float value = progressToFreq(progress);

                if (seekBar == sbFreqMin) freqMinHz = value;
                if (seekBar == sbFreqMax) freqMaxHz = value;

                sanitizeMappingValues();
                syncAllMappingControlsFromState();
                recomputeMappedOutputs();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                persistSettings();
            }
        };

        sbPitchAngleMin.setOnSeekBarChangeListener(commonAngleListener);
        sbPitchAngleMax.setOnSeekBarChangeListener(commonAngleListener);
        sbVolAngleMin.setOnSeekBarChangeListener(commonAngleListener);
        sbVolAngleMax.setOnSeekBarChangeListener(commonAngleListener);

        sbFreqMin.setOnSeekBarChangeListener(commonFreqListener);
        sbFreqMax.setOnSeekBarChangeListener(commonFreqListener);

        makeNumberEditable(tvPitchAngleMinVal, "Pitch angle min", () -> pitchAngleMinDeg, v -> pitchAngleMinDeg = v, true);
        makeNumberEditable(tvPitchAngleMaxVal, "Pitch angle max", () -> pitchAngleMaxDeg, v -> pitchAngleMaxDeg = v, true);
        makeNumberEditable(tvVolAngleMinVal, "Volume angle min", () -> volumeAngleMinDeg, v -> volumeAngleMinDeg = v, true);
        makeNumberEditable(tvVolAngleMaxVal, "Volume angle max", () -> volumeAngleMaxDeg, v -> volumeAngleMaxDeg = v, true);
        makeNumberEditable(tvFreqMinVal, "Frequency min", () -> freqMinHz, v -> freqMinHz = v, false);
        makeNumberEditable(tvFreqMaxVal, "Frequency max", () -> freqMaxHz, v -> freqMaxHz = v, false);
    }

    private interface FloatGetter {
        float get();
    }

    private interface FloatSetter {
        void set(float v);
    }

    private void makeNumberEditable(TextView target,
                                    String title,
                                    FloatGetter getter,
                                    FloatSetter setter,
                                    boolean isAngle) {
        target.setOnClickListener(v -> showEditNumberDialog(title, getter.get(), setter, isAngle));
    }

    private void showEditNumberDialog(String title,
                                      float currentValue,
                                      FloatSetter setter,
                                      boolean isAngle) {
        final EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        et.setText(String.format(Locale.US, isAngle ? "%.1f" : "%.0f", currentValue));
        et.setSelection(et.getText().length());

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(et)
                .setPositiveButton("OK", (dialog, which) -> {
                    try {
                        float v = Float.parseFloat(et.getText().toString().trim());
                        setter.set(v);
                        sanitizeMappingValues();
                        syncAllMappingControlsFromState();
                        recomputeMappedOutputs();
                        persistSettings();
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void syncAllMappingControlsFromState() {
        suppressSliderCallbacks = true;

        sbPitchAngleMin.setProgress(angleToProgress(pitchAngleMinDeg));
        sbPitchAngleMax.setProgress(angleToProgress(pitchAngleMaxDeg));
        sbVolAngleMin.setProgress(angleToProgress(volumeAngleMinDeg));
        sbVolAngleMax.setProgress(angleToProgress(volumeAngleMaxDeg));

        sbFreqMin.setProgress(freqToProgress(freqMinHz));
        sbFreqMax.setProgress(freqToProgress(freqMaxHz));

        suppressSliderCallbacks = false;
        syncMappingValueTextsOnly();
    }

    private void syncMappingValueTextsOnly() {
        tvPitchAngleMinVal.setText(String.format(Locale.US, "%.1f°", pitchAngleMinDeg));
        tvPitchAngleMaxVal.setText(String.format(Locale.US, "%.1f°", pitchAngleMaxDeg));
        tvVolAngleMinVal.setText(String.format(Locale.US, "%.1f°", volumeAngleMinDeg));
        tvVolAngleMaxVal.setText(String.format(Locale.US, "%.1f°", volumeAngleMaxDeg));

        tvFreqMinVal.setText(String.format(Locale.US, "%.0f Hz", freqMinHz));
        tvFreqMaxVal.setText(String.format(Locale.US, "%.0f Hz", freqMaxHz));
    }

    private int angleToProgress(float angle) {
        float clamped = clamp(angle, ANGLE_MIN, ANGLE_MAX);
        return Math.round((clamped - ANGLE_MIN) / ANGLE_STEP);
    }

    private float progressToAngle(int progress) {
        return ANGLE_MIN + (progress * ANGLE_STEP);
    }

    private int freqToProgress(float freq) {
        float clamped = clamp(freq, FREQ_MIN_UI, FREQ_MAX_UI);
        return Math.round(clamped - FREQ_MIN_UI);
    }

    private float progressToFreq(int progress) {
        return FREQ_MIN_UI + progress;
    }

    @SuppressLint("MissingPermission")
    private void startScanAndConnect() {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }
        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            return;
        }

        cancelAutoReconnect();

        if (isScanning) stopScanIfRunning();

        bleScanner = bluetoothAdapter != null ? bluetoothAdapter.getBluetoothLeScanner() : null;
        if (bleScanner == null) {
            appendLogSafe("Scanner unavailable");
            setStatusText("Bluetooth scanner unavailable");
            updateBleButtonText();
            return;
        }

        pitchGlove.seenDuringCurrentScan = false;
        volumeGlove.seenDuringCurrentScan = false;

        isScanning = true;
        updateStatusLineText();
        updateBleButtonText();
        appendLogSafe("Scanning...");

        try {
            bleScanner.startScan(scanCallback);
            mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
        } catch (Exception e) {
            appendLogSafe("Start scan exception");
            isScanning = false;
            scheduleAutoReconnect("scan exception");
            updateStatusLineText();
            updateBleButtonText();
        }
    }

    private void onScanTimeout() {
        if (!isScanning) return;

        appendLogSafe("Scan timeout");
        stopScanIfRunning();

        if (!pitchGlove.connected || !volumeGlove.connected) {
            scheduleAutoReconnect("scan timeout missing glove");
        }
    }

    private void stopScanIfRunning() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);

        if (!isScanning || bleScanner == null) return;

        try {
            bleScanner.stopScan(scanCallback);
        } catch (Exception ignored) {}

        isScanning = false;
        updateStatusLineText();
        updateBleButtonText();
    }

    private void scheduleAutoReconnect(String reason) {
        if (!autoReconnectEnabled || manualDisconnectRequested || appShuttingDown) return;
        if (!isBluetoothEnabled()) return;
        if (pitchGlove.connected && volumeGlove.connected) return;

        appendLogSafe("Auto-reconnect scheduled: " + reason);
        cancelAutoReconnect();
        mainHandler.postDelayed(autoReconnectRunnable, AUTO_RECONNECT_DELAY_MS);
    }

    private void runAutoReconnect() {
        if (!autoReconnectEnabled || manualDisconnectRequested || appShuttingDown) return;
        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            return;
        }
        if (pitchGlove.connected && volumeGlove.connected) return;
        if (isScanning) return;

        appendLogSafe("Auto-reconnect running");
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

        if (!silent) {
            appendLogSafe("Disconnected all gloves");
        }

        updateStatusLineText();
        updateBleButtonText();
    }

    @SuppressLint("MissingPermission")
    private void safeCloseGloveConnection(GloveClient glove) {
        if (glove == null) return;

        glove.connected = false;
        glove.connecting = false;
        glove.notificationsEnabled = false;
        glove.connectAttemptStartMs = 0L;
        glove.lastTelemetryMs = 0L;
        glove.lastPingMs = 0L;
        glove.telemetryStale = false;
        glove.directionText = "";
        glove.roleTextFromDevice = "";
        glove.txChar = null;
        glove.rxChar = null;

        BluetoothGatt gatt = glove.gatt;
        glove.gatt = null;

        if (gatt != null) {
            try {
                gatt.disconnect();
            } catch (Exception ignored) {}

            try {
                gatt.close();
            } catch (Exception ignored) {}
        }
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

            if (pitchGlove.connected && volumeGlove.connected) {
                stopScanIfRunning();
            }
            updateStatusLineText();
        }
    };

    @SuppressLint("MissingPermission")
    private void maybeConnectToGloveDevice(final GloveClient glove, BluetoothDevice device) {
        if (glove.connected || glove.connecting) return;

        if (glove.gatt != null) {
            safeCloseGloveConnection(glove);
        }

        glove.connecting = true;
        glove.connectAttemptStartMs = SystemClock.elapsedRealtime();

        try {
            glove.lastDeviceAddress = device.getAddress();
        } catch (Exception ignored) {
            glove.lastDeviceAddress = "";
        }

        appendLogSafe(glove.roleLabel + ": connecting to " + glove.targetDeviceName);

        BluetoothGattCallback callback = createGattCallback(glove);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                glove.gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                glove.gatt = device.connectGatt(appContext, false, callback);
            }
        } catch (Exception e) {
            appendLogSafe(glove.roleLabel + ": connect exception");
            glove.connecting = false;
            glove.gatt = null;
            glove.connectAttemptStartMs = 0L;
            scheduleAutoReconnect(glove.roleLabel + " connect exception");
        }
    }

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

    private BluetoothGattCallback createGattCallback(final GloveClient glove) {
        return new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (!isCurrentGattCallback(glove, gatt)) {
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
                    if (glove.gatt == gatt) {
                        appendLogSafe(glove.roleLabel + ": disconnected");
                        safeCloseGloveConnection(glove);
                    } else {
                        closeGattQuietly(gatt);
                        return;
                    }

                    updateStatusLineText();
                    updateBleButtonText();

                    if (!manualDisconnectRequested) {
                        scheduleAutoReconnect(glove.roleLabel + " disconnected");
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (!isCurrentGattCallback(glove, gatt)) {
                    closeGattQuietly(gatt);
                    return;
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    appendLogSafe(glove.roleLabel + ": service discovery failed");
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
            syncDirectionPreferenceIfNeeded(glove);
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

        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();
        boolean sharedBtEnabled = bleSnapshot == null || bleSnapshot.bluetoothEnabled;
        boolean sharedAnyConnected = calSnapshot != null
                ? (calSnapshot.pitchConnected || calSnapshot.volumeConnected)
                : (pitchGlove.connected || volumeGlove.connected);

        if (!sharedBtEnabled || !sharedAnyConnected) {
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

    private void refreshUiFast() {
        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        boolean bothConnected = calSnapshot != null && calSnapshot.pitchConnected && calSnapshot.volumeConnected;
        boolean oneConnected = calSnapshot != null && (calSnapshot.pitchConnected || calSnapshot.volumeConnected);
        boolean connecting = bleSnapshot != null && bleSnapshot.hostReady
                && (bleSnapshot.scanning
                || isConnecting(bleSnapshot.pitchConnText)
                || isConnecting(bleSnapshot.volumeConnText));

        if (bleSnapshot != null && bleSnapshot.hostReady) {
            if (calSnapshot != null) {
                pitchActiveDeltaDeg = calSnapshot.pitchActiveDeltaDeg;
                volActiveDeltaDeg = calSnapshot.volumeActiveDeltaDeg;
                pitchHasAngle = calSnapshot.pitchConnected;
                volHasAngle = calSnapshot.volumeConnected;
            }

            recomputeMappedOutputs();

            tvStatus.setText(buildPlayHeadline(bleSnapshot, bothConnected, oneConnected, connecting));
            tvAudio.setText(buildPlaySubtitle(bleSnapshot, bothConnected, oneConnected, connecting));

            tvPitchConn.setText(buildFriendlyConnectionChip("Pitch glove", bleSnapshot.pitchConnText));
            tvVolConn.setText(buildFriendlyConnectionChip("Volume glove", bleSnapshot.volumeConnText));

            tvPitchValue.setText(String.format(Locale.US, "Pitch response • %.2f°", pitchActiveDeltaDeg));
            tvVolValue.setText(String.format(Locale.US, "Volume response • %.2f°", volActiveDeltaDeg));

            tvToneValue.setText(buildToneSummary(bothConnected, mappedFreqHz, mappedVolumeLinear));

            if (bothConnected) {
                tvPitchLast.setVisibility(View.GONE);
                tvVolLast.setVisibility(View.GONE);
            } else {
                tvPitchLast.setVisibility(View.VISIBLE);
                tvVolLast.setVisibility(View.VISIBLE);
                tvPitchLast.setText(bleSnapshot.pitchLastText);
                tvVolLast.setText(bleSnapshot.volumeLastText);
            }

            updateCalibrationButtonGlow(bothConnected);
        } else {
            if (!isBluetoothEnabled()) {
                tvStatus.setText("Bluetooth is off");
                tvAudio.setText("Turn Bluetooth on to begin playing.");
            } else {
                tvStatus.setText("Preparing instrument");
                tvAudio.setText("Getting the theremin ready...");
            }

            tvPitchConn.setText("Pitch glove\nWaiting");
            tvVolConn.setText("Volume glove\nWaiting");
            tvPitchValue.setText(String.format(Locale.US, "Pitch response • %.2f°", pitchActiveDeltaDeg));
            tvVolValue.setText(String.format(Locale.US, "Volume response • %.2f°", volActiveDeltaDeg));
            tvToneValue.setText("Your live tone will appear here.");

            tvPitchLast.setVisibility(View.GONE);
            tvVolLast.setVisibility(View.GONE);

            updateCalibrationButtonGlow(false);
        }

        updateAudioStatusText();
        updateBleButtonText();

        if (thereminVisualizerView != null) {
            thereminVisualizerView.setThereminState(mappedFreqHz, mappedVolumeLinear, freqMinHz, freqMaxHz);
        }

        syncMappingValueTextsOnly();
    }

    private String buildPlayHeadline(BleHostBridge.BleUiSnapshot bleSnapshot,
                                     boolean bothConnected,
                                     boolean oneConnected,
                                     boolean connecting) {
        if (bleSnapshot == null || !bleSnapshot.hostReady) {
            return "Preparing instrument";
        }
        if (!bleSnapshot.bluetoothEnabled) {
            return "Bluetooth is off";
        }
        if (bothConnected) {
            return "Ready to perform";
        }
        if (oneConnected) {
            return "Almost ready";
        }
        if (connecting) {
            return "Connecting your gloves";
        }
        return "Waiting for gloves";
    }

    private String buildPlaySubtitle(BleHostBridge.BleUiSnapshot bleSnapshot,
                                     boolean bothConnected,
                                     boolean oneConnected,
                                     boolean connecting) {
        if (bleSnapshot == null || !bleSnapshot.hostReady) {
            return "Setting up the live instrument experience.";
        }
        if (!bleSnapshot.bluetoothEnabled) {
            return "Turn Bluetooth on to reconnect your gloves.";
        }
        if (bothConnected) {
            return "Move your hands to shape pitch and volume. Calibrate anytime for a tighter response.";
        }
        if (oneConnected) {
            return "One glove is connected. Turn on the second glove to complete the instrument.";
        }
        if (connecting) {
            return "Keep both gloves awake and close to your phone.";
        }
        return "Turn on both gloves to begin playing.";
    }

    private String buildFriendlyConnectionChip(String label, String rawLine) {
        if (rawLine == null || rawLine.trim().isEmpty()) {
            return label + "\nWaiting";
        }

        String upper = rawLine.toUpperCase(Locale.US);
        if (upper.contains("CONNECTED") && upper.contains("NO DATA")) {
            return label + "\nConnected • waiting for motion";
        }
        if (upper.contains("CONNECTED")) {
            return label + "\nReady";
        }
        if (upper.contains("CONNECTING")) {
            return label + "\nConnecting…";
        }
        return label + "\nWaiting";
    }

    private String buildToneSummary(boolean bothConnected, float freqHz, float volumeLinear) {
        if (!bothConnected) {
            return "Connect both gloves to start shaping sound.";
        }
        if (volumeLinear <= 0.01f) {
            return String.format(Locale.US,
                    "Live tone ready • %.1f Hz • Raise your volume hand to bring it in",
                    freqHz);
        }
        return String.format(Locale.US,
                "Live tone • %.1f Hz • %.0f%% intensity",
                freqHz, volumeLinear * 100f);
    }

    private void updateCalibrationButtonGlow(boolean shouldGlow) {
        if (btnOpenCalibration == null) return;

        if (shouldGlow) {
            btnOpenCalibration.setText("✨ Calibrate Precisely");
            if (calibrateGlowAnimation == null) {
                AlphaAnimation pulse = new AlphaAnimation(1.0f, 0.55f);
                pulse.setDuration(850);
                pulse.setRepeatMode(Animation.REVERSE);
                pulse.setRepeatCount(Animation.INFINITE);
                calibrateGlowAnimation = pulse;
            }
            if (btnOpenCalibration.getAnimation() == null) {
                btnOpenCalibration.startAnimation(calibrateGlowAnimation);
            }
        } else {
            btnOpenCalibration.setText("Calibrate");
            btnOpenCalibration.clearAnimation();
        }
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
        if (btnAudioStart != null) btnAudioStart.setText(running ? "Pause Tone" : "Play Tone");
        if (btnAudioStop != null) btnAudioStop.setText(bgAudioEnabled ? "Background Audio On" : "Background Audio Off");
    }

    private void setStatusText(String s) {
        statusText = s;
    }

    private void updateStatusLineText() {
        BleHostBridge.BleUiSnapshot snapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        if (snapshot != null && snapshot.hostReady) {
            statusText = snapshot.statusText;

            boolean anyConnected = calSnapshot != null && (calSnapshot.pitchConnected || calSnapshot.volumeConnected);
            if (!snapshot.bluetoothEnabled || !anyConnected) {
                audioTargetVolumeLinear = 0f;
                mappedVolumeLinear = 0f;
            }
            return;
        }

        if (!isBluetoothEnabled()) {
            setStatusText("Bluetooth is off — turn it on to play");
            return;
        }

        if (pitchGlove.connected && volumeGlove.connected) {
            setStatusText("Both gloves connected — ready to play");
        } else if (pitchGlove.connected || volumeGlove.connected) {
            setStatusText("One glove connected — connect the other glove");
        } else if (isScanning) {
            setStatusText("Scanning for gloves...");
        } else {
            setStatusText("Connect both gloves to start playing");
        }

        if (!pitchGlove.connected && !volumeGlove.connected) {
            audioTargetVolumeLinear = 0f;
            mappedVolumeLinear = 0f;
        }
    }

    private void appendLogSafe(String msg) {
        final long now = SystemClock.elapsedRealtime();
        synchronized (logLines) {
            logLines.addLast(msg);
            while (logLines.size() > LOG_MAX_LINES) logLines.removeFirst();
            if (now - lastLogFlushMs < LOG_FLUSH_MIN_INTERVAL_MS) return;
            lastLogFlushMs = now;

            StringBuilder sb = new StringBuilder();
            for (String s : logLines) sb.append(s).append('\n');
            final String all = sb.toString();

            runOnUiThread(() -> {
                if (tvLog != null) tvLog.setText(all);
            });
        }
    }

    private boolean isConnecting(String text) {
        return text != null && text.toUpperCase(Locale.US).contains("CONNECTING");
    }

    private String safeDeviceName(BluetoothDevice d) {
        if (d == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                return null;
            }
        }
        try {
            return d.getName();
        } catch (Exception e) {
            return null;
        }
    }

    private void registerBluetoothStateReceiverIfNeeded() {
        if (bluetoothStateReceiverRegistered) return;

        try {
            registerReceiver(bluetoothStateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            bluetoothStateReceiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    private void unregisterBluetoothStateReceiverIfNeeded() {
        if (!bluetoothStateReceiverRegistered) return;

        try {
            unregisterReceiver(bluetoothStateReceiver);
        } catch (Exception ignored) {
        } finally {
            bluetoothStateReceiverRegistered = false;
        }
    }

    private static final class GloveClient {
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
        long lastDirectionSyncCommandMs = 0L;
        String directionText = "";
        String roleTextFromDevice = "";

        float neutralRollDeg = 0f;

        GloveClient(String roleLabel, String targetDeviceName) {
            this.roleLabel = roleLabel;
            this.targetDeviceName = targetDeviceName;
        }
    }

    private final class AudioEngine {
        private static final int SAMPLE_RATE = 48000;
        private static final int CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO;
        private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

        private AudioTrack track;
        private Thread audioThread;
        private volatile boolean running = false;

        private float phase = 0f;

        boolean isRunning() {
            return running;
        }

        void start() {
            if (running) return;

            int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_MASK, ENCODING);
            int bufferSize = Math.max(minBuffer, 2048);

            track = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    SAMPLE_RATE,
                    CHANNEL_MASK,
                    ENCODING,
                    bufferSize,
                    AudioTrack.MODE_STREAM
            );

            running = true;
            track.play();

            audioThread = new Thread(() -> {
                short[] buffer = new short[1024];

                while (running) {
                    float freq = audioTargetFreqHz;
                    float vol = audioTargetVolumeLinear;

                    for (int i = 0; i < buffer.length; i++) {
                        phase += (2f * (float) Math.PI * freq) / SAMPLE_RATE;
                        if (phase > 2f * Math.PI) phase -= 2f * (float) Math.PI;

                        float sample = (float) Math.sin(phase);
                        short pcm = (short) (sample * vol * Short.MAX_VALUE * 0.25f);
                        buffer[i] = pcm;
                    }

                    if (track != null) {
                        try {
                            track.write(buffer, 0, buffer.length);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, "ThereminAudioThread");

            audioThread.start();
        }

        void stop() {
            running = false;

            if (audioThread != null) {
                try {
                    audioThread.join(300);
                } catch (InterruptedException ignored) {
                }
                audioThread = null;
            }

            if (track != null) {
                try {
                    track.pause();
                } catch (Exception ignored) {
                }

                try {
                    track.flush();
                } catch (Exception ignored) {
                }

                try {
                    track.release();
                } catch (Exception ignored) {
                }

                track = null;
            }
        }

        void shutdown() {
            stop();
        }
    }
}