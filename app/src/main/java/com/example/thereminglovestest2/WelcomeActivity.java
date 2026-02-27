package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

public class WelcomeActivity extends AppCompatActivity {

    private Button btnTapToStart;
    private ImageView iconBluetooth, iconSettings, iconVolume;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);

        initViews();
        setupClickListeners();
    }

    private void initViews() {
        btnTapToStart = findViewById(R.id.btnTapToStart);
        iconBluetooth = findViewById(R.id.iconBluetooth);
        iconSettings = findViewById(R.id.iconSettings);
        iconVolume = findViewById(R.id.iconVolume);
    }

    private void setupClickListeners() {
        btnTapToStart.setOnClickListener(v -> {
            Intent intent = new Intent(WelcomeActivity.this, ConnectionActivity.class);
            startActivity(intent);
        });

        // Optional: Add functionality to icons
        iconBluetooth.setOnClickListener(v -> {
            // Could open Bluetooth settings or show status
            Intent intent = new Intent(WelcomeActivity.this, ConnectionActivity.class);
            startActivity(intent);
        });

        iconSettings.setOnClickListener(v -> {
            // Could open settings screen in the future
            // For now, just go to connection
            Intent intent = new Intent(WelcomeActivity.this, ConnectionActivity.class);
            startActivity(intent);
        });

        iconVolume.setOnClickListener(v -> {
            // Could open audio settings in the future
            // For now, just go to connection  
            Intent intent = new Intent(WelcomeActivity.this, ConnectionActivity.class);
            startActivity(intent);
        });
    }
}