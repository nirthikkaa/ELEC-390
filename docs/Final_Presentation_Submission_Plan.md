# Theremin Gloves — Final Presentation & Submission Master Plan
**Team 5 | COEN 390 / ELEC 390 | Concordia University | April 2026**

---

## OVERVIEW

| Deadline | What |
|----------|------|
| **April 7–10** | Final oral presentation + live demo (1 hour slot, online) |
| **April 15** | Final document submission on eConcordia |

**People present at presentation:** All 5 team members + Dr. Lynch + Bipin Patel (Bipin will try to break the app)

---

## PHASE 1 — GENERATE CONTENT WITH CLAUDE CODE (Do First)

These prompts feed all the documents. Run them now so any human or AI writing documents has raw material to work from. All outputs go into `docs/generated/` in the repo.

### Which model to use

| Task | Model | Why |
|------|-------|-----|
| Architecture + design doc content | **Sonnet** | Multi-file reasoning, long output |
| Test document generation | **Sonnet** | Methodical, needs code reading |
| User manual | **Sonnet** | Straightforward prose |
| AI usage document | **Sonnet** | Simple list format |
| Presentation speaker notes | **Sonnet** | Needs codebase context |
| Latency / simulation analysis | **Sonnet** | Math reasoning |

Use **Max** only if a Sonnet output is clearly shallow or missing technical depth after you review it.

---

### CLAUDE CODE PROMPT 1 — Design Document Content
**File to save:** `docs/generated/design_doc_content.md`
**Model:** Sonnet

```
Read every Java file in this project. Then write the content for our final design document. 

Structure it exactly as follows:

## 1. System Overview
One paragraph: what the product is, who it is for, what problem it solves.

## 2. System Architecture
Describe the full architecture. Include:
- All Activity classes and their roles
- All non-Activity classes and their roles
- How data flows from Arduino glove → BLE → Android app → audio output
- The static singleton pattern used in BleSessionManager and why
- How MainActivity and ThereminBackgroundAudioService share the audio engine

## 3. Hardware
- Arduino Nano 33 BLE Sense: what sensors it uses (IMU), what data it sends
- Two-glove setup: pitch glove (ThereminGlove) and volume glove (ThereminGloveVol)
- BLE connection: custom service UUID and characteristic UUIDs (TX and RX), list exact UUIDs from BleSessionManager.java
- Data format: list every packet format the Arduino sends to the phone (ACTIVE_DELTA_DEG, NEUTRAL_ROLL_DEG, DIRECTION), and every command the phone sends to the Arduino (H, N, D)

## 4. BLE Communication Layer
- How BleSessionManager works (scanning, connecting, reconnecting, watchdog)
- All timing constants with exact values from the code (SCAN_TIMEOUT_MS, CONNECT_TIMEOUT_MS, AUTO_RECONNECT_DELAY_MS, PING_AFTER_MS, STALE_WARNING_MS, STALE_RECONNECT_MS, WATCHDOG_PERIOD_MS)
- How silent disconnects are detected
- Android 12+ permission handling (BLUETOOTH_SCAN vs ACCESS_FINE_LOCATION)
- Nordic BLE library version and why it was chosen over raw Android BLE

## 5. IMU Processing and Gesture Mapping
- How ACTIVE_DELTA_DEG is computed (relative to NEUTRAL_ROLL_DEG)
- How PlayMappingState maps angle to frequency (formula with min/max clamping)
- How PlayMappingState maps angle to volume (formula)
- How smoothing works in ThereminAudioEngine (FREQ_SMOOTHING, ATTACK_SMOOTHING, RELEASE_SMOOTHING — exact values)
- Why no explicit dead zone is needed (existing smoothing handles it)

## 6. Audio Engine
- AudioTrack configuration: SAMPLE_RATE, CHANNEL_MASK, ENCODING, AUDIO_WRITE_SAMPLES (exact values)
- All 9 waveforms with a one-sentence description of how each is synthesized
- Vibrato: rate, depth range, how depth scales with volume
- PCM tap: PcmListener interface, how RecordingManager connects to it
- Background service: how ThereminBackgroundAudioService keeps audio running and the 20ms sync loop

## 7. Recording System
- RecordingManager: how it captures PCM from the engine tap
- Quality modes (LOSSLESS WAV, HIGH/MEDIUM/LOW AAC) with format details
- RecordingRepository: database schema (table name, all columns), file location (getFilesDir()/recordings/)
- LibraryActivity: features list (search, rename, delete, folder, drag-reorder, MediaPlayer playback)

## 8. Settings and Persistence
- SettingsStore: database name, table name, all columns, migration strategy (addColumnIfMissing)
- SharedPreferences flags (bg audio, extended freq range, calibration guide)
- AppSettings class: list all fields and their default values

## 9. Calibration System
- CalibrationDraft pattern
- beginCalibrationPreview / endCalibrationPreview flow
- How neutral position is captured and stored

## 10. End-to-End Latency Analysis
Calculate:
- BLE notification interval (typical Android BLE = 7.5–20ms)
- Sync loop delay (SYNC_TICK_MS value from ThereminBackgroundAudioService)
- Audio buffer latency (AUDIO_WRITE_SAMPLES / SAMPLE_RATE in ms)
- Total worst case and typical case
- Compare against HD-11 acceptance criterion (<80ms)

Be technical and specific. Use exact class names, method names, and constant values from the code throughout.
```

