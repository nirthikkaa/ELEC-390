# Generative AI Usage Document — Theremin Gloves

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026
**Team 5:** Niraj Patel (40211758), Ayan Pirani (40276971), Marie Ella Cambay (40284457), Nirthika Ilaiyarajah (40298669), Matei Moldovan (40277256)
**Instructor:** Dr. William Lynch
**Due:** April 14, 2026

---

## Introduction

Per project requirements, this document discloses all uses of generative AI tools during the Theremin Gloves project, and describes the intellectual contribution made by the team in each case. The table below lists each use, the tool involved, what the AI generated, and what the team member contributed.

---

## AI Usage Table

| Use | Tool | What AI Generated | Team Member's Contribution |
|-----|------|-------------------|---------------------------|
| Android app code — core architecture | Claude (Anthropic) | Core architecture implementation: `BleSessionManager`, `ThereminAudioEngine`, `RecordingManager`, `LibraryActivity`, all Activity files, layout XML, custom views (`ToneKnobView`, `ThereminVisualizerView`) | Niraj specified the architecture, selected the Nordic BLE library (`no.nordicsemi.android:ble:2.11.x`), chose `AudioTrack` over `AudioRecord` for synthesis, debugged BLE integration on hardware, ran all hardware testing on Pixel 7, and made all design decisions. AI generated implementation from detailed specifications provided by Niraj. |
| Android app code — Sprint 2 features | Claude (Anthropic) | `RecordingManager.java` (PCM tap → WAV/AAC), `RecordingRepository.java` (SQLite schema), `LibraryActivity.java` full rebuild, `RecordingListAdapter.java`, recording UI elements in `MainActivity.java` | Each team member specified their feature requirements and reviewed generated code. Nirthika validated the storage schema. Ayan validated the UI flow. Marie Ella validated the library screen. All members tested their features on hardware. |
| Android app code — Sprint 3 features | Claude (Anthropic) | `DrumEngine.java` skeleton and pattern logic, audio effects pipeline (`applyReverb`, `applyDelay`, `applyDistortion`) in `ThereminAudioEngine`, scale quantization (`snapToScale`) implementation, octave shift integration, sensitivity multiplier in `PlayMappingState`, performance mode toggle logic | Niraj specified the effects signal chain and all audio constants. Ayan specified the UI layout for effects and scale controls. Marie Ella specified the sensitivity preset logic. Nirthika specified the performance mode visibility rules. All members tested their features. |
| Sprint task breakdown documents | Claude (Anthropic) | Sprint 2 and Sprint 3 individual task files (per-person role documents with task lists, code examples, merge checklists) | Niraj provided the full codebase, backlog, and architecture context. Claude generated task structure and code examples. Niraj reviewed and approved each document before distributing to team members. |
| Product backlog — Sprint 3 planning | Claude (Anthropic) | Sprint 3 story breakdown, task-level decomposition, and ideal hour estimates for A-10 through A-18 admin tasks and all feature stories | The team reviewed the generated backlog against the actual codebase. They removed HD-8 (Battery Check) because the Arduino Nano 33 BLE Sense does not expose the BLE Battery Service, and removed HD-21 (Dead Zone) because the existing `FREQ_SMOOTHING` constant already handles micro-tremors. These decisions required understanding of actual hardware and code constraints. |
| BLE Q&A preparation | Claude (Anthropic) | Technical Q&A document for retrospective covering BLE UUIDs, packet format, reconnect timing, and watchdog logic | Niraj provided the codebase; Claude extracted technical specifics from actual constants in `BleSessionManager.java`. All answers were verified by Niraj against the running code. |
| Documentation drafts | Claude (Anthropic) | `CLAUDE.md` architecture sections, design document sections, demo script structure, ethics report draft, computer simulation summary draft, presentation outline, this AI usage document | The team provided all factual content: measured latency values, waveform descriptions, scale definitions, demo timing. Claude organized and formatted the content. All documents were reviewed by Matei before submission. |

---

## Statement of Intellectual Contribution

All architectural decisions in the Theremin Gloves project were made by the team, not by AI tools.

Specifically:
- The decision to use BLE (Bluetooth Low Energy) over Wi-Fi or USB was made based on wearability constraints.
- The selection of the Arduino Nano 33 BLE Sense as the glove microcontroller was made based on hardware availability and BLE support.
- The selection of the Nordic BLE Android library (`no.nordicsemi.android:ble`) was made by Niraj after evaluating Nordic's library against the raw Android GATT API.
- The choice to use `AudioTrack` in streaming mode (rather than `MediaPlayer`, `SoundPool`, or `Oboe`) for real-time synthesis was a deliberate engineering decision for latency and control.
- The decision to use a PCM tap (listener pattern) rather than a system audio capture for recording was made to ensure effects and waveform choices are captured in the recording.
- The decision to use a separate `recordings.db` database (rather than adding tables to `theremin_gloves.db`) was made to avoid schema conflicts between team members.
- The decision to use `addColumnIfMissing()` for all migrations (rather than incrementing `DB_VERSION`) was made to avoid destructive upgrades on user devices.
- All audio constants (`SAMPLE_RATE=48000`, `AUDIO_WRITE_SAMPLES=2048`, `FREQ_SMOOTHING=0.003f`, `ATTACK_SMOOTHING=0.0046f`, `RELEASE_SMOOTHING=0.0018f`, `OUTPUT_GAIN=0.22f`, `VIBRATO_RATE_HZ=5.2f`) were tuned empirically through hardware testing.

AI tools were used to accelerate the implementation of specified designs and to draft documentation from content provided by the team. At no point did an AI tool make a design decision or substitute for team judgment.

Hardware debugging, BLE integration testing, and audio tuning were performed exclusively on physical hardware (Arduino Nano 33 BLE Sense gloves + Pixel 7) by the team.

---

*Document prepared by Matei Moldovan on behalf of Team 5.*
