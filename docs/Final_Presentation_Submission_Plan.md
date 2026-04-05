# Theremin Gloves — Final Presentation & Submission Master Plan

**Course:** COEN 390 / ELEC 390, Concordia University
**Team:** Team 5
**Audit date:** April 5, 2026

## 1. Official Requirements

| Date | Requirement |
|---|---|
| **April 7–10, 2026** | Final oral presentation and live demo, online, **10–12 minutes** |
| **April 15, 2026** | Final submission due |

The final presentation must:

- identify the customer
- explain the product's value for that customer
- explain how the product functions, including architecture, communication links, and important signal processing / algorithms
- include a live demo

The final submission must include:

- updated mission statement with assumptions marked true/false and impact if false
- final product backlog with completed items separated
- revised design document
- user manual if external documentation is necessary
- updated Definition of Done
- test document
- 2-page ethics report
- 1-page computer simulation summary
- 5–7 minute demo video with at most 2 minutes of introduction
- generative-AI usage list and user contribution description
- slides exported to PDF
- product app
- final team blog with agreement page
- originality form

Important packaging rule:

- aside from code/app artifacts, the written documents must be submitted as **one comprehensive PDF**
- links are **not** acceptable in place of the actual documents

## 2. Canonical Source Of Truth

These are the repo documents that should drive every final export:

- [01_Design_Document.md](01_Design_Document.md)
- [02_Test_Document.md](02_Test_Document.md)
- [03_User_Manual.md](03_User_Manual.md)
- [04_Mission_Statement.md](04_Mission_Statement.md)
- [05_Ethics_Report.md](05_Ethics_Report.md)
- [06_Computer_Simulation_Summary.md](06_Computer_Simulation_Summary.md)
- [07_Definition_of_Done.md](07_Definition_of_Done.md)
- [08_AI_Usage_Document.md](08_AI_Usage_Document.md)
- [09_Presentation_Notes.md](09_Presentation_Notes.md)
- [10_Demo_Preparation.md](10_Demo_Preparation.md)
- [12_Final_Product_Backlog.md](12_Final_Product_Backlog.md)

Code-level facts that all final materials must match:

- public tone set is **11 tones**, not 9 or 10
- `AUDIO_WRITE_FRAMES = 1024`, not `AUDIO_WRITE_SAMPLES = 2048`
- default frequency ceiling is **2,000 Hz**
- extended frequency range raises the ceiling to **20,000 Hz**
- `DrumEngine` uses a hybrid bank of bundled raw samples plus in-code synthesis
- `MainActivity` and `ThereminBackgroundAudioService` use separate engine instances during handoff
- the Library supports search, playback, rename/delete, folders, move-to-folder, and filters
- automated coverage is **139 tests** total: 40 JVM and 99 instrumented

## 3. Repo And Drive Audit

### Git repos

At the time of this audit:

- `origin/sprint3` and `backup/sprint3` were aligned to the same commit
- the backup repo also contains a `submission-docs` branch that acts as an export snapshot, not the canonical source

Implication:

- edit the canonical markdown in `sprint3` first
- then regenerate the backup export pack from those corrected sources

### Google Drive

The `COEN/ELEC 390 - Project / Sprint 3` Drive folder is not clean. It currently contains:

- duplicate user-manual PDFs
- a live `Final Report` Google Doc with stale product/architecture claims
- a live `Final Presentation` Google Slides deck with stale content
- shortcuts to backlog/team-blog assets rather than a clearly organized final package

Implication:

- treat the repo docs as source-of-truth
- replace the stale Drive artifacts after export
- do not build the final package from the current Drive copies without refreshing them

## 4. Presentation Structure

Recommended 8-slide structure for a 10–12 minute slot:

1. **Title + customer**
   - product name
   - target users
   - one sentence on the problem solved
2. **Value proposition**
   - contact-free instrument
   - wireless BLE gloves
   - recording, playback, effects, and Beat Maker support
3. **System architecture**
   - gloves
   - BLE
   - `BleSessionManager`
   - gesture mapping
   - audio engine
   - recording / library path
4. **BLE communication**
   - service UUID and characteristics
   - glove packet types
   - reconnect/watchdog model
5. **Signal processing and mapping**
   - neutral capture
   - angle normalization
   - pitch/volume formulas
   - sensitivity shaping
6. **Audio engine and latency**
   - `AudioTrack`
   - `AUDIO_WRITE_FRAMES = 1024`
   - 11 public tones
   - latency budget and why the service path is lower-latency
7. **Recording, library, and Sprint 3 features**
   - PCM tap recording
   - Library management
   - scale lock, octave shift, effects, Beat Maker
8. **Live demo + close**
   - transition into live demo
   - return for questions / team contributions

Do not present these stale claims:

- "9 waveforms"
- "10 tones"
- "`AUDIO_WRITE_SAMPLES = 2048`"
- "default frequency range is 20 Hz to 20 kHz"
- "the app shares one audio engine instance between Play and background service"
- "DrumEngine uses no raw audio assets"

## 5. Demo Plan

The safest live flow is:

1. launch app
2. connect both gloves
3. calibrate pitch and volume neutral positions
4. show live play and visualizer
5. switch between clearly different tones
6. show one or two Sprint 3 controls
7. record a short performance
8. open Library and play it back
9. show one or two persistent settings

Recommended narration emphasis:

- customer and value first
- then how the system works
- then proof through live demo

## 6. Verification Status

Current test state after the audit:

- `./gradlew test` — **pass**
- `./gradlew connectedDebugAndroidTest` — **98 / 99 pass**
- only failing instrumentation test: `BluetoothPromptUiTest.launch_promptsForBluetoothPermissions_whenMissing`
- failure is environment-dependent because the demo Pixel 7 no longer shows the OS Bluetooth permission dialog once permissions are already configured

Important regression note:

- `VisibleLaunchBenchmarkTest` previously exposed an out-of-memory issue during direct `MainActivity` launch
- this was fixed by deferring Beat Maker preview-engine creation until it is actually needed
- the launch benchmark now passes

## 7. Final Packaging Plan

1. Export the updated markdown documents into presentation-ready Word/Docs versions.
2. Produce final PDFs for:
   - mission statement
   - design document
   - user manual
   - Definition of Done
   - test document
   - ethics report
   - computer simulation summary
   - AI usage / contribution statement
3. Merge the required written documents into one comprehensive PDF.
4. Export the final slide deck to PDF.
5. Build the final APK.
6. Record and export the 5–7 minute demo video.
7. Update the external backlog spreadsheet, team blog, and originality form.
8. Refresh the Drive `Sprint 3` folder with the new final artifacts.
9. Regenerate the backup `submission-docs` package from the updated sources.

## 8. Immediate Next Actions

- finalize the slide deck from [09_Presentation_Notes.md](09_Presentation_Notes.md)
- use [10_Demo_Preparation.md](10_Demo_Preparation.md) for the rehearsal order
- export refreshed PDF copies to replace the stale Drive `Final Report` and `Final Presentation`
- remove the duplicate Drive user-manual PDFs
- rebuild the backup export pack only after all corrected docs are frozen
