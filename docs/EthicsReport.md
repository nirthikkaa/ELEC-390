# Ethics Report — Theremin Gloves

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026
**Team 5:** Niraj Patel (40211758), Ayan Pirani (40276971), Marie Ella Cambay (40284457), Nirthika Ilaiyarajah (40298669), Matei Moldovan (40277256)
**Instructor:** Dr. William Lynch
**Due:** April 12, 2026

---

## 1. Privacy of Recorded Audio

The Theremin Gloves app allows users to record their musical performances directly on the device. All recordings are stored in the application's private internal storage directory (`context.getFilesDir()/recordings/`). This directory is sandboxed by the Android operating system and is inaccessible to other applications without root access. No data is transmitted to external servers, cloud storage, or any network endpoint. The app does not include any analytics SDK, crash reporting service, or telemetry pipeline.

The user has full and exclusive control over their recordings. Files can be browsed, renamed, organized into folders, and permanently deleted from the Library screen. Deletion removes both the database record and the underlying file from storage. The app does not maintain any backup copy or hidden cache of deleted recordings.

**Limitation:** If the physical device is shared between multiple people (e.g., a family device or a shared lab device), all users with access to the device can open the app and access the Library screen. There is no per-user authentication or PIN protection. This is a known limitation for a prototype instrument.

**Mitigation:** Android's app-private storage (`getFilesDir()`) prevents recordings from being accessed by other installed applications. The app does not request the `READ_EXTERNAL_STORAGE` or `WRITE_EXTERNAL_STORAGE` permissions, meaning recordings cannot be scraped by file manager apps. A future improvement would be to offer optional screen lock or biometric protection for the library.

---

## 2. Data Ownership

All recordings made with the Theremin Gloves app are owned exclusively by the user. The application does not claim any license over user-generated content. The app does not request internet permissions (`android.permission.INTERNET`), making it architecturally impossible for it to upload content without a code change and a new APK install.

The team used generative AI tools (Claude by Anthropic) during development to assist with implementation and documentation. AI tools were not used to process any user data. No recordings, sensor telemetry, or personal information were submitted to any AI service. The AI usage is documented separately in `docs/GenerativeAI_Usage.md`.

The recording system supports multiple quality modes — lossless WAV and AAC at three bitrates. The user chooses the quality level. The choice of codec affects file size and audio fidelity but has no privacy implications, as all files remain on-device regardless of format.

There is no account system, no login, and no user profile. The app is entirely stateless from a server perspective.

---

## 3. Gesture Detection and Potential Misuse

The IMU gloves capture wrist roll angle using an inertial measurement unit (IMU) sensor on the Arduino Nano 33 BLE Sense. The sensor measures angular orientation in a single axis (roll). The data transmitted over BLE is a floating-point angle delta in degrees: `ACTIVE_DELTA_DEG:<float>`. This is a low-resolution, single-dimensional measurement.

This data cannot identify individuals. Wrist roll angle does not constitute biometric data — it does not reveal identity, health conditions, emotional state, or any sensitive personal characteristic. The data is not logged to persistent storage beyond the current BLE session. When the app is closed, all in-memory BLE telemetry is discarded.

**Potential misuse scenario:** A performer wearing the gloves in a public or semi-public setting (e.g., a stage performance or classroom demo) produces audio recordings of their performance. A third party observing the demo could note that the gestures control pitch and volume. This does not constitute a privacy violation beyond what any visible musical performance entails.

A more realistic concern: if the app were extended in a future version to log raw IMU data to a file, that log could reveal movement patterns over time. The current implementation does not log IMU data — only the final computed frequency and volume are used, and these are ephemeral.

**Mitigation:** Recording requires deliberate user opt-in (pressing the Record button). The app cannot passively record gestures or audio. There is no "always on" logging mode. The recording indicator (blinking red button and timer) provides unambiguous visual feedback that a recording is in progress.

---

## 4. Accessibility

The current design assumes the user can wear two wrist-mounted gloves and perform smooth wrist roll movements with both hands simultaneously. This assumption excludes users with:

- Limited or absent hand mobility (e.g., due to arthritis, tremor, or paralysis)
- Upper limb differences (e.g., limb loss or malformation)
- Conditions affecting fine motor control

This is a significant accessibility limitation for a prototype instrument in its current form.

**Existing mitigations:**
- The app follows Android Material Design guidelines for UI touch targets (minimum 48dp), making the on-screen controls usable with a stylus or accessibility tools.
- Font sizes use `sp` units and respect the system font size setting, making text legible for users with visual impairments.
- The app does not require audio output — it produces sound through the device speaker, which can be redirected to hearing aids or assistive listening devices via Bluetooth audio.

**Future mitigations (not yet implemented):**
- A "mouse mode" using the phone's built-in IMU (accelerometer/gyroscope) instead of the external gloves, allowing control via tilting the phone with one hand.
- On-screen slider controls for pitch and volume as a fallback input method, accessible without any glove hardware.
- Support for Android's accessibility services (TalkBack, Switch Access) to allow hands-free navigation of the app menus.

The team acknowledges that addressing mobility accessibility would require hardware redesign beyond the scope of this course project. The ethical obligation to consider these users is documented here to inform future development.

---

*Report prepared by Matei Moldovan on behalf of Team 5.*
