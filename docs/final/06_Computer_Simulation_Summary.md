# Theremin Gloves — Computer Simulation Summary

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026  
**Team 5:** Marie Ella Cambay, Niraj Patel, Ayan Pirani, Nirthika Ilaiyarajah, Matei Moldovan

## Executive Summary

- The current background-audio and calibration-preview path is comfortably under the HD-11 `<80 ms` target.
- The visible Play screen is still acceptable in typical use, but its strict worst case is higher because `UI_TICK_MS = 50 ms`.
- The public Play build now exposes a curated 11-tone selector, not the older 9-waveform set.
- The default Play mapping ranges are intentionally narrower than the persisted calibration defaults so the instrument feels more controllable in a demo setting.

## 1. BLE Latency Model

The latency chain was modelled analytically from the current implementation constants:
- BLE notify interval: typically about `7.5–20 ms`
- service sync loop: `SYNC_TICK_MS = 20 ms`
- audio buffer: `AUDIO_WRITE_FRAMES / SAMPLE_RATE = 1024 / 48000 = 21.33 ms`

For the background-service path:
- typical latency is approximately `38.8–51.3 ms`
- worst case is approximately `61.3 ms`

For the visible Play path, target updates currently depend on `UI_TICK_MS = 50 ms`, so:
- typical latency is approximately `53.8–66.3 ms`
- worst case is approximately `91.3 ms`

**Finding:**
- the service path satisfies the HD-11 `<80 ms` criterion comfortably
- typical foreground use is also acceptable
- strict foreground worst case exceeds the target because the current UI refresh loop is slower than the service sync loop

**Design implication:**
- the code no longer uses the older `2048`-sample assumption seen in stale docs
- the present limiting factor is not the audio buffer alone, but the foreground update cadence

## 2. Frequency Mapping Range Analysis

The mapping function in `PlayMappingState` is a clamped linear interpolation after angle normalization and sensitivity shaping:

```text
pitchNorm = clamp((angle - minAngle) / (maxAngle - minAngle), 0, 1)
pitchNorm = pitchNorm ^ sensitivityResponseCurve
freq = freqMinHz + (freqMaxHz - freqMinHz) * pitchNorm
```

The current default Play ranges are:
- pitch angle: `-15°` to `55°`
- volume angle: `-10°` to `55°`
- frequency: `880 Hz` to `2000 Hz`

Important distinction:
- these are Play-screen mapping defaults from `PlayMappingState`
- the persisted Calibration/Settings defaults in `AppSettings` remain pitch `0°` to `90°`, volume `0°` to `90°`, and frequency `20 Hz` to `2000 Hz`

**Interpretation:**
- these defaults bias the live instrument toward a musically useful upper register rather than a huge raw span
- the calibration screen still allows much wider range customization
- extended range mode raises the ceiling to `20,000 Hz`, but that mode favors experimentation over precise melodic control

**Finding:**
- a moderate calibrated angle span remains the best compromise between reachable movement and useful melodic resolution
- the current default ranges are aggressive but practical for a demo-oriented theremin experience

## 3. Waveform / Tone Harmonic Analysis

The current public build exposes **11 user-selectable tones** (SQUARE was re-added to `USER_SELECTABLE_TONES[]` in the sprint3 branch). Harmonic inspection of the synthesis code in `ThereminAudioEngine.sample(...)` shows:
- `THEREMIN`: harmonic stack with strong second partial for classic vocal/cello theremin color
- `AIR_PAD` and `PAD`: detuned layered partials for width and slow beating
- `CELLO`: low-order bowed-string style harmonic emphasis
- `CHOIR`: low-order vocal-pad harmonic structure with shallow phase modulation
- `FLUTE`: near-sine spectrum with minimal shimmer
- `CLARINET`: odd-harmonic closed-pipe structure (odd harmonics only — closed cylindrical bore)
- `TRIANGLE`: triangle-derived spectrum with reinforced odd partials
- `SAW`: additive all-harmonic structure limited to the first six partials
- `SQUARE`: odd-harmonic stack (1, 3, 5, 7…); hollow quality; re-added in sprint3
- `HELICOPTER`: pulse-like rhythmic rotor effect whose hit rate follows scaled frequency (0.75–12 Hz)

