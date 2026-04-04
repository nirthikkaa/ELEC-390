# Theremin Gloves

An Android app that turns two BLE IMU gloves into a real-time theremin instrument. The right glove controls pitch and the left glove controls volume by measuring wrist roll angle via the onboard IMU, streaming data over Bluetooth Low Energy from Arduino Nano 33 BLE Sense boards.

**Course:** COEN 390 / ELEC 390 — Concordia University, Winter 2026  
**Team 5:** Niraj Patel, Ayan Pirani, Marie Ella Cambay, Nirthika Ilaiyarajah, Matei Moldovan

---

## Features

- **Real-time BLE theremin** — dual-glove IMU streaming at 20 ms sync rate, <91 ms end-to-end latency
- **11 synthesized tones** — Theremin, Air Pad, Cello, Pad, Choir, Flute, Clarinet, Triangle, Saw, Square, Helicopter
- **Audio effects pipeline** — Reverb (Schroeder comb), Delay (ring buffer), Distortion (tanh saturation)
- **Scale lock** — Chromatic, Major, Minor, Pentatonic quantization
- **Octave shift** — ±2 octaves
- **Beat Maker** — 8 preset drum patterns (Rock, Funk, EDM, Hip-Hop, Reggae, Jazz, Trap, Latin/Samba), piano sequencer, BPM control
- **Recording** — PCM tap from synth engine → Lossless WAV or AAC (High/Medium/Low)
- **Library** — manage recordings with search, folders, drag-to-reorder, mini-player, loop mode
- **Calibration** — per-glove neutral capture, min/max angle and frequency range tuning
- **Sensitivity presets** — adjustable response curve (0.25 very responsive → 2.50 precise)
- **Background audio** — foreground service keeps playback alive when leaving the Play screen
- **In-app user manual** — full offline manual accessible from Settings

---

## Requirements

- Android **API 31+** (Android 12 or higher)
- Two BLE gloves (Arduino Nano 33 BLE Sense)
  - Pitch glove must advertise as **`ThereminGlove`**
  - Volume glove must advertise as **`ThereminGloveVol`**

---

## Build

```bash
./gradlew assembleDebug          # build APK
./gradlew installDebug           # install to connected device
./gradlew test                   # unit tests
./gradlew connectedAndroidTest   # instrumented tests (requires device/emulator)
./gradlew lint
```

| Property | Value |
|---|---|
| compileSdk / targetSdk | 36 |
| minSdk | 31 |
| Java | 17 |
| Key dependency | `no.nordicsemi.android:ble:2.11.0` |

---

## How It Works

1. **Launch** — `LaunchActivity` warms dependencies and checks Bluetooth permissions.
2. **Connect** — `BleSessionManager` scans for the two named gloves and establishes GATT connections with auto-reconnect.
3. **Calibrate** — On the **Cal** tab, capture neutral wrist position for each glove, set angle and frequency ranges, tap **Save & Play**.
4. **Play** — On the **Play** tab, press Play. The sync thread polls glove telemetry every 20 ms, maps wrist angle to frequency/volume, and drives `ThereminAudioEngine` running on a dedicated `THREAD_PRIORITY_AUDIO` thread.
5. **Enhance** — Select a tone from the rotary knob, enable scale lock or octave shift, add reverb/delay/distortion, or open the Beat Maker.
6. **Record** — Tap **Record** to save a performance. Audio is captured via a PCM tap directly from the engine render path — not from the microphone.
7. **Library** — Manage recordings from the **Library** tab.

---

## Architecture

### Activity Flow

```
LaunchActivity → HomeActivity → MainActivity (Play)
                              → ConnectGlovesActivity
                              → CalibrationActivity
                              → LibraryActivity
                              → SettingsActivity → UserManualActivity
                              → BeatMakerActivity
```

### Key Components

| Component | Role |
|---|---|
| `BleSessionManager` | Static singleton — owns all BLE state, two `Glove` instances, watchdog timer |
| `ThereminAudioEngine` | `AudioTrack` synthesis on dedicated audio thread, 10 tones + effects |
| `DrumEngine` | PCM-synthesized drum sounds, 8 patterns, piano mode, no `.wav` assets |
| `ThereminBackgroundAudioService` | Foreground service that keeps audio alive off-screen |
| `RecordingManager` | `PcmListener` consumer — WAV via `RandomAccessFile`, AAC via `MediaCodec` |
| `RecordingRepository` | `SQLiteOpenHelper` for recording metadata (`recordings.db`) |
| `LibraryActivity` | RecyclerView with drag-reorder, search, folders, MediaPlayer playback |
| `PlayMappingState` | Maps glove angle → frequency/volume with sensitivity curve |
| `SettingsStore` | Single-row SQLite settings with additive-only migrations |

### Audio Constants

| Constant | Value |
|---|---|
| Sample rate | 48 000 Hz |
| Audio buffer | 1 024 frames (~21.3 ms) |
| Output gain | 0.14 |
| Freq smoothing | 0.003 |
| Attack smoothing | 0.0046 |
| Release smoothing | 0.0018 |
| Vibrato rate | 4.2 Hz |

### BLE Constants

| Constant | Value |
|---|---|
| Service UUID | `12345678-1234-1234-1234-1234567890ab` |
| TX characteristic | `...ac` |
| RX characteristic | `...ad` |
| Watchdog tick | 1 000 ms |
| Sync tick | 20 ms |
| Ping after stale | 3 000 ms |
| Stale warning | 4 500 ms |
| Force reconnect | 20 000 ms |
| Auto-reconnect delay | 1 500 ms |

---

## Tones

| Knob | Name | Character |
|---|---|---|
| `THR` | Theremin | Classic theremin — vocal/cello quality |
| `AIR` | Air Pad | Soft ambient pad |
| `CEL` | Cello | Dark bowed-string style |
| `PAD` | Pad | Warm sustained synth pad |
| `CHR` | Choir | Soft vocal pad |
| `FLT` | Flute | Light, smooth flute-like |
| `CLR` | Clarinet | Woody reed-like |
| `TRI` | Triangle | Hollow, cleaner synth |
| `SAW` | Saw | Bright, sharper synth |
| `SQR` | Square | Hollow, odd-harmonic square wave |
| `HEL` | Helicopter | Rhythmic rotor-like special effect |

---

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_SCAN` | Discover BLE gloves |
| `BLUETOOTH_CONNECT` | Connect to and communicate with gloves |
| `RECORD_AUDIO` | Required by Android before the app can write audio files (audio is from synth, not microphone) |
| `FOREGROUND_SERVICE` | Keep audio running when Play screen is not visible |
| `POST_NOTIFICATIONS` | Show the background audio notification |

---

## Documentation

The full user manual is available in-app at **Settings → User Manual** and in this repo at [`docs/USER_MANUAL.md`](docs/USER_MANUAL.md).

---

## Key Development Rules

1. Never push directly to `develop` or `main` — always via PR from a feature branch.
2. Never touch `BleSessionManager.java` without understanding the full reconnect/watchdog logic.
3. `fillBuffer()` and `onPcmSamples()` must be non-blocking — no allocation, no logging on the audio thread.
4. `SettingsStore` migrations are additive only — `addColumnIfMissing()`, never drop or rename columns.
5. Always test on Pixel 7 hardware — BLE and audio behave differently on real devices.
6. `./gradlew assembleDebug` must pass before opening any PR.
