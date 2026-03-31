package com.example.thereminglovestest2;

/**
 * File guide:
 * Launcher router. It sends first-time users into Setup and returning users straight to Play.
 */

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class LaunchActivity extends AppCompatActivity {
    static final String PREFS_NAME = "theremin_prefs";
    static final String KEY_FIRST_LAUNCH_DONE = "first_launch_done";
    static final String KEY_GRID_HINT_PENDING = "grid_hint_pending";

    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_FIRST_LAUNCH_DONE, false)) {
            openPlay();
            return;
        }
        openSetup();
    }

    private void openPlay() {
        if (started) return;
        started = true;
        startActivity(new Intent(this, MainActivity.class)
                .putExtra(MainActivity.EXTRA_QUICK_START_LAUNCH, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        overridePendingTransition(0, 0);
        finish();
    }

    private void openSetup() {
        if (started) return;
        started = true;
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_FIRST_LAUNCH_DONE, true)
                .putBoolean(KEY_GRID_HINT_PENDING, true)
                .apply();
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        overridePendingTransition(0, 0);
        finish();
    }
}
