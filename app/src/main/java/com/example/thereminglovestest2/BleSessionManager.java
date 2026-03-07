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
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;

/**
 * Central BLE runtime for the whole app.
 *
 * The activities do not own Bluetooth connections directly.
 * They just ask this class to do the work and then read snapshots back.
 * That keeps navigation simple and stops screen changes from killing BLE.
 */
public final class BleSessionManager {

    private static final String PITCH_DEVICE_NAME = "ThereminGlove";
    private static final String VOLUME_DEVICE_NAME = "ThereminGloveVol";

    private static final UUID SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    private static final UUID TX_CHAR_UUID  = UUID.fromString("12345678-1234-1234-1234-1234567890ac");
    private static final UUID RX_CHAR_UUID  = UUID.fromString("12345678-1234-1234-1234-1234567890ad");
    private static final UUID CCCD_UUID     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private static final long SCAN_TIMEOUT_MS            = 12000;
    private static final long AUTO_RECONNECT_DELAY_MS    = 1500;
    private static final long CONNECT_ATTEMPT_TIMEOUT_MS = 12000;
    private static final long PING_AFTER_MS              = 3000;
    private static final long STALE_WARNING_MS           = 4500;
    private static final long STALE_RECONNECT_MS         = 20000;
    private static final long CONNECTION_WATCHDOG_PERIOD_MS = 1000;
    private static final int  EVENT_LOG_MAX_LINES        = 8;

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
        public final String recentEventsText;

