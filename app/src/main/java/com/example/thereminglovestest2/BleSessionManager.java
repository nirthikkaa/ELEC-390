package com.example.thereminglovestest2;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
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
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.UUID;
import no.nordicsemi.android.ble.BleManager;
import no.nordicsemi.android.ble.BleManagerCallbacks;
/**
 * Shared BLE owner for both theremin gloves.
 *
 * The screens only talk to this class and read one BleSnapshot back.
 * That keeps the BLE surface small even though there are still two real devices underneath.
 */
public final class BleSessionManager {
    private static final String PITCH_NAME = "ThereminGlove";
    private static final String VOLUME_NAME = "ThereminGloveVol";
    private static final long SCAN_TIMEOUT_MS = 12_000L;
    private static final long CONNECT_TIMEOUT_MS = 12_000L;
    private static final long AUTO_RECONNECT_DELAY_MS = 1_500L;
    private static final long PING_AFTER_MS = 3_000L;
    private static final long STALE_WARNING_MS = 4_500L;
    private static final long STALE_RECONNECT_MS = 20_000L;
    private static final long WATCHDOG_PERIOD_MS = 1_000L;
    private static final int EVENT_LOG_MAX_LINES = 8;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>();
    private static final Glove PITCH = new Glove("PITCH", PITCH_NAME, true);
    private static final Glove VOLUME = new Glove("VOLUME", VOLUME_NAME, false);
    private static final Glove[] GLOVES = {PITCH, VOLUME};
    private static Context appContext;
    private static BluetoothAdapter bluetoothAdapter;
    private static BluetoothLeScanner scanner;
    private static SettingsStore settingsStore;
    private static boolean initialized;
    private static boolean scanning;
    private static boolean manualDisconnectRequested;
    private static boolean receiverRegistered;
    /** null means connect both gloves, otherwise only this glove should reconnect. */
    private static Glove connectTarget;
    private static String statusText = "Connect both gloves to start playing";
    private static boolean pitchDirectionInverted = AppSettings.DEFAULT_PITCH_DIRECTION_INVERTED;
    private static boolean volumeDirectionInverted = AppSettings.DEFAULT_VOLUME_DIRECTION_INVERTED;
    private static float pitchActiveDeltaDeg;
    private static float volumeActiveDeltaDeg;
    private BleSessionManager() {}
    private static final Runnable SCAN_TIMEOUT = BleSessionManager::onScanTimeout;
    private static final Runnable AUTO_RECONNECT = BleSessionManager::runAutoReconnect;
    private static final Runnable WATCHDOG = new Runnable() {
        @Override public void run() {
            refreshTruth();
            MAIN.postDelayed(this, WATCHDOG_PERIOD_MS);
        }
    };
    private static final BroadcastReceiver BT_RECEIVER = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) return;
            onBluetoothStateChanged(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR));
        }
    };
    private static final ScanCallback SCAN_CALLBACK = new ScanCallback() {
        @Override public void onScanResult(int callbackType, @NonNull ScanResult result) {
            Glove glove = gloveForName(deviceName(result.getDevice()));
            if (glove != null) maybeConnect(glove, result.getDevice());
            if (!hasPendingConnections()) stopScan();
            updateStatus();
        }
    };
    public static synchronized void initialize(Context context) {
        if (context == null) return;
        if (initialized && appContext != null) return;
        appContext = context.getApplicationContext();
        BluetoothManager manager = (BluetoothManager) appContext.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        settingsStore = new SettingsStore(appContext);
        reloadDirectionSettings();
        registerBluetoothReceiver();
        MAIN.removeCallbacks(WATCHDOG);
        MAIN.post(WATCHDOG);
        initialized = true;
        log("BLE session initialized");
        updateStatus();
    }
    public static boolean isHostAvailable() {
        return initialized && appContext != null;
    }
    public static boolean hasRequiredPermissions(Context context) {
        if (context == null) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    public static boolean isBluetoothEnabled(Context context) {
        if (context == null) return false;
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        return adapter != null && adapter.isEnabled();
    }
    public static void requestRequiredPermissions(Activity activity, int requestCode) {
        if (activity == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT},
                    requestCode);
        } else {
            ActivityCompat.requestPermissions(activity,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    requestCode);
        }
    }
    @SuppressWarnings("deprecation")
    public static void requestEnableBluetoothPrompt(AppCompatActivity activity, int requestCode) {
        if (activity == null) return;
        try {
            activity.startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), requestCode);
        } catch (Exception ignored) {
        }
    }
    public static boolean wereAllPermissionsGranted(int[] grantResults) {
        if (grantResults == null || grantResults.length == 0) return false;
        for (int result : grantResults) if (result != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }
    public static void runWhenReady(AppCompatActivity activity, int permissionsRequestCode,
                                    int enableBluetoothRequestCode, Runnable action) {
        if (!hasRequiredPermissions(activity)) requestRequiredPermissions(activity, permissionsRequestCode);
        else if (!isBluetoothEnabled(activity)) requestEnableBluetoothPrompt(activity, enableBluetoothRequestCode);
        else if (action != null) action.run();
    }
    public static BleSnapshot getSnapshot() {
        return new BleSnapshot(
                isHostAvailable(),
                bluetoothOn(),
                scanning,
                bluetoothOn() ? "Status: " + statusText : "⚠️ BLUETOOTH OFF — Turn Bluetooth ON to play",
                "Pitch last: " + PITCH.lastPacket,
                "Volume last: " + VOLUME.lastPacket,
                buildEventText(),
                PITCH.connected,
                VOLUME.connected,
                PITCH.connecting,
                VOLUME.connecting,
                PITCH.manualHold,
                VOLUME.manualHold,
                PITCH.telemetryStale,
                VOLUME.telemetryStale,
                pitchActiveDeltaDeg,
                volumeActiveDeltaDeg,
                PITCH.neutralRollDeg,
                VOLUME.neutralRollDeg,
                safeDirection(PITCH),
                safeDirection(VOLUME)
        );
    }
    public static void requestBleToggle() {
        MAIN.post(() -> {
            if (busyOrConnected()) {
                manualDisconnectRequested = true;
                for (Glove glove : GLOVES) glove.manualHold = true;
                log("Manual disconnect requested");
                disconnectAll(false);
                return;
            }
            manualDisconnectRequested = false;
            connectTarget = null;
            clearManualHolds();
            log("Manual connect requested");
            startScan();
        });
    }
    public static void requestConnectGlove(boolean isPitch) {
        MAIN.post(() -> connectGlove(glove(isPitch), false));
    }
    public static void requestReconnectGlove(boolean isPitch) {
        MAIN.post(() -> connectGlove(glove(isPitch), true));
    }
    public static void requestDisconnectGlove(boolean isPitch) {
        MAIN.post(() -> disconnectGlove(glove(isPitch), true, true));
    }
    public static void requestConnectMissingGloves() {
        MAIN.post(() -> {
            if (!readyForBle()) return;
            manualDisconnectRequested = false;
            connectTarget = null;
            clearManualHolds();
            if (!hasPendingConnections()) {
                updateStatus();
                return;
            }
            for (Glove glove : GLOVES) if (glove.shouldConnect(connectTarget)) close(glove);
            log("Connect missing gloves requested");
            startScan();
        });
    }
    public static void maybeStartAutoConnect() {
        MAIN.post(() -> {
            if (!initialized || !bluetoothOn() || !hasRequiredPermissions(appContext)) return;
            if (manualDisconnectRequested || scanning || !hasPendingConnections()) return;
            log("Auto-connect requested from app flow");
            startScan();
        });
    }
    public static void requestCaptureNeutral(boolean isPitch) {
        MAIN.post(() -> send(glove(isPitch), "N"));
    }
    public static void requestToggleDirection(boolean isPitch) {
        MAIN.post(() -> setDesiredDirection(isPitch, !currentDirection(isPitch)));
    }
    public static void setDesiredDirection(boolean isPitch, boolean inverted) {
        MAIN.post(() -> {
            if (isPitch) pitchDirectionInverted = inverted;
            else volumeDirectionInverted = inverted;
            saveDirectionSettings();
            Glove glove = glove(isPitch);
            if (glove == null) return;
            glove.directionText = inverted ? "NEGATIVE" : "POSITIVE";
            if (!glove.connected) return;
            glove.lastDirectionSyncCommandMs = 0L;
            syncDirectionIfNeeded(glove);
        });
    }
    public static void requestRefreshHandshake() {
        MAIN.post(() -> {
            for (Glove glove : GLOVES) send(glove, "H");
        });
    }
    private static Glove glove(boolean isPitch) {
        return isPitch ? PITCH : VOLUME;
    }
    private static Glove gloveForName(String name) {
        if (PITCH_NAME.equals(name)) return PITCH;
        if (VOLUME_NAME.equals(name)) return VOLUME;
        return null;
    }
    private static void connectGlove(Glove glove, boolean forceReconnect) {
        if (!readyForBle() || glove == null) return;
        manualDisconnectRequested = false;
        connectTarget = glove;
        glove.manualHold = false;
        if (forceReconnect) {
            log("Manual reconnect requested for " + glove.label + " glove");
            close(glove);
            startScan();
            return;
        }
        if (glove.connected || glove.connecting) {
            updateStatus();
            return;
        }
        close(glove);
        log("Manual connect requested for " + glove.label + " glove");
        startScan();
    }
    private static void disconnectGlove(Glove glove, boolean manual, boolean logIt) {
        if (glove == null) return;
        glove.manualHold = manual;
        if (logIt) log((manual ? "Manual" : "Auto") + " disconnect for " + glove.label + " glove");
        close(glove);
        stopScan();
        updateStatus();
    }
    private static boolean readyForBle() {
        if (!initialized || appContext == null) return false;
        if (!hasRequiredPermissions(appContext)) return fail("BLE permissions required");
        if (!bluetoothOn()) {
            handleBluetoothOff();
            return false;
        }
        return true;
    }
    private static boolean fail(String message) {
        statusText = message;
        log(message);
        return false;
    }
    private static void reloadDirectionSettings() {
        if (settingsStore == null) return;
        AppSettings settings = settingsStore.load();
        pitchDirectionInverted = settings.pitchDirectionInverted;
        volumeDirectionInverted = settings.volumeDirectionInverted;
    }
    private static void saveDirectionSettings() {
        if (settingsStore == null) return;
        AppSettings settings = settingsStore.load();
        settings.pitchDirectionInverted = pitchDirectionInverted;
        settings.volumeDirectionInverted = volumeDirectionInverted;
        settingsStore.save(settings);
    }
    private static boolean currentDirection(boolean isPitch) {
        return isPitch ? pitchDirectionInverted : volumeDirectionInverted;
    }
    private static void clearManualHolds() {
        for (Glove glove : GLOVES) glove.manualHold = false;
    }
    private static boolean canAutoReconnect(Glove glove) {
        return glove != null && !manualDisconnectRequested && !glove.manualHold;
    }
    private static boolean bluetoothOn() {
        return bluetoothAdapter != null && bluetoothAdapter.isEnabled();
    }
    private static boolean hasPendingConnections() {
        for (Glove glove : GLOVES) if (glove.shouldConnect(connectTarget)) return true;
        return false;
    }
    private static boolean busyOrConnected() {
        return scanning || PITCH.isBusy() || VOLUME.isBusy();
    }
    private static void startScan() {
        cancelReconnect();
        if (!readyForBle()) return;
        if (!hasPendingConnections()) {
            updateStatus();
            return;
        }
        if (scanning) stopScan();
        scanner = bluetoothAdapter == null ? null : bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            fail("Bluetooth scanner unavailable");
            return;
        }
        scanning = true;
        statusText = "Scanning for gloves...";
        log("Scanning for gloves");
        try {
            scanner.startScan(SCAN_CALLBACK);
            MAIN.removeCallbacks(SCAN_TIMEOUT);
            MAIN.postDelayed(SCAN_TIMEOUT, SCAN_TIMEOUT_MS);
        } catch (Exception e) {
            scanning = false;
            log("Scan failed — retry scheduled");
            scheduleReconnect("scan exception");
            updateStatus();
        }
    }
    private static void stopScan() {
        MAIN.removeCallbacks(SCAN_TIMEOUT);
        if (!scanning || scanner == null) return;
        try { scanner.stopScan(SCAN_CALLBACK); } catch (Exception ignored) {}
        scanning = false;
        updateStatus();
    }
    private static void onScanTimeout() {
        if (!scanning) return;
        stopScan();
        if (hasPendingConnections()) {
            log("Scan timeout — retrying");
            scheduleReconnect("scan timeout");
        }
    }
    @SuppressLint("MissingPermission")
    private static void maybeConnect(Glove glove, BluetoothDevice device) {
        if (glove == null || !glove.shouldConnect(connectTarget)) return;
        close(glove);
        glove.connecting = true;
        glove.connectAttemptStartMs = SystemClock.elapsedRealtime();
        glove.manager = new ThereminGloveBleManager(appContext, glove.label, new GloveListener(glove));
        glove.manager.connectTo(device, CONNECT_TIMEOUT_MS);
        log("Connecting to " + glove.label + " glove");
        updateStatus();
    }
    private static void refreshTruth() {
        if (!initialized) return;
        if (!bluetoothOn()) {
            handleBluetoothOff();
            return;
        }
        long now = SystemClock.elapsedRealtime();
        for (Glove glove : GLOVES) refreshTruth(glove, now);
    }
    private static void refreshTruth(Glove glove, long now) {
        if (glove == null) return;
        if (glove.connecting && glove.connectAttemptStartMs > 0L
                && now - glove.connectAttemptStartMs >= CONNECT_TIMEOUT_MS) {
            drop(glove, glove.label + " connect timeout", true);
            return;
        }
        if (!glove.connected || !glove.notificationsEnabled || glove.lastTelemetryMs <= 0L) return;
        long age = now - glove.lastTelemetryMs;
        glove.telemetryStale = age > STALE_WARNING_MS;
        if (age > PING_AFTER_MS && now - glove.lastPingMs > 2_000L) {
            glove.lastPingMs = now;
            send(glove, "H");
        }
        if (age > STALE_RECONNECT_MS) drop(glove, glove.label + " telemetry stale — reconnecting", true);
    }
    private static void drop(Glove glove, String reason, boolean reconnect) {
        close(glove);
        log(reason);
        updateStatus();
        if (reconnect && canAutoReconnect(glove)) scheduleReconnect(reason);
    }
    private static void scheduleReconnect(String reason) {
        if (!initialized || manualDisconnectRequested || !bluetoothOn() || !hasPendingConnections()) return;
        cancelReconnect();
        log("Auto-reconnect scheduled: " + reason);
        MAIN.postDelayed(AUTO_RECONNECT, AUTO_RECONNECT_DELAY_MS);
    }
    private static void cancelReconnect() {
        MAIN.removeCallbacks(AUTO_RECONNECT);
    }
    private static void runAutoReconnect() {
        if (!initialized || manualDisconnectRequested) return;
        if (!bluetoothOn()) handleBluetoothOff();
        else if (!scanning && hasPendingConnections()) startScan();
    }
    private static void disconnectAll(boolean silent) {
        cancelReconnect();
        stopScan();
        if (!silent) log("Disconnecting all gloves");
        for (Glove glove : GLOVES) close(glove);
        updateStatus();
    }
    private static void close(Glove glove) {
        if (glove == null) return;
        ThereminGloveBleManager manager = glove.manager;
        glove.manager = null;
        glove.reset();
        setActiveDelta(glove, 0f);
        if (manager != null) {
            try { manager.disconnectAndClose(); } catch (Exception ignored) {}
        }
    }
    private static void send(Glove glove, String command) {
        if (glove == null || glove.manager == null || !glove.connected || !hasRequiredPermissions(appContext)) return;
        if (!bluetoothOn()) {
            handleBluetoothOff();
            return;
        }
        glove.manager.sendCommand(command);
    }
    private static void handleNotification(Glove glove, String line) {
        if (glove == null || line == null) return;
        glove.lastPacket = line;
        glove.lastTelemetryMs = SystemClock.elapsedRealtime();
        glove.telemetryStale = false;
        if (!glove.seenTelemetryThisConnection) {
            glove.seenTelemetryThisConnection = true;
            log(glove.label + " telemetry active");
        }
        if (line.startsWith("ACTIVE_DELTA_DEG:")) {
            Float value = parseTailFloat(line);
            if (value != null) setActiveDelta(glove, value);
            return;
        }
        if (line.startsWith("NEUTRAL_ROLL_DEG:")) {
            Float value = parseTailFloat(line);
            if (value != null) glove.neutralRollDeg = value;
        } else if (line.startsWith("DIRECTION:")) {
            glove.directionText = line.substring("DIRECTION:".length()).trim();
            syncDirectionIfNeeded(glove);
        }
    }
    private static void syncDirectionIfNeeded(Glove glove) {
        if (glove == null || !glove.connected) return;
        reloadDirectionSettings();
        String reported = safeDirection(glove).toUpperCase(Locale.US);
        String desired = glove.isPitch
                ? (pitchDirectionInverted ? "NEGATIVE" : "POSITIVE")
                : (volumeDirectionInverted ? "NEGATIVE" : "POSITIVE");
        if ((!reported.contains("POS") && !reported.contains("NEG")) || reported.contains(desired)) return;
        long now = SystemClock.elapsedRealtime();
        if (now - glove.lastDirectionSyncCommandMs < 1_000L) return;
        glove.lastDirectionSyncCommandMs = now;
        send(glove, "D");
    }
    private static Float parseTailFloat(String line) {
        int idx = line.indexOf(':');
        if (idx < 0 || idx >= line.length() - 1) return null;
        try {
            return Float.parseFloat(line.substring(idx + 1).trim());
        } catch (Exception ignored) {
            return null;
        }
    }
    private static void setActiveDelta(Glove glove, float value) {
        if (glove == null) return;
        if (glove.isPitch) pitchActiveDeltaDeg = value;
        else volumeActiveDeltaDeg = value;
    }
    private static void updateStatus() {
        if (!bluetoothOn()) statusText = "Bluetooth is off — turn it on to play";
        else if (PITCH.connected && VOLUME.connected) statusText = "Both gloves connected — ready to play";
        else if (PITCH.connected || VOLUME.connected) statusText = "One glove connected — connect the other glove";
        else if (scanning) statusText = "Scanning for gloves...";
        else if (PITCH.manualHold || VOLUME.manualHold) statusText = "One or more gloves are paused manually";
        else statusText = "Connect both gloves to start playing";
    }
    private static void onBluetoothStateChanged(int state) {
        switch (state) {
            case BluetoothAdapter.STATE_OFF:
            case BluetoothAdapter.STATE_TURNING_OFF:
                handleBluetoothOff();
                break;
            case BluetoothAdapter.STATE_ON:
                log("Bluetooth turned on");
                updateStatus();
                if (!manualDisconnectRequested) scheduleReconnect("Bluetooth on");
                break;
            case BluetoothAdapter.STATE_TURNING_ON:
                statusText = "Bluetooth is turning on...";
                log("Bluetooth is turning on");
                break;
            default:
                break;
        }
    }
    private static void handleBluetoothOff() {
        cancelReconnect();
        stopScan();
        for (Glove glove : GLOVES) {
            close(glove);
            glove.lastPacket = "(none)";
        }
        pitchActiveDeltaDeg = 0f;
        volumeActiveDeltaDeg = 0f;
        statusText = "⚠️ BLUETOOTH OFF — Turn Bluetooth ON to play";
        log("Bluetooth turned off");
    }
    private static void registerBluetoothReceiver() {
        if (appContext == null || receiverRegistered) return;
        try {
            appContext.registerReceiver(BT_RECEIVER, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
            receiverRegistered = true;
        } catch (Exception ignored) {
        }
    }
    private static String safeDirection(Glove glove) {
        if (glove == null || glove.directionText == null) return "UNKNOWN";
        String value = glove.directionText.trim();
        return value.isEmpty() ? "UNKNOWN" : value;
    }
    @SuppressLint("MissingPermission")
    private static String deviceName(BluetoothDevice device) {
        if (device == null) return null;
        try {
            return device.getName();
        } catch (Exception ignored) {
            return null;
        }
    }
    private static void log(String message) {
        if (message == null || message.trim().isEmpty()) return;
        String stamped = String.format(Locale.US, "%1$tH:%1$tM:%1$tS  %2$s", System.currentTimeMillis(), message.trim());
        synchronized (EVENTS) {
            EVENTS.addLast(stamped);
            while (EVENTS.size() > EVENT_LOG_MAX_LINES) EVENTS.removeFirst();
        }
    }
    private static String buildEventText() {
        synchronized (EVENTS) {
            if (EVENTS.isEmpty()) return "No connection events yet.";
            StringBuilder sb = new StringBuilder();
            Object[] lines = EVENTS.toArray();
            for (int i = lines.length - 1; i >= 0; i--) {
                if (i < lines.length - 1) sb.append('\n');
                sb.append("• ").append(lines[i]);
            }
            return sb.toString();
        }
    }
    private static final class Glove {
        final String label;
        final String advertisedName;
        final boolean isPitch;
        ThereminGloveBleManager manager;
        boolean connecting;
        boolean connected;
        boolean notificationsEnabled;
        boolean seenTelemetryThisConnection;
        boolean telemetryStale;
        boolean manualHold;
        long lastTelemetryMs;
        long lastPingMs;
        long connectAttemptStartMs;
        long lastDirectionSyncCommandMs;
        String lastPacket = "(none)";
        String directionText = "";
        float neutralRollDeg;
        Glove(String label, String advertisedName, boolean isPitch) {
            this.label = label;
            this.advertisedName = advertisedName;
            this.isPitch = isPitch;
        }
        boolean shouldConnect(Glove only) {
            return !manualHold && !connected && !connecting && (only == null || only == this);
        }
        boolean isBusy() {
            return connected || connecting;
        }
        void reset() {
            connecting = false;
            connected = false;
            notificationsEnabled = false;
            seenTelemetryThisConnection = false;
            telemetryStale = false;
            lastTelemetryMs = 0L;
            lastPingMs = 0L;
            connectAttemptStartMs = 0L;
            lastDirectionSyncCommandMs = 0L;
            directionText = "";
        }
    }
    private static final class GloveListener implements ThereminGloveBleManager.Listener {
        private final Glove glove;
        GloveListener(Glove glove) {
            this.glove = glove;
        }
        private boolean owns(@NonNull ThereminGloveBleManager manager) {
            return glove.manager == manager;
        }
        @Override public void onConnecting(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device) {
            if (!owns(manager)) return;
            glove.connecting = true;
            glove.connected = false;
            glove.notificationsEnabled = false;
            glove.telemetryStale = false;
            glove.connectAttemptStartMs = SystemClock.elapsedRealtime();
            updateStatus();
        }
        @Override public void onConnected(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device) {
            if (!owns(manager)) return;
            glove.connecting = false;
            glove.connected = true;
            glove.notificationsEnabled = false;
            glove.telemetryStale = false;
            glove.seenTelemetryThisConnection = false;
            glove.lastTelemetryMs = 0L;
            glove.lastPingMs = 0L;
            glove.connectAttemptStartMs = 0L;
            log(glove.label + " glove connected");
            updateStatus();
        }
        @Override public void onReady(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device) {
            if (!owns(manager)) return;
            glove.connected = true;
            glove.connecting = false;
            log(glove.label + " glove ready");
            send(glove, "H");
            updateStatus();
        }
        @Override public void onDisconnecting(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device) {
            if (owns(manager)) log(glove.label + " glove disconnecting");
        }
        @Override public void onDisconnected(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device) {
            if (!owns(manager)) return;
            glove.manager = null;
            drop(glove, glove.label + " glove disconnected", true);
            try { manager.disconnectAndClose(); } catch (Exception ignored) {}
        }
        @Override public void onNotSupported(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device) {
            if (!owns(manager)) return;
            glove.manager = null;
            drop(glove, glove.label + " glove not supported", false);
            try { manager.disconnectAndClose(); } catch (Exception ignored) {}
        }
        @Override public void onNotifications(@NonNull ThereminGloveBleManager manager, boolean enabled) {
            if (!owns(manager)) return;
            glove.notificationsEnabled = enabled;
            log(glove.label + " notifications " + (enabled ? "enabled" : "failed"));
            if (enabled) {
                glove.lastTelemetryMs = SystemClock.elapsedRealtime();
                glove.telemetryStale = false;
            } else {
                drop(glove, glove.label + " notify failed", true);
            }
        }
        @Override public void onLine(@NonNull ThereminGloveBleManager manager, @NonNull String line) {
            if (owns(manager)) handleNotification(glove, line);
        }
        @Override public void onError(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device,
                                      @NonNull String message, int errorCode) {
            if (owns(manager)) log(glove.label + " error: " + message + " (" + errorCode + ")");
        }
        @Override public void onConnectFailed(@NonNull ThereminGloveBleManager manager, int status) {
            if (!owns(manager)) return;
            glove.manager = null;
            drop(glove, glove.label + " glove connection failed (" + status + ")", true);
        }
    }
}
final class BleSnapshot {
    private static final String FALLBACK = "—";
    final boolean hostReady, bluetoothEnabled, scanning;
    final boolean pitchConnected, volumeConnected, pitchConnecting, volumeConnecting;
    final boolean pitchManualHold, volumeManualHold, pitchTelemetryStale, volumeTelemetryStale;
    final String statusText, pitchLastText, volumeLastText, recentEventsText;
    final float pitchActiveDeltaDeg, volumeActiveDeltaDeg, pitchNeutralRollDeg, volumeNeutralRollDeg;
    final String pitchDirectionText, volumeDirectionText;
    BleSnapshot(boolean hostReady, boolean bluetoothEnabled, boolean scanning,
                String statusText, String pitchLastText, String volumeLastText, String recentEventsText,
                boolean pitchConnected, boolean volumeConnected, boolean pitchConnecting, boolean volumeConnecting,
                boolean pitchManualHold, boolean volumeManualHold, boolean pitchTelemetryStale, boolean volumeTelemetryStale,
                float pitchActiveDeltaDeg, float volumeActiveDeltaDeg,
                float pitchNeutralRollDeg, float volumeNeutralRollDeg,
                String pitchDirectionText, String volumeDirectionText) {
        this.hostReady = hostReady;
        this.bluetoothEnabled = bluetoothEnabled;
        this.scanning = scanning;
        this.statusText = statusText;
        this.pitchLastText = pitchLastText;
        this.volumeLastText = volumeLastText;
        this.recentEventsText = recentEventsText;
        this.pitchConnected = pitchConnected;
        this.volumeConnected = volumeConnected;
        this.pitchConnecting = pitchConnecting;
        this.volumeConnecting = volumeConnecting;
        this.pitchManualHold = pitchManualHold;
        this.volumeManualHold = volumeManualHold;
        this.pitchTelemetryStale = pitchTelemetryStale;
        this.volumeTelemetryStale = volumeTelemetryStale;
        this.pitchActiveDeltaDeg = pitchActiveDeltaDeg;
        this.volumeActiveDeltaDeg = volumeActiveDeltaDeg;
        this.pitchNeutralRollDeg = pitchNeutralRollDeg;
        this.volumeNeutralRollDeg = volumeNeutralRollDeg;
        this.pitchDirectionText = pitchDirectionText;
        this.volumeDirectionText = volumeDirectionText;
    }
    boolean isBluetoothOn() { return hostReady && bluetoothEnabled; }
    boolean isPitchConnected() { return hostReady && pitchConnected; }
    boolean isVolumeConnected() { return hostReady && volumeConnected; }
    boolean isGloveConnected(boolean isPitch) { return isPitch ? isPitchConnected() : isVolumeConnected(); }
    boolean areBothGlovesConnected() { return isPitchConnected() && isVolumeConnected(); }
    boolean isAnyGloveConnected() { return isPitchConnected() || isVolumeConnected(); }
    boolean isAnyGloveConnecting() { return hostReady && (scanning || pitchConnecting || volumeConnecting); }
    boolean isBusy() { return isAnyGloveConnected() || isAnyGloveConnecting(); }
    String pairSummary() {
        if (areBothGlovesConnected()) return "Both gloves connected";
        if (isAnyGloveConnected()) return "One glove connected";
        return isAnyGloveConnecting() ? "Connecting..." : "No gloves connected";
    }
    String connectionDetail(boolean isPitch) {
        if (!hostReady) return "Waiting";
        if (!bluetoothEnabled) return "Bluetooth off";
        if (isGloveConnected(isPitch)) return stale(isPitch) ? "Connected • no data" : "Connected";
        if (isAnyGloveConnecting()) return "Connecting…";
        return manualHold(isPitch) ? "Disconnected" : "Waiting";
    }
    String connectionChipText(String label, boolean isPitch) {
        return label + "\n" + connectionDetail(isPitch);
    }
    private boolean stale(boolean isPitch) { return isPitch ? pitchTelemetryStale : volumeTelemetryStale; }
    private boolean manualHold(boolean isPitch) { return isPitch ? pitchManualHold : volumeManualHold; }
    static String stripStatusPrefix(String text) {
        return safe(text).replace("Status: ", "");
    }
    static String cleanLastValue(String text, String prefix) {
        String cleaned = safe(text);
        if (prefix != null && cleaned.startsWith(prefix)) cleaned = cleaned.substring(prefix.length()).trim();
        return cleaned.isEmpty() ? FALLBACK : cleaned;
    }
    private static String safe(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.isEmpty() ? FALLBACK : trimmed;
    }
}
final class ThereminGloveBleManager extends BleManager {
    interface Listener {
        void onConnecting(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device);
        void onConnected(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device);
        void onReady(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device);
        void onDisconnecting(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device);
        void onDisconnected(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device);
        void onNotSupported(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device);
        void onNotifications(@NonNull ThereminGloveBleManager manager, boolean enabled);
        void onLine(@NonNull ThereminGloveBleManager manager, @NonNull String line);
        void onError(@NonNull ThereminGloveBleManager manager, @NonNull BluetoothDevice device, @NonNull String message, int errorCode);
        void onConnectFailed(@NonNull ThereminGloveBleManager manager, int status);
    }
    private static final UUID SERVICE_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    private static final UUID TX_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac");
    private static final UUID RX_UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ad");
    private final String roleLabel;
    private final Listener listener;
    private final Callbacks callbacks = new Callbacks();
    private BluetoothGattCharacteristic txCharacteristic;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothDevice currentDevice;
    ThereminGloveBleManager(@NonNull Context context, @NonNull String roleLabel, @NonNull Listener listener) {
        super(context);
        this.roleLabel = roleLabel;
        this.listener = listener;
        setGattCallbacks(callbacks);
    }
    @Override public int getMinLogPriority() {
        return Log.INFO;
    }
    void connectTo(@NonNull BluetoothDevice device, long timeoutMs) {
        currentDevice = device;
        connect(device)
                .useAutoConnect(false)
                .retry(3, 250)
                .timeout((int) Math.min(Integer.MAX_VALUE, Math.max(1_000L, timeoutMs)))
                .fail((failedDevice, status) -> {
                    listener.onConnectFailed(this, status);
                    close();
                })
                .enqueue();
    }
    void disconnectAndClose() {
        if (isConnected() || getConnectionState() != BluetoothGatt.STATE_DISCONNECTED) {
            disconnect().done(device -> close()).fail((device, status) -> close()).enqueue();
        } else {
            close();
        }
    }
    void sendCommand(@NonNull String command) {
        if (!isReady() || rxCharacteristic == null) return;
        writeCharacteristic(rxCharacteristic, command.getBytes(StandardCharsets.UTF_8))
                .fail((device, status) -> {
                    BluetoothDevice target = device != null ? device : currentDevice;
                    if (target != null) listener.onError(this, target, roleLabel + " command write failed", status);
                })
                .enqueue();
    }
    @SuppressWarnings("deprecation")
    @NonNull
    @Override
    protected BleManagerGattCallback getGattCallback() {
        return new BleManagerGattCallback() {
            @Override protected boolean isRequiredServiceSupported(@NonNull BluetoothGatt gatt) {
                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                txCharacteristic = service == null ? null : service.getCharacteristic(TX_UUID);
                rxCharacteristic = service == null ? null : service.getCharacteristic(RX_UUID);
                return txCharacteristic != null && rxCharacteristic != null;
            }
            @Override protected void initialize() {
                setNotificationCallback(txCharacteristic).with((device, data) -> {
                    byte[] value = data.getValue();
                    if (value == null || value.length == 0) return;
                    String line = new String(value, StandardCharsets.UTF_8).trim();
                    if (!line.isEmpty()) listener.onLine(ThereminGloveBleManager.this, line);
                });
                enableNotifications(txCharacteristic)
                        .done(device -> listener.onNotifications(ThereminGloveBleManager.this, true))
                        .fail((device, status) -> listener.onNotifications(ThereminGloveBleManager.this, false))
                        .enqueue();
            }
            @Override protected void onServicesInvalidated() {
                txCharacteristic = null;
                rxCharacteristic = null;
            }
        };
    }
    @SuppressWarnings("deprecation")
    private final class Callbacks implements BleManagerCallbacks {
        @Override public void onDeviceConnecting(@NonNull BluetoothDevice device) { listener.onConnecting(ThereminGloveBleManager.this, device); }
        @Override public void onDeviceConnected(@NonNull BluetoothDevice device) { listener.onConnected(ThereminGloveBleManager.this, device); }
        @Override public void onDeviceDisconnecting(@NonNull BluetoothDevice device) { listener.onDisconnecting(ThereminGloveBleManager.this, device); }
        @Override public void onDeviceDisconnected(@NonNull BluetoothDevice device) { listener.onDisconnected(ThereminGloveBleManager.this, device); }
        @Override public void onLinkLossOccurred(@NonNull BluetoothDevice device) { listener.onDisconnected(ThereminGloveBleManager.this, device); }
        @Override public void onServicesDiscovered(@NonNull BluetoothDevice device, boolean optionalServicesFound) {}
        @Override public void onDeviceReady(@NonNull BluetoothDevice device) { listener.onReady(ThereminGloveBleManager.this, device); }
        @Override public void onBondingRequired(@NonNull BluetoothDevice device) {}
        @Override public void onBonded(@NonNull BluetoothDevice device) {}
        @Override public void onBondingFailed(@NonNull BluetoothDevice device) {}
        @Override public void onError(@NonNull BluetoothDevice device, @NonNull String message, int errorCode) {
            listener.onError(ThereminGloveBleManager.this, device, message, errorCode);
        }
        @Override public void onDeviceNotSupported(@NonNull BluetoothDevice device) { listener.onNotSupported(ThereminGloveBleManager.this, device); }
    }
}