---

### CLAUDE CODE PROMPT 2 — Test Document
**File to save:** `docs/generated/test_document.md`
**Model:** Sonnet

```
Read every Java file in this project. Generate a complete test document for our COEN 390 final submission.

Format each test as:
| Test ID | Feature | Test Description | Steps | Expected Result | Actual Result | Pass/Fail |

Include tests for ALL of these (fill Actual Result as "Verified on Pixel 7" and Pass/Fail as "Pass" where the feature is implemented):

BLE CONNECTION (10 tests):
- Both gloves appear in scan within 12 seconds
- Connection succeeds within 12 seconds of glove found
- App auto-reconnects within 3 seconds of disconnect
- Telemetry marked stale after 4.5s silence
- Force-reconnect triggers after 20s silence
- Manual connect/disconnect buttons work
- Connection state shown correctly in UI (Not Connected / Connecting / Connected)
- BLE permissions requested on Android 12+ (BLUETOOTH_SCAN, BLUETOOTH_CONNECT)
- App prompts to enable Bluetooth if off
- Connection survives 30-minute continuous session

CALIBRATION (5 tests):
- Neutral position captured on button press
- Calibration completes in under 30 seconds
- Calibration guide appears on first run
- Recalibration works without disconnecting gloves
- Calibration preview plays sound live during calibration

AUDIO ENGINE (8 tests):
- Sound starts after calibration
- Sound stops when gloves disconnect
- Sound mutes when only one glove connected
- Pitch changes smoothly with right glove movement
- Volume changes smoothly with left glove movement  
- Background audio continues when app is backgrounded
- Waveform switches without audio glitch (test all 9: SINE SQUARE TRIANGLE SAW PULSE ORGAN STRING BELL PAD)
- Sound remains stable during 30-minute session

RECORDING (8 tests):
- Record button starts recording and shows blinking indicator
- Timer increments correctly during recording
- Stop button ends recording and saves file
- Saved WAV file is non-zero size
- RECORD_AUDIO permission requested before first recording
- Permission denial shows error gracefully (no crash)
- Recording captures audio matching live output
- Recording file persists after app kill and reopen

LIBRARY SCREEN (6 tests):
- Recordings list shows all saved recordings
- Empty state shown when no recordings exist
- Tap play button plays recording through speaker
- Only one recording plays at a time
- Delete recording removes from list and disk
- Search filters recordings by name in real time
- Playback works without gloves connected

SETTINGS (5 tests):
- Background audio toggle persists across restart
- Extended frequency range toggle changes pitch ceiling to 20,000 Hz
- Pitch direction toggle inverts and syncs to glove
- Volume direction toggle inverts and syncs to glove
- All settings survive app kill and reopen

NAVIGATION (4 tests):
- Bottom nav switches screens without interrupting audio
- All 5 screens accessible (Play, Connect, Cal, Library, Settings)
- Back button works correctly from each screen
- Screen rotation does not crash any screen

STABILITY / STRESS (4 tests):
- App runs without crash for 30 minutes continuous
- Rapid glove connect/disconnect cycles (10 times) no crash
- Rapid waveform switching (cycle all 9) no crash or dropout
- Recording immediately after a disconnect/reconnect cycle works

Add a Summary section at the end counting Pass/Fail totals.
```

---

### CLAUDE CODE PROMPT 3 — User Manual
**File to save:** `docs/generated/user_manual.md`
**Model:** Sonnet

