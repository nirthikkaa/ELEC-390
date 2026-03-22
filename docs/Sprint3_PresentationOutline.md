# Sprint 3 Final Oral Presentation Outline — Theremin Gloves

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026
**Team 5:** Niraj Patel (40211758), Ayan Pirani (40276971), Marie Ella Cambay (40284457), Nirthika Ilaiyarajah (40298669), Matei Moldovan (40277256)
**Instructor:** Dr. William Lynch
**Presentation Window:** April 7–10, 2026
**Duration:** 10–12 minutes

---

## Time Budget

| Section | Time | Slides |
|---------|------|--------|
| Title + Team intro | 0:30 | 1 |
| Customer identification | 1:00 | 1 |
| Value proposition | 1:00 | 1 |
| System architecture | 1:30 | 1 |
| BLE communication | 1:30 | 1 |
| Signal processing pipeline | 1:00 | 1 |
| Audio engine + effects | 1:30 | 1 |
| Sprint 3 features | 1:30 | 1 |
| Recording system | 1:00 | 1 |
| Live demo transition | 0:30 | 1 |
| Conclusions | 1:00 | 1 |
| **Total** | **~12:00** | **11** |

Keep slides visual — prefer diagrams and screenshots over bullet points. One idea per slide.

---

## Slide-by-Slide Breakdown

---

### Slide 1 — Title

**Content:**
- Title: "Theremin Gloves"
- Subtitle: "A wireless gesture-controlled musical instrument"
- Team 5 — Concordia University, COEN/ELEC 390, Winter 2026
- Team member names and student IDs
- Course instructor: Dr. William Lynch

**Speaker:** All (introduce yourselves briefly)

**Notes:** Keep this under 30 seconds. Transition directly to customer slide.

---

### Slide 2 — Customer Identification

**Speaker:** Marie Ella

**Content:**
- Who is the customer?
  - Musicians and performers who want an expressive instrument without hardware expertise
  - Content creators who want to record and share unique performances
  - Music students learning about electronic instruments and gesture control
  - Hobbyists interested in DIY wearable computing projects
- Photo or illustration of someone performing (if available)

**Notes:** Emphasize that the target user does not need to know how BLE works or how audio synthesis works — they just wear the gloves and play.

---

### Slide 3 — Value Proposition

**Speaker:** Marie Ella

**Content:**
- Key differentiators:
  - Wireless — no wires, no keyboard, no traditional instrument skill
  - Expressive — continuous gesture control of both pitch and volume
  - Complete instrument — 9 waveforms, audio effects, scale lock, drum backing
  - Record + library — save and replay performances locally
- Optional: compare to a traditional theremin (requires expensive hardware, fixed pitch/volume mapping)

**Notes:** "With two BLE gloves and an Android phone, anyone can play an expressive electronic instrument in under 5 minutes."

---

### Slide 4 — System Architecture

**Speaker:** Nirthika

**Content:**
- Architecture diagram (draw or use the one from the design document):
  ```
  Arduino Nano 33 BLE Sense (x2)
      └── BLE GATT notifications
              └── BleSessionManager (Android)
                      └── BleSnapshot
                              └── ThereminBackgroundAudioService (20ms sync)
                                      ├── PlayMappingState (freq + volume)
                                      └── ThereminAudioEngine (AudioTrack)
                                              ├── 9 waveforms
                                              ├── Effects pipeline
                                              ├── Scale quantization
                                              └── PCM tap → RecordingManager
  ```
- Emphasize: all processing is on-device, no cloud dependency

**Notes:** Nirthika has the most context on the BLE + service architecture. Keep the diagram clean — one box per major component.

---

### Slide 5 — BLE Communication

**Speaker:** Nirthika

**Content:**
- Hardware: Arduino Nano 33 BLE Sense (IMU glove)
- Packet format: `ACTIVE_DELTA_DEG:<float>` — angle delta from neutral position
- BLE UUIDs: service `12345678-1234-1234-1234-1234567890ab`
- Reconnect logic: watchdog timer checks telemetry freshness every 1s; auto-reconnects at 20s stale
- Latency breakdown:
  - BLE interval: ~7.5–20ms
  - Sync loop: 20ms
  - Audio buffer: 42.7ms
  - **Typical total: ~55–70ms** (HD-11 criterion: < 80ms — passes)

**Notes:** Use a simple sequence diagram if possible: Glove → BLE → BleSessionManager → snapshot → service → engine.

---

### Slide 6 — Signal Processing Pipeline

**Speaker:** Ayan

