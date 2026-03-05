package com.example.thereminglovestest2;

import java.util.Locale;

/**
 * Small utility for BLE/status text formatting shared by Activities.
 * Keeps UI cleanup out of the activity classes without changing BLE behavior.
 */
final class BleUiText {

    private BleUiText() {
        // Utility class
    }

    static boolean isConnecting(String text) {
        return text != null && text.toUpperCase(Locale.US).contains("CONNECTING");
    }

    static String stripStatusPrefix(String text) {
        if (text == null) return "—";
        return text.trim().replace("Status: ", "");
    }

    static String cleanConnectionText(String text) {
        if (text == null || text.trim().isEmpty()) return "—";
        return text.trim()
                .replace("Pitch (ThereminGlove): ", "")
                .replace("Volume (ThereminGloveVol): ", "");
    }

    static String cleanLastValue(String text, String prefix) {
        if (text == null || text.trim().isEmpty()) return "—";
        String cleaned = text.trim();
        if (prefix != null && cleaned.startsWith(prefix)) {
            cleaned = cleaned.substring(prefix.length()).trim();
        }
        return cleaned.isEmpty() ? "—" : cleaned;
    }
}
