# Theremin Gloves — Definition of Done (Updated)

A story is considered **Done** when all of the following are true:

1. The feature is implemented and `./gradlew :app:assembleDebug` passes with zero errors.
2. The feature works on the physical Pixel 7, not only on the emulator.
3. The feature behaves correctly when both gloves are connected and, where applicable, when the gloves are disconnected.
4. The feature does not crash the app during ordinary use.
5. The feature does not break the screen that contains it under the supported device configuration.
6. Persisted behavior remains correct after app restart for features such as settings, calibration values, and recordings.
7. The code is committed on the working branch and is ready to be reviewed and merged.
8. At least one other team member has reviewed the change before final merge.
9. The branch intended for integration still builds successfully after the change is merged.

## Practical Interpretation For This Project

For Theremin Gloves specifically, a feature should not be considered done if:
- it only works on the emulator but not with the real gloves
- it works once but fails after reconnect, restart, or repeated use
- it breaks Play, Connect, Calibration, Library, or Settings state when moving between screens
- it introduces regressions in audio, BLE, recording, or persistence behavior

## Minimum Verification Checklist

- Build passes
- Pixel 7 check passes
- no obvious crash path
- persistence verified where relevant
- BLE-connected behavior verified where relevant
- disconnected behavior verified where relevant
- review completed
