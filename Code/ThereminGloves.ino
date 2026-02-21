#include <Arduino.h>

#include "ble.h"
#include "imu.h"
#include "mapping.h"

// ============================================================
// ONE SWITCH TO CHOOSE WHICH GLOVE THIS BOARD IS
// Change ONLY this before uploading:
//
// 1 = Pitch glove  (BLE name: ThereminGlove)
// 0 = Volume glove (BLE name: ThereminGloveVol)
// ============================================================
const bool GLOVE_IS_PITCH = 0;

// Role-dependent identity
const char* DEVICE_NAME = GLOVE_IS_PITCH ? "ThereminGlove" : "ThereminGloveVol";
const char* ROLE_NAME   = GLOVE_IS_PITCH ? "PITCH"        : "VOLUME";

// ============================================================
// IMU tuning (same for both gloves)
// ============================================================
ImuTuning imuTuning = {
  0.10f, // smoothingAlpha
  1.5f   // deadzoneDeg
};

// ============================================================
// Runtime state
// ============================================================
MotionState motion;
AngleTelemetry telemetry;

// ============================================================
// BLE timing
// Arduino now streams ANGLES only.
// App decides pitch/volume min/max and angle min/max.
// ============================================================
const unsigned long fastAngleNotifyIntervalMs    = 20;   // ~50 Hz
const unsigned long fastAngleHeartbeatIntervalMs = 200;  // resend even if stable
const float fastAngleNotifyMinChangeDeg          = 0.25f;

const unsigned long debugFieldNotifyIntervalMs   = 80;   // slow readable debug
const unsigned long printIntervalMs              = 50;   // serial line every 50 ms

// Timing state
unsigned long lastFastAngleNotifyMs = 0;
unsigned long lastFastAngleHeartbeatMs = 0;
unsigned long lastDebugFieldNotifyMs = 0;
unsigned long lastPrintMs = 0;
unsigned long lastBlinkMs = 0;

// Last sent fast angle
float lastSentActiveDeltaDeg = -9999.0f;

// Debug field round-robin
uint8_t debugFieldIndex = 0;

// LED state
bool ledState = false;

// ============================================================
// Helpers
// ============================================================
static float absfLocal(float x) {
  return (x < 0.0f) ? -x : x;
}

void blinkStatus(bool connected) {
  unsigned long now = millis();
  unsigned long interval = connected ? 150 : 700;

  if (now - lastBlinkMs >= interval) {
    lastBlinkMs = now;
    ledState = !ledState;
    digitalWrite(LED_BUILTIN, ledState ? HIGH : LOW);
  }
}

void updateTelemetry() {
  telemetry = buildAngleTelemetry(motion);
}

void sendRoleStatus() {
  bleSendLine(GLOVE_IS_PITCH ? "ROLE:PITCH_GLOVE" : "ROLE:VOLUME_GLOVE");
}

void sendModeStatus() {
  // Important: app now decides ALL mapping.
  bleSendLine("MODE:APP_DECIDES_MAPPING");
}

void sendDirectionStatus() {
  bleSendLine(motion.valueIncreasesForPositiveDelta
                ? "DIRECTION:POSITIVE"
                : "DIRECTION:NEGATIVE");
}

void sendNeutralStatus() {
  char msg[32];
  snprintf(msg, sizeof(msg), "NEUTRAL_ROLL_DEG:%.2f", motion.neutralRollDeg);
  bleSendLine(String(msg));
}

void printHelp() {
  Serial.println();
  Serial.println("=== Theremin Gloves (App-Decides Mapping) ===");
  Serial.print("Device name: ");
  Serial.println(DEVICE_NAME);
  Serial.print("Role: ");
  Serial.println(ROLE_NAME);
  Serial.println("Change GLOVE_IS_PITCH (1 or 0) before upload.");
  Serial.println();
  Serial.println("What Arduino does now:");
  Serial.println("  - Reads IMU roll");
  Serial.println("  - Applies smoothing + deadzone + neutral + direction");
  Serial.println("  - Sends ACTIVE_DELTA_DEG to app");
  Serial.println("What app does now:");
  Serial.println("  - Chooses angle min/max");
  Serial.println("  - Chooses freq min/max (pitch glove)");
  Serial.println("  - Chooses volume angle min/max (volume glove)");
  Serial.println();
  Serial.println("Serial commands:");
  Serial.println("  n -> capture neutral");
  Serial.println("  d -> toggle direction");
  Serial.println("  h -> help");
  Serial.println();
  Serial.println("Phone commands (write to RX char):");
  Serial.println("  N -> capture neutral");
  Serial.println("  D -> toggle direction");
  Serial.println("  H -> send role/mode/direction");
  Serial.println();
  Serial.println("Fast BLE stream:");
  Serial.println("  ACTIVE_DELTA_DEG:x.xx   (app should use this)");
  Serial.println("Slow BLE debug stream:");
  Serial.println("  full field names (round-robin)");
  Serial.println();
}

