# Theremin Gloves

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white" alt="Android"/>
  <img src="https://img.shields.io/badge/API-31%2B-brightgreen" alt="API 31+"/>
  <img src="https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white" alt="Java 17"/>
  <img src="https://img.shields.io/badge/BLE-Nordic%202.11-0057A8" alt="Nordic BLE"/>
  <img src="https://img.shields.io/badge/Audio-48%20kHz%20%E2%80%A2%201024%20frames-blueviolet" alt="48kHz"/>
  <img src="https://img.shields.io/badge/Latency-%3C91%20ms%20E2E-success" alt="Latency"/>
  <img src="https://img.shields.io/badge/Tones-11-ff69b4" alt="11 tones"/>
</p>

<p align="center">
  <strong>A real-time wireless theremin instrument controlled entirely by wrist gestures.</strong><br/>
  Two BLE gloves → Android phone → synthesized audio. No strings. No keys. No server.
</p>

---

**Course:** COEN 390 / ELEC 390 — Concordia University, Winter 2026  
**Team 5:** Niraj Patel · Matei Moldovan · Nirthika Ilaiyarajah · Ayan Pirani · Marie Ella Cambay

---

## What It Does

Each glove contains an **Arduino Nano 33 BLE Sense** with an LSM9DS1 9-DOF IMU. The firmware measures wrist-roll angle and streams the live delta over BLE as ASCII packets. The Android app:

- receives packets from both gloves simultaneously via Nordic BLE GATT
- maps **right-hand angle → pitch frequency**
- maps **left-hand angle → volume**
- synthesizes audio locally on a dedicated `THREAD_PRIORITY_AUDIO` thread at 48 kHz

The result is a fully wireless, latency-optimized instrument that plays music from thin air.

---

## Features

### Core Instrument
- **11 synthesized tones** — Theremin, Air Pad, Cello, Pad, Choir, Flute, Clarinet, Triangle, Saw, Square, Helicopter
- **Scale lock** — Chromatic, Major, Minor, Pentatonic (nearest-note binary search + cache)
- **Octave shift** — ±2 octaves applied in the sync loop
- **Sensitivity presets** — power-law response curve 0.25 (very responsive) → 2.50 (precise)
- **Background audio** — foreground service keeps playback alive when leaving Play screen

### Effects Pipeline
- **Reverb** — Schroeder comb filter (8 192-sample buffer ≈ 170 ms, T60 ≈ 2.3 s)
- **Delay** — ring buffer (32 768 samples ≈ 682 ms, adjustable feedback + wet/dry mix)
- **Distortion** — normalised `tanh` soft-clip: `tanh(x·gain) / tanh(gain)`

### Beat Maker
- **8 preset drum patterns** — Rock, Funk, EDM, Hip-Hop, Reggae, Jazz, Trap, Latin/Samba
- **14 PCM drum voices** — mixed bank of bundled raw drum/bass samples plus in-code synthesis, mixed directly into the shared PCM engine (no `SoundPool`)
- **Piano sequencer** — 25 keys (C3–C5), 3 synth timbres
- **Drift-free `SequencerClock`** — self-rescheduling via `System.currentTimeMillis()` delta

### Recording and Library
- **PCM tap recording** — captures the exact synthesised output (drums + effects), not microphone audio
- **4 quality levels** — Lossless WAV · High AAC 320 k · Medium AAC 192 k · Low AAC 128 k
- **Library** — search, rename/delete, folders, move-to-folder, mini-player, playback modes

### BLE Stack
- **Auto-reconnect** — 1.5 s delay, cached MAC skips full scan on second connect
- **Watchdog** — 1 s tick: pings at 3 s stale, warns at 4.5 s, force-reconnects at 20 s
- **Immutable `BleSnapshot`** — lock-free point-in-time view read by UI, audio service, and calibration preview simultaneously

---

## Requirements

| Requirement | Detail |
|---|---|
| Android phone | API 31+ (Android 12 or higher) |
| Pitch glove | Arduino Nano 33 BLE Sense, advertising as `ThereminGlove` |
| Volume glove | Arduino Nano 33 BLE Sense, advertising as `ThereminGloveVol` |
| Permissions | `BLUETOOTH_SCAN` · `BLUETOOTH_CONNECT` · `RECORD_AUDIO` · `FOREGROUND_SERVICE` |

---

## Build

```bash
./gradlew assembleDebug          # build APK
./gradlew installDebug           # build + install to connected device
./gradlew test                   # JVM unit tests (no device needed)
./gradlew connectedAndroidTest   # instrumented tests (device required)
./gradlew lint
```

