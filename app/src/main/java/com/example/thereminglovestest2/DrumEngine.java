package com.example.thereminglovestest2;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sprint 3: Drum backing engine.
 *
 * Runs a 16-step sequencer using SoundPool to play three synthetic drum sounds
 * (kick, snare, hi-hat). The scheduler fires on a separate thread at the
 * interval corresponding to one 16th note at the configured BPM. Audio
 * generation is entirely independent of ThereminAudioEngine so gesture controls
 * are unaffected.
 */
public class DrumEngine {

    private final SoundPool soundPool;
    private int kickId  = -1;
    private int snareId = -1;
    private int hiHatId = -1;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;

    private volatile boolean enabled = false;
    private volatile int bpm = 120;
    private int step = 0;

    // 16-step patterns (one bar of 4/4 in 16th notes)
    private final boolean[] kickPattern  = {
        true,  false, false, false, false, false, false, false,
        true,  false, false, false, false, false, false, false
    };
    private final boolean[] snarePattern = {
        false, false, false, false, true,  false, false, false,
        false, false, false, false, true,  false, false, false
    };
    private final boolean[] hihatPattern = {
        true,  true,  true,  true,  true,  true,  true,  true,
        true,  true,  true,  true,  true,  true,  true,  true
    };

    public DrumEngine(Context context) {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attrs)
                .build();
        kickId  = soundPool.load(context, R.raw.drum_kick,  1);
        snareId = soundPool.load(context, R.raw.drum_snare, 1);
        hiHatId = soundPool.load(context, R.raw.drum_hihat, 1);
    }

    /** Start the scheduler. Safe to call multiple times — no-op if already running. */
    public void start() {
        if (scheduler != null && !scheduler.isShutdown()) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DrumEngineScheduler");
            t.setDaemon(true);
            return t;
        });
        scheduleAtCurrentBpm();
    }

    private void scheduleAtCurrentBpm() {
        if (tickFuture != null) tickFuture.cancel(false);
        long intervalMs = (long) (60000.0 / bpm / 4);
        tickFuture = scheduler.scheduleAtFixedRate(this::tick, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        if (enabled) {
            if (kickPattern[step]  && kickId  > 0) soundPool.play(kickId,  0.9f, 0.9f, 0, 0, 1f);
            if (snarePattern[step] && snareId > 0) soundPool.play(snareId, 0.8f, 0.8f, 0, 0, 1f);
            if (hihatPattern[step] && hiHatId > 0) soundPool.play(hiHatId, 0.5f, 0.5f, 0, 0, 1f);
        }
        step = (step + 1) % 16;
    }

    /** Stop the scheduler and reset the step counter. */
    public void stop() {
        if (tickFuture != null) { tickFuture.cancel(false); tickFuture = null; }
        if (scheduler  != null) { scheduler.shutdown();     scheduler  = null; }
        step = 0;
    }

    /** Stop the scheduler and release SoundPool resources. Call from onDestroy. */
    public void release() {
        stop();
        soundPool.release();
    }

    // --- Getters / setters ---

    public void setEnabled(boolean on) { enabled = on; }
    public boolean isEnabled()         { return enabled; }

    /**
     * Set tempo (60–200 BPM). If the scheduler is already running the interval
     * is updated immediately.
     */
    public void setBpm(int bpm) {
        this.bpm = Math.max(60, Math.min(200, bpm));
        if (scheduler != null && !scheduler.isShutdown()) scheduleAtCurrentBpm();
    }

    public int getBpm() { return bpm; }
}
