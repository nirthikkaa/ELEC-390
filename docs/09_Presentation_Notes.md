# Theremin Gloves — Final Presentation Speaker Notes

## Slide 1 — Title

**Visual**
- Title: `Theremin Gloves — A Gesture-Controlled Wireless Instrument`
- Team names
- Course and term
- One clean product screenshot or photo of the gloves + phone

**Speaker notes**
“Hello, we’re Team 5, and our project is Theremin Gloves. It’s an Android-based wireless musical instrument controlled entirely by hand gestures. Two BLE gloves stream wrist-angle data from Arduino Nano 33 BLE Sense boards to the phone, and the phone synthesizes audio in real time. Today we’ll show who this product is for, why it is useful, and how the system works from BLE telemetry all the way to live sound output.”

## Slide 2 — Who Is The Customer?

**Visual**
- Four user groups: music students, hobbyist musicians, content creators, live performers
- Small icons or photos for each

**Speaker notes**
“Our customers are people who want an expressive electronic instrument without specialized hardware knowledge. That includes music students who want a new way to experiment with pitch and gesture, hobbyist musicians who enjoy synth-style instruments, content creators looking for something visually distinctive on camera, and performers who want a fully wireless effect on stage. The product is especially approachable because the player does not need to understand piano fingering or traditional controller hardware to get sound immediately.”

## Slide 3 — Value Proposition

**Visual**
- Three or four value bullets
- Suggested callouts: wireless, gesture-only control, recording, library playback, Beat Maker, effects

**Speaker notes**
“The value proposition is that Theremin Gloves gives the user a contact-free instrument experience using commodity hardware. The gloves connect over BLE, the phone does all of the synthesis locally, and the user shapes sound with body movement instead of touching a keyboard or string. The app also goes beyond a basic demo. It records performances through a PCM tap in the synth engine, stores them locally in a searchable library, and adds effects, scale lock, octave shift, and beat tools so the user can build a fuller performance inside one app.”

## Slide 4 — System Architecture

**Visual**
- Architecture diagram:
  - `Arduino Nano 33 BLE Sense gloves`
  - BLE
  - `BleSessionManager`
  - `BleSnapshot`
  - `MainActivity` -> foreground `ThereminAudioEngine`
  - `ThereminBackgroundAudioService` -> service `ThereminAudioEngine`
  - `AudioTrack`
  - `Speaker`
  - Parallel branch: `PcmListener` → `RecordingManager` → file → `RecordingRepository` → `LibraryActivity`

**Speaker notes**
“At a high level, the hardware side is two Arduino Nano 33 BLE Sense gloves. On the Android side, the BLE host is `BleSessionManager`, which is implemented as a static process-wide singleton. It owns scanning, connection state, reconnect logic, and the watchdog. The live BLE state is exposed as an immutable `BleSnapshot`. In the foreground Play screen, `MainActivity` reads that snapshot, maps glove angles into frequency and volume through `PlayMappingState`, and pushes those targets into its foreground `ThereminAudioEngine`. When playback is handed off outside Play, `ThereminBackgroundAudioService` runs a separate service-owned `ThereminAudioEngine` on the same BLE state. The active engine renders PCM into `AudioTrack`, which produces sound through the speaker. In parallel, the active engine can expose its mono render buffer through the `PcmListener` interface to `RecordingManager`, and after recording stops `MainActivity` saves metadata through `RecordingRepository` so the Library screen can show the result.”

## Slide 5 — BLE Communication

**Visual**
- The three UUIDs
- Small packet-format box
- Timing constants list

**Speaker notes**
“BLE communication is built around a custom service and two custom characteristics. The service UUID is `12345678-1234-1234-1234-1234567890ab`. The notify characteristic is `...90ac`, and the write characteristic is `...90ad`. The glove sends ASCII packets like `ACTIVE_DELTA_DEG:<float>`, `NEUTRAL_ROLL_DEG:<float>`, and `DIRECTION:<text>`. The phone sends three commands: `H` for handshake, `N` for capture neutral, and `D` for direction toggle. We used Nordic Semiconductor’s Android BLE library, version `2.11.0`, through `ThereminGloveBleManager`, instead of raw `BluetoothGatt`, because this project needs reliable queued operations for connect, notification enable, and characteristic writes. In `BleSessionManager`, the main watchdog constants are `SCAN_TIMEOUT_MS = 12000`, `CONNECT_TIMEOUT_MS = 12000`, `AUTO_RECONNECT_DELAY_MS = 1500`, `PING_AFTER_MS = 3000`, `STALE_WARNING_MS = 4500`, `STALE_RECONNECT_MS = 20000`, and `WATCHDOG_PERIOD_MS = 1000`.”

## Slide 6 — IMU Signal Processing And Gesture Mapping

**Visual**
- One formula box for pitch mapping
- One formula box for volume mapping
- Small diagram showing neutral position and delta angle

**Speaker notes**
“The glove firmware computes orientation from the IMU and sends `ACTIVE_DELTA_DEG`, which the app treats as the current roll angle relative to the captured neutral position. In the Play screen, `PlayMappingState.recompute()` normalizes that angle between the user’s calibrated min and max. Frequency is mapped with linear interpolation between `freqMinHz` and `freqMaxHz`, and volume is mapped between zero and one. A sensitivity exponent is applied after normalization, so the same calibrated angle span can feel either more sensitive or more precise. The default ranges are pitch `0° to 90°`, volume `0° to 90°`, and frequency `20 Hz to 20 kHz`. The output is also gated by instrument readiness. If Bluetooth is off or only one glove is connected, the final audio target volume is forced to zero.”

