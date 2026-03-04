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
        btnTapToStart.setOnClickListener(v -> goToSetup());

        Button btnOpenMenu = findViewById(R.id.btnOpenMenu);
        if (btnOpenMenu == null) {
            throw new IllegalStateException("btnOpenMenu not found in activity_launch.xml");
        }
        btnOpenMenu.setOnClickListener(v -> goToSetup());
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