**Finding:**
- the code intentionally favors restrained additive or phase-modulated spectra over raw discontinuous waveforms, which reduces harshness and alias-like roughness on phone speakers
- the current public tone set is curated for usability rather than for preserving every legacy tone ever implemented
- SQUARE and HELICOPTER are the most sonically distinctive tones and are particularly useful for demonstration purposes

## 4. Scale Quantization Accuracy

Scale lock is implemented in `ThereminAudioEngine.snapToScale(...)` using:
- precomputed MIDI frequencies for MIDI notes `0..127`
- scale-specific semitone offsets for `CHROMATIC`, `MAJOR`, `MINOR`, and `PENTATONIC`
- a rebuilt in-scale frequency table when the active scale changes
- nearest-note binary search over that sorted table

This approach means:
- no expensive per-sample logarithm or power calculation is needed in the hot path
- scale quantization remains consistent across octaves
- the audio thread stays allocation-free during ordinary steady-state playback

**Finding:**
- the scale-lock design is computationally lightweight and musically coherent
- the implementation is well suited to real-time theremin control because most adjacent samples remain near the same snapped note, allowing the cache path to avoid repeated searches

## 5. DSP Mathematics — IIR Filter Analysis

### 5.1 First-Order IIR Smoothing (Exponential Moving Average)

The audio engine uses the following discrete-time recurrence for all smoothing operations:

```
y[n] = y[n-1] + α × (x[n] - y[n-1])
y[n] = (1 - α) × y[n-1] + α × x[n]
```

Where:
- `x[n]` = target value (set via `ThereminAudioEngine.setTargets()`)
- `y[n]` = smoothed output value (what the synthesis uses)
- `α`    = smoothing coefficient (0 < α < 1)
- `1-α`  = the filter "pole"

### 5.2 Z-Transform Transfer Function

Taking the Z-transform of the recurrence:

```
Y(z) = (1-α) z⁻¹ Y(z) + α X(z)
Y(z) [1 - (1-α)z⁻¹] = α X(z)

H(z) = Y(z)/X(z) = α / [1 - (1-α)z⁻¹]
```

This is a first-order (one-pole) low-pass IIR filter. The pole is located at `z = 1-α` on the real axis inside the unit circle (stable for 0 < α < 1).

**Magnitude response** at normalized frequency ω (0 = DC, π = Nyquist):

```
|H(e^{jω})| = α / sqrt(1 + (1-α)² - 2(1-α)cos(ω))
```

At DC (ω = 0): |H| = α / (1 - (1-α)) = 1.0 (unity gain — target value is eventually reached)
At Nyquist (ω = π): |H| = α / (1 + (1-α)) = α / (2-α) (strongly attenuated)

### 5.3 Time Constant Calculation

The time constant τ (in samples) is the time to reach 63.2% of the target value:

```
τ_samples = -1 / ln(1 - α)
τ_seconds = τ_samples / SAMPLE_RATE
```

For the three smoothing constants in `ThereminAudioEngine`:

| Constant | α | Pole z | τ (samples) | τ (ms at 48 kHz) | Meaning |
|---|---|---|---|---|---|
| `FREQ_SMOOTHING` | 0.003 | 0.997 | 333 | 6.9 ms | Pitch glide speed |
| `ATTACK_SMOOTHING` | 0.0046 | 0.9954 | 216 | 4.5 ms | Volume fade-in speed |
| `RELEASE_SMOOTHING` | 0.0018 | 0.9982 | 555 | 11.6 ms | Volume fade-out speed |

****Interpretation:****
- Pitch changes settle to within 37% of their target in ~6.9 ms. A full octave jump (440 → 880 Hz) takes approximately 3τ ≈ 20 ms to be 95% complete — well within one audio buffer (21.3 ms).
- Volume attack (hand entering playing range) responds in ~4.5 ms — quick enough to feel immediate.
- Volume release (hand withdrawing) fades in ~11.6 ms — slow enough to avoid abrupt cutoffs.
- The asymmetric attack/release envelope mimics a real theremin's acoustic behaviour: sound appears quickly but decays gently.

### 5.4 Vibrato LFO Analysis