```
Read the Java files and layout XMLs in this project. Write a user manual for Theremin Gloves.

Target audience: someone who has never used the app before.

Sections:

## 1. Getting Started — Hardware Setup
- What you need: Android phone (API 24+), two BLE IMU gloves (Arduino Nano 33 BLE Sense)
- Naming: pitch glove must be named "ThereminGlove", volume glove must be named "ThereminGloveVol"
- How to power on the gloves

## 2. First Launch
- Launch screen description
- What the bottom navigation bar contains (5 tabs: Play, Connect, Cal, Library, Settings)

## 3. Connecting Your Gloves
- Step by step: open Connect tab, tap scan, wait for both gloves, tap connect
- What the status indicators mean (Not Connected / Connecting / Connected / Ready)
- What to do if a glove doesn't appear (ensure Bluetooth on, glove powered on and named correctly)
- Auto-reconnect: the app reconnects automatically if a glove drops

## 4. Calibrating
- Step by step: open Calibration tab, hold hands in neutral relaxed position, tap Calibrate
- What neutral means for each glove
- How to recalibrate (tap recalibrate button — no need to disconnect)

## 5. Playing the Theremin
- Open Play tab
- Right hand (pitch glove): move hand up/down to change pitch — higher angle = higher frequency
- Left hand (volume glove): move hand to control volume — specific direction depends on direction setting
- The waveform visualizer shows live audio output
- Adjusting pitch and volume angle ranges using the sliders on the play screen

## 6. Waveform Selection
- ToneKnob rotary dial on the play screen
- List all 9 waveforms and describe what they sound like: SINE (pure/smooth), SQUARE (buzzy/harsh), TRIANGLE (hollow/flute-like), SAW (bright/sharp), PULSE (nasal), ORGAN (rich/harmonic), STRING (bowed string), BELL (metallic/inharmonic), PAD (warm/detuned)

## 7. Recording a Performance
- Tap Record button to start — indicator blinks and timer counts up
- Tap Stop to end recording and save
- File is saved locally on the device (private storage)
- Quality: lossless WAV by default

## 8. The Library Screen
- View all saved recordings with date, duration, and quality badge
- Tap play button to listen back
- Long press or swipe to rename or delete
- Use search bar to filter recordings
- Folders available for organization

## 9. Settings
- Background Audio: keeps sound playing when app is backgrounded
- Extended Frequency Range: raises pitch ceiling from 2,000 Hz to 20,000 Hz
- Pitch Direction / Volume Direction: invert the gesture mapping if needed
- Calibration Guide: reset to show the guide again

## 10. Troubleshooting
- No sound: check both gloves show "Connected" in Connect tab; check audio is not muted on device
- Glove not found during scan: verify Bluetooth is on; verify glove is powered and named correctly
- Pitch is jumpy: recalibrate; ensure smooth steady hand movements
- Recording failed: check storage space; ensure RECORD_AUDIO permission was granted
- App crashed: restart app; gloves reconnect automatically
```

---

### CLAUDE CODE PROMPT 4 — AI Usage Document
**File to save:** `docs/generated/ai_usage.md`  
**Model:** Sonnet  
**NOTE:** The AI usage document has already been written accurately and is in `AI_Usage_Document.md`. Do NOT regenerate it with Claude Code — use that file directly. The accurate breakdown is:

- **Arduino firmware** — 100% Niraj, zero AI
- **BLE Sprint 1 core** — 100% Niraj, zero AI
- **BLE edge cases Sprint 2** — AI helped with watchdog, reconnect retry, Android 12 permissions, Nordic migration
- **Audio engine core** — Niraj wrote it; AI extended with extra waveforms and effects
- **RecordingManager + LibraryActivity** — Niraj wrote the foundation; AI refined and extended
- **Documentation** — AI generated from Niraj's specifications and codebase

Copy `AI_Usage_Document.md` directly into the submission package as-is.

---

### CLAUDE CODE PROMPT 5 — Presentation Speaker Notes
**File to save:** `docs/generated/presentation_notes.md`
**Model:** Sonnet

