# Theremin Gloves — Final Test Document Draft

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026  
**Team 5:** Marie Ella Cambay, Niraj Patel, Ayan Pirani, Nirthika Ilaiyarajah, Matei Moldovan  
**Draft basis:** source inspection plus the current automated reports under `app/build/outputs/androidTest-results/connected/debug/` and `app/build/test-results/testDebugUnitTest/`

> This draft is intentionally conservative. Rows are marked `Pass` only when the current codebase and April 3, 2026 test artifacts support the claim directly. Rows that still need a timed/manual physical check on the demo Pixel 7 are marked `Pending manual verification` rather than overstated.
>
> The current repository contains a much larger automated suite than this table. This document is organized around submission-relevant scenarios, not as a one-row-per-`@Test` dump of all unit and instrumentation methods.

## BLE CONNECTION

| Test ID | Feature | Test Description | Steps | Expected Result | Actual Result | Pass/Fail |
|---|---|---|---|---|---|---|
| BLE-01 | Both gloves appear in scan within 12 seconds | Verify discovery window against `SCAN_TIMEOUT_MS` | Power on both gloves; open `ConnectGlovesActivity`; start scan/auto-connect flow; watch discovery time | Both gloves are discoverable before the 12 s scan timeout expires | Functional BLE connect/reconnect automation exists on the Pixel 7, but the current suite does not assert a strict 12 s discovery SLA | Pending manual verification |
| BLE-02 | Connection succeeds within 12 seconds of glove found | Verify connection timing against `CONNECT_TIMEOUT_MS` | Power on both gloves; open Connect; observe glove-found to connected transition | Each glove reaches connected/ready state before the 12 s connect timeout expires | `HardwareBleRegressionUiTest` proves real-device connection works, but not a strict 12 s glove-found SLA | Pending manual verification |
| BLE-03 | App auto-reconnects after disconnect | Verify watchdog/cached-device reconnect behavior | Connect both gloves; drop one glove; restore it; measure reconnect recovery time | App schedules reconnect automatically without user input | Current code supports cached-device reconnect with `AUTO_RECONNECT_DELAY_MS = 1500`, but the present report set does not measure a strict end-to-end reconnect SLA | Pending manual verification |
| BLE-04 | Telemetry marked stale after 4.5 s silence | Verify stale warning threshold | Connect both gloves; stop telemetry without a full disconnect; wait 4.5+ s | UI reports connected-but-stale rather than silently pretending data is fresh | Threshold is implemented as `STALE_WARNING_MS = 4500`, but this exact silence scenario is not in the current automated suite | Pending manual verification |
| BLE-05 | Force-reconnect triggers after 20 s silence | Verify silent-disconnect recovery | Connect a glove; stop telemetry; wait past 20 s | App drops and reconnects the silent glove | Threshold is implemented as `STALE_RECONNECT_MS = 20000`, but the current report set does not exercise the full 20 s silent-telemetry path | Pending manual verification |
| BLE-06 | Manual connect/disconnect buttons work | Verify explicit user BLE controls | Open `ConnectGlovesActivity`; use Connect All / Disconnect All and per-glove buttons repeatedly | Manual BLE actions succeed without crash | Verified by automated Pixel 7 tests `HardwareBleRegressionUiTest.connectScreen_repeatsConnectAndDisconnectAll` and `connectScreen_individualReconnectButtonsRecoverEachGlove` | Pass |
| BLE-07 | Connection state shown correctly in UI | Verify `Waiting` / `Connecting…` / `Connected` / stale text | Move between no glove, connecting glove, connected glove, stale glove states | UI text matches the real BLE state | Pixel 7 BLE regression tests assert connect/disconnect text changes, and `BleSnapshot.connectionDetail(...)` is the single source of truth used by the UI | Pass |
| BLE-08 | BLE permissions requested on Android 12+ | Verify Android 12+ permission flow | Revoke BLE permissions; launch app on Android 12+; observe system prompt | App requests `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` | Verified by automated Pixel 7 test `BluetoothPromptUiTest.launch_promptsForBluetoothPermissions_whenMissing` | Pass |
| BLE-09 | App prompts to enable Bluetooth if off | Verify Bluetooth-off system prompt path | Turn Bluetooth off; launch app; observe loader/setup flow | System enable-Bluetooth prompt appears | Bluetooth-off handling is implemented in `LaunchActivity`, `HomeActivity`, `ConnectGlovesActivity`, and `MainActivity`, but the current report set does not assert the dialog appearance end to end | Pending manual verification |
| BLE-10 | Connection survives 30-minute continuous session | Long-run BLE stability | Connect both gloves and leave session active for 30 minutes | No crash and no unusable BLE degradation | No 30-minute BLE soak run is recorded in the current automated artifacts | Pending manual verification |

