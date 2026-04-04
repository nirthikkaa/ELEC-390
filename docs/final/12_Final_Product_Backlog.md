# Theremin Gloves — Final Product Backlog

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026  
**Team 5:** Marie Ella Cambay, Niraj Patel, Ayan Pirani, Nirthika Ilaiyarajah, Matei Moldovan

---

## Completed Items

Stories completed across all three sprints.

| Story ID | Story Title | Sprint | Points | Acceptance Criteria Met |
|---|---|---|---|---|
| HD-1 | Connection of both gloves | Sprint 1 | 8 | Both gloves discover and connect over BLE; IMU notifications arrive continuously; connection state shown in UI. |
| ID-1 | Connection UI | Sprint 1 | 3 | Status labels show Waiting / Connecting / Connected; Connect All / Disconnect All and per-glove buttons work. |
| HD-2 | Auto Reconnect | Sprint 1 | 5 | Disconnect detected within ~3 s; auto-reconnect starts automatically (`AUTO_RECONNECT_DELAY_MS = 1500`); manual reconnect per glove available. |
| HD-3 | Calibration System | Sprint 1 | 8 | Calibration completes in under 30 s; neutral position captured on glove; saved angles persist. |
| ID-2 | Recalibration Reset | Sprint 1 | 5 | Recalibrate button available on calibration screen; recalibration runs without disconnecting gloves. |
| HD-4 | Real-Time Audio Feedback | Sprint 1 | 8 | Audio starts after calibration; audio mutes when gloves disconnect; playback stable. |
| HD-5 | Pitch Control (Right Glove) | Sprint 1 | 8 | Right glove controls pitch; pitch range covers a playable melody; pitch stable when hand is steady. |
| HD-6 | Volume Control (Left Glove) | Sprint 1 | 5 | Left glove controls volume; minimum position = silence; smooth ramp. |
| HD-7 | Smoothing / Filtering | Sprint 1 | 8 | `FREQ_SMOOTHING`, `ATTACK_SMOOTHING`, `RELEASE_SMOOTHING` suppress jitter without perceptible lag. |
| ID-3 | Pitch & Volume Range Settings | Sprint 1 | 3 | Pitch/volume angle and frequency ranges adjustable in Calibration and Settings; settings persist. |
| ID-12 | Gesture Visualization | Sprint 1 | 3 | Live waveform visualizer on play screen reacts to frequency and volume in real time. |
| ID-22 | App Navigation System | Sprint 1 | 3 | Bottom navigation bar with Play, Connect, Calibration, Library, Settings; navigation does not interrupt audio. |
| ID-23 | Bluetooth Permission Handling | Sprint 1 | 3 | App requests `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+) or `ACCESS_FINE_LOCATION` (API ≤ 30) before scanning. |
| ID-24 | Bluetooth State Detection | Sprint 1 | 2 | App detects Bluetooth-off state and shows system enable prompt. |
| ID-25 | Connection Monitoring Display | Sprint 1 | 3 | Live sensor packet values (`ACTIVE_DELTA_DEG`, `NEUTRAL_ROLL_DEG`, `DIRECTION`) shown on connect screen. |
| ID-26 | Home / Launch Screen | Sprint 1 | 2 | Launch screen with app title and Play button; routes first-time vs. returning users correctly. |
| ID-27 | Settings Configuration | Sprint 1 | 3 | Settings screen: background audio toggle, extended frequency range toggle (20 Hz–20 kHz), calibration guide toggle, calibration screen link. |
| ID-4 | Record Audio Clip | Sprint 2 | 5 | Record button starts capture; blinking indicator + timer shown; stop saves file locally; WAV or AAC depending on quality setting. |
| ID-5 | Replay Recordings | Sprint 2 | 3 | Library screen lists all saved recordings; search, rename, and delete work; tap plays recording; playback works without gloves connected. |
| ID-8 | Privacy: Local Storage Only | Sprint 2 | 3 | All recordings stored in `getFilesDir()/recordings/` (app-private); no automatic upload; delete works from Library. |
| ID-9 | Final UI Polish | Sprint 2 | 3 | Play screen clean with visualizer, tone knob, connection chips, and record button; buttons large enough for demo. |
| ID-21 | Tone Shape Selection | Sprint 2 | 5 | 10 user-selectable tones (THEREMIN, AIR_PAD, CELLO, PAD, CHOIR, FLUTE, CLARINET, TRIANGLE, SAW, HELICOPTER) via `ToneKnobView` rotary selector; switching does not crash audio. |
| ID-10 | Background Drum Kit | Sprint 3 | 5 | Drum backing enabled/disabled from play screen; loop runs in rhythm for 5+ minutes; drum audio captured in recordings; 8 preset patterns; BeatMaker pattern editor. |
| HD-13 | Audio Effects Engine | Sprint 3 | 13 | Reverb (comb filter), delay (ring buffer), distortion (tanh) all functional; toggled/adjusted on play screen; effects captured in recordings; no crash during simultaneous use. |
| HD-29 | Scale Lock | Sprint 3 | 11 | CHROMATIC / MAJOR / MINOR / PENTATONIC available; frequency snaps to nearest in-scale note; binary search via precomputed table — no `Math.pow` in audio thread. |
| HD-30 | Octave Shift | Sprint 3 | 6 | Octave +/- buttons on play screen; range −2 to +2; applied in `ThereminBackgroundAudioService.pushTargets()` as `freq *= 2^shift`; persisted in `AppSettings`. |
| ID-11 | Gesture Sensitivity Settings | Sprint 3 | 5 | Response-curve exponent (0.25–2.50) applied in `PlayMappingState.recompute()`; selector in Settings; persisted; takes effect immediately. |
| HD-16 | Performance Mode | Sprint 3 | 8 | Single toggle button hides debug log, mapping sliders, glove commands, and status labels; visualizer, tone knob, connection chips, record button remain visible; state persists across restart. |
| HD-11 | Gesture Latency Optimization | Sprint 3 | 8 | Latency benchmarked: ~21.3 ms audio buffer (`AUDIO_WRITE_FRAMES=1024`); background path ~38–61 ms; foreground path ~55–91 ms (bounded by `UI_TICK_MS=50`). No code changes warranted — architecture is already optimal. |

**Total completed: 31 stories**

---

## Not Implemented (Out of Scope)

Stories that were in the backlog but not implemented in any sprint.

| Story ID | Story Title | Reason Not Implemented |
|---|---|---|
| HD-8 | Battery / Power Check | Arduino Nano 33 BLE Sense does not expose the standard BLE Battery Service (0x180F). Cannot read battery % without custom firmware. |
| HD-9 | Gesture Recognition Expansion | Out of scope for course demo; pitch + volume gesture set is sufficient for the instrument. |
| HD-10 | Hand Orientation Detection | Out of scope; covered by existing wrist-roll angle telemetry. |
| HD-12 | Instrument Switching | Covered by the 10-tone selector (ID-21) and drum engine (ID-10). Not implemented as a separate preset-based instrument switcher. |
| HD-14 | Metronome Engine | Replaced by the Drum Kit (ID-10), which includes BPM control and rhythmic backing. |
| HD-15 | Loop Recording | Out of scope for the course demo. |
| HD-17 | Gesture Presets | Out of scope. |
| HD-18 | Multi-Track Recording | Out of scope. |
| HD-19 | Multi-User Session | Out of scope. |
| ID-6 | Export Visualizer Video | Out of scope; `RecordingExportManager` supports audio export to `Music/Theremin Gloves Recordings` but not video. |
| ID-7 | Share Clip | Out of scope for this submission. |
| ID-13 | Instrument Selection UI | Covered by `ToneKnobView` rotary selector (ID-21). |
| ID-14 | Effects Control Panel | Covered as part of HD-13 (Audio Effects Engine) — sliders and toggles are on the play screen. |
| ID-15 | Metronome UI | Replaced by Drum Kit UI (ID-10). |
| ID-16 | Loop Control UI | Out of scope. |
| ID-17 | Preset Selection UI | Out of scope. |
| ID-18 | Track Mixer UI | Out of scope. |
| ID-19 | Visual Performance Timeline | Out of scope. |
| ID-20 | Session Management UI | Out of scope. |
| HD-21 | Dead Zone | Explicitly excluded: micro-tremors already suppressed by `FREQ_SMOOTHING=0.003f` and `mapLinearClamped()` clamping in `PlayMappingState`. A separate dead-zone constant is not needed. |

**Total not implemented: 20 stories**

---

## Summary

| Category | Count |
|---|---|
| Completed | 31 |
| Not implemented (out of scope) | 20 |
| **Total in backlog** | **51** |