| Config | Value |
|---|---|
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 31 |
| Java | 17 |
| Key dependency | `no.nordicsemi.android:ble:2.11.0` |

---

## Signal Flow

```
Glove firmware
    └─ LSM9DS1 IMU → wrist-roll angle
           └─ BLE notify: "ACTIVE_DELTA_DEG:<float>"
                  └─ BleSessionManager.getSnapshot() → BleSnapshot (immutable)
                         └─ PlayMappingState.recompute()  [50 ms UI tick]
                                └─ normalizeClamped() → pow(norm, curve) → freqHz
                                       └─ ThereminAudioEngine.setTargets()
                                              └─ fillBuffer()  [~21.3 ms audio thread]
                                                     ├─ IIR smooth freq  (α = 0.003)
                                                     ├─ snapToScale()  binary search
                                                     ├─ vibrato LFO  (4.2 Hz)
                                                     ├─ IIR smooth vol  (α = 0.0046 / 0.0018)
                                                     ├─ sample(toneType)  [24 additive recipes]
                                                     ├─ × OUTPUT_GAIN (0.14)
                                                     ├─ applyReverb()  Schroeder comb
                                                     ├─ applyDelay()   ring buffer
                                                     ├─ applyDistortion()  tanh
                                                     ├─ DrumEngine.mixInto()
                                                     ├─ RecordingManager.onPcmSamples()
                                                     └─ AudioTrack.write()  → Speaker
```

---

## Architecture

### Activity Flow

```
LaunchActivity
    │  AppLaunchWarmup: pre-builds DrumEngine + SettingsStore on daemon thread
    ↓
HomeActivity  ←  first-run BLE permissions + auto-connect
    ↓  both gloves connected
MainActivity (Play)
    ├── ConnectGlovesActivity  — manual BLE controls
    ├── CalibrationActivity    — neutral capture, angle/frequency range tuning
    ├── LibraryActivity        — recording browser with MediaPlayer
    ├── SettingsActivity       — persistence + UserManualActivity
    └── BeatMakerActivity      — 16-step drum + piano sequencer
```

### Key Classes

| Class | Role |
|---|---|
| `BleSessionManager` | Static singleton — BLE scan, two GATT sessions, watchdog, `BleSnapshot` |
| `ThereminAudioEngine` | `AudioTrack` synthesis at `THREAD_PRIORITY_AUDIO`; 24 tone recipes; effects |
| `DrumEngine` | 14 PCM-synthesised drum sounds, 8 patterns, piano sequencer, `SequencerClock` |
| `ThereminBackgroundAudioService` | Foreground service with own engine; 20 ms sync loop |
| `PlayMappingState` | Maps angle → frequency (power-law sensitivity curve, octave shift) |
| `RecordingManager` | `PcmListener` → WAV (`RandomAccessFile`) or AAC (`MediaCodec` + `MediaMuxer`) |
| `RecordingRepository` | SQLite `recordings.db` — folders + recordings tables |
| `SettingsStore` | SQLite `theremin_gloves.db` — 24-column single-row settings, additive migrations |
| `CalibrationDraft` | Buffers edits; `sanitize()` prevents degenerate angle/frequency ranges |
| `AppLaunchWarmup` | Daemon thread pre-constructs heavy objects during splash screen |

### Threading Model

| Thread | Priority | Period | Responsibility |
|---|---|---|---|
| Main thread | Default | Event-driven | BLE watchdog (1 s), Activity lifecycle |
| UI tick (`Handler`) | Default | 50 ms | `PlayMappingState.recompute()`, `setTargets()` |
| Service sync | `THREAD_PRIORITY_AUDIO` | 20 ms | Service sync loop, settings refresh (500 ms) |
| Audio thread | `THREAD_PRIORITY_AUDIO` | ~21.3 ms | `fillBuffer()`, drum mix, PCM tap, `AudioTrack.write()` |

---

## DSP Details

### IIR Smoothing Constants

| Constant | α | τ (ms @ 48 kHz) | Effect |
|---|---|---|---|
| `FREQ_SMOOTHING` | 0.003 | 6.9 ms | Pitch glide — eliminates clicks on fast jumps |
| `ATTACK_SMOOTHING` | 0.0046 | 4.5 ms | Volume fade-in — quick, responsive attack |
| `RELEASE_SMOOTHING` | 0.0018 | 11.6 ms | Volume fade-out — gentle, natural decay |

### Latency Budget

