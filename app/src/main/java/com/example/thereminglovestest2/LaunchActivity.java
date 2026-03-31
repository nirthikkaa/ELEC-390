package com.example.thereminglovestest2;

/**
 * File guide:
 * Launcher router. It sends first-time users into Setup and returning users straight to Play.
 */

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

public class LaunchActivity extends AppCompatActivity {
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.content.SharedPreferences prefs = getSharedPreferences("theremin_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("first_launch_done", false)) {
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
        getSharedPreferences("theremin_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("first_launch_done", true)
                .apply();
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        overridePendingTransition(0, 0);
        finish();
    }
}