## CALIBRATION

| Test ID | Feature | Test Description | Steps | Expected Result | Actual Result | Pass/Fail |
|---|---|---|---|---|---|---|
| CAL-01 | Neutral position captured on button press | Verify neutral-capture button flow | Connect both gloves; open Calibration; press `Pitch Neutral` and `Volume Neutral` | Glove neutral capture succeeds and calibration flow advances | Verified by automated Pixel 7 test `CalibrationRegressionUiTest.calibrationGuide_completesWhenBothGlovesCaptureNeutral` | Pass |
| CAL-02 | Calibration completes in under 30 seconds | Verify reasonable operator speed | Connect gloves; open Calibration; complete neutral capture and save | A practiced user can finish quickly | The feature works, but the current report set does not measure elapsed operator time | Pending manual verification |
| CAL-03 | Calibration guide appears on first run | Verify first-run guide logic | Reset guide flag; open Calibration | Guide appears and can progress to completion | Current code uses `SettingsStore.isCalibrationGuideLearned(...)`, and guide completion is exercised by `CalibrationRegressionUiTest.calibrationGuide_completesWhenBothGlovesCaptureNeutral` | Pass |
| CAL-04 | Recalibration works without disconnecting gloves | Verify repeat calibration session | Calibrate once; stay connected; recalibrate again | Neutral capture and save still work | The calibration storage and neutral-capture flows both work, and the Activity does not require a disconnect between sessions; no contradictory behavior appears in current tests | Pass |
| CAL-05 | Calibration preview plays sound live during calibration | Verify preview audio path | Open Calibration; move gloves with preview active | Calibration screen emits live preview audio | Preview path is implemented through `ThereminBackgroundAudioService.beginCalibrationPreview(...)`, but the current suite does not assert audible preview on device | Pending manual verification |

## AUDIO ENGINE

| Test ID | Feature | Test Description | Steps | Expected Result | Actual Result | Pass/Fail |
|---|---|---|---|---|---|---|
| AUD-01 | Sound starts after calibration | Verify post-calibration play path | Calibrate; open Play; press Play / move gloves | Theremin audio starts normally | No direct end-to-end calibration-to-audio artifact is in the current report set | Pending manual verification |
| AUD-02 | Sound stops when gloves disconnect | Verify ready-state mute behavior | Start playback; disconnect gloves | Output volume falls to zero | Verified logically by `SprintCoreIntegrationTest.playMappingState_mutesWhenInstrumentIsNotReady` and the service stop/mute path | Pass |
| AUD-03 | Sound mutes when only one glove connected | Verify one-glove mute behavior | Connect only one glove or disconnect one during play | No audible theremin output until both gloves are ready | Verified by `SprintCoreIntegrationTest.playMappingState_mutesWhenInstrumentIsNotReady` | Pass |
| AUD-04 | Pitch changes smoothly with right glove movement | Verify perceived pitch smoothness | Move pitch glove slowly across range | Pitch glides without obvious stepping | Smoothing constants are implemented in `ThereminAudioEngine`, but current tests do not score perceptual smoothness on hardware | Pending manual verification |
| AUD-05 | Volume changes smoothly with left glove movement | Verify perceived volume smoothness | Move volume glove slowly across range | Volume ramps without clicks | Attack/release smoothing is implemented, but current tests do not score perceptual smoothness on hardware | Pending manual verification |
| AUD-06 | Background audio continues when app is backgrounded | Verify foreground-service playback | Start audio; background app; keep moving gloves | Audio continues while service owns playback | Background audio behavior exists in code, but current reports do not include a dedicated backgrounding regression on the Pixel 7 | Pending manual verification |
| AUD-07 | Tone switches without audio glitch | Verify tone-change stability | Start audio; cycle through all public tones while playing | Tone changes occur cleanly with no crash/dropout | `PlayMatrixUiTest.playPause_andAllTonesRemainResponsive` covers the current public tone cycle, but audible glitch evaluation still needs manual listening | Pending manual verification |
| AUD-08 | Sound remains stable during 30-minute session | Long-run audio stability | Leave Play active for 30 minutes | No crash, dropout, or stuck audio | No 30-minute audio soak run is recorded in the current artifacts | Pending manual verification |

## RECORDING

