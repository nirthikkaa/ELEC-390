# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

An Android app that turns BLE-connected IMU gloves into a theremin instrument. Two gloves connect over BLE: the **pitch glove** (`ThereminGlove`) maps wrist roll angle to frequency, and the **volume glove** (`ThereminGloveVol`) maps wrist roll angle to amplitude. The app synthesizes audio in real time using `AudioTrack`.

## Build & Run Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Install to connected device
./gradlew installDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run a specific unit test class
./gradlew test --tests "com.example.thereminglovestest2.ExampleUnitTest"

# Check for lint issues
./gradlew lint
```

- **compileSdk / targetSdk**: 36
- **minSdk**: 24
- **Java**: 17 (required by Nordic BLE 2.11.x)
- **Key dependency**: `no.nordicsemi.android:ble:2.11.0`

## Architecture

### Activity flow
```
LaunchActivity → HomeActivity → MainActivity (Play screen)
                             → ConnectGlovesActivity
                             → CalibrationActivity
                             → LibraryActivity
                             → SettingsActivity
```

`LaunchActivity` is the entry point (launcher). `HomeActivity` handles BLE permissions/Bluetooth enablement and auto-connects; once both gloves connect it auto-navigates to `MainActivity`.

### Core subsystems

**`BleSessionManager`** (static singleton, `BleSessionManager.java`)
- All BLE state is owned here: scanning, two `Glove` instances (`PITCH`, `VOLUME`), event log, watchdog timer.
- All public API is `public static void request*()` — callers post work to the main thread via `Handler MAIN`.
- Exposes `getSnapshot()` which returns an immutable `BleSnapshot` value object for the UI to read.
- A 1 s watchdog (`WATCHDOG`) detects stale telemetry and triggers auto-reconnect.
- `ThereminGloveBleManager` (nested in the same file) wraps the Nordic `BleManager` and exposes a `Listener` interface. BLE UUIDs are fixed constants: service `12345678-1234-1234-1234-1234567890ab`, TX char `...ac`, RX char `...ad`.
- Glove → phone packets: `ACTIVE_DELTA_DEG:<float>`, `NEUTRAL_ROLL_DEG:<float>`, `DIRECTION:<POSITIVE|NEGATIVE>`.
- Phone → glove commands: `H` (handshake), `N` (capture neutral), `D` (toggle direction).

**`ThereminAudioEngine`** (`ThereminAudioEngine.java`)
- Owns an `AudioTrack` and runs a dedicated audio thread (`ThereminAudioThread`).
- Exposes `setTargets(freqHz, volumeLinear)` — the engine smooths toward targets each sample using `FREQ_SMOOTHING`, `ATTACK_SMOOTHING`, `RELEASE_SMOOTHING` constants.
- Supports four tone types (defined in `AppSettings`): `SINE`, `SQUARE`, `TRIANGLE`, `SAW`.
- Vibrato depth scales with volume to emulate natural playing dynamics.
- Provides `getVisualizerSnapshot()` (lock-protected) for the UI waveform display.

**`ThereminBackgroundAudioService`** (`ThereminBackgroundAudioService.java`)
- Foreground service (type `mediaPlayback`) that keeps audio running when the Play screen is hidden.
- Owns its own `ThereminAudioEngine` instance and a sync thread (20 ms tick) that polls `BleSessionManager.getSnapshot()` and pushes targets.
- `MainActivity` hands off audio to the service on `onPause` and reclaims it on resume.
- Static `calibrationPreviewSettings` allows `CalibrationActivity` to preview sound without stopping background audio.

**`PlayMappingState`** (`PlayMappingState.java`)
- Pure data + computation class (no Android dependencies except `Context` for the extended-freq-range pref).
- `recompute(BleSnapshot)` performs the linear mapping from glove angle delta → frequency/volume.
- Frequency/angle seekbar values use integer progress that maps through `progressToAngle`/`progressToFreq`.

**`SettingsStore`** (`SettingsStore.java`)
- Extends `SQLiteOpenHelper`; stores theremin mapping settings in a single-row SQLite table (`theremin_gloves.db`, table `app_settings`).
- Uses additive `ALTER TABLE … ADD COLUMN` migration (no destructive upgrades).
- Small boolean flags (background audio, extended freq range, calibration guide learned) live in `SharedPreferences`.
- `AppSettings` (same file) is a plain data class with default constants.

### UI patterns
- All screens use **ViewBinding** — binding classes are generated from `activity_*.xml` layouts.
- Each activity uses a `NavigationUtils.Poller` to tick UI refreshes on a fixed interval (80–200 ms) rather than observing live callbacks.
- `TopNavBarView` and `BottomNavBarView` are reusable custom views included in layouts.
- `ThereminVisualizerView` draws a downsampled waveform from `VisualizerSnapshot`.
- Colors for connection chips are set programmatically in `MainActivity.applyConnectionChip()`.

### Calibration flow
`CalibrationActivity` uses `CalibrationDraft` (a local mutable state mirror of `AppSettings`) and calls `ThereminBackgroundAudioService.beginCalibrationPreview` / `endCalibrationPreview` to route audio through the calibration settings live.

### Settings persistence decision
Direction inversion is stored in two places: `SettingsStore` (SQLite) and `BleSessionManager` static fields. `BleSessionManager.setDesiredDirection()` updates both and immediately syncs the glove via BLE. `SettingsActivity` calls `BleSessionManager.requestToggleDirection()` for the same effect.