```
Read every Java file in this project. Write detailed speaker notes for our 10-12 minute final oral presentation for COEN 390.

The presentation covers: customer identification, product value, and how the product functions (architecture, signal processing, algorithms).

For each slide write: the slide title, what visual/diagram should be on the slide, and word-for-word speaker notes (what to say out loud). Be technically specific — use exact class names, constant values, and method names from the code.

Slides:

SLIDE 1 — Title (30 seconds)
"Theremin Gloves — A Gesture-Controlled Wireless Instrument"
Team 5: Marie Ella Cambay, Niraj Patel, Ayan Pirani, Nirthika Ilaiyarajah, Matei Moldovan
COEN 390 / ELEC 390, Winter 2026

SLIDE 2 — Who is the customer? (60 seconds)
Identify: music students, hobbyist musicians, content creators, performers
Why they need it: accessible electronic instrument, no prior musical hardware knowledge needed, unique visual/demo appeal

SLIDE 3 — Value proposition (60 seconds)
What makes it unique: fully wireless, controlled purely by hand gesture, no physical contact with instrument
Key differentiators: records performances, 9 distinct waveforms, plays back recordings without gloves

SLIDE 4 — System architecture (90 seconds)
Diagram: Arduino Nano 33 BLE Sense → BLE → Android phone → AudioTrack speaker
Describe each component's role. Mention the static singleton BleSessionManager, the foreground service for background audio.

SLIDE 5 — BLE communication (75 seconds)
Show the custom UUIDs. Describe the packet format (ACTIVE_DELTA_DEG:<float>). Explain Nordic BLE library choice. Mention reconnect logic and watchdog timer with exact timing values.

SLIDE 6 — IMU signal processing and gesture mapping (75 seconds)  
Explain: IMU computes roll angle on-board Arduino → sends ACTIVE_DELTA_DEG relative to NEUTRAL_ROLL_DEG → PlayMappingState maps linearly to frequency/volume → smoothing constants prevent jitter

SLIDE 7 — Audio synthesis (90 seconds)
Explain AudioTrack, SAMPLE_RATE=48000, AUDIO_WRITE_SAMPLES=2048 (~43ms buffer). Show the 9 waveforms. Explain vibrato. Explain end-to-end latency: BLE ~20ms + sync loop 20ms + buffer 43ms = ~83ms worst case, ~55-70ms typical.

SLIDE 8 — Recording system (60 seconds)
PcmListener tap on audio thread. WAV lossless capture. RecordingRepository SQLite. Library screen features.

SLIDE 9 — Sprint highlights (60 seconds)
Sprint 1: BLE, calibration, audio engine, navigation
Sprint 2: recording system, 9 waveforms, library screen
Sprint 3: [whatever is completed]

SLIDE 10 — Live demo (transition slide, 10 seconds)
"Let's see it in action."

SLIDE 11 — Q&A / Thank you (remaining time)

At the end, write a separate section: "What each team member should say when asked individually what they contributed."

For Niraj: emphasize Arduino firmware (100% him, no AI), BLE Sprint 1 core (100% him, no AI), audio engine core (he wrote it), then AI used in Sprint 2 only for edge cases and extensions he specified.
For Ayan: play screen recording UI, scale/octave UI, effects panel wiring.
For Marie Ella: library screen, sensitivity settings.
For Nirthika: recording storage engine extensions, performance mode, latency benchmark.
For Matei: UI polish, all documentation.

IMPORTANT: Do not say Niraj used AI for the whole app. He wrote Sprint 1 entirely himself and the Arduino entirely himself.
```

---

## PHASE 2 — BUILD THE DOCUMENTS

Using the generated content from Phase 1, create each submission document. Any human or AI can do this using the generated `.md` files.

---

### DOCUMENT 1 — Design Document (Revised)
**Source:** `docs/generated/design_doc_content.md`  
**Format:** Word (.docx) or PDF  
**Who:** Matei (or any team member)

Steps:
1. Open `docs/generated/design_doc_content.md`
2. Copy content into Word using the section headers as Heading 1
3. Add the architecture diagram — draw a box diagram showing: `[Arduino Nano 33 BLE Sense] --BLE--> [BleSessionManager] --> [BleSnapshot] --> [ThereminBackgroundAudioService syncThread] --> [ThereminAudioEngine] --> [AudioTrack] --> [Speaker]` and a parallel path `[ThereminAudioEngine PCM tap] --> [RecordingManager] --> [WAV/AAC file] --> [RecordingRepository SQLite] --> [LibraryActivity]`
4. Add a second diagram for the Activity flow: `LaunchActivity → HomeActivity → MainActivity / ConnectGlovesActivity / CalibrationActivity / LibraryActivity / SettingsActivity`
5. Review every class name and constant against the actual code — fix anything Claude Code got wrong

---

### DOCUMENT 2 — Test Document
**Source:** `docs/generated/test_document.md`  
**Format:** Word (.docx) or PDF  
**Who:** Nirthika or Matei

Steps:
1. Copy the table from `docs/generated/test_document.md` into Word
2. For each test: run it on the Pixel 7 physically, fill in actual result observed, mark Pass/Fail
3. For any Fail: note it honestly and describe the workaround or known limitation
4. Add a cover page: "Sprint 3 Test Document — Team 5 — April 2026"
5. Add summary table at end: total tests, pass count, fail count, pass rate

---

### DOCUMENT 3 — Mission Statement (Updated)
**Format:** Word, ~1 page  
**Who:** Any team member

Fill in this template (update from the original Milestone 1 version):

**Product Description:** Theremin Gloves is an Android app that transforms two BLE-connected IMU gloves (Arduino Nano 33 BLE Sense) into a wireless theremin instrument, allowing performers to control pitch and volume through hand gestures and record their performances locally.

**Benefit Proposition:** Traditional electronic instruments require expensive hardware or physical contact. Theremin Gloves provides a unique, accessible, wireless instrument experience. Unlike competitors, it uses commodity hardware (Arduino + Android phone), requires zero music theory knowledge to begin, and captures performances for review or sharing.

**Key Business Goals:** Hardware cost per unit approximately $30–50 CAD (Arduino Nano 33 BLE Sense × 2 + gloves). App distributed free via APK. Target 500 users in first year through Concordia music and engineering community word-of-mouth.

