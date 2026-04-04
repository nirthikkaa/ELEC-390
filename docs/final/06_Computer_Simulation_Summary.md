# Theremin Gloves — Computer Simulation Summary

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026  
**Team 5:** Marie Ella Cambay, Niraj Patel, Ayan Pirani, Nirthika Ilaiyarajah, Matei Moldovan

## Executive Summary

- The current background-audio and calibration-preview path is comfortably under the HD-11 `<80 ms` target.
- The visible Play screen is still acceptable in typical use, but its strict worst case is higher because `UI_TICK_MS = 50 ms`.
- The public Play build now exposes a curated 10-tone selector, not the older 9-waveform set.
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

Finding:
- the service path satisfies the HD-11 `<80 ms` criterion comfortably
- typical foreground use is also acceptable
- strict foreground worst case exceeds the target because the current UI refresh loop is slower than the service sync loop

Design implication:
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

Interpretation:
- these defaults bias the live instrument toward a musically useful upper register rather than a huge raw span
- the calibration screen still allows much wider range customization
- extended range mode raises the ceiling to `20,000 Hz`, but that mode favors experimentation over precise melodic control

Finding:
- a moderate calibrated angle span remains the best compromise between reachable movement and useful melodic resolution
- the current default ranges are aggressive but practical for a demo-oriented theremin experience

## 3. Waveform / Tone Harmonic Analysis
The current public build exposes 10 user-selectable tones, not the older 9-waveform set. Harmonic inspection of the synthesis code in `ThereminAudioEngine.sample(...)` shows:
- `THEREMIN`: harmonic stack with strong second partial for classic vocal/cello theremin color
- `AIR_PAD` and `PAD`: detuned layered partials for width and slow beating
- `CELLO`: low-order bowed-string style harmonic emphasis
- `CHOIR`: low-order vocal-pad harmonic structure with shallow phase modulation
- `FLUTE`: near-sine spectrum with minimal shimmer
- `CLARINET`: odd-harmonic closed-pipe structure
- `TRIANGLE`: triangle-derived spectrum with reinforced odd partials
- `SAW`: additive all-harmonic structure limited to the first few partials
- `HELICOPTER`: pulse-like rhythmic rotor effect whose hit rate follows scaled frequency

Finding:
- the code intentionally favors restrained additive or phase-modulated spectra over raw discontinuous waveforms, which reduces harshness and alias-like roughness on phone speakers
- the current public tone set is curated for usability rather than for preserving every legacy tone ever implemented

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

Finding:
- the scale-lock design is computationally lightweight and musically coherent
- the implementation is well suited to real-time theremin control because most adjacent samples remain near the same snapped note, allowing the cache path to avoid repeated searches
