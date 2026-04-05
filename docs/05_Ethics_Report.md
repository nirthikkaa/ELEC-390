# Theremin Gloves — Ethics Report

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026  
**Team 5:** Marie Ella Cambay, Niraj Patel, Ayan Pirani, Nirthika Ilaiyarajah, Matei Moldovan

## 1. Privacy of Recorded Audio

Theremin Gloves records musical performances locally on the Android device. The primary recording files are written into the app-private internal directory at `getFilesDir()/recordings/`, which means other ordinary Android apps cannot browse them directly. This is the strongest default local protection the Android sandbox provides without introducing a separate account system or device-level encryption workflow inside the app.

The app does not upload recordings to any server. There is no `INTERNET` permission in the manifest, no analytics SDK, and no remote telemetry pipeline. That significantly reduces privacy risk because the recording path is architecturally local-only.

There is one important nuance. After saving a recording, the app also attempts to create a user-visible export copy through `RecordingExportManager` in `Music/Theremin Gloves Recordings`. That is helpful for user convenience, but it creates a second copy that may be more accessible on a shared device than the app-private original. As a result, the strongest privacy claim is true for the internal recording copy, but exported copies on a shared device should be treated as less private.

The ethical position is therefore:
- the app is local-first and does not transmit recordings off device
- the canonical app storage is private
- users should still understand that exported copies in shared storage are less protected than app-private files

## 2. Data Ownership

All recorded performances belong to the user. Theremin Gloves does not require login, account creation, or any content-sharing agreement. The app includes no server-side profile, no cloud account, and no remote ownership claim over recorded material.

From a technical perspective, this is reinforced by the architecture:
- recordings are created locally from the synth engine PCM tap
- metadata is stored locally in `recordings.db`
- the app has no internet permission
- the library screen allows rename, playback, organization, and deletion without involving any external platform

The result is that ownership is practical, not only theoretical. The user controls creation, playback, deletion, and local retention. The app is not designed around subscription capture, social sharing, or remote collection of user content.

The main limitation is device sharing. If multiple people have unlocked access to the same phone, they can open the app and view the library unless the phone itself is protected. That is a general device-security issue rather than a cloud-data issue, but it still matters ethically because private recordings can become accessible to other device users.

## 3. Gesture Detection Misuse

The glove data used by the app is limited. On the Android side, the BLE layer consumes packet types such as `ACTIVE_DELTA_DEG`, `NEUTRAL_ROLL_DEG`, and `DIRECTION`. The app uses that information only to drive live control of pitch and volume, detect calibration state, and render connection status.

This data is coarse compared with full-body motion tracking or biometric sensing:
- it is focused on wrist-angle style control
- it is not stored as a long-term gesture history
- it is not combined with identity or cloud analytics
- it is not sufficient on its own to identify a person

That makes misuse for surveillance or biometric profiling implausible in the current implementation. The gesture data is ephemeral session state inside `BleSessionManager` and the audio service path, not a persistent analytics stream.

The realistic misuse concern is future scope creep. If a later version of the app were extended to log raw motion traces or transmit them externally, the ethical profile would change. For the current build, that risk is limited by architecture: no internet permission, no raw telemetry persistence, and no background analytics subsystem.

## 4. Accessibility

The current product is not universally accessible. It assumes the user can wear two gloves and perform repeatable wrist motions with both hands. That creates barriers for users with limited upper-limb mobility, reduced fine motor control, limb differences, or fatigue-related conditions.

There are still some accessibility-positive choices in the app:
- the main controls are visible and persistent
- text uses scalable Android text units
- the app can replay saved performances without glove hardware
- portrait-only locking avoids some layout breakage on rotation

However, the biggest accessibility limitation remains input dependence on two gloves. A more inclusive future direction would be:
- a single-hand mode
- touch-slider fallback input on the phone screen
- larger touch targets in the most control-dense screens
- stronger non-visual feedback for important actions such as calibration success and recording state

