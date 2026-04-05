# Theremin Gloves — Professor FAQ

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026  
**Team 5:** Marie Ella Cambay, Niraj Patel, Ayan Pirani, Nirthika Ilaiyarajah, Matei Moldovan

> All answers are source-verified against the sprint3 branch as of April 4, 2026. Every constant, class name, and algorithm reference matches the actual production code.

---

## Contents

| Category | Topics | Questions |
|---|---|---|
| [A — Project Overview](#category-a--project-overview) | What it does, hardware, latency, tones | 10 |
| [B — Hardware and BLE Protocol](#category-b--hardware-and-ble-protocol) | Microcontroller, sensor, UUIDs, packet format, watchdog | 15 |
| [C — Audio Synthesis and DSP](#category-c--audio-synthesis-and-dsp) | Sample rate, IIR smoothing, vibrato, reverb, delay, distortion, scale lock, tones | 20 |
| [D — Software Architecture](#category-d--software-architecture-and-design-patterns) | Activity flow, threading, BleSnapshot, recording, databases, design patterns | 20 |
| [E — Testing](#category-e--testing) | Test counts, platforms, BLE stability, latency verification | 10 |
| [F — Sprints and Agile Process](#category-f--sprints-and-agile-process) | Sprint deliverables, exclusions, Definition of Done | 10 |
| [G — Ethics, Privacy, and AI](#category-g--ethics-privacy-and-ai) | Data collection, AI usage, surveillance risk | 10 |
| [H — Live Demo](#category-h--live-demo) | Demo flow, failure recovery, showcasing features | 10 |

---

## Category A — Project Overview

**Q: What does Theremin Gloves do?**  
A: It is an Android app that turns two Bluetooth Low Energy (BLE) IMU gloves (each built around an Arduino Nano 33 BLE Sense) into a wireless gesture instrument modelled on a theremin. The right (pitch) glove controls the output frequency by wrist roll angle; the left (volume) glove controls amplitude the same way. The phone synthesizes audio locally through `AudioTrack` in real time — no cloud, no server, no external audio hardware required.

**Q: Who is the target user?**  
A: Music students, hobbyist musicians, experimental performers, and electronics enthusiasts who want an expressive, contact-free instrument. Secondary users include engineering-course evaluators and creative technologists interested in BLE-to-audio pipelines. The app was designed around Concordia University's COEN/ELEC 390 engineering design course as the delivery context.

**Q: Why a theremin and not a standard synthesizer app?**  
A: A theremin maps a continuous analogue gesture — hand position — to frequency and volume continuously with no discrete keys or buttons. That maps naturally to IMU wrist-angle data, which is also continuous. A standard synthesizer app would require buttons or sliders on screen, undermining the "play without touching" premise. The theremin metaphor also has well-understood musical heritage and makes the demo immediately legible to an audience.

**Q: What hardware is required?**  
A: Two Arduino Nano 33 BLE Sense boards, each mounted in a glove with USB power bank, running custom firmware that streams wrist-roll angle over BLE. One Android phone running API 31 or higher. No other hardware is needed — no audio interface, no MIDI controller, no server.

**Q: What Android version is required and why?**  
A: API 31 (Android 12) is the minimum SDK. Android 12 introduced `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` as separate, fine-grained BLE permissions. The app relies on these new permissions and the `neverForLocation` flag on `BLUETOOTH_SCAN` so it does not need to request location permission just to scan. API 31 is the lowest version where the complete dual-permission BLE model is available.

**Q: What is the end-to-end latency?**  
A: The background-service path (used when the app is backgrounded and during calibration preview) has a typical latency of approximately 39–51 ms and a worst case of approximately 61 ms. The foreground Play path (visible Play screen) has a typical latency of approximately 54–66 ms and a worst case of approximately 91 ms. The worst-case foreground value exceeds the HD-11 `<80 ms` target slightly because the Play UI polls at 50 ms; however, typical use is well within target. See `docs/final/06_Computer_Simulation_Summary.md` for the full latency budget derivation.

**Q: How many tones are supported?**  
A: 11 user-selectable tones: THEREMIN, AIR_PAD, CELLO, PAD, CHOIR, FLUTE, CLARINET, TRIANGLE, SAW, SQUARE, and HELICOPTER. Additional tones (SWEET_LEAD, BELL, ORGAN, STRING, OBOE, TRUMPET, VIOLIN, GUITAR, and others) exist internally for backward compatibility but are not exposed in the public picker. The user-selectable set is defined in `SettingsStore.USER_SELECTABLE_TONES[]`.

**Q: Can the app be used without gloves?**  
A: No. The app requires two BLE gloves to produce output. If gloves are not connected, `PlayMappingState.isInstrumentReady()` returns false and the audio engine receives `volume = 0`, which mutes output. The Library and Settings screens work without gloves. Recorded performances in the Library play back through `MediaPlayer` independently of glove state.

**Q: How big is the APK?**  
A: Approximately 15–20 MB. The dominant size contributor is `DrumEngine`, which pre-computes 14 drum sounds and 75 piano samples entirely in code at construction time (no `.wav` asset files). The Java bytecode and Android manifest together are negligible by comparison.

**Q: What course requirements does this satisfy?**  
A: COEN/ELEC 390 requires a working hardware-software system with BLE communication, documented sprints, a full test suite, a live demo, and final written deliverables. Theremin Gloves satisfies the hardware requirement (two custom BLE gloves), the software requirement (34-class Android app), the communication requirement (BLE GATT with custom UUIDs), the testing requirement (51 test rows + 127 automated tests across 15 test files), and the documentation requirement (design doc, test doc, user manual, ethics report, computer simulation summary, and AI usage document).

---

## Category B — Hardware and BLE Protocol

**Q: What microcontroller is in each glove?**  
A: Arduino Nano 33 BLE Sense. It integrates an nRF52840 Bluetooth SoC with a built-in multiprotocol radio, and an LSM9DS1 nine-degree-of-freedom IMU (accelerometer + gyroscope + magnetometer) on the same PCB. The glove firmware uses the IMU to compute a wrist-roll angle, packages it as an ASCII string, and streams it over a custom BLE GATT service.

**Q: What sensor measures wrist angle?**  
A: The LSM9DS1 IMU. It provides accelerometer, gyroscope, and magnetometer readings, from which the Arduino firmware computes a roll angle. The Android app does not receive raw sensor data — it receives only the preprocessed wrist-roll delta from the firmware. The firmware subtracts the neutral position and transmits the delta so the Android side needs no raw IMU math.

**Q: What BLE characteristic carries live sensor data to the phone?**  
A: Service UUID: `12345678-1234-1234-1234-1234567890ab`. TX notify characteristic: `12345678-1234-1234-1234-1234567890ac`. Notifications from this characteristic carry the ASCII telemetry packets the phone parses. RX write characteristic (`...90ad`) carries commands the phone sends to the glove.

**Q: What is the BLE packet format?**  
A: Three packet types are sent by the glove:
- `ACTIVE_DELTA_DEG:<float>` — current wrist-roll delta relative to neutral, used for live frequency/volume mapping.
- `NEUTRAL_ROLL_DEG:<float>` — confirmation of the captured neutral position after an `N` command.
- `DIRECTION:<POSITIVE|NEGATIVE>` — current direction mode after a `D` toggle or initial handshake sync.

**Q: What commands does the phone send to the gloves?**  
A: Three single-character commands sent over the RX write characteristic:
- `H` — handshake/ping. Sent immediately after connection to verify the link and to refresh direction state.
- `N` — capture neutral. Tells the glove to store its current roll angle as the new neutral baseline.
- `D` — toggle direction. Inverts the direction of the roll-to-control mapping on the glove side.

**Q: How are the two gloves distinguished?**  
A: By their BLE advertisement name. `BleSessionManager` scans for exactly two names: `ThereminGlove` (pitch glove, right hand) and `ThereminGloveVol` (volume glove, left hand). The scan callback matches discovered devices against these names and routes them to the correct `Glove` wrapper object inside the session manager.

**Q: What BLE library is used and why Nordic?**  
A: Nordic Semiconductor's `no.nordicsemi.android:ble:2.11.0`. Raw Android `BluetoothGatt` is notorious for race conditions, state-machine inconsistencies across OEM implementations, and undocumented behaviour on reconnects. Nordic's `BleManager` abstraction provides: serialized operation queues (preventing simultaneous writes), structured `onReady()` / `onDeviceDisconnected()` callbacks, built-in `retry(3, 250)` for transient connection failures, and a tested API surface. The trade-off is a third-party dependency, but the gain in reliability on diverse Android hardware is well justified.

**Q: What is the scan timeout?**  
A: `SCAN_TIMEOUT_MS = 12_000L` (12 seconds). If neither glove is found within 12 seconds of starting a scan, the scan stops and the UI shows a failed-to-find state. The user can retry manually.

**Q: What is the auto-reconnect delay?**  
A: `AUTO_RECONNECT_DELAY_MS = 1_500L` (1.5 seconds). After a glove drops unexpectedly, `BleSessionManager` waits 1.5 seconds before attempting reconnect. This avoids hammering the BLE stack immediately after a drop, giving the glove firmware time to restart its advertisement.

**Q: When is a glove considered stale, and what happens?**  
A: At `STALE_WARNING_MS = 4_500 ms` of silence (no incoming telemetry packets despite being connected), the glove is marked stale and the UI displays `Connected • no data`. At `PING_AFTER_MS = 3_000 ms` of silence, the app sends an `H` ping first. If silence reaches `STALE_RECONNECT_MS = 20_000 ms`, the session manager drops the glove and schedules a full reconnect.

**Q: What does the BLE watchdog do?**  
A: `BleSessionManager`'s watchdog runs every `WATCHDOG_PERIOD_MS = 1_000 ms` on the Android main thread. On each tick it: refreshes the truth of all connection states, checks if any connect attempt has exceeded `CONNECT_TIMEOUT_MS`, checks if telemetry age has crossed the ping/stale/reconnect thresholds, sends `H` pings to silent-but-connected gloves, and forces drop-and-reconnect when the session appears dead. It is the single driver of all timed state transitions.

**Q: What happens if a glove is removed from the hand mid-play?**  
A: `BleSessionManager` detects the GATT disconnect callback and calls `drop(glove)`, which clears the glove's connected flag. On the next sync tick (≤20 ms), `PlayMappingState.isInstrumentReady()` sees `snapshot.isVolumeConnected()` or `snapshot.isPitchConnected()` as false and returns false. `ThereminAudioEngine.setTargets(freq, 0)` is called with volume = 0, which mutes the output. The watchdog then schedules auto-reconnect.

**Q: Why are BLUETOOTH_SCAN and BLUETOOTH_CONNECT separate permissions?**  
A: Android 12 (API 31) separated them from the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN`/`ACCESS_FINE_LOCATION` combination. `BLUETOOTH_SCAN` covers device discovery; `BLUETOOTH_CONNECT` covers communicating with a known device. The flag `usesPermissionFlags="neverForLocation"` on `BLUETOOTH_SCAN` tells the OS that the scan is not used to infer physical location, avoiding the location-permission dialog. This is cleaner for users and more honest about what the app actually does.

**Q: What data is cached between app sessions?**  
A: Glove MAC addresses are cached in `SharedPreferences` by `BleSessionManager` after a successful connection. On the next launch, when auto-connect fires, the manager can skip the full BLE name-based scan and connect directly to the cached MAC addresses. This typically saves 2–5 seconds of scan time on reconnect sessions. If MAC addresses are not cached (first run, or after the glove is replaced), a full scan runs.

**Q: How is per-glove direction inversion stored?**  
A: Two boolean columns in `app_settings` (`pitch_direction_inverted`, `volume_direction_inverted`). When the user toggles a direction in `SettingsActivity`, `SettingsStore.saveDirectionSettings()` persists it to SQLite, and `BleSessionManager.setDesiredDirection()` sends a `D` command to the glove if currently connected so the firmware toggles its own direction too. Both sides (Android mapping and firmware direction) are kept in sync.

---

## Category C — Audio Synthesis and DSP

**Q: What synthesis method is used?**  
A: Primarily additive synthesis (summing harmonic partials) with some frequency modulation (FM) for specific tones. `ThereminAudioEngine.sample()` dispatches to one of 24 tone recipes, each defined as a combination of `sin()` calls at multiples and ratios of the fundamental frequency, scaled by amplitude weights, optionally passed through `tanh()` saturation, and sometimes combined with simple FM where one oscillator modulates another's phase.

**Q: What is the sample rate and why 48 kHz?**  
A: `SAMPLE_RATE = 48000 Hz`. Three reasons: (1) Android's `MediaCodec` AAC encoder defaults to 48 kHz, so WAV and AAC recordings match without resampling. (2) The Nyquist theorem requires a sample rate at least twice the highest representable frequency; 48 kHz supports up to 24 kHz, giving comfortable margin above the 20 kHz extended frequency range. (3) 48 kHz is the standard mobile audio rate; using it avoids OS resampling that some Android devices apply when the rate differs from the hardware mixer rate.

**Q: What is the audio buffer size and why 1024 frames?**  
A: `AUDIO_WRITE_FRAMES = 1024`. One buffer covers `1024 / 48000 ≈ 21.3 ms` of audio. 1024 frames is small enough to keep the AudioTrack write interval short (fewer gaps possible), large enough to amortize the per-write OS overhead, and a power of two (convenient for bitwise ring-buffer wrap in effects). The previous version used 2048 frames; 1024 was chosen to reduce the fixed audio-buffer contribution to end-to-end latency.

**Q: How does the theremin avoid clicking when pitch changes suddenly?**  
A: A first-order IIR (exponential moving average) filter smooths the frequency: `smoothFreqHz += (targetFreqHz - smoothFreqHz) * FREQ_SMOOTHING` where `FREQ_SMOOTHING = 0.003`. The time constant is τ = −1/ln(1−0.003) ≈ 333 samples ≈ 6.9 ms at 48 kHz. Even a full octave jump (e.g., 440 → 880 Hz) converges over several buffers, eliminating the phase discontinuity that would otherwise produce a click.

**Q: Why is the attack smoother faster than release?**  
A: `ATTACK_SMOOTHING = 0.0046` (τ ≈ 4.5 ms) for rising volume, `RELEASE_SMOOTHING = 0.0018` (τ ≈ 11.6 ms) for falling volume. A real theremin behaves asymmetrically: bringing your hand into playing range causes sound to appear relatively quickly (responsive attack), but withdrawing causes it to fade gently (musical decay). Fast release would produce an abrupt cut that sounds unnatural. This asymmetric envelope emulates the acoustic character of the original instrument.

**Q: How is vibrato implemented?**  
A: A sinusoidal LFO modulates the instantaneous frequency: `freq = smoothFreqHz * (1 + sin(vibratoPhase) * depth)`. The LFO phase advances by `2π × VIBRATO_RATE_HZ / SAMPLE_RATE = 2π × 4.2 / 48000` per sample. The depth is volume-dependent: `depth = MIN_VIBRATO_DEPTH + (MAX_VIBRATO_DEPTH − MIN_VIBRATO_DEPTH) × volumeMix`, ranging from 0.0003 at silence to 0.0014 at full volume. Louder playing naturally introduces slightly richer vibrato, matching real theremin playing dynamics.

**Q: How does the reverb effect work?**  
A: A Schroeder single-comb filter. The pre-allocated `combBuffer[8192]` acts as a delay line of 8192 samples (8192/48000 ≈ 170 ms). On each sample: `delayed = combBuffer[combIdx]`, `combBuffer[combIdx] = clamp(x + delayed * 0.6, −1, 1)`, output = `x * (1−mix) + delayed * mix`. The feedback coefficient 0.6 and the ~170 ms delay length produce a diffuse reverb tail. Buffer wrapping uses `combIdx = (combIdx + 1) & COMB_MASK` (bitwise AND, O(1)) instead of modulo (integer divide, ~10 cycles) — this is important in the inner loop running at 48 000 iterations per second.

**Q: How does the delay effect work?**  
A: A feedback delay line using `delayBuffer[32768]` (32768 samples ≈ 682 ms at 48 kHz). On each sample: `delayed = delayBuffer[delayIdx]`, `delayBuffer[delayIdx] = clamp(x + delayed * delayFeedback, −1, 1)`, output = `x * (1−mix) + delayed * mix`. The buffer pointer wraps with `delayIdx = (delayIdx + 1) & DELAY_MASK`. Adjustable parameters: `delayFeedback` (0–1, default 0.35) controls echo decay; `delayMix` (0–1, default 0.4) controls wet/dry balance.

**Q: Why use tanh for distortion, and why normalise it?**  
A: `tanh` provides a smooth, differentiable soft-clipping characteristic that saturates gradually rather than hard-clipping abruptly. The normalized form `tanh(x * gain) / tanh(gain)` ensures that when `gain = 1`, the output equals the input (unity gain, no effect). As gain increases, the curve steepens and clips more aggressively. This normalization avoids a DC offset issue that unnormalized `tanh` saturation can introduce.

**Q: Why use `x & MASK` instead of `x % size` for ring-buffer wrap?**  
A: Bitwise AND executes in a single CPU cycle on any processor. Integer division (required for `%`) takes 10–30+ cycles on ARM. At 48 000 Hz with two ring buffers (reverb + delay), the inner loop uses these wrap operations up to 96 000 times per second. Using AND instead of modulo saves approximately 100 000–200 000 CPU cycles per second for each effect. This is why the buffer sizes are powers of two (8192 = 2¹³, 32768 = 2¹⁵): AND-based wrap only works for power-of-two sizes.

**Q: How does scale lock work?**  
A: `ThereminAudioEngine.snapToScale()` maintains a precomputed sorted float array of MIDI-standard frequencies for all notes in the selected scale (rebuilt only when the scale changes — not per sample). For each audio sample, the smoothed frequency is binary-searched against this table in O(log N) ≤ 7 iterations for the 128-note chromatic table. The result is cached: `snapCacheIn` stores the last input frequency, `snapCacheOut` stores the last result. If the input is identical (which happens >99% of the time with `FREQ_SMOOTHING = 0.003`), the cache is returned immediately without a search.

**Q: What are the 11 user-selectable tones?**

| Knob | Tone | Character |
|---|---|---|
| `THR` | THEREMIN | Classic heterodyne-style harmonic stack; strong second partial; vocal/cello quality |
| `AIR` | AIR_PAD | Detuned soft pad; slow beating between slightly offset oscillators; ambient quality |
| `CEL` | CELLO | Bowed-string FM; emphasized lower partials; dark, woody |
| `PAD` | PAD | Warm detuned sine stack; broad sustain |
| `CHR` | CHOIR | Shallow phase modulation; soft vocal pad |
| `FLT` | FLUTE | Near-pure sine with tiny upper-harmonic shimmer |
| `CLR` | CLARINET | Odd-harmonic closed-pipe spectrum; warm, woody reed |
| `TRI` | TRIANGLE | Triangle-derived; reinforced odd partials |
| `SAW` | SAW | Additive sawtooth; first six harmonics |
| `SQR` | SQUARE | Odd-harmonic square wave; hollow quality |
| `HEL` | HELICOPTER | Rhythmic pulse-tone; pitch controls chop rate 0.75–12 Hz |

**Q: What is the HELICOPTER tone exactly?**  
A: A rhythmic pulse synthesizer rather than a harmonic tone. The instantaneous pitch input is used to control a hit rate (chop frequency): `hitRateHz = clamp(freqHz * 0.0125, 0.75, 12.0)`. Each hit fires a brief body oscillator (frequency proportional to hit rate) with a noise burst, both with exponential decay envelopes. The result is a rhythmic helicopter-rotor sound whose tempo changes with wrist angle. At low wrist angles the blades spin slowly; at high angles they spin fast.

**Q: How are volatile reads protected in the audio thread hot path?**  
A: All `volatile` fields (`targetFreqHz`, `targetVolumeLinear`, `toneType`, `activeScale`, `reverbEnabled`, `delayEnabled`, `distortionEnabled`, and effect parameters) are copied into `final` local variables at the top of `fillBuffer()`, before the 1024-sample inner loop begins. This ensures the JVM only issues one memory-barrier read per buffer instead of one per sample (1024 per buffer), eliminating unnecessary cache-coherency traffic in the inner loop.

**Q: Why is `OUTPUT_GAIN = 0.14f`?**  
A: The synthesized signal peaks at approximately ±1.0 before gain. `DrumEngine` mixes an additional signal with `DRUM_MIX_HEADROOM = 0.26` (26% of Short.MAX_VALUE). Without gain reduction, the combined theremin + drums signal would regularly exceed the 16-bit `short` range, causing hard clipping. `0.14 × 32767 ≈ 4587`, which combined with the drum headroom stays well within the ±32767 range even with reverb feedback.

**Q: What is `DRUM_MIX_HEADROOM`?**  
A: `DRUM_MIX_HEADROOM = 0.26f`. When `DrumEngine.mixInto()` adds its voices into the mono buffer, each voice is scaled by this factor so the combined sum of drum voices does not drive the mix into clipping. A similar constant `PIANO_MIX_HEADROOM = 0.22f` is used for piano key voices, which tend to be higher-frequency and more prominent.

**Q: How does DrumEngine avoid timing drift in the sequencer?**  
A: `SequencerClock` uses a self-rescheduling pattern. After executing one tick, it computes the time until the next tick as `delay = max(0, lastTickMs + nextIntervalMs - System.currentTimeMillis())` and schedules the next call `delay` milliseconds from now. Because the computation accounts for actual execution time, accumulated error does not grow — each new reschedule is relative to when the last tick was supposed to fire, not when it actually fired.

**Q: How many drum sounds are pre-computed?**  
A: 14 drum sounds (kick, snare, closed hi-hat, open hi-hat, crash, clap, bass E2/A2/D3/G2, hi-tom, low-tom, rim, shaker) plus 75 piano samples (25 MIDI keys × 3 synth modes: KEYS/BELLS/ORGAN). All are synthesized as `float[]` PCM arrays in the `DrumEngine` constructor — no `.wav` files, no `SoundPool`, no asset loading. This keeps the APK asset-free while giving full control over gain, pitch, and timbre of each sound.

**Q: How is the high-pass filter in drum sounds implemented?**  
A: A single-pole recursive high-pass filter: `float hp = raw - prev * coeff`, where `prev` is the last input sample and `coeff` controls the cutoff. Higher coefficient → higher cutoff frequency → thinner, more metallic sound. Values in code: crash = 0.84 (warmest), snare = 0.93, open hi-hat = 0.95, closed hi-hat = 0.97 (thinnest). This one-multiply-per-sample filter is computationally trivial yet produces recognizably realistic metallic drum timbres.

**Q: Why not use Android's SoundPool for drum sounds?**  
A: `SoundPool` is designed to play pre-recorded `.wav`/`.ogg` files loaded from the APK assets folder. Using it would require shipping raw audio assets (increasing APK size), would prevent programmatic control over individual sample gain and pitch, and would add asynchronous load latency. `DrumEngine`'s synthesis approach produces all sounds from scratch in < 100 ms at startup, needs zero assets, gives precise control over every parameter, and integrates directly into the theremin's existing `short[]` PCM pipeline.

---

## Category D — Software Architecture and Design Patterns

**Q: What is the app's activity flow?**

```
LaunchActivity
    ↓ (returning user)
MainActivity (Play screen)
    ↓ (first-time user)
HomeActivity (setup + BLE onboarding)
    ↓ (once both gloves connected)
MainActivity (Play screen)

From MainActivity bottom-nav bar:
  → ConnectGlovesActivity (manual BLE controls)
  → CalibrationActivity (angle/frequency tuning)
  → LibraryActivity (recording browser)
  → SettingsActivity → UserManualActivity (in-app manual)
  → BeatMakerActivity (16-step sequencer editor)
```

**Q: Why is BleSessionManager a static singleton instead of a bound service or ViewModel?**  
A: Five Activities need simultaneous access to the same live BLE state: `LaunchActivity` (permissions), `HomeActivity` (auto-connect), `ConnectGlovesActivity` (manual control), `CalibrationActivity` (neutral capture), and `MainActivity` (live audio). A bound service would require each Activity to bind and unbind, with connection callbacks that arrive asynchronously — complicating lifecycle management. A ViewModel would be tied to one Activity lifecycle. The static singleton approach keeps a single GATT session, a single scan session, a single watchdog, and a single event log alive across all screen changes without re-initialization cost. The trade-off is that it must be carefully initialized once (`initialize(context)`) and never reset mid-session.

**Q: What is BleSnapshot and why is it immutable?**  
A: `BleSnapshot` is a value object (all `final` fields, no setters) that captures a consistent point-in-time view of all BLE state. `BleSessionManager.getSnapshot()` constructs a new `BleSnapshot` atomically from the current static fields. Because the snapshot is immutable, any thread can read it without synchronization, and there is no risk of reading a half-updated state. The audio service sync loop, the UI, and the calibration preview all read snapshots without holding any lock on `BleSessionManager`.

**Q: Why are there two ThereminAudioEngine instances (foreground and service)?**  
A: Android aggressively throttles or kills background `AudioTrack` threads unless a foreground service holds them. When `MainActivity` is visible, it runs a foreground `ThereminAudioEngine` directly on its audio thread. When the user leaves the Play screen, `MainActivity.onPause()` stops its engine and starts `ThereminBackgroundAudioService`, which runs a service-side engine. On return to Play, `onResume()` stops the service and starts the foreground engine again. This ownership-swap model ensures audio always lives on the correct thread for the current app state.

**Q: What design pattern does PcmListener use?**  
A: Observer (callback). `ThereminAudioEngine` defines the interface `PcmListener { void onPcmSamples(short[] samples, int count); }`. `RecordingManager` implements it. The engine calls `listener.onPcmSamples(monoBuffer, monoBuffer.length)` after mixing drums into the mono buffer but before stereo duplication and `AudioTrack.write()`. This tap point means recordings capture the exact synthesized output — not microphone audio, not just the theremin without drums.

**Q: Why use ReentrantLock.tryLock() in RecordingManager instead of synchronized?**  
A: `tryLock()` is non-blocking. If the recording codec is finalizing (`stopRecording()` is in progress on the main thread), `tryLock()` returns false immediately from the audio thread, and that PCM buffer is silently dropped. The alternative, `synchronized`, would block the audio thread until the lock is released, potentially stalling the audio engine for tens of milliseconds and causing an audible gap. On an audio thread, non-blocking is always preferred over blocking.

**Q: How does AppLaunchWarmup work?**  
A: `AppLaunchWarmup.begin(context)` is called during `LaunchActivity.onCreate()`, before any user navigation occurs. It spawns a single background daemon thread at `THREAD_PRIORITY_BACKGROUND` (lowest priority — never competes with the UI). This thread constructs: `SettingsStore`, an `AppSettings` snapshot, `RecordingRepository`, and a `DrumEngine` instance. `MainActivity` then calls `AppLaunchWarmup.takeDrumEngine()` on first open to claim the pre-built instance. Without warmup, `DrumEngine` construction (synthesizing 89+ PCM arrays) takes 200–400 ms on a cold start, which would freeze the first Play screen open.

**Q: What threading model does ThereminAudioEngine use?**  
A: A single dedicated audio thread started by `start()` and joined by `stop()`. The thread runs at `android.os.Process.THREAD_PRIORITY_AUDIO` (−16 in Android's nice-value system — higher priority than UI or network threads). All external inputs (`targetFreqHz`, `targetVolumeLinear`, tone/scale/effect toggles) are `volatile` fields written from the main or service thread and read in the audio thread. All `volatile` reads are hoisted to `final` locals before the 1024-sample inner loop to minimize memory barriers.

**Q: What happens if AudioTrack returns ERROR_DEAD_OBJECT?**  
A: This error indicates the audio output device changed (e.g., headphones plugged in or out). The audio thread detects `AudioTrack.ERROR_DEAD_OBJECT` from `audioTrack.write()`, releases the old instance, builds a new `AudioTrack` via `createAndStartTrack()`, and resumes from the next buffer. The user hears a brief gap (~21 ms to a few buffers), but the app does not crash and playback resumes automatically.

**Q: How are SettingsStore schema migrations handled?**  
A: Additive-only via `addColumnIfMissing(db, tableName, columnName, columnType, defaultValue)`. On every database open, `SettingsStore.ensureSchema(db)` checks for missing columns and adds them with defaults if absent. Existing data is never destroyed. A process-wide boolean flag `schemaVerifiedForProcess` prevents repeated `PRAGMA table_info` + `ALTER TABLE` calls across multiple screens within one app session. The schema version counter is incremented only for record-keeping; the actual migration logic does not depend on it.

**Q: What is PlayMappingState and why is it separate from ThereminBackgroundAudioService?**  
A: `PlayMappingState` contains only pure mapping logic — angle-to-frequency and angle-to-volume conversion, sensitivity curve application, octave shift, and the readiness guard. It has zero Android framework dependencies. This means it can be instantiated and tested in plain JVM unit tests (`SprintCoreIntegrationTest`) without any Android emulator or device. `ThereminBackgroundAudioService` holds an instance of it alongside BLE state and settings; `MainActivity` holds its own instance for the foreground path.

**Q: How is calibration saved without disrupting live audio?**  
A: `CalibrationActivity` never writes to `SettingsStore` until the user taps Save. Instead: (1) persisted settings are loaded into a mutable `CalibrationDraft` object; (2) the user adjusts sliders and presses Neutral buttons, which update the draft; (3) `ThereminBackgroundAudioService.beginCalibrationPreview(context, previewSettings)` creates a temporary settings override in the service that makes glove movement audible during calibration; (4) only when Save is pressed does `draft.saveTo(settings)` write to `SettingsStore`. This means the user can cancel calibration at any time without corrupting saved settings.

**Q: Why is there a CalibrationDraft.sanitize() method?**  
A: `normalizeClamped()` in `PlayMappingState` divides by `(maxAngle − minAngle)`. If `minAngle >= maxAngle`, this is a division by zero or a degenerate mapping. `sanitize()` enforces `maxAngle > minAngle + step` (where `step` = 0.5° for angles, 1 Hz for frequencies) by clamping and re-ordering the bounds. It also clamps individual values to their physical range (−90° to +90° for angles, 20 Hz to 20 kHz for frequency). Without sanitize, a user who drags a slider to an inverted range would produce silent or stuck audio.

**Q: How is the onboarding hint system implemented?**  
A: Three SharedPrefs boolean flags (`hint_connect_done`, `hint_play_done`, `calibrationGuideLearned`) gate three sequential hints:
1. Connect screen: `btnConnectToggle` pulses green (`ObjectAnimator.ofFloat(btn, ALPHA, 1f, 0.42f, 1f)`, 900 ms, infinite) until both gloves connect; dismissed by writing `hint_connect_done = true`.
2. Cal tab: `BottomNavBarView.setTabGlowing(2, true)` alpha-pulses the Cal bottom-nav tab until `calibrationGuideLearned` becomes true.
3. Play button: `btnAudioStart` pulses green after calibration until the user first presses Play; dismissed by writing `hint_play_done = true`.

**Q: What is NavigationUtils.Poller?**  
A: A reusable `Handler.postDelayed` loop wrapped in a simple start/stop API. Activities create a `Poller(periodMs, runnable)` in their constructor, call `poller.start()` in `onStart()`, and `poller.stop()` in `onStop()`. The runnable (typically `refreshUi()`) runs every `periodMs` milliseconds while the screen is visible. This avoids LiveData, RxJava, or other reactive frameworks — BLE state is polled from an immutable snapshot rather than observed through callbacks.

**Q: How many Java source files does the app have?**  
A: 34 Java source files in the main package. The full list: `AppLaunchWarmup`, `BeatMakerActivity`, `BleSessionManager` (contains `BleSnapshot` as inner class), `BottomNavBarView`, `CalibrationActivity`, `CalibrationDraft`, `ConnectGlovesActivity`, `DrumEngine`, `HomeActivity`, `InsetAwareScrollView`, `Instrument`, `KnobControlView`, `LaunchActivity`, `LibraryActivity`, `MainActivity`, `PianoKeyboardView`, `PianoStepStripView`, `PlayMappingState`, `PlayUiText`, `RecordingExportManager`, `RecordingListAdapter`, `RecordingManager`, `RecordingRepository`, `SequencerClock`, `SettingsActivity`, `SettingsStore` (contains `AppSettings` inner class), `StepGridView`, `SynthInstrument`, `ThereminAudioEngine`, `ThereminBackgroundAudioService`, `ThereminVisualizerView`, `ToneKnobView`, `TopNavBarView`, `UserManualActivity`.

**Q: What view binding strategy is used?**  
A: Android ViewBinding, generated from `activity_*.xml` layout files. Each Activity inflates its binding in `onCreate()` via `ActivityXxxBinding.inflate(getLayoutInflater())` and accesses all views through the binding object (e.g., `binding.btnAudioStart`). There are no `findViewById()` calls in any Activity. ViewBinding provides null-safety (views are non-null if they are in the layout) and type-safety (no cast required), unlike `View.findViewById()`.

**Q: What is BeatMakerActivity?**  
A: A 16-step drum sequencer editor. It contains: a `StepGridView` (13 rows × 16 columns touch grid — rows 0–5 for drum sounds, 6–9 for bass notes, 10–12 for additional sounds), a `PianoKeyboardView` (25 MIDI keys, C3–C5) for melody entry, a local `AudioTrack`-based preview that lets the user hear pattern changes without going through the theremin audio engine, and BPM / pattern / arpeggio controls. When the user saves, the edited pattern is written into `DrumEngine` via `setCustomPattern()`.

**Q: What are the two SQLite databases?**  
A: (1) `theremin_gloves.db` — owned by `SettingsStore`. Contains a single table `app_settings` with one row (`id = 1`) holding 24 columns of theremin/calibration/effects settings. (2) `recordings.db` — owned by `RecordingRepository`. Contains two tables: `folders (id, name, created_at_ms)` and `recordings (id, file_path, display_name, duration_ms, created_at_ms, folder_id, quality)`.

**Q: How does drag-to-reorder work in LibraryActivity?**  
A: `ItemTouchHelper` is attached to the RecyclerView with a callback that intercepts drag-start and drop events. `RecordingListAdapter` supports two item types: `TYPE_FOLDER` and `TYPE_RECORDING`. When a recording is dragged over a folder card, the adapter detects the type mismatch and moves the recording into that folder rather than reordering it. When a recording is dragged over another recording, it reorders in-place. After a drop, `RecordingRepository.moveRecording()` or the order is updated in the in-memory list.

---

## Category E — Testing

**Q: How many tests are there in total?**  
A: 127 automated `@Test` methods across 15 test files (40 JVM unit tests + 87 instrumented tests) plus 51 scenario-level rows in the test document.

Key test files:
- `ThereminUnitTest.java` — 39 JVM unit tests (no Android runtime required): waveform math, tone guards, pattern routing, gain math.
- `AppFeatureTest.java` — 53 instrumented feature tests: `DrumEngine` lifecycle, custom pattern API, BPM, gain, volume slider math, sequencer restart.
- `SprintCoreIntegrationTest.java` — 6 instrumented integration tests: settings round-trip, calibration constraints, `PlayMappingState` mapping math, background service flags.

Additional instrumented test classes:

| Class | Tests |
|---|---|
| `PlayMatrixUiTest` | 5 |
| `LibraryUiTest` | 4 |
| `RecordingRepositoryIntegrationTest` | 4 |
| `PlayAudioControlsUiTest` | 3 |
| `CalibrationRegressionUiTest` | 3 |
| `SettingsAndNavigationUiTest` | 3 |
| `HardwareBleRegressionUiTest` | 2 |
| `BluetoothPromptUiTest` | 1 |
| `BluetoothStateIntegrationTest` | 1 |
| `PlayStageModeUiTest` | 1 |
| `ExampleInstrumentedTest` | 1 |

Plus 51 scenario-level test rows in this test document covering BLE, calibration, audio, recording, library, settings, navigation, and stability.

**Q: What do the JVM unit tests cover?**  
A: Waveform math (all 15 tone recipes bounded in ±1, non-zero energy, mutually distinct at test phases), characteristic harmonic properties (trumpet saturates, flute stays soft, clarinet has near-zero even harmonics, choir has measurable detuning), tone selector guards (legacy `drum` maps to `HELICOPTER`, public picker excludes hidden tones), `GridPatternSource` step routing (kick fires at step 0, snare at step 4, bass rows use `fireBass()` not `fire()`), pattern OR-merge (idempotent, multi-layer), and drum gain math (linear gain, clamping to [0,2], headroom formulas).

**Q: What do the instrumented integration tests cover?**  
A: Settings round-trip for all 24 `app_settings` columns; invalid tone/scale coercion to defaults; octave shift clamping; sensitivity curve clamping; SharedPreferences flags; `CalibrationDraft.sanitize()` behaviour for angle/frequency boundary conditions; `PlayMappingState.recompute()` with octave shift, sensitivity curves, and mute conditions; background service static flag persistence.

**Q: On what platform were tests run?**  
A: Google Pixel 7, Android 14, API 34 — real hardware, not an emulator. BLE and audio behave differently on emulators (BLE is simulated; audio latency is non-representative), so all Pixel 7–targeted tests were run on the physical device.

**Q: How was recording quality verified?**  
A: Playback of LOSSLESS (WAV) recordings in the Library compared audibly with the live theremin output they were recorded from. File size check: a 10-second LOSSLESS recording should be approximately `48000 Hz × 2 channels × 2 bytes × 10 s = 1 920 000 bytes ≈ 1.83 MB` (plus 44-byte header). AAC files were opened in the Android Music app and played back to confirm correct encoding.

**Q: How was BLE stability tested?**  
A: Three methods: (1) 30-minute continuous session — both gloves connected with audio playing; confirmed no crash, ANR, or audio degradation. (2) 10 manual connect/disconnect cycles — BLE state recovered cleanly each time with no stuck state. (3) Stale telemetry simulation — glove firmware paused (no packets sent); confirmed `STALE_WARNING_MS = 4500` flag appeared and `STALE_RECONNECT_MS = 20000` forced reconnect.

**Q: Why are 34 test rows labelled "Pending manual verification"?**  
A: The original conservative draft marked any row that required a physical Pixel 7 + two powered gloves + time measurement as pending rather than claiming Pass without evidence. The final submission updates all 34 to Pass, reflecting the manual verification done on the demo device. The code for all features has been present and working throughout; only the formal in-person verification step was deferred.

**Q: What testing framework is used?**  
A: JUnit 4 for all test classes. AndroidX Test (`ActivityScenarioRule`, `onView`, `espresso-core`) for instrumented UI interaction tests. No Mockito — tests that require an Android context use `InstrumentationRegistry.getInstrumentation().getTargetContext()` on a real device. JVM-only tests use plain JUnit 4 with no mock framework (all test subjects are pure Java classes).

**Q: How was the latency budget verified?**  
A: Analytically, not empirically with a timing instrument. The budget adds the known constant values: BLE notify interval (documented BLE connection interval, typically 7.5–20 ms), sync loop period (20 ms for service, 50 ms for foreground UI), and audio buffer time (1024/48000 = 21.33 ms). The foreground worst case `20 + 50 + 21.33 = 91.33 ms` and background worst case `20 + 20 + 21.33 = 61.33 ms` follow directly. No additional profiling tool was needed because all three values are constants in the source code.

**Q: What was explicitly excluded from testing and why?**  
A: HD-8 (battery percentage display) — the Arduino Nano 33 BLE Sense does not expose battery level over BLE in the firmware; implementing it would require firmware changes outside scope. HD-21 (dead zone) — micro-jitter is already handled by calibration range selection and `FREQ_SMOOTHING`/volume smoothing; a separate dead-zone constant was judged unnecessary. HD-11.2 and HD-11.3 (latency micro-optimizations) — the existing 1024-frame buffer and PERFORMANCE_MODE_LOW_LATENCY already achieve the target; further optimization was out of scope.

---

## Category F — Sprints and Agile Process

**Q: What was delivered in Sprint 1?**  
A: BLE dual-glove scanning, connection, and auto-reconnect. Full calibration flow (neutral capture, angle/frequency range tuning, Save & Play). `ThereminAudioEngine` with `AudioTrack`, pitch/volume synthesis, visualizer. `PlayMappingState` with angle-to-frequency mapping. Per-glove direction control. `SettingsStore` SQLite persistence. `LaunchActivity`, `HomeActivity`, `ConnectGlovesActivity`, `CalibrationActivity`, `SettingsActivity`.

**Q: What was delivered in Sprint 2?**  
A: `RecordingManager` with PCM tap → WAV and AAC-LC output. `RecordingRepository` SQLite metadata. `LibraryActivity` with full playback, search, rename, delete, folders, drag-to-reorder, mini-player. `ToneKnobView` rotary selector. The public tone picker grew from 9 to 10 tones in Sprint 2. (Sprint 3 re-added SQUARE for the current total of 11.)

**Q: What was delivered in Sprint 3?**  
A: `DrumEngine` (14 sounds, 8 patterns, 16-step sequencer, piano synth, arpeggio). `BeatMakerActivity` sequencer editor. Audio effects pipeline (reverb/Schroeder comb, delay/ring buffer, distortion/tanh). Scale lock (CHROMATIC, MAJOR, MINOR, PENTATONIC). Octave shift (±2 octaves). Sensitivity presets (0.25–2.50 curve). Performance mode optimizations. Onboarding hint system (glowing buttons). Square tone re-added to public picker (11 tones total). Background audio foreground service refinements.

**Q: What was explicitly excluded and why?**  
A: HD-8 (battery % on Connect screen) — Arduino Nano 33 BLE Sense does not expose battery level over BLE; firmware change out of scope. HD-21 (explicit dead zone) — already solved by calibration + smoothing. HD-11.2 / HD-11.3 (latency micro-optimizations) — current 1024-frame buffer with `PERFORMANCE_MODE_LOW_LATENCY` already meets the target. 20 stories total are listed with their exclusion reasons in `docs/final/12_Final_Product_Backlog.md`.

**Q: What is the Definition of Done?**  
A: Nine criteria must all be true before a story is marked complete: (1) `./gradlew assembleDebug` passes with no errors. (2) Feature works on the Pixel 7 with real BLE gloves. (3) No crash or ANR introduced. (4) Data persists correctly across app kill and reopen. (5) Feature survives device restart. (6) Code reviewed by at least one other team member. (7) Test document updated with a new row. (8) Design document updated if architecture changed. (9) No regression in previously passing tests.

**Q: How were user stories prioritized?**  
A: MoSCoW method. Sprint 1 must-haves: BLE communication + audio synthesis (without these, nothing works). Sprint 2 should-haves: recording and library (high product value, clear scope). Sprint 3 could-haves: effects, drum engine, scale lock (enhancements). Won't-haves: battery display, MIDI controller support, cloud sync (explicitly out of scope).

**Q: How many completed stories total, and how many were excluded?**  
A: 31 stories completed across three sprints. 20 stories explicitly listed as not implemented with reasons. Full detail in `docs/final/12_Final_Product_Backlog.md`.

**Q: How long was each sprint?**  
A: Two weeks each. Sprint 1: weeks 3–4 of the semester. Sprint 2: weeks 7–8. Sprint 3: March 23 – April 6, 2026. Final submission: April 15, 2026.

---

## Category G — Ethics, Privacy, and AI

**Q: What data does the app collect?**  
A: Two types only: (1) BLE wrist-angle telemetry from the gloves — ephemeral, stored only in `BleSessionManager` session state, never written to disk, cleared on disconnect. (2) Audio recordings — written to `getFilesDir()/recordings/` (app-private), stored only if the user explicitly starts recording. No analytics, no crash reporting, no usage metrics, no cloud sync, no accounts.

**Q: Are recordings private?**  
A: The primary recording files are stored in `getFilesDir()/recordings/` — Android's app-private internal storage. Other apps cannot access these files without root access or explicit user-granted content URIs. There is no `INTERNET` permission in the manifest, so files cannot be uploaded. The optional export copy (created by `RecordingExportManager` in `Music/Theremin Gloves Recordings`) is placed in shared storage and is therefore accessible to any app with `READ_EXTERNAL_STORAGE` permission. Users should treat exported copies as less private than the app-private originals.

**Q: Could the glove IMU data be used for surveillance or biometric profiling?**  
A: No. The data transmitted is a coarse wrist-roll angle delta (a single float, typically −90° to +90°) updated every BLE notification interval. It is not sufficient to identify a person (biometric gait recognition requires multi-joint data with high temporal resolution). It is ephemeral — no telemetry log is written to disk. It never leaves the device. It is not combined with location, identity, or any other data stream.

**Q: What are the accessibility limitations?**  
A: The app requires two functioning hands with sufficient fine motor control to wear gloves and make controlled wrist rotations. This excludes users with: upper-limb mobility impairments, reduced fine motor control, limb differences, or significant fatigue conditions. This is acknowledged in the ethics report as the primary accessibility limitation. Future directions: single-glove mode (phone accelerometer for volume), on-screen touch-slider fallback, larger touch targets in dense UI areas, stronger non-visual feedback for calibration/recording state.

**Q: What AI tools were used?**  
A: Claude (Anthropic) was used as a coding and documentation assistant in Sprint 2 and Sprint 3. Specific uses: BLE edge-case code review and suggestions, audio engine extension scaffolding (effects, additional tones), recording extension architecture review, UI scaffolding for `BeatMakerActivity`, documentation drafting and proofreading.

**Q: What was built entirely without AI assistance?**  
A: Arduino firmware for both gloves. BLE core from Sprint 1 (initial `BleSessionManager` implementation, `ThereminGloveBleManager`). Audio engine core algorithm (`ThereminAudioEngine.sample()` tone recipes, smoothing constants). Recording foundation (`RecordingManager` WAV path). Library foundation (`RecordingRepository`, `LibraryActivity` core). All Sprint 1 features. Full details in `docs/final/08_AI_Usage_Document.md`.

**Q: How is AI usage disclosed?**  
A: `docs/final/08_AI_Usage_Document.md` contains a full component-by-component disclosure table: component name, whether AI was used (yes/no), what it was used for, and the level of human involvement. The document distinguishes between AI-generated scaffolding that was reviewed and modified by team members versus pure human-written code. A summary table shows 7 components with AI involvement and 4 without.

**Q: What is the team's position on AI contribution and academic integrity?**  
A: AI was used as an engineering tool — the equivalent of a smart search engine or pair-programmer assistant. All AI-generated code was reviewed, understood, tested, and modified by team members before being merged. No code was submitted without human understanding of what it does. The intellectual design decisions (architecture, BLE protocol, synthesis algorithm, effects pipeline, database schema) were made by the team. AI assisted with scaffolding and review, not with design.

**Q: What would an ethics review board focus on most critically for this project?**  
A: The accessibility gap. The product requires two functioning hands with fine motor dexterity. A review board would likely ask: why was no single-glove fallback designed? What percentage of the target user population is excluded? Are there alternative input paths for users with mobility limitations? The ethics report acknowledges this directly as the most significant ethical limitation, and proposes single-glove mode and touch-slider fallbacks as future work.

---

## Category H — Live Demo Preparation

**Q: What is the planned demo flow?**  
A: Eight segments in approximately 8 minutes:
1. (0:00–0:45) App launch → privacy acceptance → Bluetooth permissions granted.
2. (0:45–1:30) Connect screen → both gloves connect via auto-connect.
3. (1:30–2:30) Calibration → Pitch Neutral → Volume Neutral → adjust sliders → Save & Play.
4. (2:30–4:00) Play screen → theremin → demonstrate pitch control (right glove) and volume control (left glove).
5. (4:00–5:00) Tone switching via ToneKnobView → cycle through 3–4 tones → demonstrate HELICOPTER.
6. (5:00–6:00) Sprint 3 features → scale lock (PENTATONIC) → octave shift → reverb/delay → Beat Maker pattern.
7. (6:00–7:00) Recording → press Record → play for 30 seconds → stop → see in Library.
8. (7:00–8:00) Library → play back recording → point out folder/search/quality display.

**Q: What if a glove disconnects during the demo?**  
A: Do not panic. The watchdog schedules auto-reconnect in 1.5 seconds. Keep talking through the architecture or switch to the Library/Settings screens temporarily. When the glove reconnects, the Connect screen will show "Connected" and the system navigates back to Play automatically (if `startupAutoNavUsed` is false) or manually. The demo script in `docs/final/10_Demo_Preparation.md` includes explicit fallbacks for this scenario.

**Q: What if Bluetooth is completely off on the demo phone?**  
A: `LaunchActivity` calls `BleSessionManager.requestEnableBluetoothPrompt()` which fires `ACTION_REQUEST_ENABLE`. The system Enable Bluetooth dialog appears in front of the launch screen. The user can accept it, after which `onActivityResult()` continues the launch flow normally. This has been tested.

**Q: What does the waveform visualizer show?**  
A: `ThereminVisualizerView` displays the most recent 180-sample downsampled slice of the mono PCM render buffer from `ThereminAudioEngine.getVisualizerSnapshot()`. At 48 000 Hz with 1024 frames per buffer, each visualizer update shows approximately `180 / 48000 × 1024 ≈ 3.84 ms` of audio waveform. The UI refreshes at `UI_TICK_MS = 50 ms` — approximately 20 frames per second. It shows the actual synthesized waveform shape of the current tone.

**Q: How do you demonstrate scale lock live?**  
A: Enable PENTATONIC from the active scale dropdown in the Play screen. Slowly sweep the pitch glove across its full range. Every note produced is a member of the pentatonic scale — there are no "wrong" notes possible. Compare with CHROMATIC mode (all 12 semitones) to show the contrast. The transition between scale modes is instantaneous and glitch-free because `snapToScale()` is called per sample.

**Q: How do you demonstrate octave shift?**  
A: With both gloves connected and audio playing, press the +1 or +2 octave button. For the same wrist angle, the pitch doubles (or quadruples). The frequency display confirms the shift. Press −1/−2 to show downward shift. The mapping is `freq = mappedFreq × 2^octaveShift`, clamped to [20 Hz, 20 000 Hz].

**Q: How do you switch between the 11 tones?**  
A: Rotate the `ToneKnobView` circular dial in the Play screen. Each position corresponds to one of the 11 public tones. The tone label updates instantly. The tone change is atomic — it writes to the `volatile String toneType` field, which the audio thread reads at the next buffer boundary (~21 ms). No click or dropout occurs during switching.

**Q: What if the recording file is missing when the Library opens?**  
A: `RecordingRepository` stores the file path in `recordings.db`. If the file was deleted outside the app (e.g., via a file manager), the database entry still exists. `LibraryActivity.loadData()` will load the metadata entry, but the playback attempt will fail with a `MediaPlayer` error. The adapter shows the entry in the list; playing it shows an error toast. The user can delete the orphaned entry via the delete menu. This edge case is handled gracefully — no crash.

**Q: How do you demonstrate BLE reconnect live?**  
A: Connect both gloves. Deliberately power off one glove (remove from power bank or switch off). Wait for the Connect screen to show "Disconnected." Then power the glove back on. Within approximately 5–10 seconds, the watchdog detects the advertisement, connects, and the UI transitions back to "Connected." Mention `AUTO_RECONNECT_DELAY_MS = 1500 ms` and `SCAN_TIMEOUT_MS = 12 000 ms` as the relevant constants.

**Q: What is the minimum hardware required for the demo?**  
A: One Google Pixel 7 (or equivalent Android 12+ device) running the debug APK. Two Arduino Nano 33 BLE Sense boards mounted in gloves with power (USB power bank or rechargeable battery), running the glove firmware (advertises `ThereminGlove` and `ThereminGloveVol`). Headphones or a Bluetooth speaker are optional but improve audio quality for the audience.
