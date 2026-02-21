#include "imu.h"
#include <Arduino_BMI270_BMM150.h>
#include <math.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

void imuInitMotionState(MotionState& s) {
  s.rawRollDeg = 0.0f;
  s.controlRollDeg = 0.0f;
  s.filteredRollDeg = 0.0f;
  s.outputRollDeg = 0.0f;
  s.neutralRollDeg = 0.0f;
  s.firstSample = true;
  s.valueIncreasesForPositiveDelta = true;
}

bool imuBegin() {
  return IMU.begin();
}

float imuWrapAngle180(float a) {
  while (a >= 180.0f) a -= 360.0f;
  while (a < -180.0f) a += 360.0f;
  return a;
}

float imuAngleDeltaDeg(float currentDeg, float referenceDeg) {
  return imuWrapAngle180(currentDeg - referenceDeg);
}

void imuCaptureNeutral(MotionState& s) {
  s.neutralRollDeg = s.filteredRollDeg;
}

void imuToggleDirection(MotionState& s) {
  s.valueIncreasesForPositiveDelta = !s.valueIncreasesForPositiveDelta;
}

void imuUpdateRoll(MotionState& s, const ImuTuning& t) {
  float ax, ay, az;

  if (!IMU.accelerationAvailable()) return;

  IMU.readAcceleration(ax, ay, az);

  // Accel-only roll (drift-free)
  s.rawRollDeg = atan2f(ay, az) * 180.0f / (float)M_PI;

  // Flip sign so clockwise (user perspective) can be positive
  s.controlRollDeg = -s.rawRollDeg;

  // First sample initializes everything
  if (s.firstSample) {
    s.filteredRollDeg = s.controlRollDeg;
    s.outputRollDeg = s.filteredRollDeg;
    s.neutralRollDeg = s.filteredRollDeg; // auto-neutral at boot
    s.firstSample = false;
    return;
  }

  // Exponential smoothing
  s.filteredRollDeg = s.filteredRollDeg + t.smoothingAlpha * (s.controlRollDeg - s.filteredRollDeg);

  // Deadzone around neutral (wrapped delta)
  float deltaToNeutral = imuAngleDeltaDeg(s.filteredRollDeg, s.neutralRollDeg);
  if (fabsf(deltaToNeutral) < t.deadzoneDeg) {
    s.outputRollDeg = s.neutralRollDeg;
  } else {
    s.outputRollDeg = imuWrapAngle180(s.filteredRollDeg);
  }
}