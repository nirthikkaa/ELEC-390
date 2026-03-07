package com.example.thereminglovestest2;

import java.util.Locale;

/**
 * Tiny BLE text/state helper.
 *
 * The activities already have enough going on. This keeps the little
 * "what state are we really in?" checks and text cleanup in one place.
 */
final class BleUiText {

    private static final String FALLBACK = "—";

    private BleUiText() {
    }

    static boolean isConnecting(String text) {
        return safe(text).toUpperCase(Locale.US).contains("CONNECTING");
    }

    static boolean isBluetoothOn(BleSnapshot snapshot) {
        return snapshot != null && snapshot.hostReady && snapshot.bluetoothEnabled;
    }

    static boolean isBluetoothOn(BleSessionManager.CalibrationUiSnapshot snapshot) {
        return snapshot != null && snapshot.hostReady && snapshot.bluetoothEnabled;
    }

    static boolean isPitchConnected(BleSnapshot snapshot) {
        return snapshot != null && snapshot.hostReady && snapshot.pitchConnected;
    }

    static boolean isVolumeConnected(BleSnapshot snapshot) {
        return snapshot != null && snapshot.hostReady && snapshot.volumeConnected;
    }

    static boolean areBothGlovesConnected(BleSnapshot snapshot) {
        return isPitchConnected(snapshot) && isVolumeConnected(snapshot);
    }

    static boolean isAnyGloveConnected(BleSnapshot snapshot) {
        return isPitchConnected(snapshot) || isVolumeConnected(snapshot);
    }

    static boolean isAnyGloveConnecting(BleSnapshot snapshot) {
        return snapshot != null
                && snapshot.hostReady
                && (snapshot.scanning
                || isConnecting(snapshot.pitchConnText)
                || isConnecting(snapshot.volumeConnText));
    }

    static boolean isGloveConnected(BleSnapshot snapshot, boolean isPitch) {
        return isPitch ? isPitchConnected(snapshot) : isVolumeConnected(snapshot);
    }

    static boolean isGloveConnected(BleSessionManager.CalibrationUiSnapshot snapshot, boolean isPitch) {
        if (snapshot == null || !snapshot.hostReady) {
            return false;
        }
        return isPitch ? snapshot.pitchConnected : snapshot.volumeConnected;
    }

    static String pairSummary(BleSnapshot snapshot) {
        if (areBothGlovesConnected(snapshot)) return "Both gloves connected";
        if (isAnyGloveConnected(snapshot)) return "One glove connected";
        if (isAnyGloveConnecting(snapshot)) return "Connecting...";
        return "No gloves connected";
    }

    static String buildPlayChipText(String label, BleSnapshot snapshot, boolean isPitch) {
        if (snapshot == null || !snapshot.hostReady) return label + "\nWaiting";
        if (!snapshot.bluetoothEnabled) return label + "\nBluetooth off";
        if (isGloveConnected(snapshot, isPitch)) return label + "\nConnected";
        if (isAnyGloveConnecting(snapshot)) return label + "\nConnecting…";
        return label + "\nWaiting";
    }

    static String stripStatusPrefix(String text) {
        return safe(text).replace("Status: ", "");
    }

    static String cleanConnectionText(String text) {
        return safe(text)
                .replace("Pitch (ThereminGlove): ", "")
                .replace("Volume (ThereminGloveVol): ", "");
    }

    static String cleanLastValue(String text, String prefix) {
        String cleaned = safe(text);
        if (prefix != null && cleaned.startsWith(prefix)) {
            cleaned = cleaned.substring(prefix.length()).trim();
        }
        return cleaned.isEmpty() ? FALLBACK : cleaned;
    }

    private static String safe(String text) {
        if (text == null) {
            return FALLBACK;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? FALLBACK : trimmed;
    }
}
