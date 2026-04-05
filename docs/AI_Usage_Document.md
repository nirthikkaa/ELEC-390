# Generative AI Usage Document
**Project:** Theremin Gloves  
**Team:** Team 5 — COEN 390 / ELEC 390, Winter 2026  
**Primary Developer:** Niraj Patel (40211758)

---

## Overview

Generative AI (Claude by Anthropic, via claude.ai and Claude Code CLI in Android Studio) was used selectively during Sprint 2 and Sprint 3 of this project. It was not used during Sprint 1 or for any hardware/firmware work. In all cases where AI was used, the core implementation already existed or had been designed by the developer before AI assistance was introduced. AI was used to handle edge cases, extend existing implementations, and produce documentation — not to make architectural decisions or write systems from scratch.

---

## What Was Built Without AI

### Arduino Firmware — 100% Human
The entire Arduino Nano 33 BLE Sense firmware was written by Niraj Patel without any AI assistance. This includes:
- IMU sensor fusion and roll angle computation from the onboard accelerometer/gyroscope
- ACTIVE_DELTA_DEG and NEUTRAL_ROLL_DEG calculation relative to the calibrated neutral position
- BLE peripheral setup: custom service UUID, TX and RX characteristic definitions, notification callbacks
- Glove naming convention (ThereminGlove / ThereminGloveVol)
- Command handling (H handshake, N capture neutral, D toggle direction)
- All packet formatting sent over BLE to the phone

### BLE Communication Layer — Sprint 1 (100% Human)
The core BLE implementation on the Android side was written by Niraj Patel in Sprint 1 without any AI assistance. This includes:
- BleSessionManager architecture: static singleton design, the two-Glove instance model
- Initial BLE scanning logic using BluetoothLeScanner and ScanCallback
- Device filtering by name (ThereminGlove / ThereminGloveVol)
- Basic GATT connection flow and notification subscription
- BleSnapshot immutable value object pattern for thread-safe UI reads
- ThereminGloveBleManager wrapping the Nordic BLE library
- ConnectGlovesActivity and all connection UI

### Audio Engine Core — Human Written
The core ThereminAudioEngine was written by Niraj Patel. This includes:
- AudioTrack configuration and the dedicated audio thread architecture
- The fundamental real-time synthesis loop (fillBuffer, advancePhase)
- Frequency and volume smoothing algorithm design (exponential smoothing approach)
- Vibrato implementation (rate, depth, volume-dependent scaling)
- Initial waveform set (SINE, SQUARE, TRIANGLE, SAW)
- Output gain and clipping strategy

### RecordingManager Foundation — Human Written
The recording system architecture was designed and initially implemented by Niraj Patel:
- PcmListener interface design — the decision to tap PCM directly from the audio engine rather than using AudioRecord (microphone)
- WAV file writing logic including the 44-byte header structure
- RecordingRepository database schema design and SQLite implementation
- Decision to use a separate recordings.db file to avoid migration conflicts with SettingsStore

### LibraryActivity Foundation — Human Written
The library screen was initially built by the team:
- RecyclerView architecture with RecordingListAdapter
- MediaPlayer integration for audio playback
- Core CRUD operations (list, play, delete)
- Empty state handling

### All Sprint 1 Features — No AI Used
BLE, calibration system, audio engine, pitch and volume mapping, range settings, waveform visualizer, navigation, settings screen, home screen — all Sprint 1 work was completed without any AI assistance.

---

## Where AI Was Used

### 1. BLE Edge Cases and Robustness (Sprint 2)
**Tool:** Claude Code CLI (Android Studio)  
**What AI helped with:** After the core BLE connection logic was working, Claude Code was used to help handle the difficult edge cases that surfaced during real-device testing:
- Watchdog timer logic for detecting silent disconnects (telemetry stale detection at 4,500ms, force-reconnect at 20,000ms)
- The ping-after-silence mechanism (sending `H` handshake after 3,000ms of no telemetry)
- Auto-reconnect scheduling with the 1,500ms delay and retry loop
- Android 12+ permission split handling (BLUETOOTH_SCAN vs ACCESS_FINE_LOCATION branching on Build.VERSION.SDK_INT)
- STATUS_133 resilience via `connect().retry(3, 250)` in ThereminGloveBleManager

**Niraj's contribution:** Niraj identified each failure mode through hardware testing. He specified the exact timing values based on real-device behavior. He reviewed and tested every edge case fix on the Pixel 7 with actual gloves. The architecture (static singleton, snapshot pattern, watchdog runnable) was already in place — AI helped implement specific recovery behaviors within that architecture.

---

### 2. Audio Engine Extensions (Sprint 2–3)
**Tool:** Claude Code CLI  
**What AI helped with:** Extending the existing audio engine:
- Five additional waveforms beyond the original four: PULSE, ORGAN, STRING, BELL, PAD — including the mathematical synthesis approaches (drawbar simulation for ORGAN, inharmonic partials for BELL, detuned oscillator beating for PAD)
- ToneKnobView custom rotary selector widget
- Audio effects pipeline (reverb comb filter, delay ring buffer, distortion tanh normalization) — Sprint 3

