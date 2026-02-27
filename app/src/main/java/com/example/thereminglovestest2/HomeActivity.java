package com.example.thereminglovestest2;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        TopNavBarView topNavBarView = findViewById(R.id.topNavBar);
        if (topNavBarView != null) {
            topNavBarView.setTitleText("Home");
        }

        wireButton(R.id.btnGoLivePlay, MainActivity.class);
        wireButton(R.id.btnGoConnect, ConnectGlovesActivity.class);
        wireButton(R.id.btnGoCalibration, CalibrationActivity.class);
        wireButton(R.id.btnGoLibrary, LibraryActivity.class);
        wireButton(R.id.btnGoPlayback, PlaybackActivity.class);
        wireButton(R.id.btnGoSettings, SettingsActivity.class);
    }

    private void wireButton(int id, Class<?> targetActivity) {
        Button button = findViewById(id);
        if (button == null) return;
        button.setOnClickListener(v -> startActivity(new Intent(this, targetActivity)));
    }
}