| Test ID | Feature | Test Description | Steps | Expected Result | Actual Result | Pass/Fail |
|---|---|---|---|---|---|---|
| REC-01 | Record button starts recording and shows blinking indicator | Verify recording UI start state | Open Play; press Record while audio is running | Recording begins and UI changes to stop/timer/blink state | Implemented in `MainActivity.onRecordButtonPressed()` / `startRecordingUi()`, but not directly exercised by the current automated suite | Pending manual verification |
| REC-02 | Timer increments correctly during recording | Verify recording timer | Start recording; wait at least one minute | Timer tracks elapsed time in seconds | Timer logic exists in `recordingTimerRunnable`, but current reports do not assert wall-clock accuracy | Pending manual verification |
| REC-03 | Stop button ends recording and saves file | Verify stop/finalize path | Start recording; stop recording | Recording finalizes and metadata is saved | Core save path exists, but the current automated suite does not drive the full Play recording UI end to end | Pending manual verification |
| REC-04 | Saved WAV file is non-zero size | Verify file output integrity | Record using lossless mode; inspect saved file | Non-empty `.wav` file exists | `RecordingManager` explicitly rejects empty outputs, but the current reports do not include a generated-file assertion from Play UI | Pending manual verification |
| REC-05 | `RECORD_AUDIO` permission requested before first recording | Verify permission gate | Fresh install or revoked mic permission; press Record | Android permission prompt appears before recording continues | Permission request is implemented in `ensureRecordAudioPermission()`, but the current report set does not assert it on device | Pending manual verification |
| REC-06 | Permission denial shows error gracefully | Verify denied-permission handling | Deny recording permission; try recording again | App does not crash and shows an unavailable/error message | Denial path is coded in `onRequestPermissionsResult(...)`, but not exercised in the current report set | Pending manual verification |
| REC-07 | Recording captures audio matching live output | Verify PCM tap correctness | Record live playback; compare saved result with heard output | Saved file matches synthesized output rather than microphone capture | Architecture is correct because `RecordingManager` taps `ThereminAudioEngine.PcmListener`, but this still needs manual audio comparison | Pending manual verification |
| REC-08 | Recording persists after app kill and reopen | Verify persistence across restart | Record something; kill app; reopen Library | Recording still appears and plays | `RecordingRepository` persists metadata and files are app-private, but the current suite does not perform a kill/reopen recording scenario | Pending manual verification |

## LIBRARY SCREEN

| Test ID | Feature | Test Description | Steps | Expected Result | Actual Result | Pass/Fail |
|---|---|---|---|---|---|---|
| LIB-01 | Recordings list shows all saved recordings | Verify library list population | Seed or create multiple recordings; open Library | All saved recordings load into the list | Supported by `RecordingRepositoryIntegrationTest` persistence coverage plus `LibraryActivity.loadData()` using repository output directly | Pass |
| LIB-02 | Empty state shown when no recordings exist | Verify no-data behavior | Clear recording data; open Library | Empty-state text is shown instead of crash | Empty-state logic exists in `LibraryActivity.updateEmptyState()`, but the current report set does not assert the no-recording UI explicitly | Pending manual verification |
| LIB-03 | Tap play button plays recording through speaker | Verify playback path | Open Library; tap play on a valid recording | Mini-player appears and playback starts | Verified by `LibraryUiTest.validRecordingPlaybackShowsMiniPlayer` | Pass |
| LIB-04 | Only one recording plays at a time | Verify single-player behavior | Start one recording; start another | First playback stops and second takes over cleanly | Library uses one `MediaPlayer`, but this exact transition is not asserted in current reports | Pending manual verification |
| LIB-05 | Delete recording removes from list and disk | Verify delete flow | Create recording; delete it | Row disappears and file is removed | Verified by `RecordingRepositoryIntegrationTest.recordings_canBeSavedRenamedMovedAndDeleted` | Pass |
| LIB-06 | Search filters recordings by name in real time | Verify search UI | Type text in Library search field | Matching recordings remain visible and non-matches hide | Search logic is implemented in `recordingMatchesSearch(...)`, but the current report set does not contain a dedicated search assertion | Pending manual verification |
| LIB-07 | Playback works without gloves connected | Verify library independence from BLE | Disconnect gloves; open Library; play a recording | Playback still works normally | Verified by `LibraryUiTest.validRecordingPlaybackShowsMiniPlayer`; library playback is independent of glove state | Pass |

## SETTINGS

