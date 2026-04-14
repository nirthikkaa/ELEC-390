# Theremin Gloves — Demo Video Submission Script

This script is tailored to the current `sprint3` build and the final course video requirement captured in the repo docs:

- total video length: `5–7 minutes`
- introduction: `2 minutes max`
- must identify the customer
- must explain the product value
- must explain how the product works, including architecture, communication links, and important algorithms
- must include a real product demo

This version assumes:

- you are on camera
- a camera operator is filming you and the phone
- you are using the real gloves
- you want a clean recorded demo, not the longer live-presentation runbook

## Recommended Filming Setup

- Use a returning-user phone state so the app opens directly into `Play`.
- Turn Bluetooth on before filming.
- Power on both gloves before filming.
- Have at least one older saved recording in `Library` as backup.
- Film in a quiet room so the phone speaker is audible.
- Ask the camera operator to alternate between:
  - medium shot of you wearing the gloves
  - over-the-shoulder shot of the phone
  - close-up of your hands while you play

## Time Plan

- `0:00–1:20` intro and technical overview
- `1:20–2:00` app launch and connection
- `2:00–2:45` calibration
- `2:45–4:10` live playing demo
- `4:10–4:55` tone / scale / octave / effects
- `4:55–5:35` recording
- `5:35–6:20` library playback
- `6:20–6:45` settings and close

## Shot-By-Shot Script

### 0:00-0:25 — Opening

**Camera**
Wide or medium shot. You are holding the phone and wearing or showing both gloves.

**Say**

“Hi, I’m [Your Name], and this is Theremin Gloves, a wireless theremin-style instrument controlled entirely by wrist gestures.”

“Our target users are music students, hobbyist musicians, content creators, and performers who want an expressive instrument without needing a keyboard or other traditional controller.”

### 0:25-0:55 — Value Proposition

**Camera**
Stay on you, then move slightly closer to show the gloves clearly.

**Say**

“The value of this product is that it gives the user a contact-free instrument experience using low-cost hardware and a standard Android phone.”

“One glove controls pitch, the other controls volume, and the user can also calibrate, record, play back recordings, change tones, use effects, and add beat support inside the same app.”

### 0:55-1:20 — How It Works

**Camera**
Over-the-shoulder shot of the phone, with the gloves still visible if possible.

**Say**

“Each glove uses an Arduino Nano 33 BLE Sense. The gloves send wrist-angle data to the phone over Bluetooth Low Energy.”

“In the app, `BleSessionManager` manages both glove connections, `PlayMappingState` maps the right glove to pitch and the left glove to volume, and `ThereminAudioEngine` synthesizes the sound locally through `AudioTrack` at 48 kilohertz.”

“We also smooth pitch and volume to reduce jitter, and software features like scale lock, octave shift, reverb, delay, and distortion are applied in real time.”

### 1:20-1:40 — Launch

**Camera**
Close-up of the phone home screen, then the app opening.

**Action**
Launch the app and let the loading screen appear.

**Say**

“I’ll start by launching the app.”

“The launcher warms audio, Bluetooth, and saved settings so returning users can get into Play quickly.”

### 1:40-2:00 — Connect Screen

**Camera**
Stay on phone close-up.

**Action**
Tap `Connect`. If needed, tap `Connect All`. Let both gloves show connected.

**Say**

“On the Connect screen, I can connect both gloves together or control each glove individually.”

“The right glove is the pitch glove, and the left glove is the volume glove.”

“The app also supports reconnect logic, so if one glove drops, it can recover that glove without restarting the whole app.”

### 2:00-2:45 — Calibration

**Camera**
Over-the-shoulder shot of the phone, then a slightly wider shot so your neutral hand position is visible.

**Action**
Open `Cal`.
Tap `Pitch Neutral`.
Tap `VOLUME`.
Tap `Volume Neutral`.
Tap `Save & Play`.

**Say**

“Next is calibration. Calibration captures each glove’s neutral resting position.”

“This matters because the app measures motion relative to the performer’s own baseline instead of assuming one fixed wrist position for every user.”

“The flow is guided: first pitch neutral, then volume neutral, and then the calibration is saved back into Play.”

