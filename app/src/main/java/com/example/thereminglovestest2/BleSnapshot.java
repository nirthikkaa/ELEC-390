package com.example.thereminglovestest2;

/**
 * Combined BLE + calibration state snapshot for UI use.
 * Replaces the two separate inner snapshot classes that were on BleSessionManager.
 * Activities call BleSessionManager.getSnapshot() to get one object with everything.
 */
public final class BleSnapshot {

    // ── Host / Bluetooth state ────────────────────────────────────────────────
    public final boolean hostReady;
    public final boolean bluetoothEnabled;
    public final boolean scanning;
    public final boolean busyOrConnected;

    // ── Connection text ───────────────────────────────────────────────────────
    public final String statusText;
    public final String pitchConnText;
    public final String volumeConnText;
    public final String pitchLastText;
    public final String volumeLastText;
    public final String recentEventsText;

    // ── Glove connection flags ────────────────────────────────────────────────
    public final boolean pitchConnected;
    public final boolean volumeConnected;

    // ── Calibration values ────────────────────────────────────────────────────
    public final float pitchActiveDeltaDeg;
    public final float volumeActiveDeltaDeg;
    public final float pitchNeutralRollDeg;
    public final float volumeNeutralRollDeg;
    public final String pitchDirectionText;
    public final String volumeDirectionText;

    public BleSnapshot(
            boolean hostReady,
            boolean bluetoothEnabled,
            boolean scanning,
            boolean busyOrConnected,
            String statusText,
            String pitchConnText,
            String volumeConnText,
            String pitchLastText,
            String volumeLastText,
            String recentEventsText,
            boolean pitchConnected,
            boolean volumeConnected,
            float pitchActiveDeltaDeg,
            float volumeActiveDeltaDeg,
            float pitchNeutralRollDeg,
            float volumeNeutralRollDeg,
            String pitchDirectionText,
            String volumeDirectionText
    ) {
        this.hostReady            = hostReady;
        this.bluetoothEnabled     = bluetoothEnabled;
        this.scanning             = scanning;
        this.busyOrConnected      = busyOrConnected;
        this.statusText           = statusText;
        this.pitchConnText        = pitchConnText;
        this.volumeConnText       = volumeConnText;
        this.pitchLastText        = pitchLastText;
        this.volumeLastText       = volumeLastText;
        this.recentEventsText     = recentEventsText;
        this.pitchConnected       = pitchConnected;
        this.volumeConnected      = volumeConnected;
        this.pitchActiveDeltaDeg  = pitchActiveDeltaDeg;
        this.volumeActiveDeltaDeg = volumeActiveDeltaDeg;
        this.pitchNeutralRollDeg  = pitchNeutralRollDeg;
        this.volumeNeutralRollDeg = volumeNeutralRollDeg;
        this.pitchDirectionText   = pitchDirectionText;
        this.volumeDirectionText  = volumeDirectionText;
    }

    /** Convenience: both gloves fully connected. */
    public boolean bothConnected() { return pitchConnected && volumeConnected; }

    /** Convenience: at least one glove connected. */
    public boolean anyConnected()  { return pitchConnected || volumeConnected; }
}