## Slide 7 — Audio Synthesis And Latency

**Visual**
- Audio engine constants
- Tone list
- Latency calculation block

**Speaker notes**
“`ThereminAudioEngine` runs the actual synthesis. It uses `AudioTrack` at `SAMPLE_RATE = 48000`, stereo output, 16-bit PCM, and `AUDIO_WRITE_FRAMES = 1024`, which corresponds to about `21.3 milliseconds` of audio per write buffer. The engine smooths pitch and volume using `FREQ_SMOOTHING = 0.0030`, `ATTACK_SMOOTHING = 0.0046`, and `RELEASE_SMOOTHING = 0.0018`. Vibrato runs at `4.2 hertz`, with depth scaling from `0.0003` to `0.0014` based on current volume. The current public build exposes eleven user-selectable tones: Theremin, Air Pad, Cello, Pad, Choir, Flute, Clarinet, Triangle, Saw, Square, and Helicopter. For latency, the background-service path is the tightest one: BLE notify is typically about `7.5 to 20 milliseconds`, the service sync loop is `20 milliseconds` worst case, and the audio buffer is `21.3 milliseconds`, so the background path lands around `39 to 61 milliseconds`. In visible Play, the UI target-refresh loop is currently `50 milliseconds`, so typical latency is still under `80 milliseconds`, but strict foreground worst case is higher than the background-service bound.”

## Slide 8 — Recording System

**Visual**
- Recording pipeline diagram
- Screenshot of Record button and Library screen

**Speaker notes**
“Recording is done from the synth engine itself, not from the microphone. `ThereminAudioEngine` exposes the `PcmListener` interface, and `RecordingManager` implements it. That means the saved audio is the exact synthesized signal, including the live tone choice and drum mix. The app supports lossless WAV and AAC at multiple bitrates. After `RecordingManager` finalizes the file, `MainActivity` stores metadata in `recordings.db` through `RecordingRepository`, and `LibraryActivity` gives the user search, playback, rename, delete, folders, and date or duration filtering. The canonical recording copy stays in app-private storage, and the app may also create a second convenience export in `Music/Theremin Gloves Recordings`. There is no cloud upload or analytics pipeline.”

## Slide 9 — Sprint Highlights

**Visual**
- Three sprint columns

**Speaker notes**
“Sprint 1 established the core system: BLE connectivity, glove state management, calibration, the foreground Play screen, and the core `AudioTrack` synthesis path. Sprint 2 added recording, metadata persistence, the Library screen, and more mature audio behavior. Sprint 3 extended the instrument into a more complete performance tool. In the current codebase, Sprint 3 functionality includes scale lock, octave shift, reverb, delay, distortion, Beat Maker and beat preset flows, the user-facing `Stage View` performance layout, richer library filters including custom date range and duration range, sensitivity response controls, and broader automated regression coverage.”

## Slide 10 — Live Demo

**Visual**
- Transition slide: `Let’s see it in action`

**Speaker notes**
“Now we’ll switch from architecture to a live demonstration. We’ll show the connection flow, calibration, live play, tone changes, recording, playback from the library, and the settings that affect the instrument behavior.”

## Slide 11 — Q&A / Thank You

**Visual**
- Thank-you slide
- Team names
- Maybe one backup architecture image in the corner

**Speaker notes**
“Thank you. We’re happy to answer questions about the BLE layer, the audio engine, calibration, latency, testing, or the design tradeoffs we made between responsiveness and stability.”

## What Each Team Member Should Say When Asked Individually What They Contributed

Use these as balanced high-level speaking points. They are intentionally phrased as area summaries, not exclusive line-by-line authorship claims.

### Niraj Patel
“My main areas were the firmware-facing and core runtime pieces: the Arduino telemetry path, the BLE session architecture, and the base theremin audio path. That includes the custom BLE packet flow, the glove-state and watchdog foundation in `BleSessionManager`, and the real-time synthesis path built around `AudioTrack` and `ThereminAudioEngine`.”

### Ayan Pirani
“My main areas were the Play-screen interaction flows and performer-facing controls. That includes the recording UI state, transport or control wiring, and the user actions for things like scale, octave, and effect controls that update the live instrument state.”

### Marie Ella Cambay
“My main areas were the Library experience and the user-facing management flows around saved performances. That includes browsing, search, organization, rename/delete behavior, and related usability work on the app side that helps users manage recordings and settings.”

### Nirthika Ilaiyarajah
“My main areas were the recording pipeline, persistence behavior, and performance-oriented validation work. That includes the PCM capture and saved-file flow, repository and settings-side persistence behavior, and the latency or responsiveness reasoning used to keep the instrument practical on-device.”

### Matei Moldovan
“My main areas were UI cohesion and the submission package. That includes layout polish across the user-facing screens, presentation-facing refinement for the demo flow, and the final written material such as the design, testing, demo, ethics, and submission documents.”

## Notes To The Team Before Final Slides Are Exported

- Update any slide that still says “9 waveforms” or “10 tones.” The current public Play build exposes 11 tones (Square was re-added in Sprint 3).
- Update any slide that still says `AUDIO_WRITE_SAMPLES = 2048`. The current code uses `AUDIO_WRITE_FRAMES = 1024`.
- Update any slide that implies `MainActivity` and `ThereminBackgroundAudioService` literally share one audio-engine object. They hand off playback responsibility between separate foreground and service engines.
