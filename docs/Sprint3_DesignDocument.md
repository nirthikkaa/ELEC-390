# Sprint 3 Design Document — Theremin Gloves

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026
**Team 5:** Niraj Patel (40211758), Ayan Pirani (40276971), Marie Ella Cambay (40284457), Nirthika Ilaiyarajah (40298669), Matei Moldovan (40277256)
**Instructor:** Dr. William Lynch
**Sprint:** March 23 – April 6, 2026

---

## Overview

Sprint 3 delivers the final feature set required to make the Theremin Gloves app demo-ready as a complete musical instrument. Building on the BLE connection, real-time audio synthesis, recording, and library features completed in Sprints 1 and 2, Sprint 3 adds:

- **Drum Backing Kit (ID-10):** A rhythmic drum track that runs alongside the theremin and is captured in recordings.
- **Audio Effects Engine (HD-13):** Real-time reverb, delay, and distortion applied inside the audio thread.
- **Scale Lock (HD-29):** Pitch quantization to musical scales (chromatic, major, minor, pentatonic).
- **Octave Shift (HD-30):** Transposition of the output frequency by -2 to +2 octaves.
- **Gesture Sensitivity Settings (ID-11):** Three sensitivity presets adjusting how much hand movement is required.
- **Performance Mode (HD-16):** A clean-view toggle that hides setup controls during live performance.

All Sprint 3 settings are persisted via the existing additive SQLite migration pattern (`addColumnIfMissing()`).

---

## Architecture Changes

### Updated Component Graph

```
BleSessionManager (static)
    └── BleSnapshot (immutable value object)
            │
            ▼
ThereminBackgroundAudioService (foreground service, SYNC_TICK_MS=20)
    ├── PlayMappingState.recompute(snapshot)
    │       └── sensitivityMultiplier  [NEW — ID-11]
    │       └── freq, volume targets
    │
    ├── octaveShift applied: freq *= 2^octaveShift  [NEW — HD-30]
    │
    └── ThereminAudioEngine
            ├── fillBuffer()
            │       ├── updateFrequency() → smoothedFreq
            │       ├── snapToScale(smoothedFreq, activeScale)  [NEW — HD-29]
            │       ├── sample(toneType, phase, volume)  [9 waveforms]
            │       ├── applyReverb(s)   [NEW — HD-13]
            │       ├── applyDelay(s)    [NEW — HD-13]
            │       ├── applyDistortion(s) [NEW — HD-13]
            │       └── buffer[i] = (short)(clamp(s) * Short.MAX_VALUE)
            │
            ├── PcmListener → RecordingManager.onPcmSamples()
            │       └── + drumMixBuffer (summed before encode) [NEW — ID-10]
            │
            └── DrumEngine  [NEW — ID-10]
                    ├── SoundPool (kick, snare, hihat)
                    └── ScheduledExecutorService (16th-note scheduler)
```

The effects pipeline runs entirely on the audio thread using pre-allocated float arrays — no heap allocation occurs during playback.

---

## Feature Descriptions

### Drum Backing Kit (ID-10)

**Class:** `DrumEngine.java` (new file)
**Assets:** `res/raw/drum_kick.wav`, `res/raw/drum_snare.wav`, `res/raw/drum_hihat.wav`

`DrumEngine` uses Android's `SoundPool` for low-latency one-shot sample playback. A `ScheduledExecutorService` fires a `tick()` callback at 16th-note intervals (interval = 60000ms / BPM / 4). The default pattern is:

| Step | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 | 11 | 12 | 13 | 14 | 15 | 16 |
|------|---|---|---|---|---|---|---|---|---|----|----|----|----|----|----|-----|
| Kick  | X |   |   |   |   |   |   |   | X |    |    |    |    |    |    |    |
| Snare |   |   |   |   | X |   |   |   |   |    |    |    | X  |    |    |    |
| Hihat | X | X | X | X | X | X | X | X | X | X  | X  | X  | X  | X  | X  | X  |

