# Sprint 3 Testing Results

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026
**Team 5:** Niraj Patel, Ayan Pirani, Marie Ella Cambay, Nirthika Ilaiyarajah, Matei Moldovan
**Sprint:** March 23 – April 6, 2026
**Test Device:** Pixel 7 (primary), Pixel 3a (secondary)

---

## Instructions

Fill in the "Actual" and "Pass/Fail" columns after hardware testing with both gloves connected. Each team member is responsible for the features listed beside their name in the Comments column. Collect results by April 12.

- **Pass** = actual result matches expected exactly
- **Fail** = deviation from expected; describe the deviation in the Actual column
- **N/T** = not yet tested

---

## Test Results Table

| Feature | Test Case | Expected | Actual | Pass/Fail |
|---------|-----------|----------|--------|-----------|
| **Drum Kit** | Drum loop runs for 5+ minutes continuously | No timing drift; consistent rhythm throughout; no BPM deviation audible | | |
| **Drum Kit** | Toggle drum on mid-play | Drum audio begins immediately on next 16th-note beat with no audio glitch or dropout from theremin | | |
| **Drum Kit** | Toggle drum off mid-play | Drum audio stops immediately; theremin continues without interruption | | |
| **Drum Kit** | Drum audio appears in recording (record 30s with drum enabled) | Library playback includes both theremin and drum audio mixed together | | |
| **Drum Kit** | Pitch and volume gestures work normally while drum is playing | Theremin pitch and volume respond to glove movements normally; no interference | | |
| **Drum Kit** | App runs stably with drum enabled for 10+ minutes | No crash, no memory growth, no audio degradation | | |
| **Audio Effects** | Enable reverb — audible effect | Reverb (room echo) clearly audible; `reverbMix=0.3` default produces moderate effect | | |
| **Audio Effects** | Enable delay — audible effect | Echo repetition clearly audible at ~500ms with `delayMix=0.4` default | | |
| **Audio Effects** | Enable distortion — audible effect | Saturation/overdrive effect clearly audible; `distortionGain=3.0` default produces moderate saturation | | |
| **Audio Effects** | All 3 effects enabled simultaneously for 5+ minutes | No crash, no audio dropout, no buffer overflow; all three effects audibly active throughout | | |
| **Audio Effects** | Toggle effects on/off mid-performance | No audio glitch or dropout when enabling or disabling any effect during playback | | |
| **Audio Effects** | Effects captured in recording | Record 30s with reverb + delay enabled; library playback includes effects audibly | | |
| **Audio Effects** | Effect settings persist after app restart | Reopen app; previously enabled effects and mix values are restored | | |
| **Scale Lock** | CHROMATIC — free pitch | All frequencies between freqMin and freqMax available; no pitch snapping observed | | |
| **Scale Lock** | MAJOR scale — notes snap to key | During play, only major scale notes heard; no out-of-key pitches; smooth snap near scale boundaries | | |
| **Scale Lock** | MINOR scale — notes snap correctly | Only minor scale notes heard; semitone pattern [0,2,3,5,7,8,10] correctly applied | | |
| **Scale Lock** | PENTATONIC scale — notes snap to 5-note scale | Only 5 pentatonic pitches per octave heard; confirmed by ear | | |
| **Scale Lock** | Switch scales mid-play (e.g., CHROMATIC → MAJOR) | No audio glitch on switch; new scale takes effect immediately | | |
| **Scale Lock** | Scale setting persists after app restart | Reopen app; previously selected scale is restored | | |
| **Octave Shift** | Oct +1 — pitch one octave higher than baseline | Audible one-octave increase; same gesture produces twice the frequency | | |
| **Octave Shift** | Oct +2 — pitch two octaves higher | Audible double-octave increase | | |
| **Octave Shift** | Oct -1 — pitch one octave lower | Audible one-octave decrease; same gesture produces half the frequency | | |
| **Octave Shift** | Oct -2 — pitch two octaves lower | Audible double-octave decrease | | |
| **Octave Shift** | Octave shift switch mid-play | No audio dropout on octave change | | |
| **Octave Shift** | Frequency clamped at extremes (Oct +2 at high freq) | Output does not exceed 20,000 Hz; clamping applied | | |
| **Octave Shift** | Octave setting persists after app restart | Reopen app; previously selected octave shift is restored | | |
| **Gesture Sensitivity** | High sensitivity — smaller movement reaches full range | Reaches max pitch and max volume at approximately half the normal hand movement | | |
| **Gesture Sensitivity** | Medium sensitivity — baseline behavior | Behavior matches pre-Sprint-3 baseline; requires full calibrated range of movement | | |
| **Gesture Sensitivity** | Low sensitivity — larger movement required | Requires approximately 1.5× the normal movement to reach full range | | |
| **Gesture Sensitivity** | Sensitivity setting persists after app restart | Reopen app; previously selected sensitivity level is restored | | |
| **Gesture Sensitivity** | Switch sensitivity mid-session | Change takes effect on next settings reload; no crash | | |
| **Gesture Sensitivity** | BLE and audio unaffected (regression) | Pitch and volume gestures work correctly at all three sensitivity levels | | |
| **Performance Mode** | Enter performance mode | `cardDebugLog`, `cardPlayMapping`, `cardGloveCommands`, `tvStatus`, `tvAudio`, secondary labels all hidden (GONE) | | |
| **Performance Mode** | Exit performance mode | All hidden elements restored to VISIBLE; layout returns to normal | | |
| **Performance Mode** | Essential controls visible in performance mode | Visualizer, tone knob, connection chips, record button, scale chips, octave buttons, drum toggle, effects panel all visible | | |
| **Performance Mode** | Audio during performance mode toggle | Theremin audio unaffected when entering or exiting performance mode | | |
| **Performance Mode** | Performance mode persists after navigating away and back | Navigate to Library and back; performance mode state is preserved | | |
| **Performance Mode** | Performance mode persists after app restart | Reopen app; previously set performance mode state is restored | | |
| **Performance Mode** | Recording works normally in performance mode | Press Record in performance mode; recording starts, timer shows, stop saves file | | |
| **Build** | `./gradlew assembleDebug` | BUILD SUCCESSFUL with no errors or warnings | | |
| **BLE Latency** | Gesture-to-sound end-to-end latency | Typical < 80ms (HD-11 acceptance criterion) | ~55–70ms (analytical estimate) | Pass (typical) |
| **Regression: Sprint 1** | BLE connection — both gloves connect within 10s | Both gloves appear connected on Connect screen | | |
| **Regression: Sprint 1** | Auto-reconnect after disconnect | Reconnect attempt starts within 3s of disconnect | | |
| **Regression: Sprint 2** | Record and play back a clip | Recording saved; playback in Library matches performance | | |
| **Regression: Sprint 2** | Waveform switching via ToneKnob | All 9 waveforms produce distinct audible output | | |

