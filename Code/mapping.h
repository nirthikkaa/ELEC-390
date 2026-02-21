#pragma once

#include <Arduino.h>
#include "imu.h"

// What the app needs to do its own mapping
struct AngleTelemetry {
  float signedDeltaDeg; // shortest delta from neutral (can be + or -)
  float activeDeltaDeg; // direction-adjusted delta (what app should map)
};

// Build app-facing angle telemetry from current motion state
AngleTelemetry buildAngleTelemetry(const MotionState& motion);