**Niraj's contribution:** Niraj wrote the original four waveforms and the synthesis loop. He specified the desired timbral characteristics for each new waveform (e.g., "Hammond-style harmonics" for ORGAN, "metallic ring" for BELL). He tested each waveform on hardware and approved or rejected the output. He integrated ToneKnobView into the existing UI and wiring.

---

### 3. Recording System Extensions (Sprint 2)
**Tool:** Claude Code CLI  
**What AI helped with:** Extending the recording system:
- AAC encoding pathway using MediaCodec and MediaMuxer (HIGH/MEDIUM/LOW quality modes beyond the base WAV implementation)
- Storage space check before recording (StatFs approach)
- Error handling for IOException during write, partial file cleanup on failure
- RecordingRepository extensions: folder management, quality metadata column, drag-reorder support

**Niraj's contribution:** Niraj designed and implemented the core recording pipeline (PCM tap → WAV). He specified the quality modes and reviewed the MediaCodec implementation. He tested recording on real hardware across all quality modes.

---

### 4. Library Screen Extensions (Sprint 2–3)
**Tool:** Claude Code CLI  
**What AI helped with:** Extending the library beyond basic CRUD:
- Drag-to-reorder implementation with ItemTouchHelper
- Search/filter bar with real-time RecyclerView filtering
- Folder organization UI and database queries
- Rename via inline EditText on long press
- Quality badge display

**Niraj's contribution:** Niraj built the original LibraryActivity with playback, list, and delete. He specified the additional features based on the product backlog. He integrated all extensions into the existing activity and tested on Pixel 7.

---

### 5. Sprint 3 Features (Scale Lock, Octave Shift, Sensitivity, Performance Mode)
**Tool:** Claude Code CLI  
**What AI helped with:** Implementing new Sprint 3 features within the existing architecture:
- Scale quantization (MIDI snap formula, scale semitone arrays)
- Octave shift multiplier integration in ThereminBackgroundAudioService.pushTargets()
- Sensitivity multiplier in PlayMappingState.recompute()
- Performance mode view visibility toggling in MainActivity

**Niraj's contribution:** Niraj designed the feature specifications, chose where in the architecture each feature should live, and tested all implementations on hardware.

---

### 6. In-App User Manual (UserManualActivity)
**Tool:** Claude Code CLI  
**What AI helped with:** Creating `UserManualActivity.java`, `activity_user_manual.xml`, and the Settings card that launches it. The manual content (all 11 sections) was authored by the team in `docs/final/03_User_Manual.md`; AI generated the Android UI scaffolding (activity, layout, card builder, ViewBinding wiring) from that existing content.

**Niraj's contribution:** Niraj specified the requirement (in-app manual, no links, matches app visual style), reviewed the implementation, and verified the build.

---

### 7. Documentation and Planning
**Tool:** claude.ai (web interface)  
**What AI helped with:**
- Sprint task breakdown files for teammates (Ayan, Marie Ella, Nirthika, Matei)
- Product backlog Sprint 3 story descriptions and hour estimates
- CLAUDE.md architecture reference file
- Design document content generation (from code)
- Test document structure and test case descriptions
- User manual content
- Team blog formatting

**Niraj's contribution:** Niraj provided all technical specifications, codebase context, and factual accuracy verification. He reviewed every generated document and corrected inaccuracies. Sprint task files were based on his understanding of the architecture and who could safely own which files without causing merge conflicts. All estimates were reviewed against actual implementation complexity.

---

## Summary

| Component | AI Used | Extent |
|-----------|---------|--------|
| Arduino firmware | No | — |
| BLE core (Sprint 1) | No | — |
| BLE edge cases (Sprint 2) | Yes | Specific recovery behaviors within existing architecture |
| Audio engine core | No | — |
| Audio engine extensions | Yes | Additional waveforms and effects within existing engine |
| RecordingManager core | No | — |
| RecordingManager extensions | Yes | AAC quality modes and error handling |
| LibraryActivity core | No | — |
| LibraryActivity extensions | Yes | Drag-reorder, search, folders |
| Sprint 3 features | Yes | New features within existing architecture |
| Documentation | Yes | Generated from specifications provided by developer |

---

## Statement of Intellectual Contribution

All hardware design, Arduino firmware, BLE protocol selection, core architecture decisions, and Sprint 1 implementation were done entirely by Niraj Patel without AI assistance. For Sprint 2 and Sprint 3, AI was used to extend and refine working systems — not to design them. In every case, Niraj specified what was needed, reviewed the output, tested it on real hardware, and approved or reworked the result. AI served as an accelerator for a developer who already understood the full system deeply. All design decisions, hardware choices, debugging, and real-device validation were performed by the team.