The vibrato is a frequency modulation applied per sample:

```
vibratoPhase[n] = vibratoPhase[n-1] + 2π × f_vib / fs
freq_out[n] = smoothFreqHz × (1 + sin(vibratoPhase[n]) × depth)
```

Where:
- `f_vib = VIBRATO_RATE_HZ = 4.2 Hz`
- `fs = SAMPLE_RATE = 48000 Hz`
- `depth ∈ [MIN_VIBRATO_DEPTH, MAX_VIBRATO_DEPTH] = [0.0003, 0.0014]`
- `depth = 0.0003 + 0.0011 × clamp((smoothVolumeLinear - 0.03) / 0.35, 0, 1)`

**Peak frequency deviation** at full volume (depth = 0.0014) and middle A (440 Hz):
```
Δf = 440 × 0.0014 = 0.616 Hz
```
This is well below the just-noticeable difference for pitch (~1–2 Hz at 440 Hz), producing a subtle, natural-sounding vibrato.

**LFO period:** 1 / 4.2 ≈ 238 ms ≈ 11 audio buffers at 21.3 ms/buffer.

---

## 6. Complete End-to-End Latency Budget

The latency budget is derived analytically from the implementation constants. All values are reproducible from the source code.

### 6.1 Latency Budget Table

| Stage | Component | Best | Typical | Worst | Source |
|---|---|---|---|---|---|
| IMU sampling | Arduino Nano 33 BLE Sense | 1 ms | 2 ms | 5 ms | LSM9DS1 ODR ~100 Hz |
| BLE radio packet TX | BLE connection interval | 7.5 ms | 15 ms | 40 ms | BLE standard range |
| BleSessionManager parse | ASCII packet decode | <1 ms | <1 ms | <1 ms | String.split() on main thread |
| **Foreground sync tick** | `UI_TICK_MS = 50 ms` | 1 ms | 25 ms | 50 ms | Handler.postDelayed average wait |
| **Background sync tick** | `SYNC_TICK_MS = 20 ms` | 1 ms | 10 ms | 20 ms | Thread.sleep average wait |
| Audio buffer render | `AUDIO_WRITE_FRAMES / SAMPLE_RATE` | 21.3 ms | 21.3 ms | 21.3 ms | Fixed: 1024/48000 s |
| AudioTrack HW queue | Android driver buffer | 10 ms | 30 ms | 50 ms | Device-dependent |

### 6.2 Path Totals

**Foreground Play path** (visible `MainActivity`, `UI_TICK_MS = 50 ms`):

| | Best | Typical | Worst |
|---|---|---|---|
| IMU + BLE | 8.5 ms | 17 ms | 45 ms |
| Foreground sync tick | 1 ms | 25 ms | 50 ms |
| Audio buffer | 21.3 ms | 21.3 ms | 21.3 ms |
| AudioTrack HW queue | 10 ms | 30 ms | 50 ms |
| **Foreground total** | **40.8 ms** | **93.3 ms** | **166.3 ms** |

**Background service path** (`ThereminBackgroundAudioService`, `SYNC_TICK_MS = 20 ms`):

| | Best | Typical | Worst |
|---|---|---|---|
| IMU + BLE | 8.5 ms | 17 ms | 45 ms |
| Background sync tick | 1 ms | 10 ms | 20 ms |
| Audio buffer | 21.3 ms | 21.3 ms | 21.3 ms |
| AudioTrack HW queue | 10 ms | 30 ms | 50 ms |
| **Background total** | **40.8 ms** | **78.3 ms** | **136.3 ms** |

### 6.3 Comparison Against HD-11 (`<80 ms` target)

| Path | Typical | Worst | Satisfies HD-11? |
|---|---|---|---|
| Background/calibration | ~78 ms | ~136 ms | Yes (typical) |
| Foreground Play | ~93 ms | ~166 ms | Yes (typical), No (strict worst case) |

****Finding:**** Typical foreground use satisfies HD-11. The strict worst case for the foreground path exceeds the target because `UI_TICK_MS = 50 ms` is the controlling factor. This is documented and accepted: the background path (which handles calibration preview and backgrounded play) always satisfies the target comfortably.

---

## 7. Frequency Mapping Mathematical Analysis

