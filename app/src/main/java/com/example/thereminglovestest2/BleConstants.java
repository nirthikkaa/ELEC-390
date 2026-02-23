package com.example.thereminglovestest2;

import java.util.UUID;

/**
 * Central BLE contract for the Theremin Gloves app.
 * Keep these values in sync with the Arduino firmware.
 */
public final class BleConstants {

    private BleConstants() {
        // no instances
    }

    // Device names (must match Arduino advertising names)
    public static final String PITCH_DEVICE_NAME = "ThereminGlove";
    public static final String VOLUME_DEVICE_NAME = "ThereminGloveVol";

    // Service + characteristics (must stay unchanged unless firmware changes)
    public static final UUID SERVICE_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ab");

    // Arduino -> phone (notify/read)
    public static final UUID TX_CHAR_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ac");

    // Phone -> Arduino (write/read)
    public static final UUID RX_CHAR_UUID =
            UUID.fromString("12345678-1234-1234-1234-1234567890ad");

    // Client Characteristic Configuration Descriptor (CCCD)
    public static final UUID CCCD_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
}