**Content:**
- IMU angle → linear mapping to frequency/volume
- `PlayMappingState.recompute(BleSnapshot)`: `mapLinearClamped(angleDeg, min, max, freqMin, freqMax)`
- Calibration establishes neutral position; all control is relative to that baseline
- Gesture sensitivity: multiplier scales effective angle span (High=0.5×, Medium=1.0×, Low=1.5×)
- Octave shift: `freq *= 2^octaveShift` applied after mapping

**Notes:** A simple diagram showing angle → mapping function → frequency works well here.

---

### Slide 7 — Audio Engine and Effects

**Speaker:** Niraj

**Content:**
- `ThereminAudioEngine`: `AudioTrack`, `THREAD_PRIORITY_AUDIO`, 2048-sample buffer at 48kHz
- 9 waveforms: SINE, SQUARE, TRIANGLE, SAW, PULSE, ORGAN, STRING, BELL, PAD
- Selected via `ToneKnobView` (custom rotary dial)
- Effects pipeline (Sprint 3):
  - Reverb: Schroeder comb filter (`combBuffer[4800]`, ~100ms)
  - Delay: ring buffer (`delayBuffer[24000]`, 500ms), configurable feedback
  - Distortion: `tanh(x * gain) / tanh(gain)` normalization
- All arrays pre-allocated — zero heap allocation on audio thread

**Notes:** "The audio thread runs at `THREAD_PRIORITY_AUDIO` — highest priority available on Android. Every sample is computed in a tight loop with no allocation, no logging, no blocking calls."

---

### Slide 8 — Sprint 3 Features

**Speaker:** Ayan

**Content:**
- Drum Backing Kit (ID-10): `DrumEngine`, `SoundPool`, 16th-note scheduler, default 4/4 pattern at 120 BPM; mixed into recordings
- Scale Lock (HD-29): MIDI-snap to CHROMATIC / MAJOR / MINOR / PENTATONIC; applied after frequency smoothing
- Octave Shift (HD-30): -2 to +2 octaves; +/- buttons on play screen
- Gesture Sensitivity (ID-11): three presets; RadioGroup in Settings
- Performance Mode (HD-16): hides setup controls, shows only performer-facing UI; state persisted

**Notes:** This is a feature showcase slide. Use icons or small screenshots if available. Keep descriptions to one line each.

---

### Slide 9 — Recording System

**Speaker:** Matei

**Content:**
- PCM tap: `ThereminAudioEngine.setPcmListener(RecordingManager)` — receives every buffer fill
- Quality modes: LOSSLESS (WAV), HIGH/MEDIUM/LOW (AAC-LC via `MediaCodec` + `MediaMuxer`)
- Storage: `context.getFilesDir()/recordings/` — app-private, no upload, no cloud
- Library screen: search, rename, folders, drag-to-reorder, swipe-to-delete, `MediaPlayer` playback
- Drum mix-in: drum PCM summed with theremin PCM before encoder; effects already applied at tap point

**Notes:** Emphasize privacy: "There is no internet permission. Recordings cannot leave the device without the user explicitly choosing to share them."

---

### Slide 10 — Live Demo

**Speaker:** All

**Content:**
- Transition slide: large text "Live Demo"
- Optional: QR code to download APK

**Notes:** Follow the `docs/DemoScript.md` script. Target 7 minutes. Have a backup recording pre-loaded in the Library in case live recording fails.

---

### Slide 11 — Conclusions

**Speaker:** All

**Content:**
- What we built: a complete wireless theremin instrument with recording, effects, scale lock, drum backing, and performance mode — built in 3 sprints over 12 weeks
- What worked well: Nordic BLE library stability, AudioTrack performance, additive SQLite migration pattern, PCM tap design
- What we would do with more time:
  - Accessibility: on-screen slider fallback for users without gloves
  - Export: video export of visualizer synchronized to audio (ID-6)
  - Multi-track recording (HD-18)
  - Lower audio latency via Oboe (C++ audio API) for < 20ms buffer
- Questions?

---

## Rehearsal Notes

- Rehearse at least twice before presentation day. First run: timing check. Second run: projector + audio setup.
- Assign one person to manage the phone during the live demo. Do not pass the phone — place it on a stand visible to the audience.
- Each speaker should know the next speaker's first sentence so handoffs are smooth.
- If a team member is absent, the remaining members should be prepared to cover their slides.

---

*Document prepared by Matei Moldovan. Presentation structure based on A-12 deliverable in CLAUDE.md Sprint 3 documentation.*
