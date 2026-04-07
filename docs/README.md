# Theremin Gloves

An Android app that turns two BLE-connected IMU gloves into a real-time theremin instrument. The right pitch glove controls pitch and the left volume glove controls volume — both by wrist roll angle. Audio is synthesized live on the phone using `AudioTrack`.

**Course:** COEN 390 / ELEC 390 — Concordia University, Winter 2026  
**Team 5:** Niraj Patel · Matei Moldovan · Nirthika Ilaiyarajah · Ayan Pirani · Marie Ella Cambay

---

## Build

```bash
./gradlew assembleDebug       # build APK
./gradlew installDebug        # install to connected device
./gradlew connectedAndroidTest # run instrumented tests
```

- **compileSdk / targetSdk:** 36 · **minSdk:** 31 · **Java:** 17

---

## How It Works

Two Arduino Nano 33 BLE Sense gloves stream wrist-angle telemetry over BLE. `BleSessionManager` handles scanning, connection, and auto-reconnect. `PlayMappingState` maps the incoming angles to frequency and volume targets. `ThereminAudioEngine` synthesizes audio in real time using 11 selectable tones, a live effects pipeline (reverb, delay, distortion), scale lock, and octave shift. Performances can be recorded as lossless WAV or AAC and managed in the built-in library.

---

## Documentation

| Document | Description |
|----------|-------------|
| [Design Document](01_Design_Document.md) | Full architecture — subsystems, constants, data flow |
| [Test Document](02_Test_Document.md) | 51-row test matrix covering BLE, audio, recording, settings |
| [User Manual](03_User_Manual.md) | End-user walkthrough, screen-by-screen |
| [Mission Statement](04_Mission_Statement.md) | Product positioning and target users |
| [Ethics Report](05_Ethics_Report.md) | Privacy, data ownership, accessibility |
| [Computer Simulation Summary](06_Computer_Simulation_Summary.md) | Latency analysis (background ~61 ms, foreground ~91 ms) |
| [Definition of Done](07_Definition_of_Done.md) | Team acceptance criteria |
| [AI Usage Document](08_AI_Usage_Document.md) | Generative AI disclosure |
| [Presentation Notes](09_Presentation_Notes.md) | Speaker notes for all 11 slides |
| [Demo Preparation](10_Demo_Preparation.md) | Live demo runbook with fallback steps |
| [Submission Checklist](11_Submission_Checklist.md) | eConcordia filing checklist |
| [Final Product Backlog](12_Final_Product_Backlog.md) | All completed stories across sprints 1–3 |

---

## Key Technical Facts

| Constant | Value |
|----------|-------|
| `SAMPLE_RATE` | 48 000 Hz |
| `AUDIO_WRITE_FRAMES` | 1 024 (~21.3 ms/buffer) |
| `SYNC_TICK_MS` | 20 ms |
| `UI_TICK_MS` | 50 ms |
| Public tones | THEREMIN, AIR\_PAD, CELLO, PAD, CHOIR, FLUTE, CLARINET, TRIANGLE, SAW, SQUARE, HELICOPTER |
| BLE service UUID | `12345678-1234-1234-1234-1234567890ab` |
