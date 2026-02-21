#include "ble.h"

// ============================================================
// Shared UUIDs (same for pitch + volume glove)
// ============================================================
static const char* kServiceUuid = "12345678-1234-1234-1234-1234567890ab";
static const char* kTxUuid      = "12345678-1234-1234-1234-1234567890ac"; // Arduino -> phone (notify/read)
static const char* kRxUuid      = "12345678-1234-1234-1234-1234567890ad"; // Phone -> Arduino (write/read)

// ============================================================
// BLE objects
// ============================================================
static BLEService gService(kServiceUuid);

static BLEStringCharacteristic gTxChar(
  kTxUuid,
  BLERead | BLENotify,
  32
);

static BLEStringCharacteristic gRxChar(
  kRxUuid,
  BLERead | BLEWrite,
  32
);

static String gDeviceName = "";

const char* bleServiceUuid() { return kServiceUuid; }
const char* bleTxUuid()      { return kTxUuid; }
const char* bleRxUuid()      { return kRxUuid; }

const char* bleCurrentDeviceName() {
  return gDeviceName.c_str();
}

bool bleBeginGlove(const char* deviceName) {
  gDeviceName = deviceName ? deviceName : "";

  if (!BLE.begin()) {
    return false;
  }

  BLE.setLocalName(gDeviceName.c_str());
  BLE.setAdvertisedService(gService);

  gService.addCharacteristic(gTxChar);
  gService.addCharacteristic(gRxChar);
  BLE.addService(gService);

  gTxChar.writeValue("BOOT");
  gRxChar.writeValue("ready");

  BLE.advertise();
  return true;
}

void blePollNow() {
  BLE.poll();
}

BLEDevice bleGetCentral() {
  return BLE.central();
}

void bleSendLine(const String& msg) {
  // BLEStringCharacteristic length is 32.
  // Keep <= 31 chars to stay safe.
  String out = msg;
  if (out.length() > 31) {
    out = out.substring(0, 31);
  }
  gTxChar.writeValue(out);
}

void bleSendLine(const char* msg) {
  bleSendLine(String(msg));
}

bool bleReadIncoming(String& out) {
  if (!gRxChar.written()) return false;
  out = gRxChar.value();
  return true;
}