---

## Latency Breakdown

| Component | Value | Notes |
|-----------|-------|-------|
| BLE notification interval | ~7.5–20ms | Android-negotiated; non-deterministic |
| Sync loop tick (`SYNC_TICK_MS=20`) | 20ms | `ThereminBackgroundAudioService` |
| Audio buffer (2048 samples @ 48kHz) | 42.7ms | Dominant contributor |
| **Worst case total** | **~83ms** | |
| **Typical total** | **~55–70ms** | Passes HD-11 criterion |

HD-11 acceptance criterion: < 80ms gesture-to-sound latency. Typical case passes. Worst case marginally exceeds criterion due to non-deterministic BLE scheduling.

---

## Test Result Summary

*(Fill in after testing is complete)*

| Category | Total Tests | Pass | Fail | Not Tested |
|----------|------------|------|------|-----------|
| Drum Kit | 6 | | | |
| Audio Effects | 8 | | | |
| Scale Lock | 6 | | | |
| Octave Shift | 7 | | | |
| Gesture Sensitivity | 6 | | | |
| Performance Mode | 7 | | | |
| Build / Latency | 2 | | | |
| Regression | 4 | | | |
| **TOTAL** | **46** | | | |

---

*Document prepared by Matei Moldovan. Results to be filled in by team after hardware testing (target: April 12, 2026).*
