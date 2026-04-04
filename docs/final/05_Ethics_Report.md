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
