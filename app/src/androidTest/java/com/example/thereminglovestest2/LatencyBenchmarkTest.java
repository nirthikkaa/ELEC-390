package com.example.thereminglovestest2;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Real-device latency benchmark suite for Theremin Gloves.
 *
 * Run with:
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.example.thereminglovestest2.LatencyBenchmarkTest
 *
 * PRECONDITIONS:
 *   - Pixel 7 connected via USB with USB debugging enabled.
 *   - ThereminGlove (pitch) and ThereminGloveVol (volume) powered on and in BLE range.
 *   - Slowly wave the pitch glove during test_06 (BLE inter-arrival) so pitchActiveDeltaDeg changes.
 *
 * Results are logged to Logcat tag LATENCY_BENCH and written to:
 *   /data/data/com.example.thereminglovestest2/files/latency_benchmark.txt
 *
 * Retrieve after the run:
 *   adb exec-out run-as com.example.thereminglovestest2 \
 *     cat /data/data/com.example.thereminglovestest2/files/latency_benchmark.txt
 */
@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class LatencyBenchmarkTest {

    private static final String TAG     = "LATENCY_BENCH";
    private static final int    WARMUP  = 20;
    private static final int    SAMPLES = 200;

    /** Accumulates all result lines; written to file in @AfterClass. */
    private static final StringBuilder REPORT = new StringBuilder();

    private Context ctx;

    @BeforeClass
    public static void suiteSetup() {
        REPORT.setLength(0);
        REPORT.append("# Theremin Gloves — Latency Benchmark Raw Results\n");
        REPORT.append("# Device  : ").append(android.os.Build.MODEL).append("\n");
        REPORT.append("# Android : ").append(android.os.Build.VERSION.RELEASE)
              .append("  API ").append(android.os.Build.VERSION.SDK_INT).append("\n");
        REPORT.append("# Format  : METRIC  min_us  avg_us  max_us  stddev_us  n\n\n");
    }

    @Before
    public void setup() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        TestAppState.resetAll();
    }

    @After
    public void teardown() {
        TestAppState.resetAll();
    }

    @AfterClass
    public static void writeReport() {
        Context c = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File out = new File(c.getFilesDir(), "latency_benchmark.txt");
        try (FileWriter w = new FileWriter(out, false)) {
            w.write(REPORT.toString());
        } catch (IOException e) {
            Log.e(TAG, "Failed to write report file", e);
        }
        Log.i(TAG, "=== FULL BENCHMARK REPORT ===\n" + REPORT);
    }

    // =========================================================================
    // Test 01 — DrumEngine construction time
    // Measures: time from new DrumEngine(ctx) to constructor return.
    // Significance: DrumEngine synthesizes 14 drum sounds + 75 piano samples in-memory
    // at construction; AppLaunchWarmup pre-builds this off the main thread to hide latency.
    // =========================================================================
    @Test
    public void test_01_drumEngineConstruction() {
        int n = 5;
        long[] times = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            DrumEngine drum = new DrumEngine(ctx);
            times[i] = System.nanoTime() - t0;
            drum.release();
            SystemClock.sleep(100);
        }
        record("DRUM_ENGINE_CONSTRUCTION_MS", nsToUs(times)); // reported as µs but named _MS for doc clarity
    }

    // =========================================================================
    // Test 02 — Audio buffer actual period
    // Measures: wall-clock time between consecutive PcmListener.onPcmSamples() calls.
    // Expected: ~21 333 µs (= 1024 / 48000 × 10^6). Jitter shows OS scheduling variance.
    // =========================================================================
    @Test
    public void test_02_audioBufferPeriod() throws InterruptedException {
        int total = WARMUP + SAMPLES + 1;
        long[] stamps = new long[total];
        AtomicInteger idx = new AtomicInteger(0);

        ThereminAudioEngine engine = new ThereminAudioEngine();
        engine.setPcmListener((samples, count) -> {
            int i = idx.getAndIncrement();
            if (i < stamps.length) stamps[i] = System.nanoTime();
        });
        engine.start();

        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (idx.get() < total && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50);
        }
        engine.setPcmListener(null);
        engine.stop();

        assertTrue("Timed out waiting for PcmListener callbacks; got " + idx.get(),
                idx.get() >= total);

        long[] deltas = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            deltas[i] = (stamps[WARMUP + i + 1] - stamps[WARMUP + i]) / 1_000; // ns→µs
        }
        record("AUDIO_BUFFER_PERIOD_US", deltas);
    }

    // =========================================================================
    // Test 03 — fillBuffer() compute time, effects OFF
    // Measures: time spent inside fillBuffer() producing 1024 PCM samples.
    // This is the synthesis cost only — does not include drum mix, visualizer, or AudioTrack.write().
    // =========================================================================
    @Test
    public void test_03_fillBufferNoEffects() throws InterruptedException {
        ThereminAudioEngine engine = new ThereminAudioEngine();
        engine.setReverbEnabled(false);
        engine.setDelayEnabled(false);
        engine.setDistortionEnabled(false);
        long[] times = collectFillDurations(engine, WARMUP, SAMPLES);
        record("FILL_BUFFER_EFFECTS_OFF_US", times);
    }

    // =========================================================================
    // Test 04 — fillBuffer() compute time, all effects ON
    // Measures: fillBuffer() cost with reverb (Schroeder comb) + delay (ring buffer) +
    // distortion (tanh) all active simultaneously.
    // Comparison with test_03 shows the DSP overhead of the effects chain per 1024-sample buffer.
    // =========================================================================
    @Test
    public void test_04_fillBufferAllEffects() throws InterruptedException {
        ThereminAudioEngine engine = new ThereminAudioEngine();
        engine.setReverbEnabled(true);
        engine.setDelayEnabled(true);
        engine.setDistortionEnabled(true);
        long[] times = collectFillDurations(engine, WARMUP, SAMPLES);
        record("FILL_BUFFER_ALL_EFFECTS_US", times);
    }

    // =========================================================================
    // Test 05 — Audio engine start → first PcmListener callback latency
    // Measures: time from engine.start() call to the first onPcmSamples() invocation.
    // Includes: AudioTrack creation, PERFORMANCE_MODE_LOW_LATENCY setup, pre-warm writes,
    // first fillBuffer() + drum mix + visualizer update. This is the cold-start audio delay.
    // =========================================================================
    @Test
    public void test_05_audioEngineStartLatency() throws InterruptedException {
        int runs = 3;
        long[] latencies = new long[runs];

        for (int r = 0; r < runs; r++) {
            long[] firstCallNs = {0L};
            AtomicInteger fired = new AtomicInteger(0);

            ThereminAudioEngine engine = new ThereminAudioEngine();
            engine.setPcmListener((samples, count) -> {
                if (fired.getAndIncrement() == 0) firstCallNs[0] = System.nanoTime();
            });

            long startNs = System.nanoTime();
            engine.start();

            long deadline = SystemClock.elapsedRealtime() + 5_000;
            while (fired.get() == 0 && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(5);
            }
            engine.setPcmListener(null);
            engine.stop();

            assertTrue("Engine did not fire PcmListener within 5 s on run " + r, fired.get() > 0);
            latencies[r] = (firstCallNs[0] - startNs) / 1_000; // ns→µs
            SystemClock.sleep(300);
        }
        record("AUDIO_ENGINE_START_LATENCY_US", latencies);
    }

    // =========================================================================
    // Test 06 — BLE notification inter-arrival interval
    // Measures: wall-clock time between consecutive BLE ACTIVE_DELTA_DEG packets
    // arriving from the pitch glove, detected via pitchActiveDeltaDeg value changes.
    //
    // Method: poll BleSessionManager.getSnapshot() every 1 ms; record a timestamp each
    // time pitchActiveDeltaDeg changes. Inter-arrival = diff between consecutive timestamps.
    // Accuracy: ±1 ms (poll jitter) vs BLE connection interval of 7.5–20 ms — ~5–13% error.
    // This is acceptable; a hardware timer tap would require modifying BleSessionManager.java
    // which is restricted under CLAUDE.md ("never touch without understanding watchdog logic").
    //
    // PRECONDITION: slowly wave the pitch glove so pitchActiveDeltaDeg changes each packet.
    // =========================================================================
    @Test
    public void test_06_bleInterArrival() {
        TestAppState.grantBlePermissions();
        BleSessionManager.initialize(ctx);

        BleSnapshot connected = TestAppState.waitForBleSnapshotOrNull(
                s -> s.areBothGlovesConnected(), 30_000);
        if (connected == null) {
            recordSkip("BLE_INTER_ARRIVAL_US", "Gloves not connected within 30 s");
            return;
        }

        int needed = 102; // seed + 100 intervals + spare
        long[] stamps = new long[needed];
        int count = 0;
        float lastDelta = BleSessionManager.getSnapshot().pitchActiveDeltaDeg;
        stamps[count++] = System.nanoTime();

        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (count < needed && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(1);
            float cur = BleSessionManager.getSnapshot().pitchActiveDeltaDeg;
            if (cur != lastDelta) {
                stamps[count++] = System.nanoTime();
                lastDelta = cur;
            }
        }

        if (count < 10) {
            recordSkip("BLE_INTER_ARRIVAL_US",
                    "Only " + count + " changes seen — wave the pitch glove more");
            return;
        }

        int validCount = count - 1;
        long[] deltas = new long[validCount];
        for (int i = 0; i < validCount; i++) {
            deltas[i] = (stamps[i + 1] - stamps[i]) / 1_000;
        }
        record("BLE_INTER_ARRIVAL_US", deltas);
    }

    // =========================================================================
    // Test 07 — PlayMappingState.recompute() per-call time
    // Measures: time for one recompute() call (angle→frequency + sensitivity curve + octave shift).
    // This runs on the sync thread (background) or UI thread (foreground) every 20–50 ms.
    // =========================================================================
    @Test
    public void test_07_playMappingRecompute() {
        PlayMappingState state = new PlayMappingState();
        state.pitchAngleMinDeg  = -45f;
        state.pitchAngleMaxDeg  =  45f;
        state.freqMinHz         = 100f;
        state.freqMaxHz         = 2_000f;
        state.volumeAngleMinDeg = -45f;
        state.volumeAngleMaxDeg =  45f;
        state.setSensitivityResponseCurve(1.0f);
        state.setOctaveShift(0);

        BleSnapshot snap = new BleSnapshot(
                true, true, false, "ok", "p", "v", "e",
                true, true, false, false,
                false, false, false, false,
                22f, 22f, 0f, 0f,
                "POSITIVE", "POSITIVE");

        for (int i = 0; i < WARMUP; i++) state.recompute(snap);

        int n = 1_000;
        long[] times = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            state.recompute(snap);
            times[i] = (System.nanoTime() - t0) / 1_000;
        }
        record("PLAY_MAPPING_RECOMPUTE_US", times);
    }

    // =========================================================================
    // Test 08 — SettingsStore load and save
    // Measures: SQLite read (load) and write (save) round-trip for the 24-column app_settings row.
    // These run on the main thread at settings screen open/close.
    // =========================================================================
    @Test
    public void test_08_settingsStoreIO() {
        SettingsStore store = new SettingsStore(ctx);
        AppSettings settings = new AppSettings();

        for (int i = 0; i < WARMUP; i++) { store.load(); store.save(settings); }

        int n = 50;
        long[] loadTimes = new long[n], saveTimes = new long[n];

        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            store.load();
            loadTimes[i] = (System.nanoTime() - t0) / 1_000;
        }
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            store.save(settings);
            saveTimes[i] = (System.nanoTime() - t0) / 1_000;
        }
        store.close();

        record("SETTINGS_STORE_LOAD_US", loadTimes);
        record("SETTINGS_STORE_SAVE_US", saveTimes);
    }

    // =========================================================================
    // Test 09 — RecordingRepository insert and query
    // Measures: SQLite insert (saveRecording) and full-table query (getAllRecordings).
    // Query grows as rows accumulate — reported numbers reflect the final iteration (n rows).
    // =========================================================================
    @Test
    public void test_09_recordingRepositoryIO() throws IOException {
        RecordingRepository repo = new RecordingRepository(ctx);
        File dir = new File(ctx.getFilesDir(), "bench_recordings");
        if (!dir.exists()) assertTrue(dir.mkdirs());

        int n = 20;
        long[] insertTimes = new long[n], queryTimes = new long[n];

        for (int i = 0; i < n; i++) {
            File f = new File(dir, "bench_" + i + ".wav");
            if (!f.exists()) assertTrue(f.createNewFile());

            long t0 = System.nanoTime();
            repo.saveRecording(f.getAbsolutePath(), "bench_" + i, 5_000L, "HIGH");
            insertTimes[i] = (System.nanoTime() - t0) / 1_000;

            t0 = System.nanoTime();
            repo.getAllRecordings();
            queryTimes[i] = (System.nanoTime() - t0) / 1_000;
        }

        record("RECORDING_REPO_INSERT_US", insertTimes);
        record("RECORDING_REPO_QUERY_US",  queryTimes);
    }

    // =========================================================================
    // Test 10 — fillBuffer() overhead: CHROMATIC vs PENTATONIC scale lock
    // Measures: fillBuffer() compute time with scale=CHROMATIC versus PENTATONIC at 440 Hz.
    // Both run the snapToScale() binary search, but PENTATONIC has fewer notes so nearest-
    // note boundaries are farther apart — with FREQ_SMOOTHING=0.003 the cache hit rate is
    // even higher for PENTATONIC (input rarely crosses a note boundary per buffer).
    // Difference = overhead of binary search cache miss on scale boundaries.
    // =========================================================================
    @Test
    public void test_10_scaleOverhead() throws InterruptedException {
        ThereminAudioEngine engineC = new ThereminAudioEngine();
        engineC.setActiveScale(AppSettings.SCALE_CHROMATIC);
        engineC.setTargets(440f, 0.5f);
        long[] chromatic = collectFillDurations(engineC, WARMUP, SAMPLES);
        record("FILL_BUFFER_CHROMATIC_US", chromatic);

        ThereminAudioEngine engineP = new ThereminAudioEngine();
        engineP.setActiveScale(AppSettings.SCALE_PENTATONIC);
        engineP.setTargets(440f, 0.5f);
        long[] pentatonic = collectFillDurations(engineP, WARMUP, SAMPLES);
        record("FILL_BUFFER_PENTATONIC_US", pentatonic);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Start engine, collect WARMUP+SAMPLES lastFillDurationNs readings, stop engine.
     *  Returns the SAMPLES readings after warmup, converted to µs. */
    private long[] collectFillDurations(ThereminAudioEngine engine,
                                         int warmup, int samples) throws InterruptedException {
        int total = warmup + samples;
        long[] raw = new long[total];
        AtomicInteger idx = new AtomicInteger(0);

        engine.setPcmListener((buf, count) -> {
            int i = idx.getAndIncrement();
            if (i < total) raw[i] = engine.lastFillDurationNs;
        });
        engine.start();

        long deadline = SystemClock.elapsedRealtime() + 15_000;
        while (idx.get() < total && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(50);
        }
        engine.setPcmListener(null);
        engine.stop();

        if (idx.get() < total) {
            fail("collectFillDurations timed out: got " + idx.get() + "/" + total);
        }
        long[] result = new long[samples];
        for (int i = 0; i < samples; i++) result[i] = raw[warmup + i] / 1_000; // ns→µs
        return result;
    }

    private static long[] nsToUs(long[] ns) {
        long[] us = new long[ns.length];
        for (int i = 0; i < ns.length; i++) us[i] = ns[i] / 1_000;
        return us;
    }

    /** Log and append one result line: sorted stats in µs. */
    private static void record(String metric, long[] usValues) {
        long[] sorted = Arrays.copyOf(usValues, usValues.length);
        Arrays.sort(sorted);

        double sum = 0;
        for (long v : sorted) sum += v;
        double avg = sum / sorted.length;

        double variance = 0;
        for (long v : sorted) variance += (v - avg) * (v - avg);
        long stddev = (long) Math.sqrt(variance / sorted.length);

        long p50 = sorted[sorted.length / 2];
        long p95 = sorted[(int) (sorted.length * 0.95)];
        long p99 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.99))];

        String line = String.format(
                "%-42s  min=%6d  avg=%6d  p50=%6d  p95=%6d  p99=%6d  max=%6d  stddev=%5d  n=%d",
                metric,
                sorted[0], (long) avg, p50, p95, p99, sorted[sorted.length - 1], stddev,
                sorted.length);

        Log.i(TAG, line);
        REPORT.append(line).append("\n");
    }

    private static void recordSkip(String metric, String reason) {
        String line = metric + "  SKIPPED: " + reason;
        Log.w(TAG, line);
        REPORT.append(line).append("\n");
    }
}
