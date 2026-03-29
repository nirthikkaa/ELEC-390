package com.example.thereminglovestest2;

/**
 * File guide:
 * Simple launch screen. It is the first screen the user sees before entering the main app flow.
 */

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityLaunchBinding;

public class LaunchActivity extends AppCompatActivity {

    private ActivityLaunchBinding binding;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Only show the launch/branding screen on the very first install or storage clear.
        // Returning users skip straight to the Connect screen.
        android.content.SharedPreferences prefs = getSharedPreferences("theremin_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("first_launch_done", false)) {
            startActivity(new Intent(this, ConnectGlovesActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
            overridePendingTransition(0, 0);
            finish();
            return;
        }
        binding = ActivityLaunchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.btnTapToStart.setOnClickListener(v -> openSetup());
    }

    private void openSetup() {
        if (started) return;
        started = true;
        getSharedPreferences("theremin_prefs", MODE_PRIVATE).edit().putBoolean("first_launch_done", true).apply();
        startActivity(new Intent(this, ConnectGlovesActivity.class).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION));
        overridePendingTransition(0, 0);
        finish();
    }
}