Default BPM: 120. Range: 60–200 BPM.

**Drum in recordings:** `RecordingManager.onPcmSamples()` receives theremin PCM from the engine's PCM tap. A `short[] drumMixBuffer` is maintained by `DrumEngine` at the same 48kHz sample rate. Before the WAV/AAC encoder writes a frame, the two buffers are summed sample-by-sample with clipping protection. This ensures drum audio is captured identically in every recording.

**UI:** A toggle button on the play screen calls `audioEngine.getDrumEngine().setEnabled(boolean)`. An optional BPM slider allows range 60–200.

**Lifecycle:** `DrumEngine.start()` is called when `ThereminAudioEngine.start()` is called. `DrumEngine.stop()` is called on `ThereminAudioEngine.stop()`. `DrumEngine.release()` frees the `SoundPool` on engine destroy.

---

### Audio Effects Engine (HD-13)

**Location:** Inside `ThereminAudioEngine.fillBuffer()`, after `sample()` computation, before `buffer[i]` assignment.

**Signal chain:**

```
sample(toneType, phase, volume)
    → applyReverb(s)      [if reverbEnabled]
    → applyDelay(s)       [if delayEnabled]
    → applyDistortion(s)  [if distortionEnabled]
    → clamp(-1, 1)
    → buffer[i] = (short)(s * Short.MAX_VALUE)
```

All effect state arrays are declared as `final float[]` fields — allocated once at engine construction, never on the audio thread.

#### Reverb

Implementation: Schroeder single comb filter.

- Buffer: `float[] combBuffer = new float[4800]` (~100ms at 48kHz)
- Feedback coefficient: 0.7
- Mix parameter: `reverbMix` (0.0–1.0, default 0.3)
- Algorithm: `out = x + combBuffer[idx] * 0.7f; combBuffer[idx] = out; return x*(1-mix) + out*mix`

#### Delay

Implementation: Ring buffer with configurable feedback.

- Buffer: `float[] delayBuffer = new float[24000]` (500ms at 48kHz)
- Parameters: `delayFeedback` (0.0–0.9, default 0.35), `delayMix` (0.0–1.0, default 0.4)
- Algorithm: reads delayed sample, writes `x + delayed * feedback`, returns `x + delayed * mix`

#### Distortion

Implementation: Hyperbolic tangent soft clipping, normalized to unit output.

- Parameter: `distortionGain` (1.0–10.0, default 3.0)
- Algorithm: `tanh(x * gain) / tanh(gain)` — normalized so output stays in [-1, 1]
- Builds on the `Math.tanh()` saturation already used in the SINE waveform

**Effect persistence:** `reverbEnabled`, `reverbMix`, `delayEnabled`, `delayFeedback`, `delayMix`, `distortionEnabled`, `distortionGain` are stored in `SettingsStore` via `addColumnIfMissing()`.

**Effects in recordings:** Because the PCM tap fires after `fillBuffer()` completes, `RecordingManager.onPcmSamples()` already receives the post-effects signal. No additional wiring is needed.

**UI:** On the play screen, a collapsible card contains a `SwitchCompat` and a `SeekBar` for each of the three effects. Public setters on `ThereminAudioEngine` are called from `MainActivity`:

```
setReverbEnabled(boolean) / setReverbMix(float)
setDelayEnabled(boolean)  / setDelayFeedback(float) / setDelayMix(float)
setDistortionEnabled(boolean) / setDistortionGain(float)
```

---

### Scale Lock (HD-29)

**Location:** `ThereminAudioEngine`, after frequency smoothing (`updateFrequency()`), before vibrato application.

**Supported scales:**

| Scale | Semitone offsets from root (C=0) |
|-------|----------------------------------|
| CHROMATIC | No lock — all frequencies pass through |
| MAJOR | 0, 2, 4, 5, 7, 9, 11 |
| MINOR | 0, 2, 3, 5, 7, 8, 10 |
| PENTATONIC | 0, 2, 4, 7, 9 |

