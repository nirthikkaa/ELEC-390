package com.example.thereminglovestest2;

/**
 * Pluggable backend for the shared BLE session layer.
 *
 * Current state:
 * - the app still uses a MainActivity-backed backend
 * - a later step can swap this to a ConnectGlovesActivity-owned backend
 *   without changing every UI screen again
 */
public interface BleSessionBackend {
    boolean isHostAvailable();

    BleSessionManager.BleUiSnapshot getBleUiSnapshot();

    BleSessionManager.CalibrationUiSnapshot getCalibrationUiSnapshot();

    void requestBleToggle();

    void requestCaptureNeutral(boolean isPitch);

    void requestToggleDirection(boolean isPitch);

    void requestRefreshHandshake();
}