### 2:45-3:35 — Basic Playing Demo

**Camera**
Medium shot showing you and the phone together. The operator should keep both your hands and the screen visible.

**Action**
On `Play`, press the main `Play` button if needed.
Move the right glove to change pitch.
Move the left glove to change volume.

**Say**

“Now I’m in the Play screen.”

“The right glove changes pitch, and the left glove changes loudness.”

“The visualizer is showing the synthesized output from the engine, not microphone input.”

“If either glove disconnects, the app mutes the instrument until both gloves are available again.”

### 3:35-4:10 — Stage View

**Camera**
Start on the phone, then widen to show you performing with the cleaner layout.

**Action**
Tap `Stage View`.
Play a short phrase again.

**Say**

“Stage View is the cleaner performance layout.”

“It hides extra UI so the app is easier to use in a live performance setting while still showing the key live metrics.”

### 4:10-4:35 — Tone Switching

**Camera**
Phone close-up for the tone knob, then back to your hands while the sound changes.

**Action**
Rotate through several tones, ideally `Theremin`, `Saw`, `Choir`, and `Helicopter`.

**Say**

“The current build exposes eleven public tones.”

“I’ll switch through a few so you can hear the difference between the classic theremin sound, brighter synth tones, softer vocal-style tones, and the helicopter effect.”

### 4:35-4:55 — Scale / Octave / Effects

**Camera**
Phone close-up.

**Action**
Tap one scale option such as `MAJOR` or `PENTA`.
Tap octave up or down once.
Toggle one effect such as `Reverb`.

**Say**

“Sprint 3 added scale lock, octave shifting, and effects.”

“Scale lock snaps the pitch to musical notes, octave shift moves the playable range, and effects like reverb, delay, and distortion are applied directly in the synthesis pipeline.”

### 4:55-5:35 — Recording

**Camera**
Show the record button and keep your hands visible during the short performance.

**Action**
Tap `Record`.
Play for about `10` seconds.
Tap `Stop`.

**Say**

“Now I’ll record a short performance.”

“Recording is taken directly from the synthesized PCM output, so it captures exactly what the app generated, including the selected tone, effects, and beat content.”

“It is not recording from the phone microphone.”

### 5:35-6:20 — Library

**Camera**
Phone close-up.

**Action**
Open `Library`.
Play the new recording.
Briefly show search.
Optionally open a three-dot menu on an older recording to show rename, move, or delete.

**Say**

“The Library stores recordings locally on the device.”

“Users can play them back, search by name, rename them, move them into folders, and delete them.”

“Playback works even when the gloves are not connected, because this screen is reading saved files rather than live BLE data.”

### 6:20-6:45 — Settings And Close

**Camera**
Phone close-up, then end on a medium shot of you with the gloves.

**Action**
Open `Settings`.
Show `Keep audio playing when leaving Play`, `Extended frequency range`, and one direction toggle.

**Say**

“These settings persist across app restarts.”

“For example, I can keep audio playing when leaving Play, extend the pitch ceiling from 2,000 hertz to 20,000 hertz, and invert pitch or volume direction depending on user preference.”

“That’s Theremin Gloves: connect, calibrate, perform, record, and review, all on-device with two BLE gloves and one Android app.”

## Shorter Backup Closing Line

If you need to end faster, use this instead:

“Theremin Gloves demonstrates a complete BLE-to-audio product: the gloves stream gesture data, the phone maps it into musical control, and the app synthesizes, records, and plays back the result locally in real time.”

## Practical Notes For Filming

- Do not say “9 waveforms” or “10 tones.” The current public build exposes `11` tones.
- Do not say calibration is one tap. It is a guided two-step flow: `Pitch Neutral`, then `Volume Neutral`, then `Save & Play`.
- Do not say recordings use the microphone. The app records from the synth engine’s PCM tap.
- Do not spend too long in the intro. Keep the first section under `1:20` so you remain safely inside the `2 minute` maximum introduction rule.
- If a connection attempt stalls during filming, stop and retake the shot instead of narrating a long recovery. This is a recorded submission, not a live oral demo.
