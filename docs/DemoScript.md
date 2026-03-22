# Sprint 3 Demo Script — Theremin Gloves

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026
**Team 5:** Niraj Patel, Ayan Pirani, Marie Ella Cambay, Nirthika Ilaiyarajah, Matei Moldovan
**Version:** Final Demo (Sprint 3)
**Target duration:** 7 minutes
**Prepared by:** Matei Moldovan

---

## Pre-Demo Checklist

Before starting the demo, verify:

- [ ] Both gloves are charged and powered on
- [ ] Pixel 7 Bluetooth is enabled
- [ ] App is installed (latest APK from `develop` branch)
- [ ] App has been opened at least once so permissions are granted
- [ ] Background audio is enabled in Settings
- [ ] A test recording from a previous session exists in Library (to show if live recording fails)
- [ ] `scrcpy` or screen mirroring is active if projecting to a screen
- [ ] Room is reasonably quiet so theremin audio is audible through the phone speaker

---

## Demo Flow

### [0:00] Launch App from Home Screen

**Action:** Show the home screen with app title and Play button.

**Say:** "This is Theremin Gloves — a wireless musical instrument controlled entirely by hand gestures. There is no keyboard, no strings, and no hardware expertise required. You wear two BLE gloves and move your hands to play."

---

### [0:30] Connect Both Gloves

**Action:** Navigate to the Connect screen. Power on both gloves if not already on. Wait for both chips to show "Connected."

**Say:** "Two BLE gloves connect automatically. The right glove controls pitch — the musical note. The left glove controls volume. Both gloves appear within about 10 seconds."

**Watch for:** If the scan times out before showing the gloves, tap the Scan button manually. If a glove connects but shows "stale" after a few seconds, the watchdog will auto-reconnect — narrate this as a feature: "The app monitors for stale telemetry and reconnects automatically."

---

### [1:30] Calibrate

**Action:** Navigate to the Calibration screen. Hold both hands in a relaxed neutral position. Tap Calibrate.

**Say:** "Calibration captures the neutral hand position as a baseline. Everything is measured as a delta from this point — so the instrument adapts to the performer, not the other way around."

**Wait for:** The calibration success message. Then continue.

---

### [2:30] Play a Melody on the Play Screen

**Action:** Navigate to the Play screen. Move the right hand (pitch glove) up and down to change pitch. Slowly raise and lower the left hand (volume glove) to show volume control. Play a simple ascending gesture.

**Say:** "Now I'm playing. Moving my right hand raises pitch, moving it down lowers it. The left hand controls volume — bring it up to increase, down to silence. The waveform visualizer shows the live audio output in real time."

**Demonstrate:** A slow ascending gesture with the right hand, then a volume swell with the left hand.

---

### [3:30] Show Waveform Switching with the ToneKnob

**Action:** Rotate the tone knob on the play screen to cycle through SINE, SAW, ORGAN, and BELL waveforms. Play a note on each.

**Say:** "We have 9 waveform shapes — sine, square, triangle, sawtooth, pulse, organ, string, bell, and pad. Each gives the instrument a completely different character. Listen to the difference between sine and organ."

---

### [4:00] Enable Drum Backing

**Action:** Tap the Drum toggle button on the play screen.

**Say:** "The drum backing adds a rhythmic layer. It uses a 4/4 pattern at 120 BPM — kick on beats 1 and 3, snare on 2 and 4, hi-hat on every 16th note. It runs in a separate thread so it never affects pitch or volume gesture response. And it gets mixed directly into recordings."

---

### [4:30] Enable an Audio Effect

**Action:** Expand the effects panel. Toggle Reverb on.

**Say:** "Real-time reverb is processed directly inside the audio engine — on the same thread that generates each sample. The reverb, delay, and distortion are all available simultaneously. Notice how the sound now has a sense of space."

**Optional:** If time allows, also toggle Delay on briefly to demonstrate echo, then disable both.

---

### [5:00] Switch to Pentatonic Scale Lock

**Action:** Tap the Pentatonic chip in the scale selector.

**Say:** "Scale lock snaps the output frequency to the nearest note in the selected scale. Right now I'm in pentatonic — it's impossible to play a wrong note. You can hear that as I move my hand, the pitch jumps cleanly between notes rather than sliding continuously. We also have major, minor, and chromatic — chromatic means no lock at all."

**Demonstrate:** Move the pitch hand slowly while in pentatonic mode so the discrete note steps are audible.

---

### [5:30] Record a Performance

**Action:** Press the Record button. Play the theremin for approximately 15 seconds — include pitch movement, volume variation, and leave the drum and reverb on. Then press Stop.

**Say:** "Recording captures exactly what you hear — including effects, drum backing, and scale quantization. The file is saved locally to the device. No data is uploaded anywhere."

**Watch for:** The blinking red indicator and timer confirm recording is active. After stop, a "Recording saved" Toast should appear.

---

### [6:00] Open the Library Screen

**Action:** Navigate to the Library screen. Tap play on the just-recorded file.

**Say:** "The library stores all recordings locally. You can search by name, rename files, organize into folders, and delete. The metadata shows the date and duration. Playback works even when the gloves are not connected."

---

### [6:30] Show Performance Mode

**Action:** Navigate back to the Play screen. Tap the performance mode button (fullscreen icon).

**Say:** "Performance mode hides all the setup controls — calibration sliders, debug log, glove commands, status labels — leaving only what a performer needs on stage. The visualizer, tone knob, gloves status, recording button, and all the musical controls stay visible. Tap again to exit."

**Action:** Tap again to exit performance mode, showing the controls return.

---

### [7:00] Q&A / Wrap-Up

**Say:** "That covers the full feature set. BLE gloves, real-time theremin synthesis, 9 waveforms, audio effects, scale lock, octave shift, gesture sensitivity, recording, and a full library. Happy to take questions."

---

## If Something Goes Wrong

| Problem | Recovery |
|---------|---------|
| Glove won't connect | Tap Scan button manually; wait 15s; power cycle the glove |
| Glove connects then shows stale | Wait — watchdog auto-reconnects within 20s; narrate as a feature |
| No audio on play screen | Check Settings: background audio must be enabled; tap Audio Start button on play screen |
| App crashes | Have the APK pre-installed; restart and continue from the play screen; the Library recording will still be there |
| Recording file is empty | Use the test recording prepared before the demo; narrate that live recording has already been tested successfully |
| Scale lock not snapping audibly | Slow down hand movement so discrete steps are clearer; pentatonic is the easiest to hear |
| Effects not audible | Increase volume with left glove first; effects are more noticeable at higher volumes |

---

## Rehearsal Schedule

- **April 5:** Full run-through with all team members present. Target: under 7 minutes.
- **April 6:** Dry run with projector/scrcpy setup. Confirm audio is audible through phone speaker from 3 meters.
- **April 7–10:** Presentation window. Arrive 15 minutes early. Have the APK pre-installed.

---

*Document prepared by Matei Moldovan. Demo script based on the A-13 deliverable in CLAUDE.md Sprint 3 documentation.*
