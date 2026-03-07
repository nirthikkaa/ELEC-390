package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;

import com.example.thereminglovestest2.databinding.ActivityLaunchBinding;

public class LaunchActivity extends AppCompatActivity {

    private ActivityLaunchBinding binding;
    private boolean started = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLaunchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // View Binding keeps this tiny screen tiny.
        binding.btnTapToStart.setOnClickListener(v -> goToSetup());
        binding.btnOpenMenu.setOnClickListener(v -> goToSetup());
    }

    private void goToSetup() {
        if (started) return;
        started = true;

        Intent intent = new Intent(this, HomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }
}