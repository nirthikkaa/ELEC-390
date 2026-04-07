# Theremin Gloves — Phase 3 Demo Preparation

This runbook is aligned to the current Android build in this repo. It replaces older demo notes that still assume a generic `Scan` button, a one-tap `Calibrate` button, landscape support, or a 9-waveform Play screen.

## Demo Goals

For the final presentation, the safest story to prove live is:

1. the gloves connect reliably
2. calibration is understandable
3. gesture input changes pitch and volume in real time
4. the app can record and play back a performance
5. the app survives basic attempts to break it

If time gets tight or the phone behaves unpredictably, prioritize proving those five points over showing every optional control.

## Current-Build Ground Truth

- `LaunchActivity` always appears first with a circular loading indicator.
- Returning users route from `LaunchActivity` directly to `MainActivity` (`Play`).
- First-time users route from `LaunchActivity` to `HomeActivity` (`Setup`).
- All activities are locked to portrait in `AndroidManifest.xml`.
- The Connect screen uses `Connect All` / `Disconnect All` plus per-glove connect buttons.
- Calibration is a guided two-page flow with `PITCH` and `VOLUME` tabs.
- The guide sequence is `Pitch Neutral` -> `VOLUME` tab -> `Volume Neutral` -> `Calibration complete` -> `Save & Play`.
- `Stage View` is the user-facing label for the cleaner performance-mode layout on Play.
- The current public tone cycle contains 11 tones: Theremin, Air Pad, Cello, Pad, Choir, Flute, Clarinet, Triangle, Saw, Square, Helicopter.
- Default ranges: pitch `0°` to `90°`, volume `0°` to `90°`, and frequency `20 Hz` to `2,000 Hz` unless Extended Frequency Range is enabled.

## Recommended Demo Phone State

- Use a returning-user state unless the team specifically wants to show the full Setup screen.
- Turn Bluetooth on before the meeting starts unless you want to deliberately demonstrate the enable-Bluetooth prompt.
- Fully charge the Pixel 7 and both gloves.
- Close unrelated apps and disable noisy notifications.
- Have at least one saved recording in the Library as a fallback in case the live recording step goes wrong.
- Keep both gloves already powered but not necessarily connected before screen sharing starts.

## Full Demo Script

### 0:00-0:20 App Launch

Action:
- Launch the app from the phone home screen.
- Let the loading spinner appear.

Say:
- "This is Theremin Gloves, a wireless theremin controlled entirely by hand gestures."
- "The launcher warms audio, Bluetooth, and saved settings before opening the app."

If the app opens to `Play`:
- Continue directly and say: "Returning users land straight in Play so the instrument is ready faster."

If the app opens to `Setup`:
- Continue and say: "On first launch, the app starts in Setup and guides the user into connection and calibration."

### 0:20-1:20 Connect Both Gloves

Recommended path:
- If you started in `Play`, tap the bottom `Connect` tab.
- If you started in `Setup`, either let auto-connect work or tap `Connection Details`.
- On `Connect`, tap `Connect All`.
- Wait for both gloves to show connected.

Say:
- "The right pitch glove controls pitch and the left volume glove controls volume."
- "The app uses BLE and tracks the two gloves independently, so it can reconnect only the missing glove if needed."
- "We can connect both at once or control each glove individually from this screen."

Show if stable:
- The big status card.
- The right Pitch card with its R badge and the left Volume card with its L badge.
- The individual `Connect Pitch Glove` / `Connect Volume Glove` buttons.

Fallback if one glove is missing:
- Power-cycle the missing glove.
- Tap its individual reconnect button instead of retrying the whole flow.

### 1:20-2:20 Calibration

Action:
- Open the `Cal` tab.
- Confirm the `PITCH` page is active.
- Hold hands in a natural neutral position.
- Tap `Pitch Neutral`.
- Tap the `VOLUME` tab when it glows or when instructed.
- Tap `Volume Neutral`.
- Wait for `Calibration complete`.
- Tap `Save & Play`.

Say:
- "Calibration captures each glove's neutral resting angle as the zero reference."
- "All live motion is measured relative to that baseline, so the system adapts to the performer."
- "The guide is step-based: pitch neutral first, then volume neutral, then the settings are saved back into Play."

Important note:
- Do not describe Calibration as a single `Calibrate` button. That is not how the current build works.

### 2:20-3:40 Play Basic Theremin Control

Action:
- In `Play`, press the center `Play` button if audio is not already active.
- Move the right pitch glove slowly upward and downward.
- Move the left volume glove to fade in and out.
- Point to the live visualizer and the live metric cards.

Say:
- "Pitch and volume are driven by live `ACTIVE_DELTA_DEG` values from the gloves."
- "The app maps those angles into frequency and amplitude in real time."
- "The visualizer shows the live synthesized output, not microphone audio."

Optional:
- Tap `Stage View` in the top-left if you want the cleaner performance layout.

### 3:40-4:40 Tone Switching

Action:
- Rotate the tone knob through several clearly different sounds.

Recommended tones to demonstrate:
- `Theremin`
- `Saw`
- `Choir`
- `Flute`
- `Helicopter`

Say:
- "The current build exposes 11 tones in the public selector (Square was re-added in Sprint 3)."
- "Some are classic synth-style tones and some are more stylized character sounds."

Do not say:
- "There are 9 waveforms."

### 4:40-5:20 Sprint 3 Controls

Action:
- Show octave up/down.
- Show the scale buttons.
- If stable on the demo phone, briefly tap `BEATS`.

Say:
- "Sprint 3 added scale locking, octave shifting, and beat support on top of the original theremin control."

