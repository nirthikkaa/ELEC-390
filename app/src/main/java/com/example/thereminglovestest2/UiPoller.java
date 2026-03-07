package com.example.thereminglovestest2;

import android.os.Handler;
import android.os.Looper;

/**
 * Small reusable main-thread poll helper for Activities that need
 * lightweight recurring UI refreshes.
 */
public final class UiPoller {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long intervalMs;
    private final Runnable task;

    private final Runnable loopRunnable = new Runnable() {
        @Override
        public void run() {
            task.run();
            handler.postDelayed(this, intervalMs);
        }
    };

    public UiPoller(long intervalMs, Runnable task) {
        this.intervalMs = intervalMs;
        this.task = task;
    }

    public void start() {
        stop();
        handler.post(loopRunnable);
    }

    public void stop() {
        handler.removeCallbacks(loopRunnable);
    }
}