**MIDI snap formula:**

1. Convert Hz to MIDI note: `midi = 69 + 12 * log2(freq / 440)`
2. Round to nearest integer MIDI note number
3. Find the nearest note that belongs to the selected scale (within ±6 semitones)
4. Convert back to Hz: `freq = 440 * 2^((nearestMidi - 69) / 12)`

Scale lookup uses `static final int[]` arrays — no allocation on the audio thread.

**Field:** `volatile String activeScale = "CHROMATIC"` on `ThereminAudioEngine`. Setter: `setActiveScale(String)`.

**Persistence:** `active_scale TEXT NOT NULL DEFAULT 'CHROMATIC'` column in `SettingsStore`.

**UI:** A `ChipGroup` with single-selection on the play screen. Default: CHROMATIC chip selected.

---

### Octave Shift (HD-30)

**Location:** `ThereminBackgroundAudioService.pushTargets()`, after `PlayMappingState.recompute()` computes the target frequency.

**Formula:**

```java
freq *= (float) Math.pow(2.0, active.octaveShift);
freq  = clamp(freq, 20f, 20000f);
```

**Range:** -2 to +2 octaves. Stored as `int octaveShift = 0` in `AppSettings`.

**Persistence:** `octave_shift INTEGER NOT NULL DEFAULT 0` column in `SettingsStore`.

**UI:** Two `ImageButton` controls (+ and -) on the play screen. A `TextView` displays the current state (e.g., "Oct 0", "Oct +1", "Oct -2"). Range is clamped to [-2, +2] in the setter.

---

### Gesture Sensitivity (ID-11)

**Location:** `PlayMappingState.recompute(BleSnapshot)`, applied before the linear frequency/volume mapping.

**Three presets:**

| Level | Multiplier | Effect |
|-------|-----------|--------|
| High | 0.5× | Smaller movements cover the full range — easier for beginners |
| Medium | 1.0× | Default behavior — matches calibration settings |
| Low | 1.5× | Larger movements required — more precise control |

**Implementation:** The multiplier scales the effective angle span symmetrically around the calibrated midpoint:

```
pitchMid  = (pitchAngleMinDeg + pitchAngleMaxDeg) / 2
pitchSpan = (pitchAngleMaxDeg - pitchAngleMinDeg) * sensitivityMultiplier
effPitchMin = pitchMid - pitchSpan / 2
effPitchMax = pitchMid + pitchSpan / 2
```

The mapping then uses `effPitchMin`/`effPitchMax` instead of the stored calibration values. The same logic applies to the volume glove.

**Field:** `float sensitivityMultiplier = 1.0f` on `PlayMappingState`. Setter: `setSensitivityMultiplier(float)`. Static helper `levelToMultiplier(String level)` converts the stored string to the float value.

**Persistence:** `sensitivity_level TEXT NOT NULL DEFAULT 'MEDIUM'` in `SettingsStore`. Read on `onResume()` in `MainActivity` and applied to `PlayMappingState`.

**UI:** A `RadioGroup` with three `RadioButton` options (Low / Medium / High) in `SettingsActivity`.

---

### Performance Mode (HD-16)

**Location:** `MainActivity.java` — toggles `View.GONE` / `View.VISIBLE` on play screen elements.

**Always visible in performance mode:**
- `thereminVisualizerView` — live waveform display
- `toneKnob` — waveform selector (ToneKnobView)
- `tvPitchConn` / `tvVolConn` — glove connection chips
- `btnRecord` / `tvRecordingTimer` — recording controls
- `btnScanConnect` — manual reconnect
- `btnAudioStart` / `btnAudioStop` — core audio controls
- Scale ChipGroup, octave buttons, drum toggle, effects panel

**Hidden in performance mode:**
- `cardDebugLog` — debug/event log card
- `cardPlayMapping` — angle/frequency calibration sliders
- `cardGloveCommands` — neutral capture, direction toggle, help buttons
- `tvStatus`, `tvAudio` — status text labels
- `tvPlayRemoteLabel`, `tvReconnectLabel`, `tvBackgroundLabel` — secondary informational labels