        public BleUiSnapshot(
                boolean hostReady, boolean bluetoothEnabled, boolean scanning,
                boolean busyOrConnected, String statusText,
                String pitchConnText, String volumeConnText,
                String pitchLastText, String volumeLastText, String recentEventsText) {
            this.hostReady        = hostReady;
            this.bluetoothEnabled = bluetoothEnabled;
            this.scanning         = scanning;
            this.busyOrConnected  = busyOrConnected;
            this.statusText       = statusText;
            this.pitchConnText    = pitchConnText;
            this.volumeConnText   = volumeConnText;
            this.pitchLastText    = pitchLastText;
            this.volumeLastText   = volumeLastText;
            this.recentEventsText = recentEventsText;
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
                boolean hostReady, boolean bluetoothEnabled,
                boolean pitchConnected, boolean volumeConnected,
                float pitchActiveDeltaDeg, float volumeActiveDeltaDeg,
                float pitchNeutralRollDeg, float volumeNeutralRollDeg,
                String pitchDirectionText, String volumeDirectionText) {
            this.hostReady            = hostReady;
            this.bluetoothEnabled     = bluetoothEnabled;
            this.pitchConnected       = pitchConnected;
            this.volumeConnected      = volumeConnected;
            this.pitchActiveDeltaDeg  = pitchActiveDeltaDeg;
            this.volumeActiveDeltaDeg = volumeActiveDeltaDeg;
            this.pitchNeutralRollDeg  = pitchNeutralRollDeg;
            this.volumeNeutralRollDeg = volumeNeutralRollDeg;
            this.pitchDirectionText   = pitchDirectionText;
            this.volumeDirectionText  = volumeDirectionText;
        }
    }

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static Context appContext;
    private static BluetoothAdapter bluetoothAdapter;
    private static BluetoothLeScanner bleScanner;
    private static SettingsStore settingsRepo;

    private static boolean initialized = false;
    private static boolean isScanning = false;
    private static boolean manualDisconnectRequested = false;
    private static boolean bluetoothStateReceiverRegistered = false;

    private static final int CONNECT_SCOPE_BOTH        = 0;
    private static final int CONNECT_SCOPE_PITCH_ONLY  = 1;
    private static final int CONNECT_SCOPE_VOLUME_ONLY = 2;
    private static int connectScope = CONNECT_SCOPE_BOTH;

    private static String statusText = "Connect both gloves to start playing";
    private static final ArrayDeque<String> recentEventLines = new ArrayDeque<>();

    private static boolean pitchDirectionInverted  = Defaults.PITCH_DIRECTION_INVERTED;
    private static boolean volumeDirectionInverted = Defaults.VOLUME_DIRECTION_INVERTED;

    private static float pitchActiveDeltaDeg = 0f;
    private static float volumeActiveDeltaDeg = 0f;

    private static final GloveClient pitchGlove  = new GloveClient("PITCH",  PITCH_DEVICE_NAME);
    private static final GloveClient volumeGlove = new GloveClient("VOLUME", VOLUME_DEVICE_NAME);

    private static final Runnable scanTimeoutRunnable = BleSessionManager::onScanTimeout;

    private static final Runnable autoReconnectRunnable = BleSessionManager::runAutoReconnect;

    private static final Runnable connectionTruthWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            runConnectionTruthWatchdog();
            mainHandler.postDelayed(this, CONNECTION_WATCHDOG_PERIOD_MS);
        }
    };

    private static final BroadcastReceiver bluetoothStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            onBluetoothAdapterStateChanged(state);
        }
    };

    private static final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, @NonNull ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeDeviceName(device);
            if (name == null) return;

            if (PITCH_DEVICE_NAME.equals(name) && shouldConnectGlove(pitchGlove)) {
                maybeConnectToGloveDevice(pitchGlove, device);
            } else if (VOLUME_DEVICE_NAME.equals(name) && shouldConnectGlove(volumeGlove)) {
                maybeConnectToGloveDevice(volumeGlove, device);
            }

            if (!hasPendingAllowedConnection()) stopScanIfRunning();
            updateStatusLineText();
        }
    };

    private BleSessionManager() {}

    public static synchronized void initialize(Context context) {
        if (context == null) return;
        if (initialized && appContext != null) return;

        appContext = context.getApplicationContext();
        BluetoothManager bluetoothManager =
                (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) bluetoothAdapter = bluetoothManager.getAdapter();

        settingsRepo = new SettingsStore(appContext);
        reloadSettingsFromRepository();
        registerBluetoothStateReceiverIfNeeded();

        mainHandler.removeCallbacks(connectionTruthWatchdogRunnable);
        mainHandler.post(connectionTruthWatchdogRunnable);

        initialized = true;
        appendEvent("BLE session initialized");
        updateStatusLineText();
    }

    public static boolean hasRequiredPermissions(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN)    == PackageManager.PERMISSION_GRANTED
                && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    public static boolean isHostAvailable() {
        return initialized && appContext != null;
    }

    /**
     * One combined snapshot keeps the screens simpler.
     * They can still ask for the older UI/calibration slices if they want,
     * but this is now the single source we build from.
     */
    public static BleSnapshot getSnapshot() {
        boolean btEnabled = isBluetoothEnabled();
        String bigStatus = btEnabled
                ? ("Status: " + statusText)
                : "⚠️ BLUETOOTH OFF — Turn Bluetooth ON to play";

        return new BleSnapshot(
                isHostAvailable(),
                btEnabled,
                isScanning,
                isBleBusyOrConnected(),
                bigStatus,
                connLine(pitchGlove,  "Pitch ("  + PITCH_DEVICE_NAME  + ")"),
                connLine(volumeGlove, "Volume (" + VOLUME_DEVICE_NAME + ")"),
                "Pitch last: "  + pitchGlove.lastPacket,
                "Volume last: " + volumeGlove.lastPacket,
                buildRecentEventsText(),
                pitchGlove.connected,
                volumeGlove.connected,
                pitchActiveDeltaDeg,
                volumeActiveDeltaDeg,
                pitchGlove.neutralRollDeg,
                volumeGlove.neutralRollDeg,
                safeDirectionText(pitchGlove),
                safeDirectionText(volumeGlove)
        );
    }

    public static BleUiSnapshot getBleUiSnapshot() {
        BleSnapshot snapshot = getSnapshot();
        return new BleUiSnapshot(
                snapshot.hostReady,
                snapshot.bluetoothEnabled,
                snapshot.scanning,
                snapshot.busyOrConnected,
                snapshot.statusText,
                snapshot.pitchConnText,
                snapshot.volumeConnText,
                snapshot.pitchLastText,
                snapshot.volumeLastText,
                snapshot.recentEventsText
        );
    }

    public static CalibrationUiSnapshot getCalibrationUiSnapshot() {
        BleSnapshot snapshot = getSnapshot();
        return new CalibrationUiSnapshot(
                snapshot.hostReady,
                snapshot.bluetoothEnabled,
                snapshot.pitchConnected,
                snapshot.volumeConnected,
                snapshot.pitchActiveDeltaDeg,
                snapshot.volumeActiveDeltaDeg,
                snapshot.pitchNeutralRollDeg,
                snapshot.volumeNeutralRollDeg,
                snapshot.pitchDirectionText,
                snapshot.volumeDirectionText
        );
    }

    public static void requestBleToggle() {
        mainHandler.post(() -> {
            if (!ensureReadyForBleWork()) return;

            if (isBleBusyOrConnected()) {
                manualDisconnectRequested = true;
                clearManualHolds();
                appendEvent("Manual disconnect requested");
                disconnectAllGlovesInternal(false);
                return;
            }

            manualDisconnectRequested = false;
            clearManualHolds();
            connectScope = CONNECT_SCOPE_BOTH;
            appendEvent("Manual connect requested");
            startScanAndConnect();
        });
    }

    public static void requestCaptureNeutral(final boolean isPitch) {
        mainHandler.post(() -> sendCommandToGlove(isPitch ? pitchGlove : volumeGlove, "N"));
    }

    public static void requestToggleDirection(final boolean isPitch) {
        mainHandler.post(() -> {
            reloadSettingsFromRepository();
            if (isPitch) pitchDirectionInverted = !pitchDirectionInverted;
            else         volumeDirectionInverted = !volumeDirectionInverted;
            persistDirectionSettingsOnly();
            sendCommandToGlove(isPitch ? pitchGlove : volumeGlove, "D");
        });
    }

    public static void requestRefreshHandshake() {
        mainHandler.post(() -> {
            sendCommandToGlove(pitchGlove,  "H");
            sendCommandToGlove(volumeGlove, "H");
        });
    }


    public static void requestConnectGlove(final boolean isPitch) {
        mainHandler.post(() -> {
            if (!ensureReadyForBleWork()) return;

            GloveClient target = isPitch ? pitchGlove : volumeGlove;
            manualDisconnectRequested = false;
            target.manualHold = false;
            connectScope = isPitch ? CONNECT_SCOPE_PITCH_ONLY : CONNECT_SCOPE_VOLUME_ONLY;

            if (target.connected || target.connecting) {
                updateStatusLineText();
                return;
            }

            safeCloseGloveConnection(target);
            appendEvent("Manual connect requested for " + target.roleLabel + " glove");
            startScanAndConnect();
        });
    }

    public static void requestDisconnectGlove(final boolean isPitch) {
        mainHandler.post(() -> {
            GloveClient target = isPitch ? pitchGlove : volumeGlove;
            target.manualHold = true;
            appendEvent("Manual disconnect requested for " + target.roleLabel + " glove");
            safeCloseGloveConnection(target);
            stopScanIfRunning();
            updateStatusLineText();
        });
    }

    public static void requestConnectMissingGloves() {
        mainHandler.post(() -> {
            if (!ensureReadyForBleWork()) return;

            manualDisconnectRequested = false;
            clearManualHolds();
            connectScope = CONNECT_SCOPE_BOTH;

            if (!hasPendingAllowedConnection()) {
                updateStatusLineText();
                return;
            }

            stopScanIfRunning();
            resetAllowedNonConnectedGlovesForFreshConnect();
            appendEvent("Connect missing gloves requested");
            startScanAndConnect();
        });
    }

    public static void requestReconnectGlove(final boolean isPitch) {
        mainHandler.post(() -> {
            if (!ensureReadyForBleWork()) return;

            manualDisconnectRequested = false;

            GloveClient target = isPitch ? pitchGlove : volumeGlove;
            target.manualHold = false;
            connectScope = isPitch ? CONNECT_SCOPE_PITCH_ONLY : CONNECT_SCOPE_VOLUME_ONLY;

            appendEvent("Manual reconnect requested for " + target.roleLabel + " glove");
            disconnectSingleGloveInternal(target);
            updateStatusLineText();
            startScanAndConnect();
        });
    }

    public static void maybeStartAutoConnect() {
        mainHandler.post(() -> {
            if (!initialized || !isBluetoothEnabled() || !hasRequiredPermissions(appContext)) return;
            if (manualDisconnectRequested) return;
            if (!hasPendingAllowedConnection()) return;
            if (isScanning) return;
            appendEvent("Auto-connect requested from app flow");
            startScanAndConnect();
        });
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private static boolean ensureReadyForBleWork() {
        if (!initialized || appContext == null) return false;

        if (!hasRequiredPermissions(appContext)) {
            statusText = "BLE permissions required";
            appendEvent("BLE permissions required");
            return false;
        }

        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            return false;
        }

        return true;
    }

    private static void clearManualHolds() {
        pitchGlove.manualHold = false;
        volumeGlove.manualHold = false;
    }

    private static boolean canAutoReconnect(GloveClient glove) {
        return glove != null && !manualDisconnectRequested && !glove.manualHold;
    }

    private static void appendEvent(String msg) {
        if (msg == null || msg.trim().isEmpty()) return;
        String stamped = String.format(Locale.US, "%1$tH:%1$tM:%1$tS  %2$s",
                System.currentTimeMillis(), msg.trim());
        synchronized (recentEventLines) {
            recentEventLines.addLast(stamped);
            while (recentEventLines.size() > EVENT_LOG_MAX_LINES) recentEventLines.removeFirst();
        }
    }

    private static String buildRecentEventsText() {
        synchronized (recentEventLines) {
            if (recentEventLines.isEmpty()) return "No connection events yet.";
            StringBuilder sb = new StringBuilder();
            Object[] lines = recentEventLines.toArray();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (i < lines.length - 1) sb.append('\n');
                sb.append("• ").append(lines[i]);
            }
            return sb.toString();
        }
    }

    private static void reloadSettingsFromRepository() {
        if (settingsRepo == null) return;
        AppSettings s = settingsRepo.load();
        pitchDirectionInverted  = s.pitchDirectionInverted;
        volumeDirectionInverted = s.volumeDirectionInverted;
    }

    private static void persistDirectionSettingsOnly() {
        if (settingsRepo == null) return;
        AppSettings s = settingsRepo.load();
        s.pitchDirectionInverted  = pitchDirectionInverted;
        s.volumeDirectionInverted = volumeDirectionInverted;
        settingsRepo.save(s);
    }

    private static String desiredDirectionTextForGlove(GloveClient glove) {
        boolean inverted = (glove == volumeGlove) ? volumeDirectionInverted : pitchDirectionInverted;
        return inverted ? "NEGATIVE" : "POSITIVE";
    }

    private static boolean reportedDirectionMatchesDesired(GloveClient glove) {
        String reported = safeDirectionText(glove).trim().toUpperCase(Locale.US);
        if (reported.isEmpty() || "UNKNOWN".equals(reported)) return false;
        return reported.contains(desiredDirectionTextForGlove(glove));
    }

    private static void syncDirectionPreferenceIfNeeded(GloveClient glove) {
        if (glove == null || !glove.connected) return;
        reloadSettingsFromRepository();

        String reported = safeDirectionText(glove).trim().toUpperCase(Locale.US);
        if (!reported.contains("POS") && !reported.contains("NEG")) return;
        if (reportedDirectionMatchesDesired(glove)) return;

        long now = SystemClock.elapsedRealtime();
        if (now - glove.lastDirectionSyncCommandMs < 1000L) return;

        glove.lastDirectionSyncCommandMs = now;
        sendCommandToGlove(glove, "D");
    }

    private static boolean isBluetoothEnabled() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }

    private static void onBluetoothAdapterStateChanged(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
            case BluetoothAdapter.STATE_TURNING_OFF:
                handleBluetoothOffHard();
                break;
            case BluetoothAdapter.STATE_ON:
                updateStatusLineText();
                appendEvent("Bluetooth turned on");
                if (!manualDisconnectRequested) scheduleAutoReconnect("Bluetooth on");
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                statusText = "Bluetooth is turning on...";
                appendEvent("Bluetooth is turning on");
                break;
        }
    }

    private static void handleBluetoothOffHard() {
        cancelAutoReconnect();
        stopScanIfRunning();

        safeCloseGloveConnection(pitchGlove);
        safeCloseGloveConnection(volumeGlove);

        pitchActiveDeltaDeg  = 0f;
        volumeActiveDeltaDeg = 0f;

        pitchGlove.lastPacket  = "(none)";
        volumeGlove.lastPacket = "(none)";

        statusText = "⚠️ BLUETOOTH OFF — Turn Bluetooth ON to play";
        appendEvent("Bluetooth turned off");
    }

    private static void runConnectionTruthWatchdog() {
        if (!initialized) return;

        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        maybeHandleGloveConnectTimeout(pitchGlove,  now);
        maybeHandleGloveConnectTimeout(volumeGlove, now);
        maybeHandleGloveTelemetry(pitchGlove,  now);
        maybeHandleGloveTelemetry(volumeGlove, now);
    }

    private static void maybeHandleGloveConnectTimeout(GloveClient glove, long nowMs) {
        if (glove == null || !glove.connecting || glove.connectAttemptStartMs <= 0L) return;
        if (nowMs - glove.connectAttemptStartMs < CONNECT_ATTEMPT_TIMEOUT_MS) return;

        safeCloseGloveConnection(glove);
        updateStatusLineText();
        appendEvent(glove.roleLabel + " connect timeout");
        if (canAutoReconnect(glove)) scheduleAutoReconnect(glove.roleLabel + " connect timeout");
    }

    private static void maybeHandleGloveTelemetry(GloveClient glove, long nowMs) {
        if (glove == null || !glove.connected || !glove.notificationsEnabled || glove.lastTelemetryMs <= 0L) return;

        long age = nowMs - glove.lastTelemetryMs;
        glove.telemetryStale = (age > STALE_WARNING_MS);

        if (age > PING_AFTER_MS && (nowMs - glove.lastPingMs) > 2000) {
            glove.lastPingMs = nowMs;
            sendCommandToGlove(glove, "H");
        }

        if (age > STALE_RECONNECT_MS) {
            safeCloseGloveConnection(glove);
            updateStatusLineText();
            appendEvent(glove.roleLabel + " telemetry stale — reconnecting");
            if (canAutoReconnect(glove)) scheduleAutoReconnect(glove.roleLabel + " telemetry stale");
        }
    }

    private static void startScanAndConnect() {
        cancelAutoReconnect();

        if (!initialized || !hasRequiredPermissions(appContext)) {
            statusText = "BLE permissions required";
            appendEvent("BLE permissions required");
            return;
        }

        if (!isBluetoothEnabled()) {
            handleBluetoothOffHard();
            return;
        }

        if (isScanning) stopScanIfRunning();

        bleScanner = bluetoothAdapter != null ? bluetoothAdapter.getBluetoothLeScanner() : null;
        if (bleScanner == null) {
            statusText = "Bluetooth scanner unavailable";
            appendEvent("Bluetooth scanner unavailable");
            return;
        }

        if (!hasPendingAllowedConnection()) {
            updateStatusLineText();
            return;
        }

        isScanning = true;
        statusText = "Scanning for gloves...";
        appendEvent("Scanning for gloves");

        try {
            bleScanner.startScan(scanCallback);
            scheduleScanTimeout();
        } catch (Exception e) {
            isScanning = false;
            appendEvent("Scan failed — retry scheduled");
            scheduleAutoReconnect("scan exception");
            updateStatusLineText();
        }
    }

    private static void scheduleScanTimeout() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        mainHandler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
    }

    private static void onScanTimeout() {
        if (!isScanning) return;
        stopScanIfRunning();
        if (hasPendingAllowedConnection()) {
            appendEvent("Scan timeout — retrying");
            scheduleAutoReconnect("scan timeout missing glove");
        }
    }

    private static void stopScanIfRunning() {
        mainHandler.removeCallbacks(scanTimeoutRunnable);
        if (!isScanning || bleScanner == null) return;
        try { bleScanner.stopScan(scanCallback); } catch (Exception ignored) {}
        isScanning = false;
        updateStatusLineText();
    }

    @SuppressLint("MissingPermission")
    private static void maybeConnectToGloveDevice(final GloveClient glove, BluetoothDevice device) {
        if (glove.connected || glove.connecting) return;
        if (glove.gatt != null) safeCloseGloveConnection(glove);

        glove.connecting = true;
        glove.connectAttemptStartMs = SystemClock.elapsedRealtime();
        appendEvent("Connecting to " + glove.roleLabel + " glove");

        BluetoothGattCallback callback = createGattCallback(glove);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                glove.gatt = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                glove.gatt = device.connectGatt(appContext, false, callback);
            }
        } catch (Exception e) {
            glove.connecting = false;
            glove.gatt = null;
            glove.connectAttemptStartMs = 0L;
            appendEvent(glove.roleLabel + " glove connection failed — retrying");
            scheduleAutoReconnect(glove.roleLabel + " connect exception");
        }
    }

    private static void scheduleAutoReconnect(String reason) {
        if (!initialized || manualDisconnectRequested || !isBluetoothEnabled()) return;
        if (!hasPendingAllowedConnection()) return;
        cancelAutoReconnect();
        appendEvent("Auto-reconnect scheduled: " + reason);
        mainHandler.postDelayed(autoReconnectRunnable, AUTO_RECONNECT_DELAY_MS);
    }

    private static void runAutoReconnect() {
        if (!initialized || manualDisconnectRequested) return;
        if (!isBluetoothEnabled()) { handleBluetoothOffHard(); return; }
        if (!hasPendingAllowedConnection() || isScanning) return;
        startScanAndConnect();
    }

    private static void cancelAutoReconnect() {
        mainHandler.removeCallbacks(autoReconnectRunnable);
    }

    private static void disconnectAllGlovesInternal(boolean silent) {
        cancelAutoReconnect();
        stopScanIfRunning();
        if (!silent) appendEvent("Disconnecting all gloves");
        safeCloseGloveConnection(pitchGlove);
        safeCloseGloveConnection(volumeGlove);
        updateStatusLineText();
    }

    private static void disconnectSingleGloveInternal(GloveClient glove) {
        if (glove == null) return;
        stopScanIfRunning();
        safeCloseGloveConnection(glove);
    }

    private static boolean shouldConnectGlove(GloveClient glove) {
        if (glove == null || glove.manualHold) return false;
        if (connectScope == CONNECT_SCOPE_PITCH_ONLY)  return glove == pitchGlove;
        if (connectScope == CONNECT_SCOPE_VOLUME_ONLY) return glove == volumeGlove;
        return true;
    }

    private static boolean hasPendingAllowedConnection() {
        return (shouldConnectGlove(pitchGlove)  && !pitchGlove.connected)
            || (shouldConnectGlove(volumeGlove) && !volumeGlove.connected);
    }

    private static void resetAllowedNonConnectedGlovesForFreshConnect() {
        if (shouldConnectGlove(pitchGlove)  && !pitchGlove.connected)  safeCloseGloveConnection(pitchGlove);
        if (shouldConnectGlove(volumeGlove) && !volumeGlove.connected) safeCloseGloveConnection(volumeGlove);
        updateStatusLineText();
    }

    private static boolean isCurrentGattCallback(GloveClient glove, BluetoothGatt gatt) {
        return glove != null && gatt != null && glove.gatt == gatt;
    }

    @SuppressLint("MissingPermission")
    private static void closeGattQuietly(BluetoothGatt gatt) {
        if (gatt == null) return;
        try { gatt.disconnect(); } catch (Exception ignored) {}
        try { gatt.close();      } catch (Exception ignored) {}
    }

    @SuppressLint("MissingPermission")
    private static void safeCloseGloveConnection(GloveClient glove) {
        if (glove == null) return;

        glove.connecting               = false;
        glove.connected                = false;
        glove.notificationsEnabled     = false;
        glove.telemetryStale           = false;
        glove.seenTelemetryThisConnection = false;
        glove.lastPingMs               = 0L;
        glove.connectAttemptStartMs    = 0L;
        glove.lastDirectionSyncCommandMs = 0L;
        glove.directionText            = "";
        glove.txChar                   = null;
        glove.rxChar                   = null;

        BluetoothGatt g = glove.gatt;
        glove.gatt = null;

        if (g != null) {
            try { g.disconnect(); } catch (Exception ignored) {}
            try { g.close();      } catch (Exception ignored) {}
        }
    }

    private static BluetoothGattCallback createGattCallback(final GloveClient glove) {
        return new BluetoothGattCallback() {

            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (!isCurrentGattCallback(glove, gatt)) { closeGattQuietly(gatt); return; }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    glove.connecting               = false;
                    glove.connected                = true;
                    glove.notificationsEnabled     = false;
                    glove.telemetryStale           = false;
                    glove.seenTelemetryThisConnection = false;
                    glove.lastTelemetryMs          = 0L;
                    glove.lastPingMs               = 0L;
                    glove.connectAttemptStartMs    = 0L;

                    updateStatusLineText();
                    appendEvent(glove.roleLabel + " glove connected");

                    try { gatt.discoverServices(); }
                    catch (Exception e) { scheduleAutoReconnect(glove.roleLabel + " discoverServices exception"); }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (glove.gatt == gatt) {
                        safeCloseGloveConnection(glove);
                    } else {
                        closeGattQuietly(gatt);
                        return;
                    }

                    updateStatusLineText();
                    appendEvent(glove.roleLabel + " glove disconnected");
                    if (canAutoReconnect(glove)) scheduleAutoReconnect(glove.roleLabel + " disconnected");
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (!isCurrentGattCallback(glove, gatt)) { closeGattQuietly(gatt); return; }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    safeCloseGloveConnection(glove);
                    appendEvent(glove.roleLabel + " service discovery failed");
                    scheduleAutoReconnect(glove.roleLabel + " service discovery failed");
                    return;
                }

                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service == null) {
                    safeCloseGloveConnection(glove);
                    appendEvent(glove.roleLabel + " service missing");
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
                if (!isCurrentGattCallback(glove, gatt)) { closeGattQuietly(gatt); return; }

                glove.notificationsEnabled = (status == BluetoothGatt.GATT_SUCCESS);
                appendEvent(glove.roleLabel + " notifications " + (glove.notificationsEnabled ? "enabled" : "failed"));
                glove.lastTelemetryMs = SystemClock.elapsedRealtime();
                glove.telemetryStale  = false;

                if (!glove.notificationsEnabled) {
                    safeCloseGloveConnection(glove);
                    scheduleAutoReconnect(glove.roleLabel + " notify failed");
                }
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
                if (!isCurrentGattCallback(glove, gatt)) { closeGattQuietly(gatt); return; }
                handleGloveNotification(glove, characteristic, value);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
                if (!isCurrentGattCallback(glove, gatt)) { closeGattQuietly(gatt); return; }
                handleGloveNotification(glove, characteristic, characteristic.getValue());
            }
        };
    }

    @SuppressLint("MissingPermission")
    private static void enableNotifications(GloveClient glove, BluetoothGatt gatt, BluetoothGattCharacteristic ch) {
        try {
            gatt.setCharacteristicNotification(ch, true);
            BluetoothGattDescriptor cccd = ch.getDescriptor(CCCD_UUID);
            if (cccd == null) return;
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(cccd);
        } catch (Exception ignored) {}
    }

    private static void handleGloveNotification(GloveClient glove, BluetoothGattCharacteristic ch, byte[] value) {
        if (ch == null || value == null) return;
        if (!TX_CHAR_UUID.equals(ch.getUuid())) return;

        String line = new String(value, StandardCharsets.UTF_8).trim();
        glove.lastPacket = line;

        if (!glove.seenTelemetryThisConnection) {
            glove.seenTelemetryThisConnection = true;
            appendEvent(glove.roleLabel + " telemetry active");
        }
        glove.lastTelemetryMs = SystemClock.elapsedRealtime();
        glove.telemetryStale  = false;

        if (line.startsWith("ACTIVE_DELTA_DEG:")) {
            Float v = parseTailFloat(line);
            if (v != null) {
                if (glove == pitchGlove) pitchActiveDeltaDeg  = v;
                else                     volumeActiveDeltaDeg = v;
            }
            return;
        }

        if (line.startsWith("NEUTRAL_ROLL_DEG:")) {
            Float v = parseTailFloat(line);
            if (v != null) glove.neutralRollDeg = v;
        } else if (line.startsWith("DIRECTION:")) {
            glove.directionText = line.substring("DIRECTION:".length()).trim();
            syncDirectionPreferenceIfNeeded(glove);
        }
        // ROLE: lines ignored — role is determined by device name, not self-report
    }

    private static Float parseTailFloat(String line) {
        int idx = line.indexOf(':');
        if (idx < 0 || idx >= line.length() - 1) return null;
        try {
            return Float.parseFloat(line.substring(idx + 1).trim());
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private static void sendCommandToGlove(GloveClient glove, String cmd) {
        if (glove == null || glove.gatt == null || glove.rxChar == null || !glove.connected) return;
        if (!hasRequiredPermissions(appContext)) return;
        if (!isBluetoothEnabled()) { handleBluetoothOffHard(); return; }

        try {
            glove.rxChar.setValue(cmd.getBytes(StandardCharsets.UTF_8));
            glove.gatt.writeCharacteristic(glove.rxChar);
        } catch (Exception ignored) {}
    }

    private static void updateStatusLineText() {
        if (!isBluetoothEnabled()) {
            statusText = "Bluetooth is off — turn it on to play";
        } else if (pitchGlove.connected && volumeGlove.connected) {
            statusText = "Both gloves connected — ready to play";
        } else if (pitchGlove.connected || volumeGlove.connected) {
            statusText = "One glove connected — connect the other glove";
        } else if (isScanning) {
            statusText = "Scanning for gloves...";
        } else if (pitchGlove.manualHold || volumeGlove.manualHold) {
            statusText = "One or more gloves are paused manually";
        } else {
            statusText = "Connect both gloves to start playing";
        }
    }

    @SuppressLint("MissingPermission")
    private static String safeDeviceName(BluetoothDevice d) {
        if (d == null) return null;
        try { return d.getName(); } catch (Exception ignored) { return null; }
    }

    private static void registerBluetoothStateReceiverIfNeeded() {
        if (appContext == null || bluetoothStateReceiverRegistered) return;
        try {
            appContext.registerReceiver(bluetoothStateReceiver,
                    new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            bluetoothStateReceiverRegistered = true;
        } catch (Exception ignored) {}
    }

    private static String connLine(GloveClient g, String prefix) {
        if (g.connected) {
            return g.telemetryStale
                    ? prefix + ": CONNECTED ⚠️ (no data)"
                    : prefix + ": CONNECTED ✅";
        }
        if (g.connecting) return prefix + ": CONNECTING…";
        if (g.manualHold) return prefix + ": DISCONNECTED ⏸";
        return prefix + ": DISCONNECTED ❌";
    }

    private static String safeDirectionText(GloveClient glove) {
        if (glove == null || glove.directionText == null) return "UNKNOWN";
        String trimmed = glove.directionText.trim();
        return trimmed.isEmpty() ? "UNKNOWN" : trimmed;
    }

    private static boolean isBleBusyOrConnected() {
        return isScanning
            || pitchGlove.connecting  || volumeGlove.connecting
            || pitchGlove.connected   || volumeGlove.connected;
    }

    // ─── GloveClient ──────────────────────────────────────────────────────────

    private static final class GloveClient {
        final String roleLabel;
        final String targetDeviceName;

        BluetoothGatt gatt;
        BluetoothGattCharacteristic txChar;
        BluetoothGattCharacteristic rxChar;

        boolean connecting             = false;
        boolean connected              = false;
        boolean notificationsEnabled   = false;
        boolean seenTelemetryThisConnection = false;

        long lastTelemetryMs         = 0L;
        long lastPingMs              = 0L;
        long connectAttemptStartMs   = 0L;
        long lastDirectionSyncCommandMs = 0L;
        boolean telemetryStale       = false;
        boolean manualHold           = false;

        String lastPacket    = "(none)";
        String directionText = "";

        float neutralRollDeg = 0f;

        GloveClient(String roleLabel, String targetDeviceName) {
            this.roleLabel        = roleLabel;
            this.targetDeviceName = targetDeviceName;
        }
    }
}