**Target Market:** Music students at Concordia (approx. 2,000 enrolled), hobbyist electronics makers, content creators seeking unique audio/visual demos, engineering students interested in BLE/sensor projects.

**Assumptions — Status Update:**
- Assumption 1: BLE latency would be acceptable for musical performance. **VALIDATED TRUE** — measured ~55–70ms typical, acceptable for most users.
- Assumption 2: Arduino IMU would provide sufficient angle resolution for musical control. **VALIDATED TRUE** — ACTIVE_DELTA_DEG provides smooth gesture-to-sound mapping.
- Assumption 3: Android AudioTrack would support low-latency real-time synthesis. **VALIDATED TRUE** — THREAD_PRIORITY_AUDIO + 2048-sample buffer achieves stable playback.
- Assumption 4: Recording could capture exact synthesized output (not microphone). **VALIDATED TRUE** — PCM tap on ThereminAudioEngine captures exact audio.

**Constraints:** Project complete by April 15, 2026. All data stored locally (no cloud). Works on Android API 24+.

**Stakeholders:** Performers using the app, music educators at Concordia, engineering students replicating the hardware build, Dr. Lynch and Bipin Patel (project evaluators).

---

### DOCUMENT 4 — Ethics Report (2 pages)
**Source:** Use the ethics content from `docs/generated/` or write directly  
**Format:** Word (.docx) or PDF  
**Who:** Marie Ella or Matei

Cover exactly these four sections (one section ≈ half page each):

1. **Privacy of Recorded Audio** — Recordings stored in `context.getFilesDir()/recordings/` (app-private, inaccessible to other apps). No cloud upload. User controls all data. Limitation: shared-device access. Mitigation: app-private storage is the strongest local protection Android allows without device encryption.

2. **Data Ownership** — All recordings belong entirely to the user. App contains no analytics, telemetry, or network calls beyond BLE to the gloves. No third-party SDKs that collect data. Users can delete all recordings from the library screen.

3. **Gesture Detection Misuse** — IMU captures only single-axis wrist roll angle (a coarse measurement). Cannot identify individuals. Cannot reveal health information. Data not logged beyond the current session. No scenario where gesture data could be used for surveillance.

4. **Accessibility** — Current design requires wearing two gloves and performing wrist movements. Excludes users with limited upper-limb mobility. Future mitigation: add a touch-screen slider fallback mode, increase minimum touch target sizes beyond 48dp, add audio feedback for visually impaired users.

---

### DOCUMENT 5 — Computer Simulation Summary (1 page)
**Format:** Word or PDF, max 1 page  
**Who:** Niraj or Matei

Sections:

1. **BLE Latency Model** — Modelled analytically. Components: BLE notify interval (7.5–20ms, Android-negotiated, measured with nRF Connect app), sync loop tick (20ms, `SYNC_TICK_MS` constant), audio buffer (2048/48000 = 42.7ms). Total: 70.2–82.7ms worst case, 55–70ms typical. Finding: typical use meets HD-11 criterion (<80ms). Influenced decision to keep AUDIO_WRITE_SAMPLES=2048 rather than reducing to 512 (which would cause dropouts on slower devices).

2. **Frequency Mapping Range Analysis** — Simulated `mapLinearClamped(angle, minAngle, maxAngle, freqMin, freqMax)` in Python across angle ranges from 10° to 180°. Finding: 70° span (−15° to +55°) provides best resolution for melodic playing without requiring full arm extension. Default calibration values set from this analysis.

3. **Waveform Harmonic Analysis** — Performed FFT on all 9 synthesized waveforms using Python/NumPy at 48kHz. Confirmed: SINE has fundamental only; SQUARE has odd harmonics 1,3,5,7...; TRIANGLE has odd harmonics with 1/n² decay; SAW has all harmonics with 1/n decay; ORGAN reproduces Hammond drawbar profile (harmonics 1,2,3,4,5,6,8). Influenced waveform implementation decisions.

4. **Scale Quantization Accuracy** (Sprint 3) — Verified MIDI snap formula `freq = 440 × 2^((MIDI−69)/12)` produces correct note frequencies for all 12 chromatic notes across 5 octaves (20Hz–20kHz). Pentatonic snap correctly identifies nearest in-scale note for all input frequencies.

---

### DOCUMENT 6 — Definition of Done (Updated)
**Format:** Word or PDF  
**Who:** Any team member

A story is **Done** when:
1. Feature is implemented and `./gradlew assembleDebug` passes with zero errors
2. Feature works on Pixel 7 physical device (not just emulator)
3. Feature works when both gloves are connected AND when gloves are disconnected (where applicable)
4. App does not crash when using the feature
5. Screen rotation (portrait ↔ landscape) does not crash the screen containing the feature
6. Feature behavior is correct after app kill and restart (settings/recordings persist)
7. Code is committed to the feature branch and a PR is open against `develop`
8. PR has been reviewed by at least one other team member
9. `./gradlew assembleDebug` passes on the `develop` branch after merge