void captureNeutralNow(const char* sourceLabel) {
  imuCaptureNeutral(motion);
  updateTelemetry();

  Serial.print(sourceLabel);
  Serial.print(" neutral captured at: ");
  Serial.print(motion.neutralRollDeg, 2);
  Serial.println(" deg");

  sendNeutralStatus();
}

void toggleDirectionNow(const char* sourceLabel) {
  imuToggleDirection(motion);
  updateTelemetry();

  Serial.print(sourceLabel);
  Serial.print(" direction -> ");
  Serial.println(motion.valueIncreasesForPositiveDelta ? "POSITIVE_DELTA_UP" : "NEGATIVE_DELTA_UP");

  sendDirectionStatus();
}

void handleSerialCommands() {
  while (Serial.available()) {
    char c = (char)Serial.read();

    if (c == 'n' || c == 'N') {
      captureNeutralNow("Serial");
    } else if (c == 'd' || c == 'D') {
      toggleDirectionNow("Serial");
    } else if (c == 'h' || c == 'H') {
      printHelp();
    }
  }
}

void handlePhoneWritesAndEcho() {
  String incoming;
  if (!bleReadIncoming(incoming)) return;

  Serial.print("Phone wrote: ");
  Serial.println(incoming);

  // Echo back for debugging
  String echo = "ECHO:" + incoming;
  bleSendLine(echo);

  Serial.print("Echoed: ");
  Serial.println(echo);

  if (incoming.equalsIgnoreCase("N")) {
    captureNeutralNow("Remote");
  } else if (incoming.equalsIgnoreCase("D")) {
    toggleDirectionNow("Remote");
  } else if (incoming.equalsIgnoreCase("H")) {
    sendRoleStatus();
    sendModeStatus();
    sendDirectionStatus();
    sendNeutralStatus();
  }
}

void maybeSendFastAngle() {
  unsigned long now = millis();

  bool intervalElapsed = (now - lastFastAngleNotifyMs >= fastAngleNotifyIntervalMs);
  bool heartbeatElapsed = (now - lastFastAngleHeartbeatMs >= fastAngleHeartbeatIntervalMs);
  bool changedEnough = absfLocal(telemetry.activeDeltaDeg - lastSentActiveDeltaDeg) >= fastAngleNotifyMinChangeDeg;

  if ((intervalElapsed && changedEnough) || heartbeatElapsed) {
    char msg[32];
    snprintf(msg, sizeof(msg), "ACTIVE_DELTA_DEG:%.2f", telemetry.activeDeltaDeg);
    bleSendLine(String(msg));

    lastSentActiveDeltaDeg = telemetry.activeDeltaDeg;
    lastFastAngleNotifyMs = now;
    lastFastAngleHeartbeatMs = now;
  }
}

void maybeSendSlowDebugField() {
  unsigned long now = millis();
  if (now - lastDebugFieldNotifyMs < debugFieldNotifyIntervalMs) return;
  lastDebugFieldNotifyMs = now;

  char msg[32];

  switch (debugFieldIndex) {
    case 0:
      snprintf(msg, sizeof(msg), "RAW_ROLL_DEG:%.2f", motion.rawRollDeg);
      bleSendLine(String(msg));
      break;

    case 1:
      snprintf(msg, sizeof(msg), "CONTROL_ROLL_DEG:%.2f", motion.controlRollDeg);
      bleSendLine(String(msg));
      break;

    case 2:
      snprintf(msg, sizeof(msg), "FILTERED_ROLL_DEG:%.2f", motion.filteredRollDeg);
      bleSendLine(String(msg));
      break;

    case 3:
      snprintf(msg, sizeof(msg), "OUTPUT_ROLL_DEG:%.2f", motion.outputRollDeg);
      bleSendLine(String(msg));
      break;

    case 4:
      snprintf(msg, sizeof(msg), "NEUTRAL_ROLL_DEG:%.2f", motion.neutralRollDeg);
      bleSendLine(String(msg));
      break;

    case 5:
      snprintf(msg, sizeof(msg), "SIGNED_DELTA_DEG:%.2f", telemetry.signedDeltaDeg);
      bleSendLine(String(msg));
      break;

    case 6:
      snprintf(msg, sizeof(msg), "ACTIVE_DELTA_DEG:%.2f", telemetry.activeDeltaDeg);
      bleSendLine(String(msg));
      break;

    case 7:
      sendDirectionStatus();
      break;

    case 8:
      sendRoleStatus();
      break;

    case 9:
      sendModeStatus();
      break;

    case 10:
      sendNeutralStatus();
      break;
  }

  debugFieldIndex++;
  if (debugFieldIndex > 10) {
    debugFieldIndex = 0;
  }
}

