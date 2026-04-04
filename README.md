# Theremin Gloves

An Android app that turns two BLE IMU gloves into a real-time theremin. The right glove controls pitch and the left glove controls volume via wrist roll angle, streamed over Bluetooth Low Energy from Arduino Nano 33 BLE Sense boards.

**Course:** COEN 390 / ELEC 390 — Concordia University, Winter 2026  
**Team 5:** Niraj Patel, Ayan Pirani, Marie Ella Cambay, Nirthika Ilaiyarajah, Matei Moldovan

---

## Requirements

- Android API 31+
- Two BLE gloves (Arduino Nano 33 BLE Sense)
  - Pitch glove must advertise as `ThereminGlove`
  - Volume glove must advertise as `ThereminGloveVol`

---

## Build

```bash
./gradlew assembleDebug       # build APK
./gradlew installDebug        # install to connected device
./gradlew test                # unit tests
./gradlew connectedAndroidTest  # instrumented tests (requires device/emulator)
./gradlew lint
```

- **compileSdk / targetSdk:** 36
- **minSdk:** 36
- **Java:** 17

---

## How It Works

1. Launch the app — it checks Bluetooth permissions and auto-connects to known gloves.
2. Calibrate on the **Cal** tab: capture neutral wrist position for each glove, set angle and frequency ranges, tap **Save & Play**.
3. On the **Play** tab, press the Play button. Move your pitch hand to change frequency; move your volume hand to change loudness.
4. Optional: select a tone from the rotary knob, apply scale lock or octave shift, enable reverb/delay/distortion, or open the Beat Maker.
5. Tap **Record** to save a performance; manage recordings in the **Library** tab.

---

## Key Technical Details

| Constant | Value |
|---|---|
| Sample rate | 48 000 Hz |
| Audio buffer | 1 024 frames (~21.3 ms) |
| BLE service UUID | `12345678-1234-1234-1234-1234567890ab` |
| Watchdog ping | 3 000 ms stale |
| Auto-reconnect delay | 1 500 ms |
| Output gain | 0.14 |

**Tones:** Theremin, Air Pad, Cello, Pad, Choir, Flute, Clarinet, Triangle, Saw, Helicopter  
**Effects:** Reverb (Schroeder comb), Delay (ring buffer), Distortion (tanh saturation)  
**Scales:** Chromatic, Major, Minor, Pentatonic  
**Recording formats:** Lossless WAV, High/Medium/Low AAC
