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
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
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
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    // =========================================================
    // BLE (must match Arduino)
    // =========================================================
    private static final String PITCH_DEVICE_NAME = "ThereminGlove";
    private static final String VOLUME_DEVICE_NAME = "ThereminGloveVol";

    private static final UUID SERVICE_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    private static final UUID TX_CHAR_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ac");
    private static final UUID RX_CHAR_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ad");
    private static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final int REQ_PERMS = 1001;
    private static final long SCAN_TIMEOUT_MS = 12000;
    private static final long AUTO_RECONNECT_DELAY_MS = 1500;

    // =========================================================
    // Slider ranges (app tuning UI)
    // =========================================================
    private static final float ANGLE_MIN = -90f;
    private static final float ANGLE_MAX = 90f;
    private static final float ANGLE_STEP = 0.5f;
    private static final int ANGLE_PROGRESS_MAX = (int) ((ANGLE_MAX - ANGLE_MIN) / ANGLE_STEP);

    private static final float FREQ_MIN_UI = 20f;
    private static final float FREQ_MAX_UI = 2000f;
    private static final int FREQ_PROGRESS_MAX = (int) (FREQ_MAX_UI - FREQ_MIN_UI);

    // =========================================================
    // UI widgets
    // =========================================================
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

    // NEW: center live visual
    private ThereminVisualizerView thereminVisualizerView;

    // =========================================================
    // BLE core
    // =========================================================
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private boolean isScanning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable scanTimeoutRunnable = this::onScanTimeout;
    private final Runnable autoReconnectRunnable = this::runAutoReconnect;

    private volatile boolean autoReconnectEnabled = true;
    private volatile boolean manualDisconnectRequested = false;
    private volatile boolean appShuttingDown = false;
    private volatile boolean startupAutoConnectAttempted = false;

    // =========================================================
    // App-side mapping settings
    // =========================================================
    private volatile float pitchAngleMinDeg = 0.0f;
    private volatile float pitchAngleMaxDeg = 45.0f;
    private volatile float freqMinHz = 523.25f;
    private volatile float freqMaxHz = 880.0f;

    private volatile float volumeAngleMinDeg = 0.0f;
    private volatile float volumeAngleMaxDeg = 45.0f;

    // =========================================================
    // Latest glove values
    // =========================================================
    private volatile float pitchActiveDeltaDeg = 0.0f;
    private volatile float volActiveDeltaDeg = 0.0f;
    private volatile boolean pitchHasAngle = false;
    private volatile boolean volHasAngle = false;

    // Mapped outputs
    private volatile float mappedFreqHz = 523.25f;
    private volatile float mappedVolumeLinear = 0.0f; // 0..1

    // Audio targets
    private volatile float audioTargetFreqHz = 523.25f;
    private volatile float audioTargetVolumeLinear = 0.0f;

    // UI state
    private volatile String statusText = "Idle";
    private volatile boolean suppressSliderCallbacks = false;

    private final Runnable uiTicker = new Runnable() {
        @Override
        public void run() {
            refreshUiFast();
            mainHandler.postDelayed(this, 80);
        }
    };

    private AudioEngine audioEngine;

    private final GloveClient pitchGlove = new GloveClient("PITCH", PITCH_DEVICE_NAME);
    private final GloveClient volumeGlove = new GloveClient("VOLUME", VOLUME_DEVICE_NAME);

    // =========================================================
    // Activity lifecycle
    // =========================================================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();

        BluetoothManager bluetoothManager =
                (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        audioEngine = new AudioEngine();

        setupSlidersAndClickNumbers();
        setDefaultMappingValues();
        syncAllMappingControlsFromState();
        recomputeMappedOutputs();

        wireButtons();

        setStatusText("Idle");
        appendLogSafe("App started");
        appendLogSafe("Dual glove app: app-side mapping (angles -> freq + volume)");
    }

    @Override
    protected void onStart() {
        super.onStart();
        autoReconnectEnabled = true;
        mainHandler.post(uiTicker);

        // Auto-connect as soon as MainActivity is visible
        maybeAutoConnectOnLaunchOrReturn();
    }

    @Override
    protected void onStop() {
        super.onStop();
        autoReconnectEnabled = false; // prevent background reconnect loop
        cancelAutoReconnect();
        mainHandler.removeCallbacks(uiTicker);
    }

    @Override
    protected void onDestroy() {
        appShuttingDown = true;
        autoReconnectEnabled = false;
        cancelAutoReconnect();
        cancelScanTimeout();

        super.onDestroy();

        stopScanIfRunning();
        disconnectAllGloves();
        if (audioEngine != null) {
            audioEngine.stop();
        }
    }

    // =========================================================
    // Startup / visibility auto-connect
    // =========================================================
    private void maybeAutoConnectOnLaunchOrReturn() {
        if (appShuttingDown) return;
        if (manualDisconnectRequested) return;

        boolean missingGlove = !pitchGlove.connected || !volumeGlove.connected;
        if (!missingGlove) return;
        if (isScanning) return;

        if (!startupAutoConnectAttempted) {
            startupAutoConnectAttempted = true;
            appendLogSafe("Auto-connect on launch");
        } else {
            appendLogSafe("Auto-connect on resume (missing glove)");
        }

        startScanAndConnect();
    }

    // =========================================================
    // Bind UI
    // =========================================================
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

    // =========================================================
    // Mapping UI
    // =========================================================
    private void setupSlidersAndClickNumbers() {
        sbPitchAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbPitchAngleMax.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMin.setMax(ANGLE_PROGRESS_MAX);
        sbVolAngleMax.setMax(ANGLE_PROGRESS_MAX);

        sbFreqMin.setMax(FREQ_PROGRESS_MAX);
        sbFreqMax.setMax(FREQ_PROGRESS_MAX);

        sbPitchAngleMin.setOnSeekBarChangeListener(simpleSeekBar((progress, fromUser) -> {
            if (!fromUser || suppressSliderCallbacks) return;
            pitchAngleMinDeg = angleFromProgress(progress);
            onMappingChanged("pitch angle min");
        }));

        sbPitchAngleMax.setOnSeekBarChangeListener(simpleSeekBar((progress, fromUser) -> {
            if (!fromUser || suppressSliderCallbacks) return;
            pitchAngleMaxDeg = angleFromProgress(progress);
            onMappingChanged("pitch angle max");
        }));

        sbVolAngleMin.setOnSeekBarChangeListener(simpleSeekBar((progress, fromUser) -> {
            if (!fromUser || suppressSliderCallbacks) return;
            volumeAngleMinDeg = angleFromProgress(progress);
            onMappingChanged("volume angle min");
        }));

        sbVolAngleMax.setOnSeekBarChangeListener(simpleSeekBar((progress, fromUser) -> {
            if (!fromUser || suppressSliderCallbacks) return;
            volumeAngleMaxDeg = angleFromProgress(progress);
            onMappingChanged("volume angle max");
        }));

        sbFreqMin.setOnSeekBarChangeListener(simpleSeekBar((progress, fromUser) -> {
            if (!fromUser || suppressSliderCallbacks) return;
            freqMinHz = freqFromProgress(progress);
            onMappingChanged("freq min");
        }));

        sbFreqMax.setOnSeekBarChangeListener(simpleSeekBar((progress, fromUser) -> {
            if (!fromUser || suppressSliderCallbacks) return;
            freqMaxHz = freqFromProgress(progress);
            onMappingChanged("freq max");
        }));

        tvPitchAngleMinVal.setOnClickListener(v -> showNumberInputDialog(
                "Pitch Angle Min (deg)", pitchAngleMinDeg, true, newValue -> {
                    pitchAngleMinDeg = clampAngle(newValue);
                    onMappingChanged("pitch angle min");
                    syncAllMappingControlsFromState();
                }
        ));

        tvPitchAngleMaxVal.setOnClickListener(v -> showNumberInputDialog(
                "Pitch Angle Max (deg)", pitchAngleMaxDeg, true, newValue -> {
                    pitchAngleMaxDeg = clampAngle(newValue);
                    onMappingChanged("pitch angle max");
                    syncAllMappingControlsFromState();
                }
        ));

        tvVolAngleMinVal.setOnClickListener(v -> showNumberInputDialog(
                "Volume Angle Min (deg)", volumeAngleMinDeg, true, newValue -> {
                    volumeAngleMinDeg = clampAngle(newValue);
                    onMappingChanged("volume angle min");
                    syncAllMappingControlsFromState();
                }
        ));

        tvVolAngleMaxVal.setOnClickListener(v -> showNumberInputDialog(
                "Volume Angle Max (deg)", volumeAngleMaxDeg, true, newValue -> {
                    volumeAngleMaxDeg = clampAngle(newValue);
                    onMappingChanged("volume angle max");
                    syncAllMappingControlsFromState();
                }
        ));

        tvFreqMinVal.setOnClickListener(v -> showNumberInputDialog(
                "Freq Min (Hz)", freqMinHz, false, newValue -> {
                    freqMinHz = clampFreq(newValue);
                    onMappingChanged("freq min");
                    syncAllMappingControlsFromState();
                }
        ));

        tvFreqMaxVal.setOnClickListener(v -> showNumberInputDialog(
                "Freq Max (Hz)", freqMaxHz, false, newValue -> {
                    freqMaxHz = clampFreq(newValue);
                    onMappingChanged("freq max");
                    syncAllMappingControlsFromState();
                }
        ));
    }

    private void setDefaultMappingValues() {
        pitchAngleMinDeg = 0f;
        pitchAngleMaxDeg = 45f;
        freqMinHz = 523.25f;
        freqMaxHz = 880f;

        volumeAngleMinDeg = 0f;
        volumeAngleMaxDeg = 45f;
    }

    private void onMappingChanged(String source) {
        sanitizeMappingValues();
        recomputeMappedOutputs();
        syncMappingValueTextsOnly();
        appendLogSafe("Mapping updated: " + source);
    }

    private void sanitizeMappingValues() {
        pitchAngleMinDeg = clampAngle(pitchAngleMinDeg);
        pitchAngleMaxDeg = clampAngle(pitchAngleMaxDeg);
        volumeAngleMinDeg = clampAngle(volumeAngleMinDeg);
        volumeAngleMaxDeg = clampAngle(volumeAngleMaxDeg);

        freqMinHz = clampFreq(freqMinHz);
        freqMaxHz = clampFreq(freqMaxHz);

        if (pitchAngleMinDeg > pitchAngleMaxDeg) {
            float t = pitchAngleMinDeg;
            pitchAngleMinDeg = pitchAngleMaxDeg;
            pitchAngleMaxDeg = t;
        }
        if (volumeAngleMinDeg > volumeAngleMaxDeg) {
            float t = volumeAngleMinDeg;
            volumeAngleMinDeg = volumeAngleMaxDeg;
            volumeAngleMaxDeg = t;
        }
        if (freqMinHz > freqMaxHz) {
            float t = freqMinHz;
            freqMinHz = freqMaxHz;
            freqMaxHz = t;
        }
    }

    private void syncAllMappingControlsFromState() {
        suppressSliderCallbacks = true;
        try {
            sbPitchAngleMin.setProgress(progressFromAngle(pitchAngleMinDeg));
            sbPitchAngleMax.setProgress(progressFromAngle(pitchAngleMaxDeg));
            sbVolAngleMin.setProgress(progressFromAngle(volumeAngleMinDeg));
            sbVolAngleMax.setProgress(progressFromAngle(volumeAngleMaxDeg));

            sbFreqMin.setProgress(progressFromFreq(freqMinHz));
            sbFreqMax.setProgress(progressFromFreq(freqMaxHz));

            syncMappingValueTextsOnly();
        } finally {
            suppressSliderCallbacks = false;
        }
    }

    private void syncMappingValueTextsOnly() {
        tvPitchAngleMinVal.setText(formatDeg(pitchAngleMinDeg));
        tvPitchAngleMaxVal.setText(formatDeg(pitchAngleMaxDeg));
        tvVolAngleMinVal.setText(formatDeg(volumeAngleMinDeg));
        tvVolAngleMaxVal.setText(formatDeg(volumeAngleMaxDeg));
        tvFreqMinVal.setText(formatHz(freqMinHz));
        tvFreqMaxVal.setText(formatHz(freqMaxHz));
    }

    private String formatDeg(float v) {
        return String.format(Locale.US, "%.1f°", v);
    }

    private String formatHz(float v) {
        return String.format(Locale.US, "%.1f Hz", v);
    }

    private float angleFromProgress(int p) {
        return ANGLE_MIN + p * ANGLE_STEP;
    }

    private int progressFromAngle(float deg) {
        float clamped = clampAngle(deg);
        return Math.round((clamped - ANGLE_MIN) / ANGLE_STEP);
    }

    private float freqFromProgress(int p) {
        return clampFreq(FREQ_MIN_UI + p);
    }

    private int progressFromFreq(float hz) {
        float clamped = clampFreq(hz);
        return Math.round(clamped - FREQ_MIN_UI);
    }

    private float clampAngle(float x) {
        if (x < ANGLE_MIN) return ANGLE_MIN;
        if (x > ANGLE_MAX) return ANGLE_MAX;
        return x;
    }

    private float clampFreq(float x) {
        if (x < FREQ_MIN_UI) return FREQ_MIN_UI;
        if (x > FREQ_MAX_UI) return FREQ_MAX_UI;
        return x;
    }

    private interface SliderChangeHandler {
        void onChanged(int progress, boolean fromUser);
    }

    private SeekBar.OnSeekBarChangeListener simpleSeekBar(SliderChangeHandler h) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                h.onChanged(progress, fromUser);
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        };
    }

    private interface NumberSetter {
        void onValue(float v);
    }

    private void showNumberInputDialog(String title, float currentValue, boolean signed, NumberSetter setter) {
        final EditText input = new EditText(this);
        input.setText(String.format(Locale.US, "%.2f", currentValue));
        input.setSelection(input.getText().length());

        int type = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        if (signed) type |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        input.setInputType(type);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton("Set", (dialog, which) -> {
                    try {
                        float v = Float.parseFloat(input.getText().toString().trim());
                        setter.onValue(v);
                    } catch (Exception e) {
                        toastSafe("Invalid number");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // =========================================================
    // Buttons
    // =========================================================
    private void wireButtons() {
        btnScanConnect.setOnClickListener(v -> {
            manualDisconnectRequested = false;
            startScanAndConnect();
        });

        btnDisconnectAll.setOnClickListener(v -> disconnectAllGloves());

        btnAudioStart.setOnClickListener(v -> {
            audioEngine.start();
            updateAudioStatusText();
        });

        btnAudioStop.setOnClickListener(v -> {
            audioEngine.stop();
            updateAudioStatusText();
        });

        btnNeutralPitch.setOnClickListener(v -> sendCommandToGlove(pitchGlove, "N"));
        btnNeutralVol.setOnClickListener(v -> sendCommandToGlove(volumeGlove, "N"));

        btnDirectionPitch.setOnClickListener(v -> sendCommandToGlove(pitchGlove, "D"));
        btnDirectionVol.setOnClickListener(v -> sendCommandToGlove(volumeGlove, "D"));

        btnHelpPitch.setOnClickListener(v -> sendCommandToGlove(pitchGlove, "H"));
        btnHelpVol.setOnClickListener(v -> sendCommandToGlove(volumeGlove, "H"));

        btnDefaults.setOnClickListener(v -> {
            setDefaultMappingValues();
            sanitizeMappingValues();
            syncAllMappingControlsFromState();
            recomputeMappedOutputs();
            appendLogSafe("Mapping reset to defaults");
        });
    }

    // =========================================================
    // BLE scanning / connecting
    // =========================================================
    private void startScanAndConnect() {
        cancelAutoReconnect();

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }

        if (isScanning) {
            appendLogSafe("Scan already running -> restarting scan window");
            stopScanIfRunning();
        }

        if (bluetoothAdapter == null) {
            toastSafe("Bluetooth not supported");
            setStatusText("Bluetooth unsupported");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            toastSafe("Turn on Bluetooth first");
            setStatusText("Bluetooth off");
            scheduleAutoReconnect("Bluetooth off");
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            appendLogSafe("BLE scanner unavailable");
            toastSafe("BLE scanner unavailable");
            scheduleAutoReconnect("scanner unavailable");
            return;
        }

        pitchGlove.seenDuringCurrentScan = false;
        volumeGlove.seenDuringCurrentScan = false;

        isScanning = true;
        setStatusText("Scanning for gloves...");
        appendLogSafe("Scanning for " + PITCH_DEVICE_NAME + " + " + VOLUME_DEVICE_NAME);

        cancelScanTimeout();
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);

        try {
            bleScanner.startScan(scanCallback);
            appendLogSafe("Scan started (" + (SCAN_TIMEOUT_MS / 1000) + "s window)");
        } catch (SecurityException e) {
            appendLogSafe("Scan permission error");
            toastSafe("BLE scan permission error");
            scheduleAutoReconnect("scan permission error");
        } catch (Exception e) {
            appendLogSafe("Scan start exception: " + e.getClass().getSimpleName());
            scheduleAutoReconnect("scan start exception");
        }
    }

    private void onScanTimeout() {
        if (!isScanning) return;
        appendLogSafe("Scan timeout");
        stopScanIfRunning();

        if (!manualDisconnectRequested && (!pitchGlove.connected || !volumeGlove.connected)) {
            scheduleAutoReconnect("scan timeout");
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScanIfRunning() {
        cancelScanTimeout();
        if (!isScanning || bleScanner == null) return;
        try {
            bleScanner.stopScan(scanCallback);
        } catch (Exception ignored) { }
        isScanning = false;
        appendLogSafe("Scan stopped");
        updateStatusLineText();
    }

    private void cancelScanTimeout() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
    }

    private void cancelAutoReconnect() {
        mainHandler.removeCallbacks(autoReconnectRunnable);
    }

    private void scheduleAutoReconnect(String reason) {
        if (appShuttingDown || !autoReconnectEnabled || manualDisconnectRequested) return;
        if (pitchGlove.connected && volumeGlove.connected) return;

        cancelAutoReconnect();
        appendLogSafe("Auto-reconnect scheduled (" + reason + ") in " + AUTO_RECONNECT_DELAY_MS + " ms");
        mainHandler.postDelayed(autoReconnectRunnable, AUTO_RECONNECT_DELAY_MS);
    }

    private void runAutoReconnect() {
        if (appShuttingDown || !autoReconnectEnabled || manualDisconnectRequested) return;
        if (pitchGlove.connected && volumeGlove.connected) return;
        if (isScanning) return;

        appendLogSafe("Auto-reconnect: scanning for missing glove(s)");
        startScanAndConnect();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeDeviceName(device);
            if (name == null) return;

            if (PITCH_DEVICE_NAME.equals(name)) {
                if (!pitchGlove.seenDuringCurrentScan) {
                    appendLogSafe("Scan hit: PITCH " + safeNameWithFallback(device));
                }
                pitchGlove.seenDuringCurrentScan = true;
                maybeConnectToGloveDevice(pitchGlove, device);
            } else if (VOLUME_DEVICE_NAME.equals(name)) {
                if (!volumeGlove.seenDuringCurrentScan) {
                    appendLogSafe("Scan hit: VOLUME " + safeNameWithFallback(device));
                }
                volumeGlove.seenDuringCurrentScan = true;
                maybeConnectToGloveDevice(volumeGlove, device);
            }

            if (pitchGlove.connected && volumeGlove.connected) {
                cancelAutoReconnect();
                stopScanIfRunning();
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            cancelScanTimeout();
            isScanning = false;
            appendLogSafe("Scan failed: " + errorCode);
            setStatusText("Scan failed: " + errorCode);
            scheduleAutoReconnect("scan failed " + errorCode);
        }
    };

    @SuppressLint("MissingPermission")
    private void maybeConnectToGloveDevice(GloveClient glove, BluetoothDevice device) {
        if (glove.connected || glove.connecting) return;
        if (glove.gatt != null) return;

        glove.connecting = true;
        try {
            glove.lastDeviceAddress = device.getAddress();
        } catch (SecurityException e) {
            glove.lastDeviceAddress = "";
        }

        appendLogSafe(glove.roleLabel + ": connecting to " + safeNameWithFallback(device));

        BluetoothGattCallback callback = createGattCallback(glove);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                glove.gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                glove.gatt = device.connectGatt(this, false, callback);
            }
        } catch (SecurityException e) {
            glove.connecting = false;
            glove.gatt = null;
            appendLogSafe(glove.roleLabel + ": connect permission error");
            scheduleAutoReconnect(glove.roleLabel + " connect permission error");
        } catch (Exception e) {
            glove.connecting = false;
            glove.gatt = null;
            appendLogSafe(glove.roleLabel + ": connect exception " + e.getClass().getSimpleName());
            scheduleAutoReconnect(glove.roleLabel + " connect exception");
        }
    }

    @SuppressLint("MissingPermission")
    private void disconnectAllGloves() {
        manualDisconnectRequested = true;
        cancelAutoReconnect();
        stopScanIfRunning();
        disconnectGlove(pitchGlove);
        disconnectGlove(volumeGlove);
        updateStatusLineText();
        appendLogSafe("Manual disconnect all (auto-reconnect disabled until next Scan/Connect)");
    }

    @SuppressLint("MissingPermission")
    private void disconnectGlove(GloveClient glove) {
        glove.connecting = false;
        glove.connected = false;
        glove.notificationsEnabled = false;

        if (glove.gatt != null) {
            try { glove.gatt.disconnect(); } catch (Exception ignored) {}
            try { glove.gatt.close(); } catch (Exception ignored) {}
        }

        glove.gatt = null;
        glove.txChar = null;
        glove.rxChar = null;
        glove.lastPacket = "(none)";
    }

    private BluetoothGattCallback createGattCallback(GloveClient glove) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                appendLogSafe(glove.roleLabel + ": connState status=" + status + ", newState=" + newState);

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    glove.connecting = false;
                    glove.connected = true;
                    cancelAutoReconnect();

                    appendLogSafe(glove.roleLabel + ": connected");
                    updateStatusLineText();

                    try {
                        gatt.discoverServices();
                    } catch (SecurityException e) {
                        appendLogSafe(glove.roleLabel + ": discoverServices permission error");
                    } catch (Exception e) {
                        appendLogSafe(glove.roleLabel + ": discoverServices exception");
                        scheduleAutoReconnect(glove.roleLabel + " discoverServices exception");
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    glove.connecting = false;
                    glove.connected = false;
                    glove.notificationsEnabled = false;
                    glove.txChar = null;
                    glove.rxChar = null;

                    appendLogSafe(glove.roleLabel + ": disconnected (status=" + status + ")");

                    try { gatt.close(); } catch (Exception ignored) {}
                    if (glove.gatt == gatt) glove.gatt = null;

                    updateStatusLineText();

                    if (!manualDisconnectRequested) {
                        scheduleAutoReconnect(glove.roleLabel + " disconnected");
                    }
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    appendLogSafe(glove.roleLabel + ": service discovery failed " + status);
                    scheduleAutoReconnect(glove.roleLabel + " service discovery failed");
                    return;
                }

                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service == null) {
                    appendLogSafe(glove.roleLabel + ": service not found");
                    scheduleAutoReconnect(glove.roleLabel + " service missing");
                    return;
                }

                glove.txChar = service.getCharacteristic(TX_CHAR_UUID);
                glove.rxChar = service.getCharacteristic(RX_CHAR_UUID);

                appendLogSafe(glove.roleLabel + ": TX=" + (glove.txChar != null) + ", RX=" + (glove.rxChar != null));

                if (glove.txChar != null) {
                    enableNotifications(glove, gatt, glove.txChar);
                }

                sendCommandToGlove(glove, "H");
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                glove.notificationsEnabled = (status == BluetoothGatt.GATT_SUCCESS);
                appendLogSafe(glove.roleLabel + ": notifications " + (glove.notificationsEnabled ? "ON" : "FAILED"));
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic,
                                                byte[] value) {
                handleGloveNotification(glove, characteristic, value);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic) {
                handleGloveNotification(glove, characteristic, characteristic.getValue());
            }

            @Override
            public void onCharacteristicWrite(BluetoothGatt gatt,
                                              BluetoothGattCharacteristic characteristic,
                                              int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    appendLogSafe(glove.roleLabel + ": write failed " + status);
                }
            }
        };
    }

    @SuppressLint("MissingPermission")
    private void enableNotifications(GloveClient glove, BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try {
            boolean localOk = gatt.setCharacteristicNotification(characteristic, true);
            appendLogSafe(glove.roleLabel + ": setNotify=" + localOk);

            BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                appendLogSafe(glove.roleLabel + ": CCCD missing");
                return;
            }

            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            boolean ok = gatt.writeDescriptor(cccd);
            appendLogSafe(glove.roleLabel + ": writeCCCD=" + ok);
        } catch (SecurityException e) {
            appendLogSafe(glove.roleLabel + ": notify permission error");
        } catch (Exception e) {
            appendLogSafe(glove.roleLabel + ": notify exception");
        }
    }

    // =========================================================
    // Notification parsing
    // =========================================================
    private void handleGloveNotification(GloveClient glove,
                                         BluetoothGattCharacteristic characteristic,
                                         byte[] value) {
        if (characteristic == null || value == null) return;
        if (!TX_CHAR_UUID.equals(characteristic.getUuid())) return;

        String line = new String(value, StandardCharsets.UTF_8).trim();
        glove.lastPacket = line;

        if (line.startsWith("ACTIVE_DELTA_DEG:")) {
            Float v = parseTailFloat(line);
            if (v != null) {
                glove.activeDeltaDeg = v;
                glove.hasActiveDelta = true;

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
            glove.directionText = line.substring("DIRECTION:".length());
        } else if (line.startsWith("ROLE:")) {
            glove.roleTextFromDevice = line.substring("ROLE:".length());
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

    // =========================================================
    // App-side mapping logic
    // =========================================================
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
    }

    private float mapLinearClamped(float x, float inMin, float inMax, float outMin, float outMax) {
        if (Math.abs(inMax - inMin) < 1e-6f) return outMin;
        float t = (x - inMin) / (inMax - inMin);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return outMin + t * (outMax - outMin);
    }

    // =========================================================
    // Send commands to a glove
    // =========================================================
    @SuppressLint("MissingPermission")
    private void sendCommandToGlove(GloveClient glove, String cmd) {
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions();
            return;
        }

        if (glove.gatt == null || glove.rxChar == null || !glove.connected) {
            toastSafe(glove.roleLabel + " glove not connected");
            return;
        }

        try {
            glove.rxChar.setValue(cmd.getBytes(StandardCharsets.UTF_8));
            boolean ok = glove.gatt.writeCharacteristic(glove.rxChar);
            appendLogSafe(glove.roleLabel + " -> " + cmd + " (write=" + ok + ")");
        } catch (SecurityException e) {
            appendLogSafe(glove.roleLabel + ": write permission error");
            toastSafe("BLE permission error");
        } catch (Exception e) {
            appendLogSafe(glove.roleLabel + ": write exception");
            toastSafe("BLE write error");
        }
    }

    // =========================================================
    // Permissions
    // =========================================================
    private boolean hasRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPermission(Manifest.permission.BLUETOOTH_SCAN)
                    && hasPermission(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private boolean hasPermission(String perm) {
        return ActivityCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
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
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMS) return;

        boolean allGranted = true;
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (allGranted) {
            appendLogSafe("BLE permissions granted");
            manualDisconnectRequested = false;
            startScanAndConnect();
        } else {
            appendLogSafe("BLE permissions denied");
            toastSafe("BLE permissions required");
        }
    }

    // =========================================================
    // UI refresh
    // =========================================================
    private void refreshUiFast() {
        tvStatus.setText("Status: " + statusText);

        tvPitchConn.setText("Pitch (" + PITCH_DEVICE_NAME + "): " +
                (pitchGlove.connected ? "CONNECTED ✅" : (pitchGlove.connecting ? "CONNECTING..." : "DISCONNECTED ❌")));

        tvVolConn.setText("Volume (" + VOLUME_DEVICE_NAME + "): " +
                (volumeGlove.connected ? "CONNECTED ✅" : (volumeGlove.connecting ? "CONNECTING..." : "DISCONNECTED ❌")));

        updateAudioStatusText();

        tvPitchValue.setText(String.format(Locale.US, "Pitch ACTIVE_DELTA_DEG = %.2f°", pitchActiveDeltaDeg));
        tvVolValue.setText(String.format(Locale.US, "Volume ACTIVE_DELTA_DEG = %.2f°", volActiveDeltaDeg));
        tvToneValue.setText(String.format(Locale.US, "Mapped tone: %.1f Hz | Volume %.0f%%",
                mappedFreqHz, mappedVolumeLinear * 100f));

        tvPitchLast.setText("Pitch last packet: " + pitchGlove.lastPacket);
        tvVolLast.setText("Volume last packet: " + volumeGlove.lastPacket);

        // NEW: center visual updates live
        if (thereminVisualizerView != null) {
            thereminVisualizerView.setThereminState(
                    mappedFreqHz,
                    mappedVolumeLinear,
                    freqMinHz,
                    freqMaxHz
            );
        }

        syncMappingValueTextsOnly();
    }

    private void updateAudioStatusText() {
        tvAudio.setText("Audio: " + (audioEngine != null && audioEngine.isRunning() ? "RUNNING 🔊" : "STOPPED"));
    }

    private void updateStatusLineText() {
        if (pitchGlove.connected && volumeGlove.connected) {
            setStatusText("Both gloves connected");
        } else if (pitchGlove.connected || volumeGlove.connected) {
            setStatusText("One glove connected");
        } else if (isScanning) {
            setStatusText("Scanning...");
        } else {
            setStatusText("Idle / disconnected");
        }
    }

    private void setStatusText(String text) {
        statusText = text;
    }

    // =========================================================
    // Thread-safe UI helpers
    // =========================================================
    private void appendLogSafe(String s) {
        mainHandler.post(() -> {
            String old = tvLog.getText().toString();
            String next = old + "\n" + s;
            if (next.length() > 8000) {
                next = next.substring(next.length() - 8000);
            }
            tvLog.setText(next);
        });
    }

    private void toastSafe(String s) {
        mainHandler.post(() -> Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show());
    }

    private String safeDeviceName(BluetoothDevice device) {
        if (device == null) return null;
        try {
            return device.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    private String safeNameWithFallback(BluetoothDevice device) {
        String n = safeDeviceName(device);
        if (n == null) n = "(no name)";
        String addr;
        try {
            addr = device.getAddress();
        } catch (SecurityException e) {
            addr = "(address denied)";
        }
        return n + " [" + addr + "]";
    }

    // =========================================================
    // Glove connection holder
    // =========================================================
    private static class GloveClient {
        final String roleLabel;
        final String targetDeviceName;

        BluetoothGatt gatt;
        BluetoothGattCharacteristic txChar;
        BluetoothGattCharacteristic rxChar;

        boolean connecting = false;
        boolean connected = false;
        boolean notificationsEnabled = false;
        boolean seenDuringCurrentScan = false;

        String lastDeviceAddress = "";
        String lastPacket = "(none)";
        String directionText = "";
        String roleTextFromDevice = "";

        float activeDeltaDeg = 0f;
        float neutralRollDeg = 0f;
        boolean hasActiveDelta = false;

        GloveClient(String roleLabel, String targetDeviceName) {
            this.roleLabel = roleLabel;
            this.targetDeviceName = targetDeviceName;
        }
    }

    // =========================================================
    // Audio engine (continuous sine)
    // =========================================================
    private class AudioEngine {
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
                try { thread.join(400); } catch (InterruptedException ignored) {}
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
                    float targetFreq = Math.max(20f, audioTargetFreqHz);
                    float targetVol = clamp01(audioTargetVolumeLinear);

                    freqSmooth += 0.20f * (targetFreq - freqSmooth);
                    volSmooth += 0.15f * (targetVol - volSmooth);

                    for (int i = 0; i < buffer.length; i++) {
                        double sample = Math.sin(phase);
                        float amp = 0.85f * volSmooth;
                        int pcm = (int) Math.round(sample * amp * 32767.0);

                        if (pcm > 32767) pcm = 32767;
                        if (pcm < -32768) pcm = -32768;

                        buffer[i] = (short) pcm;

                        phase += twoPi * freqSmooth / SAMPLE_RATE;
                        if (phase >= twoPi) phase -= twoPi;
                    }

                    track.write(buffer, 0, buffer.length);
                }

                try { track.pause(); } catch (Exception ignored) {}
                try { track.flush(); } catch (Exception ignored) {}
                try { track.stop(); } catch (Exception ignored) {}
            } catch (Exception e) {
                appendLogSafe("Audio error: " + e.getMessage());
                toastSafe("Audio error");
            } finally {
                if (track != null) {
                    try { track.release(); } catch (Exception ignored) {}
                }
                running = false;
            }
        }

        private float clamp01(float x) {
            if (x < 0f) return 0f;
            if (x > 1f) return 1f;
            return x;
        }
    }
}