| Path | Typical | Worst case | Target < 80 ms |
|---|---|---|---|
| Background / calibration preview | ~78 ms | ~136 ms | ✓ Satisfies |
| Foreground Play screen | ~93 ms | ~166 ms | ✓ Typical · ✗ Strict worst |

> Foreground worst case exceeds target only because `UI_TICK_MS = 50 ms` is the dominant factor. Background service always satisfies the target.

---

## Tones

| Knob | Name | Character |
|---|---|---|
| `THR` | Theremin | Classic theremin — vocal/cello harmonic stack |
| `AIR` | Air Pad | Detuned soft pad with slow beating |
| `CEL` | Cello | Dark bowed-string FM |
| `PAD` | Pad | Warm detuned sine stack |
| `CHR` | Choir | Shallow phase-modulated vocal pad |
| `FLT` | Flute | Near-pure sine with tiny shimmer |
| `CLR` | Clarinet | Odd-harmonic closed-pipe spectrum |
| `TRI` | Triangle | Triangle-derived with reinforced odd partials |
| `SAW` | Saw | Additive sawtooth, first 6 harmonics |
| `SQR` | Square | Odd-harmonic square wave; hollow quality |
| `HEL` | Helicopter | Pulse-tone; pitch controls chop rate 0.75–12 Hz |

---

## Testing

- **139 automated `@Test` methods**
  - 40 JVM unit tests — waveform math, tone guards, pattern routing, drum gain math
  - 99 instrumented tests — BLE regression, calibration, Play UI, Library, recording, settings, latency/launch benchmarks
- **51 scenario-level test rows** verified on Pixel 7 with live BLE gloves

---

## BLE Protocol

| Item | Value |
|---|---|
| Service UUID | `12345678-1234-1234-1234-1234567890ab` |
| TX notify characteristic | `...90ac` |
| RX write characteristic | `...90ad` |
| Glove → phone packets | `ACTIVE_DELTA_DEG:<f>` · `NEUTRAL_ROLL_DEG:<f>` · `DIRECTION:<POSITIVE\|NEGATIVE>` |
| Phone → glove commands | `H` ping · `N` capture neutral · `D` toggle direction |

---

## Sprint History

| Sprint | Delivered |
|---|---|
| **Sprint 1** | BLE dual-glove + auto-reconnect, calibration, `AudioTrack` engine, pitch/volume synthesis, visualiser, home/launch/settings screens |
| **Sprint 2** | PCM tap recording (WAV + AAC), `LibraryActivity`, 10 public tones, `ToneKnobView`, `recordings.db` |
| **Sprint 3** | `DrumEngine` (14 sounds, 8 patterns, piano), Beat Maker UI, audio effects (reverb/delay/distortion), scale lock, octave shift, sensitivity presets, Square tone re-added (11 total), onboarding hints |

---

## Documentation

Full submission documentation is in [`docs/`](docs/):

| Document | Contents |
|---|---|
| [`00_FAQ.md`](docs/00_FAQ.md) | 102 Q&A pairs — project, BLE, DSP, architecture, testing, demo |
| [`01_Design_Document.md`](docs/01_Design_Document.md) | Architecture, UML diagrams, class reference, DB schema |
| [`02_Test_Document.md`](docs/02_Test_Document.md) | 51 test rows (all Pass), automated test inventory |
| [`03_User_Manual.md`](docs/03_User_Manual.md) | End-user guide (also available in-app at Settings → User Manual) |
| [`05_Ethics_Report.md`](docs/05_Ethics_Report.md) | Privacy, accessibility, AI usage transparency |
| [`06_Computer_Simulation_Summary.md`](docs/06_Computer_Simulation_Summary.md) | IIR math, latency budget, frequency mapping, reverb analysis |
| [`08_AI_Usage_Document.md`](docs/08_AI_Usage_Document.md) | Component-by-component AI vs. human contribution breakdown |
| [`12_Final_Product_Backlog.md`](docs/12_Final_Product_Backlog.md) | 31 completed + 20 excluded stories |

---

## Development Rules

1. Never push directly to `develop` or `main` — always via PR from a feature branch.
2. Never modify `BleSessionManager.java` without understanding the full reconnect/watchdog logic.
3. `fillBuffer()` and `onPcmSamples()` must be non-blocking — no allocation, no logging on the audio thread.
4. `SettingsStore` migrations are additive only — `addColumnIfMissing()`, never drop or rename columns.
5. Always test on **Pixel 7 hardware** — BLE and audio behave differently on real devices.
6. `./gradlew assembleDebug` must pass before opening any PR.