The ethical conclusion is straightforward: Theremin Gloves is a compelling prototype, but it currently serves a narrower group of users than an accessibility-first instrument would. That limitation should be acknowledged explicitly rather than treated as solved.

## 5. AI Transparency and Intellectual Contribution

The team used Claude (Anthropic) as an AI assistant in Sprint 2 and Sprint 3 for code scaffolding, documentation drafting, and architecture review. Full disclosure of what was built with and without AI assistance is available in `docs/08_AI_Usage_Document.md`.

The ethical question raised by AI-assisted development is: does using an AI coding assistant undermine the intellectual integrity of the work? The team's position is no, provided the following conditions are met — and they were:

1. **Review**: Every AI-generated code contribution was reviewed and understood by at least one team member before merging. No code was submitted without human comprehension of its function.
2. **Modification**: AI-generated scaffolding was always adapted to fit the actual architecture. In no case was raw AI output committed unchanged.
3. **Design ownership**: The architectural decisions (static BLE singleton, dual-engine audio ownership handoff, PCM tap for recording, additive synthesis tone recipes, SQLite additive migration strategy) were made by the team, not suggested by AI.
4. **Disclosure**: All AI usage is documented transparently, component by component, in `08_AI_Usage_Document.md`.

This approach treats AI as a pair-programmer tool rather than a replacement for engineering judgment. The ethical obligation is accurate disclosure, which this project meets.

## 6. Data Retention and Lifecycle

The app maintains two types of persistent data:

**Audio recordings:** Stored in `getFilesDir()/recordings/` (app-private SQLite metadata in `recordings.db`). Recordings persist until the user explicitly deletes them through the Library screen. The app provides a delete function for this purpose. There is no automatic expiry, no maximum retention period enforced by code, and no background deletion policy.

**BLE telemetry:** All incoming glove telemetry is ephemeral. `BleSessionManager` holds live values in volatile fields; `BleSnapshot` represents a point-in-time copy. No telemetry is written to disk, no history is accumulated, and no session log persists. The event log displayed in `ConnectGlovesActivity` is an in-memory circular buffer (8 lines maximum) that resets on process death.

**Settings:** Persisted in `theremin_gloves.db` until the user resets or uninstalls. No personal data is present in settings — only calibration angles, frequency ranges, and effect parameters.

**Privacy implication:** The app contains no telemetry, analytics, or crash-reporting SDKs. There is no `INTERNET` permission. The only data that could constitute a privacy risk is audio recordings of the user's musical performances, which are app-private and user-controlled.

## 7. Future Accessibility Directions

The team acknowledges the current accessibility limitation honestly: Theremin Gloves requires two functional hands with sufficient fine motor control to wear gloves and perform controlled wrist rotations. This is a real barrier for a non-trivial portion of the population.

Three concrete technical directions are proposed for future work, each of which the current architecture could accommodate without a full rewrite:

1. **Single-glove mode**: Use only the pitch glove for frequency control, and replace the volume glove with the phone's built-in accelerometer (measuring tilt). `PlayMappingState.recompute()` already accepts a `BleSnapshot`; a synthetic snapshot constructed from phone sensor data could replace the volume glove's contribution without changing the audio pipeline.

2. **On-screen touch slider fallback**: Add a vertical `SeekBar` in `MainActivity` that directly sets `ThereminAudioEngine.setTargets(freq, volumeFromSlider)`. This would allow users without gloves to use the full synth engine from a touchscreen, removing the hardware dependency entirely for volume (and potentially pitch via a second slider).

3. **Visual and non-visual feedback improvements**: The current calibration confirmation and recording-start state rely on visual indicators only. Adding vibration feedback (Android `Vibrator`) for neutral-capture confirmation and recording-start events would make the app more accessible to users with visual impairments or attention differences.

None of these require changes to the BLE, audio engine, or database layers. They are additive features that respect the existing architecture.
