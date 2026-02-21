#pragma once

#include <Arduino.h>
#include <ArduinoBLE.h>

// Shared BLE UUIDs for both gloves
const char* bleServiceUuid();
const char* bleTxUuid();
const char* bleRxUuid();

// Init + runtime
bool bleBeginGlove(const char* deviceName);
void blePollNow();
BLEDevice bleGetCentral();

// Send notify/read text (auto-truncated to fit 32-byte BLE string char)
void bleSendLine(const String& msg);
void bleSendLine(const char* msg);

// Read phone -> Arduino write text
bool bleReadIncoming(String& out);

// Current local name (for debug)
const char* bleCurrentDeviceName();