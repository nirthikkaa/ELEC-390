package com.example.thereminglovestest2;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Self-rescheduling 16th-note sequencer clock.
 *
 * Design principles
 * ─────────────────
 * Single responsibility: this class knows only about BPM, time, and tick delivery.
 * It has zero knowledge of instruments, patterns, PCM, or audio.
 *
 * Drift compensation
 * ──────────────────
 * Each tick advances {@code lastTickMs} by the nominal interval — not by the actual
 * wall-clock time — so accumulated scheduling jitter never causes tempo drift.
 * Actual delays are bounded by Android's scheduler resolution (~1–5 ms) and do not
 * compound across ticks.
 *
 * Glitch-free BPM changes
 * ────────────────────────
 * {@code bpm} is volatile.  The new interval is read on each reschedule, so tempo
 * changes take effect on the very next tick with no clock restart and no step reset.
 *
 * Thread safety
 * ─────────────
 * {@link #setBpm} and {@link #setListener} are safe from any thread.
 * {@link #start} / {@link #stop} should be called from the same thread (or with
 * external serialisation), as is standard for lifecycle objects.
 * {@link #getStep16} and {@link #getBar} are written on the scheduler thread and read
 * without synchronisation — callers should treat them as approximate (best-effort
 * display values), not as hard coordination primitives.
 */
public final class SequencerClock {

    /**
     * Receives one callback per 16th-note boundary.
     * Called on the scheduler thread — must be non-blocking and allocation-free.
     */
    public interface TickListener {
        /**
         * @param step16  current 16th-note position within the bar (0–15)
         * @param bar     monotonically increasing bar index (0 at start; increments every 16 steps)
         */
        void onTick(int step16, int bar);
    }

    private volatile int            bpm      = 120;
    private volatile TickListener   listener;
    private volatile ScheduledExecutorService executor;

    // Only modified on the scheduler thread after start()
    private int  step16    = 0;
    private int  bar       = 0;
    private long lastTickMs = 0L;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /** Start the clock from step 0, bar 0.  No-op if already running. */
    public void start() {
        if (executor != null && !executor.isShutdown()) return;
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SequencerClock");
            t.setDaemon(true);
            return t;
        });
        executor    = ex;
        step16      = 0;
        bar         = 0;
        lastTickMs  = System.currentTimeMillis();
        ex.schedule(this::tick, intervalMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Stop the clock and reset position to step 0, bar 0.
     * The next {@link #start()} will begin a fresh phrase from the top.
     */
    public void stop() {
        ScheduledExecutorService ex = executor;
        executor = null;
        if (ex != null) ex.shutdownNow();
        step16 = 0;
        bar    = 0;
    }

    // ── Configuration ─────────────────────────────────────────────────────────

    /** Set tempo in BPM, clamped to [30, 300].  Change takes effect on the next tick. */
    public void setBpm(int newBpm) {
        bpm = Math.max(30, Math.min(300, newBpm));
    }
    public int getBpm() { return bpm; }

    /** Replace the tick listener.  Change is visible on the next scheduled tick. */
    public void setListener(TickListener l) { listener = l; }

    // ── State inspection ──────────────────────────────────────────────────────

    /** Current 16th-note step within the bar (0–15).  Approximate — for display only. */
    public int getStep16() { return step16; }

    /** Monotonically increasing bar index.  0 at start; increments at every bar boundary. */
    public int getBar()    { return bar; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private long intervalMs() {
        return (long)(60_000.0 / bpm / 4);  // duration of one 16th note at current BPM
    }

    private void tick() {
        // Drift compensation: advance expected time by one nominal interval.
        lastTickMs += intervalMs();

        TickListener l = listener;
        if (l != null) l.onTick(step16, bar);

        step16 = (step16 + 1) % 16;
        if (step16 == 0) bar++;

        // Schedule next tick relative to the expected time, not wall-clock time.
        long delay = Math.max(0L, lastTickMs + intervalMs() - System.currentTimeMillis());
        ScheduledExecutorService ex = executor;
        if (ex != null && !ex.isShutdown()) ex.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
    }
}