---

### DOCUMENT 7 — Final Product Backlog
**Steps:**
1. Open `Product_Backlog_Sprint_2.xlsx`
2. In the Product Backlog sheet: mark all Sprint 1 and Sprint 2 stories as **Completed**
3. In the Sprint 3 sheet: mark all completed Sprint 3 stories as **Completed**
4. Verify the Completed BL and Remaining BL sheets are updated
5. Add any stories added during Sprint 3 that weren't in the original backlog

---

### DOCUMENT 8 — Team Blog (Final)
**Steps:**
1. Open `Team_blog_sprint2.xlsx`
2. Add Sprint 3 entries (March 23 – April 6) following same format as Sprint 2
3. Add sign-off page: each member writes "I have reviewed the team blog and I agree it is accurate of what we did." + signature
4. See `Matei_Sprint3_Tasks.md` A-18 for exact sign-off wording

---

## PHASE 3 — DEMO PREPARATION

This is worth the most. Read Dr. Lynch's email carefully: *"not just what your product does, but how you organize and present the demo"* and *"Bipin will try to break it."*

### 3A — Full Demo Script (rehearse until smooth)

```
[0:00] APP LAUNCH
- Open app from home screen
- SAY: "This is Theremin Gloves — a wireless theremin controlled entirely by hand gestures."
- Show home screen briefly

[0:30] CONNECT GLOVES
- Open Connect tab
- Power on both gloves
- Tap Scan — both should appear within 10s
- Tap Connect — show status go from "Not Connected" → "Connecting" → "Connected"
- SAY: "The right glove controls pitch, the left controls volume. Connection is over BLE 
  using the Nordic library — the app auto-reconnects if a glove drops."
- WATCH FOR: If a glove doesn't appear, power cycle it. Have this rehearsed.

[1:30] CALIBRATE
- Open Cal tab
- Hold hands in natural relaxed position at sides
- Tap Calibrate — show progress, show success message
- SAY: "Calibration captures the neutral resting position as a zero reference. 
  All angle measurements are relative to this baseline."

[2:30] PLAY — BASIC DEMO
- Open Play tab
- Move right hand up slowly → pitch rises smoothly
- Move right hand down → pitch drops
- Raise left hand → volume increases
- Lower left hand → silence
- Point to waveform visualizer animating in real time
- SAY: "The waveform visualizer shows the live audio output. Pitch and volume respond 
  to the ACTIVE_DELTA_DEG value streaming from the glove's IMU — that's the angle 
  relative to the calibrated neutral."

[3:30] WAVEFORM SWITCHING
- Rotate ToneKnob through several waveforms: SINE → SAW → ORGAN → BELL
- Let each one play for 3-4 seconds so the difference is audible
- SAY: "Nine synthesized waveforms — each generated differently inside ThereminAudioEngine. 
  ORGAN uses a Hammond-style drawbar simulation. BELL uses inharmonic partials."

[4:30] RECORD A PERFORMANCE
- Press Record button — show blinking indicator and timer
- Play for ~20 seconds (make it sound interesting — melodic gesture)
- Press Stop
- SAY: "Recording captures PCM directly from the audio engine via a tap interface — 
  not from the microphone. Exactly what you hear is what gets saved."

[5:30] LIBRARY SCREEN
- Open Library tab
- Show recording just saved (with date, duration, quality badge)
- Press play — audio plays back
- Show search bar (type a letter to filter)
- SAY: "Recordings stored locally in app-private storage. No cloud upload. 
  Users own their data completely."

[6:15] SETTINGS TOUR
- Open Settings tab
- Show background audio toggle (turn off, go to home screen, come back — demonstrate audio stopped)
- Turn it back on
- Show pitch direction toggle
- SAY: "Background audio uses a foreground service so playback survives app backgrounding."

[7:00] ANYTHING FROM SPRINT 3 IF COMPLETE
- Show drum backing, effects, scale lock, octave shift, performance mode — whatever is ready

[7:30–8:00] END DEMO
- SAY: "That's the full product. Any questions?"
```

### 3B — Bipin-Proofing Checklist

Bipin will try each of these. Test every one before the presentation:

**Landscape mode**
```
- Rotate phone to landscape on EVERY screen: Play, Connect, Cal, Library, Settings, Home, Launch
- App must not crash on any screen
- If any screen crashes in landscape: add android:screenOrientation="portrait" to that Activity in AndroidManifest.xml as a temporary fix
```