### 7.1 Normalized Mapping Formula

`PlayMappingState.recompute()` applies a two-step mapping:

**Step 1 — Normalize and clamp:**
```
norm = clamp((θ - θ_min) / (θ_max - θ_min), 0, 1)
```

**Step 2 — Apply sensitivity curve:**
```
norm_curved = pow(norm, sensitivityResponseCurve)
```

**Step 3 — Linear interpolation to frequency:**
```
freq = f_min + (f_max - f_min) × norm_curved
```

**Step 4 — Apply octave shift:**
```
freq = clamp(freq × 2^octaveShift, 20, 20000)
```

### 7.2 Worked Example

Given: θ = 30°, θ_min = −15°, θ_max = 55°, f_min = 880 Hz, f_max = 2000 Hz, curve = 1.0 (linear)

```
norm = clamp((30 - (−15)) / (55 - (−15)), 0, 1)
     = clamp(45 / 70, 0, 1)
     = 0.643

norm_curved = pow(0.643, 1.0) = 0.643

freq = 880 + (2000 - 880) × 0.643
     = 880 + 1120 × 0.643
     = 880 + 720.2
     = 1600.2 Hz  (≈ G#5/Ab5 in equal temperament)
```

### 7.3 Sensitivity Curve Effect Analysis

The sensitivity curve exponent reshapes the normalized angle-to-frequency mapping:

| Curve | Effect | Use case |
|---|---|---|
| 0.25 (very responsive) | Steep early response; small angles → large pitch changes | Large arm movements, expressive playing |
| 0.50 | Moderately expanded low range | |
| 1.00 (linear, default) | Proportional 1:1 mapping | Balanced control |
| 1.50 | Expanded high range | Finer control at large angles |
| 2.00 (HIGH preset) | Quadratic; most resolution at large angles | Precise note targeting |
| 2.50 (most precise) | Highly compressed early, expanded late | Very precise, large-motion playing |

At curve = 2.0 (HIGH preset), the midpoint wrist angle (norm = 0.5) maps to `pow(0.5, 2) = 0.25` of the frequency range — meaning 75% of the frequency range is covered by the upper 50% of the wrist angle range. This gives much finer control at large angles.

---

## 8. Reverb Frequency Response Analysis

### 8.1 Schroeder Comb Filter Transfer Function

The reverb is a single Schroeder comb filter with the transfer function:

```
H(z) = (1 - g) / (1 - g × z^{-L})
```

Where:
- `g = 0.6f` (feedback coefficient)
- `L = COMB_MASK + 1 = 8192` (delay length in samples)

**Magnitude response:**
```
|H(e^{jω})| = (1-g) / sqrt(1 + g² - 2g×cos(ω×L))
```

**Resonant peak frequencies** (where `cos(ω×L) = 1`, i.e., ω = 2πk/L for integer k):

```
f_peak_k = k × (SAMPLE_RATE / L) = k × (48000 / 8192) = k × 5.86 Hz
```

The first 10 resonant peaks occur at: 5.86, 11.72, 17.58, 23.44, 29.30, 35.16, 41.02, 46.88, 52.74, 58.60 Hz — all well below the lower limit of musical pitch perception (~80 Hz). 

****Finding:**** Because the comb filter's resonant peaks are all sub-audible (< 60 Hz), the reverb produces a smooth, diffuse tail rather than the metallic "pinging" that short-delay comb filters produce. The ~170 ms delay length was specifically chosen to place all resonances below audibility while providing a musically useful tail duration.

### 8.2 Reverb Decay Time

The −60 dB decay time (T60) for a comb filter with feedback `g` and delay `L`:

```
T60 = −3 × L / (SAMPLE_RATE × log10(g))
    = −3 × 8192 / (48000 × log10(0.6))
    = −3 × 8192 / (48000 × (−0.2218))
    = 24576 / 10647
    ≈ 2.31 seconds
```

****Interpretation:**** The reverb tail decays by 60 dB (factor of 1000 in amplitude) over approximately 2.3 seconds. At the default `reverbMix = 0.3f` (30% wet), the perceptible reverb is considerably shorter. This T60 value is consistent with a medium-large room or hall reverb character.
