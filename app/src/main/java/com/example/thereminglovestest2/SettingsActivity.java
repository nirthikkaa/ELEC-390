package com.example.thereminglovestest2;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "theremin_prefs";
    private static final String PREF_BG_AUDIO_ENABLED = "bg_audio_enabled";

    private static final String UI_PREFS_NAME = "calibration_ui_prefs";
    private static final String KEY_CALIBRATION_GUIDE_LEARNED = "calibration_guide_learned";

    private SwitchCompat switchBackgroundAudio;
    private TextView tvBackgroundAudioState;
    private TextView tvTutorialState;

    private boolean suppressBackgroundAudioCallback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        bindViews();
        wireActions();
        refreshUiFromPrefs();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUiFromPrefs();
    }

    private void bindViews() {
        TopNavBarView topNavBar = findViewById(R.id.topNavBar);
        if (topNavBar != null) {
            topNavBar.setTitleText("Settings");
        }

        tvBackgroundAudioState = findViewById(R.id.tvBackgroundAudioState);
        tvTutorialState = findViewById(R.id.tvTutorialState);
        switchBackgroundAudio = findViewById(R.id.switchBackgroundAudio);
    }

    private void wireActions() {
        if (switchBackgroundAudio != null) {
            switchBackgroundAudio.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (suppressBackgroundAudioCallback) return;

                saveBackgroundAudioEnabled(isChecked);
                refreshUiFromPrefs();

                Toast.makeText(
                        this,
                        isChecked ? "Background audio enabled" : "Background audio disabled",
                        Toast.LENGTH_SHORT
                ).show();
            });
        }

        Button btnShowTutorialAgain = findViewById(R.id.btnShowTutorialAgain);
        if (btnShowTutorialAgain != null) {
            btnShowTutorialAgain.setOnClickListener(v -> {
                setCalibrationGuideLearned(false);
                refreshUiFromPrefs();
                Toast.makeText(this, "Calibration guide will show again", Toast.LENGTH_SHORT).show();
            });
        }

        Button btnOpenCalibration = findViewById(R.id.btnOpenCalibration);
        if (btnOpenCalibration != null) {
            btnOpenCalibration.setOnClickListener(v -> openScreen(CalibrationActivity.class));
        }

        Button btnOpenSetup = findViewById(R.id.btnOpenSetup);
        if (btnOpenSetup != null) {
            btnOpenSetup.setOnClickListener(v -> openScreen(HomeActivity.class));
        }

        Button btnOpenPlay = findViewById(R.id.btnOpenPlay);
        if (btnOpenPlay != null) {
            btnOpenPlay.setOnClickListener(v -> openScreen(MainActivity.class));
        }

        Button btnOpenConnect = findViewById(R.id.btnOpenConnect);
        if (btnOpenConnect != null) {
            btnOpenConnect.setOnClickListener(v -> openScreen(ConnectGlovesActivity.class));
        }

        Button btnOpenLibrary = findViewById(R.id.btnOpenLibrary);
        if (btnOpenLibrary != null) {
            btnOpenLibrary.setOnClickListener(v -> openScreen(LibraryActivity.class));
        }
    }

    private void refreshUiFromPrefs() {
        boolean bgAudioEnabled = isBackgroundAudioEnabled();
        boolean tutorialLearned = isCalibrationGuideLearned();

        if (tvBackgroundAudioState != null) {
            tvBackgroundAudioState.setText(
                    bgAudioEnabled
                            ? "Background audio is ON."
                            : "Background audio is OFF."
            );
        }

        if (switchBackgroundAudio != null) {
            suppressBackgroundAudioCallback = true;
            switchBackgroundAudio.setChecked(bgAudioEnabled);
            suppressBackgroundAudioCallback = false;
        }

        if (tvTutorialState != null) {
            tvTutorialState.setText(
                    tutorialLearned
                            ? "The calibration guide is hidden because it has already been completed once."
                            : "The calibration guide is enabled and will appear in Calibration."
            );
        }
    }

    private boolean isBackgroundAudioEnabled() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(PREF_BG_AUDIO_ENABLED, true);
    }

    private void saveBackgroundAudioEnabled(boolean enabled) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_BG_AUDIO_ENABLED, enabled).apply();
    }

    private boolean isCalibrationGuideLearned() {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE);
        return prefs.getBoolean(KEY_CALIBRATION_GUIDE_LEARNED, false);
    }

    private void setCalibrationGuideLearned(boolean learned) {
        SharedPreferences prefs = getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_CALIBRATION_GUIDE_LEARNED, learned).apply();
    }

    private void openScreen(Class<? extends Activity> target) {
        if (getClass().equals(target)) return;

        Intent intent = new Intent(this, target);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
        );
        startActivity(intent);
        overridePendingTransition(0, 0);
    }
}