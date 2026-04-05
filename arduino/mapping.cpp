#include "mapping.h"

AngleTelemetry buildAngleTelemetry(const MotionState& motion) {
  AngleTelemetry t;

  // Delta from neutral using shortest wrapped path
  t.signedDeltaDeg = imuAngleDeltaDeg(motion.outputRollDeg, motion.neutralRollDeg);

  // Direction toggle is applied here.
  // App maps THIS value to pitch/volume ranges.
  t.activeDeltaDeg = t.signedDeltaDeg;
  if (!motion.valueIncreasesForPositiveDelta) {
    t.activeDeltaDeg = -t.activeDeltaDeg;
  }

  return t;
}