**State persistence:** Stored in `SharedPreferences` under key `performance_mode_active`. Restored in `onResume()` so the mode survives navigation away from and back to the play screen.

**UI:** A single `ImageButton` in the play screen header. Icon is `ic_fullscreen` when in normal mode; switches to `ic_fullscreen_exit` when performance mode is active. No audio changes occur when toggling.

---

## Latency Analysis

The end-to-end gesture-to-sound latency is determined by three sequential contributors:

| Component | Value | Notes |
|-----------|-------|-------|
| BLE notification interval | ~7.5–20ms | Android-negotiated connection interval |
| Sync loop tick (`SYNC_TICK_MS`) | 20ms | `ThereminBackgroundAudioService` poll rate |
| Audio buffer (`AUDIO_WRITE_SAMPLES=2048 @ 48kHz`) | 42.7ms | Dominant contributor |
| **Total worst case** | **~83ms** | BLE 20ms + sync 20ms + buffer 43ms |
| **Total typical** | **~55–70ms** | BLE ~12ms + sync ~10ms + buffer ~43ms |

**HD-11 acceptance criterion:** < 80ms. The typical case passes. Worst case marginally exceeds the criterion due to Android's BLE connection interval being non-deterministic.

Reducing `AUDIO_WRITE_SAMPLES` from 2048 to 512 would cut buffer latency to ~11ms but risks audio dropouts on lower-end Android devices. The 2048-sample buffer was retained as the stable balance point after analysis.

---

## Settings Persistence — Sprint 3 Columns

All new settings use the existing `addColumnIfMissing()` pattern in `SettingsStore`. No `DB_VERSION` bump is required.

| Column | Type | Default | Feature |
|--------|------|---------|---------|
| `active_scale` | TEXT | `'CHROMATIC'` | Scale Lock (HD-29) |
| `octave_shift` | INTEGER | `0` | Octave Shift (HD-30) |
| `reverb_enabled` | INTEGER | `0` | Audio Effects (HD-13) |
| `reverb_mix` | REAL | `0.3` | Audio Effects (HD-13) |
| `delay_enabled` | INTEGER | `0` | Audio Effects (HD-13) |
| `delay_feedback` | REAL | `0.35` | Audio Effects (HD-13) |
| `delay_mix` | REAL | `0.4` | Audio Effects (HD-13) |
| `distortion_enabled` | INTEGER | `0` | Audio Effects (HD-13) |
| `distortion_gain` | REAL | `3.0` | Audio Effects (HD-13) |
| `sensitivity_level` | TEXT | `'MEDIUM'` | Gesture Sensitivity (ID-11) |

---

## File Ownership — Sprint 3

| File | Owner | Change |
|------|-------|--------|
| `ThereminAudioEngine.java` | Niraj | Effects pipeline, scale hook, drum wiring |
| `ThereminBackgroundAudioService.java` | Niraj | Octave shift in `pushTargets()`, `DrumEngine` lifecycle |
| `DrumEngine.java` | Niraj | New file |
| `res/raw/drum_kick.wav` etc. | Niraj | New assets |
| `MainActivity.java` | Ayan + Nirthika | Scale/octave/drum/effects UI (Ayan); performance mode (Nirthika) |
| `activity_main.xml` | Ayan first, Nirthika second | Coordinate merge order |
| `PlayMappingState.java` | Marie Ella | Sensitivity multiplier in `recompute()` |
| `SettingsActivity.java` | Marie Ella | Sensitivity selector UI |
| `activity_settings.xml` | Marie Ella | Sensitivity RadioGroup |
| `SettingsStore.java` | All (additive columns) | One person per column — coordinate |
| `RecordingManager.java` | Niraj | Drum mix-in to PCM buffer |

---

*Document prepared by Matei Moldovan. Technical content sourced from CLAUDE.md Sprint 3 Implementation Guide and team architecture decisions.*