void debugPrintLine(bool isConnected) {
  unsigned long now = millis();
  if (now - lastPrintMs < printIntervalMs) return;
  lastPrintMs = now;

  Serial.print("device=");
  Serial.print(DEVICE_NAME);

  Serial.print("\trole=");
  Serial.print(ROLE_NAME);

  Serial.print("\tBLE=");
  Serial.print(isConnected ? "ON" : "OFF");

  Serial.print("\traw=");
  Serial.print(motion.rawRollDeg, 2);

  Serial.print("\tcontrol=");
  Serial.print(motion.controlRollDeg, 2);

  Serial.print("\tfiltered=");
  Serial.print(motion.filteredRollDeg, 2);

  Serial.print("\toutput=");
  Serial.print(motion.outputRollDeg, 2);

  Serial.print("\tneutral=");
  Serial.print(motion.neutralRollDeg, 2);

  Serial.print("\tsignedDelta=");
  Serial.print(telemetry.signedDeltaDeg, 2);

  Serial.print("\tactiveDelta=");
  Serial.print(telemetry.activeDeltaDeg, 2);

  Serial.print("\tdir=");
  Serial.print(motion.valueIncreasesForPositiveDelta ? "+" : "-");

  Serial.println(); // IMPORTANT: new line each print
}

void setup() {
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, LOW);

  Serial.begin(115200);
  delay(500);

  imuInitMotionState(motion);

  Serial.println();
  Serial.println("Theremin Gloves - Unified Sketch (App-decides mapping)");
  Serial.print("Compiling as: ");
  Serial.println(GLOVE_IS_PITCH ? "PITCH GLOVE" : "VOLUME GLOVE");
  Serial.print("BLE device name: ");
  Serial.println(DEVICE_NAME);
  Serial.println("IMU lib: Arduino_BMI270_BMM150");
  printHelp();

  if (!imuBegin()) {
    Serial.println("ERROR: IMU.begin() failed");
    Serial.println("Check board selection + IMU library install");
    while (1) {
      blinkStatus(false);
      delay(50);
    }
  }
  Serial.println("IMU OK");

  if (!bleBeginGlove(DEVICE_NAME)) {
    Serial.println("ERROR: BLE.begin() failed");
    while (1) {
      blinkStatus(false);
      delay(50);
    }
  }

  Serial.print("BLE advertising as: ");
  Serial.println(DEVICE_NAME);
  Serial.print("Service UUID: ");
  Serial.println(bleServiceUuid());
  Serial.print("TX UUID: ");
  Serial.println(bleTxUuid());
  Serial.print("RX UUID: ");
  Serial.println(bleRxUuid());

  // Build initial telemetry
  updateTelemetry();

  Serial.println("Ready.");
}

void loop() {
  blePollNow();
  handleSerialCommands();

  imuUpdateRoll(motion, imuTuning);
  updateTelemetry();

  BLEDevice central = bleGetCentral();

  if (central) {
    Serial.print("Connected: ");
    Serial.print(central.address());
    Serial.print(" (device=");
    Serial.print(DEVICE_NAME);
    Serial.println(")");

    // Reset schedulers on new connection
    lastFastAngleNotifyMs = 0;
    lastFastAngleHeartbeatMs = 0;
    lastDebugFieldNotifyMs = 0;
    lastSentActiveDeltaDeg = -9999.0f;
    debugFieldIndex = 0;

    // Initial status burst
    sendRoleStatus();
    sendModeStatus();
    sendDirectionStatus();
    sendNeutralStatus();

    while (central.connected()) {
      blePollNow();

      handleSerialCommands();
      imuUpdateRoll(motion, imuTuning);
      updateTelemetry();

      handlePhoneWritesAndEcho();
      maybeSendFastAngle();
      maybeSendSlowDebugField();

      debugPrintLine(true);
      blinkStatus(true);
    }

    Serial.print("Disconnected (device=");
    Serial.print(DEVICE_NAME);
    Serial.println(")");
  } else {
    // Keep IMU + serial alive for tuning even when phone is not connected
    debugPrintLine(false);
    blinkStatus(false);
  }
}