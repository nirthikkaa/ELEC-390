package com.example.thereminglovestest2;

import android.content.Context;

/** Shared bridge for cross-screen BLE access. */
public final class BleHostBridge {

    private BleHostBridge() {}

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
                boolean hostReady,
                boolean bluetoothEnabled,
                boolean scanning,
                boolean busyOrConnected,
                String statusText,
                String pitchConnText,
                String volumeConnText,
                String pitchLastText,
                String volumeLastText,
                String recentEventsText
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
            this.recentEventsText = recentEventsText;
        }

        public static BleUiSnapshot fromManager(BleSessionManager.BleUiSnapshot source) {
            if (source == null) return null;
            return new BleUiSnapshot(
                    source.hostReady,
                    source.bluetoothEnabled,
                    source.scanning,
                    source.busyOrConnected,
                    source.statusText,
                    source.pitchConnText,
                    source.volumeConnText,
                    source.pitchLastText,
                    source.volumeLastText,
                    source.recentEventsText
            );
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

        public static CalibrationUiSnapshot fromManager(BleSessionManager.CalibrationUiSnapshot source) {
            if (source == null) return null;
            return new CalibrationUiSnapshot(
                    source.hostReady,
                    source.bluetoothEnabled,
                    source.pitchConnected,
                    source.volumeConnected,
                    source.pitchActiveDeltaDeg,
                    source.volumeActiveDeltaDeg,
                    source.pitchNeutralRollDeg,
                    source.volumeNeutralRollDeg,
                    source.pitchDirectionText,
                    source.volumeDirectionText
            );
        }
    }

    public static void initialize(Context context) {
        BleSessionManager.initialize(context);
    }

    public static boolean hasRequiredPermissions(Context context) {
        return BleSessionManager.hasRequiredPermissions(context);
    }

    public static boolean isBleHostAvailable() {
        return BleSessionManager.isHostAvailable();
    }

    public static BleUiSnapshot getBleUiSnapshot() {
        return BleUiSnapshot.fromManager(BleSessionManager.getBleUiSnapshot());
    }

    public static CalibrationUiSnapshot getCalibrationUiSnapshot() {
        return CalibrationUiSnapshot.fromManager(BleSessionManager.getCalibrationUiSnapshot());
    }

    public static void requestBleToggle() {
        BleSessionManager.requestBleToggle();
    }

    public static void requestCaptureNeutral(boolean isPitch) {
        BleSessionManager.requestCaptureNeutral(isPitch);
    }

    public static void requestToggleDirection(boolean isPitch) {
        BleSessionManager.requestToggleDirection(isPitch);
    }

    public static void requestRefreshHandshake() {
        BleSessionManager.requestRefreshHandshake();
    }

    public static void requestReconnectGlove(boolean isPitch) {
        BleSessionManager.requestReconnectGlove(isPitch);
    }

    public static void maybeStartAutoConnect() {
        BleSessionManager.maybeStartAutoConnect();
    }
}