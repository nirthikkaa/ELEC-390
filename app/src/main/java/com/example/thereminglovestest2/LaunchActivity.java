package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class LaunchActivity extends AppCompatActivity {

    private boolean started = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launch);

        Button btnTapToStart = findViewById(R.id.btnTapToStart);
        if (btnTapToStart == null) {
            throw new IllegalStateException("btnTapToStart not found in activity_launch.xml");
        }

        btnTapToStart.setOnClickListener(v -> goToMain());

        Button btnOpenMenu = findViewById(R.id.btnOpenMenu);
        if (btnOpenMenu != null) {
            btnOpenMenu.setOnClickListener(v -> goToHome());
        }
    }

    private void goToHome() {
        if (started) return;
        started = true;

        Intent intent = new Intent(this, HomeActivity.class);
        startActivity(intent);
        finish();
    }

    private void goToMain() {
        if (started) return;
        started = true;

        Intent intent = new Intent(this, MainActivity.class);
        startActivity(intent);
        finish();
    }
}