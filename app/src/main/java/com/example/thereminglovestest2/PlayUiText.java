package com.example.thereminglovestest2;

import java.util.Locale;

final class PlayUiText {
    private PlayUiText() {}

    static String headline(BleSnapshot snapshot, boolean bothConnected, boolean oneConnected, boolean connecting) {
        if (snapshot == null || !snapshot.hostReady) return "Preparing instrument";
        if (!snapshot.bluetoothEnabled) return "Bluetooth is off";
        if (bothConnected) return "Ready to perform";
        if (oneConnected) return "Almost ready";
        return connecting ? "Connecting your gloves" : "Waiting for gloves";
    }

    static String subtitle(BleSnapshot snapshot, boolean bothConnected, boolean oneConnected, boolean connecting) {
        if (snapshot == null || !snapshot.hostReady) return "Setting up the live instrument experience.";
        if (!snapshot.bluetoothEnabled) return "Turn Bluetooth on to reconnect your gloves.";
        if (bothConnected) return "Move your hands to shape pitch and volume. Calibrate anytime for a tighter response.";
        if (oneConnected) return "One glove is connected. Turn on the second glove to complete the instrument.";
        return connecting
                ? "Keep both gloves awake and close to your phone."
                : "Turn on both gloves to begin playing.";
    }

    static String frequency(float freqHz) {
        float freq = Math.max(PlayMappingState.FREQ_MIN_UI, freqHz);
        return freq >= 1000f
                ? String.format(Locale.US, "Frequency • %.2f kHz", freq / 1000f)
                : String.format(Locale.US, "Frequency • %.0f Hz", freq);
    }

    static String volume(float volumeLinear) {
        int percent = Math.round(Math.max(0f, Math.min(1f, volumeLinear)) * 100f);
        return String.format(Locale.US, "Volume level • %d%%", percent);
    }

    static String tone(boolean bothConnected, float mappedFreqHz, float mappedVolumeLinear) {
        if (!bothConnected) return "Connect both gloves to start shaping sound.";
        if (mappedVolumeLinear <= 0.01f) {
            return String.format(Locale.US,
                    "Live tone ready • %.1f Hz • Raise your volume hand to bring it in", mappedFreqHz);
        }
        return String.format(Locale.US,
                "Live tone • %.1f Hz • %.0f%% intensity", mappedFreqHz, mappedVolumeLinear * 100f);
    }
}