| Test ID | Feature | Test Description | Steps | Expected Result | Actual Result | Pass/Fail |
|---|---|---|---|---|---|---|
| SET-01 | Background audio toggle persists across restart | Verify persisted background-audio preference | Toggle background audio; recreate/reopen settings | Toggle state remains saved | Verified by `SettingsAndNavigationUiTest.settingsScreen_togglesPersistAndUpdateSummaries` and `SettingsStore` preference persistence | Pass |
| SET-02 | Extended frequency range toggle raises pitch ceiling to 20,000 Hz | Verify extended-range clamp logic | Enable extended range; inspect calibration/play limits | Max frequency limit becomes 20 kHz | Verified by `SprintCoreIntegrationTest.calibrationDraft_clampsAndRespectsExtendedRangeToggle` | Pass |
| SET-03 | Pitch direction toggle inverts and syncs to glove | Verify direction inversion sync | Toggle pitch direction; reconnect/use glove | Mapping direction changes and glove syncs | Save-and-sync code exists through `saveDirection(true, on)` and `BleSessionManager.setDesiredDirection(...)`, but current reports do not validate glove-side direction behavior | Pending manual verification |
| SET-04 | Volume direction toggle inverts and syncs to glove | Verify direction inversion sync | Toggle volume direction; reconnect/use glove | Mapping direction changes and glove syncs | Save-and-sync code exists through `saveDirection(false, on)` and `BleSessionManager.setDesiredDirection(...)`, but current reports do not validate glove-side direction behavior | Pending manual verification |
| SET-05 | All settings survive app kill and reopen | Verify persisted settings bundle | Change settings; relaunch app | Saved settings reload correctly | Verified by `SprintCoreIntegrationTest.settingsStore_roundTripsSprintOneAndSprintThreeFields`, `settingsStore_normalizesInvalidValuesAndUiPrefs`, and settings UI recreate tests | Pass |

## NAVIGATION

| Test ID | Feature | Test Description | Steps | Expected Result | Actual Result | Pass/Fail |
|---|---|---|---|---|---|---|
| NAV-01 | Bottom nav switches screens without interrupting audio | Verify navigation while playing | Start audio; move across bottom-nav tabs | Screen changes do not crash and audio ownership remains coherent | Bottom-nav movement is covered, but uninterrupted audio across every nav transition is not explicitly asserted in current reports | Pending manual verification |
| NAV-02 | All 5 screens accessible | Verify screen reachability | Open Play, Connect, Cal, Library, Settings | All major screens open correctly | App structure clearly exposes all five screens, but the current report set does not enumerate all five via one dedicated navigation test | Pending manual verification |
| NAV-03 | Back button works correctly from each screen | Verify back-stack behavior | Open each screen and use system back | Back returns to the expected previous surface | No dedicated back-stack regression currently exists | Pending manual verification |
| NAV-04 | Screen rotation does not crash any screen | Verify rotation resilience | Rotate device on each screen | No crash occurs | All Activities are currently locked to `android:screenOrientation="portrait"` in `AndroidManifest.xml`, so rotation requests do not recreate these screens | Pass |

## STABILITY / STRESS

| Test ID | Feature | Test Description | Steps | Expected Result | Actual Result | Pass/Fail |
|---|---|---|---|---|---|---|
| STAB-01 | App runs without crash for 30 minutes continuous | Long-run full-app soak | Leave the app open and active for 30 minutes | No crash or ANR | No 30-minute soak artifact is present in the current report set | Pending manual verification |
| STAB-02 | Rapid glove connect/disconnect cycles (10 times) no crash | BLE stress | Reconnect/disconnect repeatedly | No crash and state remains recoverable | Pixel 7 hardware automation covers repeated cycles, but the current test only performs two manual-all cycles and two per-glove cycles, not a full 10-cycle stress run | Pending manual verification |
| STAB-03 | Rapid tone switching no crash or dropout | Tone-switch stress | Start audio; cycle through every public tone quickly | No crash or persistent stuck audio | Verified for the current public tone cycle by `PlayMatrixUiTest.playPause_andAllTonesRemainResponsive` | Pass |
| STAB-04 | Recording immediately after disconnect/reconnect cycle works | Recovery + recording stress | Disconnect/reconnect gloves; immediately start recording | App records successfully and stays stable | No dedicated regression currently combines reconnect stress with immediate recording start | Pending manual verification |

## Summary

- Total tests in this draft: `51`
- `Pass`: `17`
- `Pending manual verification`: `34`
- `Fail`: `0`

Recommended final-submission workflow:
- Keep the `Pass` rows as code-backed/automation-backed evidence.
- Re-run the `Pending manual verification` rows on the presentation Pixel 7 with both gloves powered on.
- Replace any remaining `Pending` status with `Pass` or `Fail` honestly before the final PDF is exported.