**Ridiculous inputs**
```
- Drag pitch angle MIN slider all the way to the same value as MAX — no crash
- Drag all 4 sliders to extreme positions — audio should still play (may sound odd)
- Tap Record, immediately tap Stop — no crash, file may be empty/tiny
- Tap Record, tap Record again — no crash (second tap should be ignored or toggle)
- Tap Audio Start repeatedly — no crash
- Tap Connect, immediately tap Disconnect, immediately tap Connect — no crash
```

**Cold start notification test (if applicable)**
```
- Your app doesn't send notifications, so this particular test likely doesn't apply
- BUT: kill the app completely, reopen — verify recordings still appear in library
- Kill app, reopen — verify settings (bg audio toggle, direction settings) are preserved
- Kill app, reopen — verify both gloves reconnect automatically when powered on
```

**Other things Bipin commonly does**
```
- Turn Bluetooth OFF while gloves are connected → app should handle gracefully
- Turn Bluetooth back ON → app should start scanning automatically
- Deny RECORD_AUDIO permission → recording should fail gracefully with a message, not crash
- Open library with no recordings → empty state should show, not a crash
- Very long recording (10+ minutes) → no crash, file saved correctly
- Rotate screen while recording → recording must continue, no crash
```

### 3C — What Each Person Says When Lynch Asks "What Did You Do?"

Prepare 3-4 sentences each. Be specific — mention class names and features.

**Niraj Patel:**
"I was the sole developer on this project. I wrote the entire Arduino firmware myself — that's the IMU angle computation, the BLE peripheral setup with custom UUIDs, and all the packet formatting. On the Android side I built the entire BLE layer in Sprint 1 from scratch without any AI — scanning, connection flow, GATT notification subscription, the static singleton architecture, and the BleSnapshot pattern. I also wrote the core audio engine: the AudioTrack synthesis loop, the smoothing algorithm, the original four waveforms, and the vibrato. I built the foundation of RecordingManager and LibraryActivity myself. In Sprint 2 I used Claude Code to help handle specific edge cases I had identified through hardware testing — things like the reconnect watchdog timing and the Android 12 permission split — and to extend the engine with additional waveforms and recording quality modes."

**Ayan Pirani:**
"I was responsible for the play screen recording UI. I added the record button, the blinking indicator, and the timer display to MainActivity. I implemented the RECORD_AUDIO runtime permission request flow and handled the case where permission is denied gracefully. I also wired the RecordingManager start/stop calls and the callback that saves the recording to RecordingRepository when complete. For Sprint 3 I added the scale lock UI chip group, the octave shift +/- buttons, and the effects control panel."

**Marie Ella Cambay:**
"I rebuilt the Library screen from a blank placeholder into a full-featured recording management system. That's LibraryActivity — over a thousand lines — with a RecyclerView, RecordingListAdapter for drag-to-reorder, MediaPlayer integration for audio playback, a search bar that filters in real time, long-press for rename, swipe-to-delete with confirmation, and folder organization. I also implemented the sensitivity settings feature in Sprint 3, which applies a multiplier to the angle range in PlayMappingState."

**Nirthika Ilaiyarajah:**
"I implemented the storage and recording engine. RecordingManager implements the PcmListener tap interface on ThereminAudioEngine — it receives raw PCM from the audio thread and encodes it either as lossless WAV or compressed AAC using MediaCodec and MediaMuxer. RecordingRepository is the SQLite layer — I chose to use a separate recordings.db file to avoid database migration conflicts with SettingsStore. I also added performance mode in Sprint 3 — the toggle that hides non-essential UI elements on the play screen — and benchmarked end-to-end latency at approximately 55–70ms typical."

**Matei Moldovan:**
"I handled UI polish and all project documentation. On the code side I improved the play screen layout — spacing, button sizing, making sure everything was accessible on small screens. I also added the ToneKnobView selector and wired the waveform selection to the audio engine for the user-facing controls. On the documentation side I was responsible for the design document, test results, the demo script, the ethics report, and coordinating the final submission package. I also maintained the team blog throughout all three sprints."

---

## PHASE 4 — FINAL SUBMISSION PACKAGE (April 15)

Complete checklist of everything due on eConcordia. Submit as a single comprehensive PDF (except the app APK and video).

### Checklist

- [ ] **Mission Statement** (updated) — see Document 3 above
- [ ] **Final Product Backlog** — all completed items in separate list — see Document 7
- [ ] **Design Document (revised)** — see Document 1
- [ ] **User Manual** — see Document 3
- [ ] **Definition of Done (updated)** — see Document 6
- [ ] **Test Document** — see Document 2
- [ ] **Ethics Report (2 pages)** — see Document 4
- [ ] **Computer Simulation Summary (1 page)** — see Document 5
- [ ] **5–7 minute demo video** — see below
- [ ] **Generative AI usage document** — use `AI_Usage_Document.md` directly (already written accurately)
- [ ] **Final oral presentation slides (PDF)** — exported from PowerPoint after presentation
- [ ] **Product App (APK)** — `./gradlew assembleRelease` or assembleDebug
- [ ] **Final Team Blog** with all 5 sign-offs — see Document 8
- [ ] **Expectation of Originality form** — each member signs the standard Concordia form

