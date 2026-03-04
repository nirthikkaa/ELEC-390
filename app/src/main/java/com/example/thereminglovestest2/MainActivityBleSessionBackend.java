package com.example.thereminglovestest2;

/**
 * Legacy compatibility backend.
 *
 * This shim now proxies directly to the shared BLE bridge instead of depending
 * on MainActivity static facade methods. That keeps older migration code
 * compile-safe while preserving the newer activity-independent BLE path.
 */
public final class MainActivityBleSessionBackend implements BleSessionBackend {

    @Override
    public boolean isHostAvailable() {
        return BleHostBridge.isBleHostAvailable();
    }

    @Override
    public BleSessionManager.BleUiSnapshot getBleUiSnapshot() {
        BleHostBridge.BleUiSnapshot source = BleHostBridge.getBleUiSnapshot();
        if (source == null) return null;

        return new BleSessionManager.BleUiSnapshot(
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

    @Override
    public BleSessionManager.CalibrationUiSnapshot getCalibrationUiSnapshot() {
        BleHostBridge.CalibrationUiSnapshot source = BleHostBridge.getCalibrationUiSnapshot();
        if (source == null) return null;

        return new BleSessionManager.CalibrationUiSnapshot(
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

    @Override
    public void requestBleToggle() {
        BleHostBridge.requestBleToggle();
    }

    @Override
    public void requestCaptureNeutral(boolean isPitch) {
        BleHostBridge.requestCaptureNeutral(isPitch);
    }

    @Override
    public void requestToggleDirection(boolean isPitch) {
        BleHostBridge.requestToggleDirection(isPitch);
    }

    @Override
    public void requestRefreshHandshake() {
        BleHostBridge.requestRefreshHandshake();
    }
}