# Theremin Gloves — Latency Benchmark Results

**Course:** COEN 390 / ELEC 390, Concordia University, Winter 2026  
**Team 5:** Niraj Patel, Matei Moldovan, Nirthika Ilaiyarajah, Ayan Pirani, Marie Ella Cambay  
**Verification basis:** Targeted instrumentation benchmark run on Pixel 7 (Android 16), April 5, 2026

## Benchmark Commands

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.thereminglovestest2.LatencyBenchmarkTest
```

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.example.thereminglovestest2.VisibleLaunchBenchmarkTest
```

## Expected Runtime Behavior

- `LatencyBenchmarkTest` is a headless instrumentation benchmark. It does not launch `LaunchActivity`, `HomeActivity`, or `MainActivity`.
- Most `LatencyBenchmarkTest` methods instantiate the audio, mapping, or persistence classes directly using the target app context.
- A short audible sound can occur during the audio-engine cases even though no app screen appears on the phone.
- The BLE interval case only records data if both gloves are connected and the right pitch glove is moved during the sampling window.
- `VisibleLaunchBenchmarkTest` is the opposite: it intentionally launches visible Activities so the phone clearly shows the app opening during the run.

## Run Summary

- Device: Pixel 7
- OS: Android 16
- `LatencyBenchmarkTest` result: 10 benchmark methods completed, 0 failures, 0 errors
- `LatencyBenchmarkTest` BLE note: `BLE_INTER_ARRIVAL_US` was recorded as a benchmark-level skip because both gloves were not connected within 30 seconds during this run
- `VisibleLaunchBenchmarkTest` result: 2 benchmark methods completed, 0 failures, 0 errors

## Measured Results

All values below are reported in microseconds unless explicitly noted otherwise.

### Headless Component Benchmarks

| Metric | Avg | P95 | Max | Samples | Notes |
|---|---:|---:|---:|---:|---|
| `DRUM_ENGINE_CONSTRUCTION_MS` | 1,916,328 | 2,010,131 | 2,010,131 | 5 | Metric name is historical; the logged values are in microseconds, so the average constructor time was about 1.92 s |
| `AUDIO_BUFFER_PERIOD_US` | 21,359 | 31,497 | 36,044 | 200 | Matches the expected ~21.33 ms output period for `1024 / 48000` |
| `FILL_BUFFER_EFFECTS_OFF_US` | 11,622 | 12,920 | 15,972 | 200 | Dry synth path only |
| `FILL_BUFFER_ALL_EFFECTS_US` | 12,940 | 14,220 | 18,097 | 200 | Reverb + delay + distortion enabled |
| `AUDIO_ENGINE_START_LATENCY_US` | 51,599 | 64,424 | 64,424 | 3 | Cold start to first `PcmListener` callback |
| `PLAY_MAPPING_RECOMPUTE_US` | 20 | 22 | 167 | 1000 | Mapping recompute cost is negligible relative to the 20 ms or 50 ms scheduling loops |
| `SETTINGS_STORE_LOAD_US` | 814 | 1,019 | 1,216 | 50 | Single-row settings load |
| `SETTINGS_STORE_SAVE_US` | 1,390 | 1,811 | 2,899 | 50 | Single-row settings save |
| `RECORDING_REPO_INSERT_US` | 980 | 7,934 | 7,934 | 20 | Insert outlier appears in the tail, but average cost remains under 1 ms |
| `RECORDING_REPO_QUERY_US` | 1,168 | 1,766 | 1,766 | 20 | Full-table query over the benchmark dataset |
| `FILL_BUFFER_CHROMATIC_US` | 11,505 | 13,234 | 18,813 | 200 | Scale lock enabled, chromatic table |
| `FILL_BUFFER_PENTATONIC_US` | 11,899 | 13,809 | 17,099 | 200 | Scale lock enabled, pentatonic table |

### Visible On-Screen Launch Benchmarks

| Metric | Avg | P95 | Max | Samples | Notes |
|---|---:|---:|---:|---:|---|
| `VISIBLE_MAIN_ACTIVITY_LAUNCH_US` | 1,877,330 | 2,542,575 | 2,542,575 | 5 | Direct visible launch of `MainActivity` from the home screen |
| `VISIBLE_LAUNCH_ACTIVITY_US` | 633,260 | 654,219 | 654,219 | 5 | Visible launch of the lightweight loader screen `LaunchActivity` |

## Interpretation

- The fixed audio buffer period measured at `21.36 ms` average, which is consistent with the current `AUDIO_WRITE_FRAMES = 1024` and `SAMPLE_RATE = 48000`.
- Enabling all three audio effects increased average `fillBuffer()` compute cost from `11.62 ms` to `12.94 ms`, a delta of about `1.32 ms`.
- Audio engine cold-start latency averaged `51.6 ms` to the first PCM callback on the Pixel 7.
- Mapping, settings, and recording-database operations are all far below the audio-buffer timescale and are not the dominant latency contributors in this build.
- The visible launcher screen came up in about `0.63 s` average.
- Direct visible `MainActivity` launch averaged about `1.88 s` on the Pixel 7 for this five-run sample.

## Raw Artifacts

- HTML test reports:
  - `app/build/reports/androidTests/connected/debug/com.example.thereminglovestest2.LatencyBenchmarkTest.html`
  - `app/build/reports/androidTests/connected/debug/com.example.thereminglovestest2.VisibleLaunchBenchmarkTest.html`
- Per-test logcat captures: `app/build/outputs/androidTest-results/connected/debug/Pixel 7 - 16/`