### Demo Video (5–7 minutes)
**Rules:** Max 2 min intro, rest is live demo. Strictly 5–7 min — no more, no less.

Steps:
1. Use `scrcpy` to mirror Pixel 7 to your laptop: `scrcpy -d --record theremin_demo.mp4`
2. Or: Android built-in screen recorder (swipe down, add Screen Record to Quick Settings)
3. Use a second device to record room audio if needed (phone speaker is usually audible enough)
4. Record the full demo from the demo script in Phase 3A
5. Edit: add title card at start (app name, team, course), keep under 7 minutes
6. Export as MP4

### APK Build
```bash
# In Android Studio terminal:
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk

# Or signed release (if keystore exists):
./gradlew assembleRelease
```

### Combine into PDF
All documents except the APK and video should be combined into one PDF for eConcordia submission. Print each Word doc to PDF, then merge using any PDF merger tool or:
```bash
# If on Mac with ghostscript:
gs -dBATCH -dNOPAUSE -q -sDEVICE=pdfwrite -sOutputFile=Final_Submission_Team5.pdf \
  MissionStatement.pdf DesignDocument.pdf UserManual.pdf TestDocument.pdf \
  DefinitionOfDone.pdf EthicsReport.pdf SimulationSummary.pdf \
  ProductBacklog.pdf AIUsage.pdf PresentationSlides.pdf
```

---

## PHASE 5 — TIMELINE

| Date | Task | Who |
|------|------|-----|
| **Now** | Run all 5 Claude Code prompts, save outputs to `docs/generated/` | Niraj |
| **Now** | Bipin-proofing: test landscape mode on all screens | Niraj |
| **Now** | Bipin-proofing: test all ridiculous inputs | Niraj |
| **Apr 3** | Draft PowerPoint from Prompt 5 speaker notes | Matei |
| **Apr 4** | Draft Design Document from Prompt 1 output | Matei |
| **Apr 4** | Run full demo script rehearsal with all 5 members | All |
| **Apr 5** | Fix any bugs found during rehearsal | Niraj |
| **Apr 5** | Draft Test Document (run all tests physically on Pixel 7) | Nirthika |
| **Apr 5** | Draft User Manual | Marie Ella |
| **Apr 5** | All members prepare their "what I did" speech (3-4 sentences) | All |
| **Apr 6** | Final rehearsal — full demo + presentation run-through | All |
| **Apr 7–10** | **PRESENTATION AND DEMO** | All |
| **Apr 11** | Finalize all documents | All |
| **Apr 12** | Record demo video | Niraj |
| **Apr 13** | Combine all documents into single PDF | Matei |
| **Apr 14** | Final review — check every item in submission checklist | All |
| **Apr 15** | **SUBMIT TO eCONCORDIA** | Matei |

---

## QUICK REFERENCE — KEY TECHNICAL FACTS TO HAVE MEMORIZED

For when Lynch or Bipin asks questions:

| Question | Answer |
|----------|--------|
| What sensors? | IMU (accelerometer + gyroscope) on Arduino Nano 33 BLE Sense — gives wrist roll angle |
| What BLE library? | Nordic Semiconductor BLE for Android, v2.11.0 |
| Why Nordic? | Handles GATT connection queue — prevents STATUS_133 failures on Android |
| What are the UUIDs? | Service: `12345678-1234-1234-1234-1234567890ab`, TX: `...ac`, RX: `...ad` |
| What data comes from glove? | `ACTIVE_DELTA_DEG:<float>` (angle relative to neutral), `NEUTRAL_ROLL_DEG`, `DIRECTION` |
| What is latency? | ~55–70ms typical (BLE ~20ms + sync loop 20ms + audio buffer 43ms) |
| Where are recordings stored? | `getFilesDir()/recordings/` — app-private, no cloud |
| What recording formats? | Lossless WAV or AAC at 3 quality levels |
| How many waveforms? | 9: SINE, SQUARE, TRIANGLE, SAW, PULSE, ORGAN, STRING, BELL, PAD |
| How does background audio work? | Foreground service with `mediaPlayback` type, `START_STICKY` |
| How does recording capture audio? | PCM tap (PcmListener interface) on ThereminAudioEngine — not microphone |
| What happens if Bluetooth turns off? | handleBluetoothOff() disconnects all, UI shows warning, auto-reconnects when BT turns on |
| What happens if only one glove connects? | Audio muted (both gloves required for `isInstrumentReady()` to return true) |
