#pragma once

#include <Arduino.h>

// IMU roll state shared by both gloves
struct MotionState {
  float rawRollDeg;          // atan2(ay, az) convention
  float controlRollDeg;      // sign-flipped for user-friendly direction
  float filteredRollDeg;     // smoothed control roll
  float outputRollDeg;       // after deadzone
  float neutralRollDeg;      // captured neutral reference

  bool firstSample;
  bool valueIncreasesForPositiveDelta; // direction toggle (D command)
};

struct ImuTuning {
  float smoothingAlpha; // 0.05..0.20 typical
  float deadzoneDeg;    // deadzone around neutral
};

void imuInitMotionState(MotionState& s);
bool imuBegin();
void imuUpdateRoll(MotionState& s, const ImuTuning& t);

void imuCaptureNeutral(MotionState& s);
void imuToggleDirection(MotionState& s);

// Helpers used by debug + app-facing angle telemetry
float imuWrapAngle180(float a);
float imuAngleDeltaDeg(float currentDeg, float referenceDeg);