Safety note:
- Only open `BEATS` during the live demo if the phone is already in a known-good state and the team has rehearsed that exact path.
- If the live demo feels unstable, skip `BEATS` and go straight to recording and Library playback.

### 5:20-6:10 Record a Performance

Action:
- Tap `Record`.
- Play for about 10 to 15 seconds.
- Tap `Stop`.

Say:
- "Recording taps the synthesized PCM directly from the engine, so it captures exactly what the app generated."
- "It is not recording from the microphone."

### 6:10-7:00 Library

Action:
- Open `Library`.
- Show the new recording in the list.
- Tap play on a recording.
- Use the search field briefly.
- If stable, show rename or delete on an older test recording instead of the fresh one.

Say:
- "Recordings are stored locally on the device."
- "Users can search, organize, play back, rename, and delete recordings from the Library screen."
- "Playback works even when the gloves are not connected."

### 7:00-7:40 Settings

Action:
- Open `Settings`.
- Show `Keep audio playing when leaving Play`.
- Show pitch and volume direction toggles.
- Show `Extended Frequency Range`.
- Show `Show Calibration Guide Again`.

Say:
- "These settings persist across app restarts."
- "Direction can be inverted globally without changing the calibration screen."
- "Extended Frequency Range raises the ceiling from 2,000 Hz to 20,000 Hz."

### 7:40-8:00 Close

Say:
- "That is the full product flow: connect, calibrate, perform, record, and review."
- "We are ready for questions."

## Bipin-Proof Checklist

## Orientation and Device State

- Rotate the phone on every screen and confirm it remains stable in portrait.
- Verify there is no forced crash when rotating during `Play`, `Connect`, `Cal`, `Library`, `Settings`, `Setup`, or the launch loader.
- Verify the phone does not dim or sleep aggressively during the demo.

## Startup and Permissions

- Launch with Bluetooth already on.
- Launch once with Bluetooth off and confirm the system enable-Bluetooth prompt appears over the loader or visible app screen.
- Deny Bluetooth permission once on a test run and confirm the app stays alive.
- Deny `RECORD_AUDIO` once on a test run and confirm recording fails gracefully.

## BLE Resilience

- Tap `Connect All`, then `Disconnect All`, then `Connect All` again.
- Connect only one glove and confirm Play stays muted or not-ready.
- Disconnect one glove mid-session and confirm the UI reflects the missing glove.
- Turn Bluetooth off while gloves are connected and confirm the app handles it without crashing.
- Turn Bluetooth back on and confirm reconnect logic recovers.

## Calibration Stress

- Tap `Pitch Neutral` multiple times.
- Switch tabs repeatedly during calibration.
- Save calibration after a successful run and verify `Save & Play` returns cleanly.
- Reopen `Cal` and recalibrate without restarting the app.

## Play Screen Stress

- Tap `Play` repeatedly.
- Start and stop recording rapidly.
- Cycle tones quickly through the knob.
- Change octave and scale while audio is active.
- Open and close `BEATS` only if the team intends to show it live.

## Library and Persistence

- Open Library with no recordings on a clean test state and confirm the empty state is stable.
- Kill the app, reopen it, and verify recordings are still listed.
- Kill the app, reopen it, and verify settings still persist.
- Confirm playback from Library works with gloves disconnected.

## Recommended Rehearsal Fallbacks

- If live BLE becomes unreliable, reconnect from `Connect` first instead of waiting silently on `Play`.
- If auto-connect fails, go straight to `Connect` and use the per-glove reconnect buttons.
- If calibration gets awkward, redo only the current neutral step instead of restarting the whole app.
- If live recording fails, switch immediately to a pre-saved Library recording and continue the demo.
- If Bluetooth is misbehaving, narrate the recovery path and show reconnect logic instead of waiting silently.

## What Each Team Member Should Be Ready To Say

Use these as balanced high-level talking points. They are intentionally written as area summaries rather than exclusive ownership claims.

### Niraj Patel

- "My main areas were the firmware-facing and core runtime pieces: the Arduino telemetry path, the BLE session architecture, and the base theremin audio path."
- "On Android, that includes the BLE session flow, glove-state/watchdog foundation, and the real-time synthesis path around `AudioTrack`."

### Matei Moldovan

- "My main areas were UI cohesion and the submission package."
- "That includes layout polish across the user-facing screens, presentation-facing refinement for the demo flow, and the final written material."

### Nirthika Ilaiyarajah

- "My main areas were the recording pipeline, persistence behavior, and performance-oriented validation work."
- "That includes the PCM capture and saved-file flow, repository or settings-side persistence behavior, and latency or responsiveness reasoning."

### Ayan Pirani

- "My main areas were the Play-screen interaction flows and performer-facing controls."
- "That includes recording UI behavior, transport or control wiring, and the Play-side actions for scale, octave, and effects."

### Marie Ella Cambay

- "My main areas were the Library experience and the user-facing management flows around saved performances."
- "That includes browsing, search, organization, rename/delete behavior, and related usability work on the app side."

## Final 15-Minute Pre-Demo Check

- Confirm Pixel 7 battery is above 70%.
- Confirm both gloves are charged and powered.
- Confirm Bluetooth is on.
- Confirm the app launches cleanly.
- Confirm both gloves can connect.
- Confirm Calibration can complete once.
- Confirm live audio plays in `Play`.
- Confirm one recording can be created and appears in `Library`.
- Confirm there is at least one backup recording already saved.
- Confirm the presenter knows whether the demo phone is in first-launch or returning-user state.
