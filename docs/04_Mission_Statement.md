# Theremin Gloves — Mission Statement (Updated)

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026  
**Team 5:** Niraj Patel, Matei Moldovan, Nirthika Ilaiyarajah, Ayan Pirani, Marie Ella Cambay

## Product Description
Theremin Gloves is an Android app that transforms two BLE-connected IMU gloves built around Arduino Nano 33 BLE Sense boards into a wireless theremin-style instrument. One glove controls pitch, the other controls volume, and the phone synthesizes the sound locally in real time. The app also supports calibration, recording, local playback, tone selection, effects, beat tools, and settings persistence.

## Benefit Proposition
Traditional electronic instruments often require dedicated hardware, physical contact, or prior knowledge of established controllers such as keyboards, pads, or stringed interfaces. Theremin Gloves offers a different experience: a fully wireless, gesture-controlled instrument that works with commodity hardware and a standard Android phone. A new user can begin making sound with only hand motion, while more advanced users can take advantage of calibration, effects, recording, scale lock, and beat features to shape a fuller performance.

## Key Business Goals
- Hardware cost target: approximately `$30–50 CAD` per prototype unit for two Arduino-based gloves plus basic glove materials
- Distribution model: APK-based Android distribution
- Adoption goal: reach an initial community of student musicians, hobbyist makers, and demo-focused creators through Concordia and adjacent word-of-mouth channels
- Product positioning goal: demonstrate that a low-cost BLE + Android stack can support a musically usable gesture instrument

## Target Market
- Concordia music students
- hobbyist musicians and electronics makers
- content creators looking for a visually distinctive instrument
- engineering students interested in BLE, sensing, and real-time audio projects

## Assumptions — Status Update

### Assumption 1
BLE latency would be acceptable for musical performance.

**Status:** validated, with nuance.  
The current code path shows a strong background-service latency profile and a weaker foreground Play worst-case bound because foreground target updates currently depend on `UI_TICK_MS = 50 ms`. Typical use is still within an acceptable range for this project, but the exact bound depends on whether playback is being driven through the foreground screen or the service path.

### Assumption 2
The Arduino IMU would provide sufficient angular resolution for musical control.

**Status:** validated.  
The Android app successfully uses live `ACTIVE_DELTA_DEG` telemetry together with calibration ranges and smoothing to map gesture input into stable frequency and volume output.

### Assumption 3
Android `AudioTrack` would support low-latency real-time synthesis.

**Status:** validated.  
The app uses `AudioTrack` at `48 kHz`, stereo, 16-bit PCM with `AUDIO_WRITE_FRAMES = 1024`, plus an audio-priority render thread. This has proven sufficient for stable real-time synthesis on the target device class.

### Assumption 4
Recording could capture the exact synthesized output rather than microphone audio.

**Status:** validated.  
`RecordingManager` consumes the `ThereminAudioEngine.PcmListener` tap, so saved output is taken directly from the engine render path.

## Constraints
- final course submission deadline: April 15, 2026
- Android minimum platform: API 31
- local-first design: no cloud backend
- project scope must remain practical for a course deliverable using two custom BLE gloves and one Android client

## Stakeholders
- end users performing with the app
- Concordia students and educators evaluating the concept
- teammates responsible for development and documentation
- course evaluators including Dr. Lynch and Bipin Patel
