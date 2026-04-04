# Conversation Summary — Theremin Gloves Sprint 2 → Final Submission
**Date:** March–April 2026 | **Student:** Niraj Patel (40211758)

---

## Project State at Start of This Conversation
- Sprint 1 complete (all BLE, calibration, audio, navigation)
- Sprint 2 in progress → completed during conversation
- Codebase: ThereminGlovestest2_16.zip (latest)
- GitHub repo: nirthikkaa/ELEC-390, branch develop, PR #1 open (Niraj's PCM tap)

---

## What Was Done

### Claude Code Setup
- Explained Claude Code CLI usage in Android Studio (JetBrains plugin, Cmd+Esc shortcut, diff viewer, file references with @File#L1-99)
- Niraj's PCM tap was tested: `got 2048 samples` in Logcat = confirmed working
- CLAUDE.md created for project root context
- Backlog converted to `backlog.txt` and added to docs/

### Sprint 2 Task Files Generated
Individual .md files created for each team member:
- `Niraj_Sprint2_Tasks.md` — PCM tap (DONE), waveforms already existed
- `Ayan_Sprint2_Tasks.md` — Recording UI in MainActivity + activity_main.xml
- `MarieElla_Sprint2_Tasks.md` — Full LibraryActivity rebuild
- `Nirthika_Sprint2_Tasks.md` — RecordingManager + RecordingRepository (merges first)
- `Matei_Sprint2_Tasks.md` — UI polish + documentation (merges last)
- Merge order: Nirthika → Niraj → Ayan → Marie Ella → Matei → develop → main

### Sprint 2 Code — Niraj's Part
- Confirmed all 4 waveforms already existed (SINE, SQUARE, TRIANGLE, SAW) in AppSettings
- SettingsStore already had tone_type column — no DB bump needed
- Only real change: added PcmListener interface + setPcmListener() to ThereminAudioEngine.java
- PCM tap fires after fillBuffer(), before track.write(), using volatile local reference (thread-safe)
- PR #1 opened on GitHub: feature/sprint2-waveforms-audio-core → develop, no conflicts

### Git Workflow Established
- Branch: feature/sprint2-waveforms-audio-core (Niraj's)
- CLAUDE.md added to .gitignore (initially), later decided to commit it for shared context
- cat command for appending multiple files: `cat file1 file2 file3 >> CLAUDE.md`
- Confirmed push goes to feature branch, not develop — PRs only

### Sprint 2 Retrospective Prep
- Full BLE Q&A prepared with exact constants from BleSessionManager.java:
  - SCAN_TIMEOUT_MS=12000, CONNECT_TIMEOUT_MS=12000, AUTO_RECONNECT_DELAY_MS=1500
  - PING_AFTER_MS=3000, STALE_WARNING_MS=4500, STALE_RECONNECT_MS=20000
  - BLE UUIDs: service 12345678-...-ab, TX ...ac, RX ...ad
  - connect().retry(3, 250) for STATUS_133 resilience
  - Watchdog checks every 1000ms
- Latency chain: BLE ~20ms + sync loop 20ms + audio buffer 43ms = ~83ms worst case
- Retro speech written: "I used nRF Connect to validate Arduino side first, then migrated to Nordic BLE"
- Sprint retro what went wrong: late integration, no shared debugging setup, hardware dependency bottleneck

### Version 16 Codebase Analysis
Confirmed Sprint 2 was FULLY complete in v16:
- RecordingManager.java: WAV + AAC at 4 quality levels (Lossless/High/Medium/Low)
- RecordingRepository.java: separate recordings.db, folder support, quality metadata
- LibraryActivity.java: 1070 lines, full UI with search/rename/delete/folders/drag-reorder
- ThereminAudioEngine.java: 9 waveforms (SINE, SQUARE, TRIANGLE, SAW, PULSE, ORGAN, STRING, BELL, PAD)
- ToneKnobView.java: custom rotary selector (308 lines)
- RecordingListAdapter.java: full adapter with drag-to-drop

### Product Backlog Sprint 3
- Analyzed codebase to determine what's actually needed vs already done
- Removed HD-8 (battery — Arduino Nano 33 BLE Sense doesn't expose BLE Battery Service 0x180F)
- Removed HD-21 (dead zone — already handled by FREQ_SMOOTHING + mapLinearClamped)
- Trimmed HD-11 to benchmark only (audio already optimized)
- Final Sprint 3: ID-10 (Drum Kit 31h), HD-11.1 (latency doc 3h), HD-13 (Effects 29h), HD-29 (Scale Lock 11h), HD-30 (Octave Shift 6h), ID-11 (Sensitivity 9h), HD-16 (Performance Mode 8h), ADMIN A-10 to A-18 (30h) = 127h total
- Product_Backlog_Sprint_3.xlsx: color-coded (matching Sprint 2 palette), thick black outline borders per story block, uniform row heights, column widths matching Sprint 2, TOTAL IDEAL HOURS formula, Sprint 3 goals cell

### Sprint 3 Task Files Generated
- `Niraj_Sprint3_Tasks.md` — DrumEngine + audio effects pipeline (35h)
- `Ayan_Sprint3_Tasks.md` — Scale lock UI + octave shift + drum toggle + effects panel (28h)
- `MarieElla_Sprint3_Tasks.md` — Sensitivity settings in PlayMappingState + SettingsActivity (18h)
- `Nirthika_Sprint3_Tasks.md` — Performance mode + latency benchmark (15h)
- `Matei_Sprint3_Tasks.md` — All final docs A-10 through A-18 (30h)

### CLAUDE.md Updated for Sprint 3
- Full architecture reference with all constants
- Sprint 1+2 history marked complete
- Sprint 3 implementation guide with code snippets (DrumEngine skeleton, reverb comb filter, delay ring buffer, distortion tanh, MIDI snap formula, octave shift multiplier, performance mode toggle)
- File ownership table
- 19.5k chars (under 40k limit)

### Team Blog Sprint 2
- Added 7 entries to Team_blog.xlsx rows 48–54 matching Sprint 1 format exactly:
  - Green fill (FFC6EFCE) cols A–J, thin borders K–O, medium borders on outer edges
  - Row heights 15.75, Calibri 11pt
  - Entries: Mar 9 (planning), Mar 10 (Niraj+Nirthika engine), Mar 12 (Marie Ella library), Mar 14 (Niraj+Ayan+Matei features), Mar 17 (integration), Mar 20 (bug fixes+demo), Mar 22 (docs)
  - Purpose and output text made short to match Sprint 1 style

### Final Presentation & Submission Plan
- `Final_Presentation_Submission_Plan.md` — comprehensive plan with:
  - 5 Claude Code prompts (Design Doc, Test Doc, User Manual, AI Usage, Speaker Notes)
  - All model selections (Sonnet for everything)
  - Document build instructions for all 13 submission items
  - Full demo script with timestamps (0:00–8:00)
  - Bipin-proofing checklist (landscape mode every screen, ridiculous inputs, cold start)
  - Individual "what I did" speeches per team member
  - Timeline April 3–15
  - Quick reference table of technical facts

### AI Usage Document
- `AI_Usage_Document.md` — accurate version reflecting real contribution:
  - Arduino firmware: 100% Niraj, zero AI
  - BLE Sprint 1 core: 100% Niraj, zero AI
  - BLE edge cases Sprint 2: AI helped (watchdog, reconnect, Android 12 permissions, Nordic migration)
  - Audio engine core: Niraj wrote it; AI extended (extra waveforms, effects)
  - RecordingManager + LibraryActivity: Niraj wrote foundation; AI extended
  - Documentation: AI generated from Niraj's specs
  - Final plan updated to reference this document directly instead of regenerating

---

## Key Technical Facts (Quick Reference)

| Item | Value |
|------|-------|
| Package | com.example.thereminglovestest2 |
| compileSdk | 36, minSdk 24, Java 17 |
| BLE Library | no.nordicsemi.android:ble:2.11.0 |
| Service UUID | 12345678-1234-1234-1234-1234567890ab |
| TX char | ...ac (notifications, Arduino→phone) |
| RX char | ...ad (write, phone→Arduino) |
| Glove names | ThereminGlove (pitch), ThereminGloveVol (volume) |
| SAMPLE_RATE | 48000 Hz |
| AUDIO_WRITE_SAMPLES | 2048 (~43ms buffer) |
| SYNC_TICK_MS | 20ms |
| FREQ_SMOOTHING | 0.003f |
| Waveforms | 9: SINE SQUARE TRIANGLE SAW PULSE ORGAN STRING BELL PAD |
| Recordings DB | recordings.db (separate from theremin_gloves.db) |
| Storage path | context.getFilesDir()/recordings/ |
| Latency | ~55–70ms typical, ~83ms worst case |

---

## Files Produced in This Conversation (in /mnt/user-data/outputs/)
- Niraj_Sprint2_Tasks.md
- Ayan_Sprint2_Tasks.md
- MarieElla_Sprint2_Tasks.md
- Nirthika_Sprint2_Tasks.md
- Matei_Sprint2_Tasks.md
- Niraj_Sprint3_Tasks.md
- Ayan_Sprint3_Tasks.md
- MarieElla_Sprint3_Tasks.md
- Nirthika_Sprint3_Tasks.md
- Matei_Sprint3_Tasks.md
- CLAUDE.md (Sprint 3 version)
- Product_Backlog_Sprint_3.xlsx
- Sprint3_GoogleSheets.csv
- backlog.txt
- Team_blog_sprint2.xlsx
- BLE_QA_Prep.docx
- Final_Presentation_Submission_Plan.md
- AI_Usage_Document.md

---

## Pending / Next Steps
- Sprint 3 features to implement (Niraj doing everything): DrumEngine, audio effects, scale lock, octave shift, sensitivity, performance mode
- Run 5 Claude Code prompts to generate document content (design doc, test doc, user manual, speaker notes)
- Bipin-proof the app (landscape mode on all screens, ridiculous inputs)
- Build PowerPoint from speaker notes
- Record demo video (5–7 min)
- Submit everything by April 15 on eConcordia
