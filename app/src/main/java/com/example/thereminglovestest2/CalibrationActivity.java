package com.example.thereminglovestest2;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public class CalibrationActivity extends AppCompatActivity {

    private static final float DEFAULT_PITCH_ANGLE_MIN = -15.0f;
    private static final float DEFAULT_PITCH_ANGLE_MAX = 55.0f;
    private static final float DEFAULT_FREQ_MIN = 880.0f;
    private static final float DEFAULT_FREQ_MAX = 2000.0f;
    private static final float DEFAULT_VOLUME_ANGLE_MIN = -10.0f;
    private static final float DEFAULT_VOLUME_ANGLE_MAX = 55.0f;

    private static final float ANGLE_MIN_LIMIT = -90.0f;
    private static final float ANGLE_MAX_LIMIT = 90.0f;
    private static final float FREQ_MIN_LIMIT = 20.0f;
    private static final float FREQ_MAX_LIMIT = 2000.0f;

    private AppSettingsRepository settingsRepo;

    private EditText etPitchAngleMin;
    private EditText etPitchAngleMax;
    private EditText etFreqMin;
    private EditText etFreqMax;
    private EditText etVolumeAngleMin;
    private EditText etVolumeAngleMax;

    private TextView tvSavedSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settingsRepo = new AppSettingsRepository(this);
        setContentView(buildScreen());

        loadSavedIntoFields();
    }

    private LinearLayout buildScreen() {
        LinearLayout screenRoot = new LinearLayout(this);
        screenRoot.setOrientation(LinearLayout.VERTICAL);

        InsetAwareScrollView scroll = new InsetAwareScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);

        TopNavBarView top = new TopNavBarView(this);
        top.setTitleText("Calibration");
        content.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText("Calibration");
        title.setTextSize(24f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleLp.topMargin = dp(16);
        content.addView(title, titleLp);

        TextView intro = new TextView(this);
        intro.setText(
                "Edit the saved app-side mapping used by Play.\n\n" +
                        "Press Enter / Done to save immediately and close the keyboard.\n\n" +
                        "Pitch glove angle maps to frequency.\n" +
                        "Volume glove angle maps to loudness."
        );
        intro.setTextSize(16f);
        LinearLayout.LayoutParams introLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        introLp.topMargin = dp(10);
        content.addView(intro, introLp);

        etPitchAngleMin = addLabeledNumberField(content, "Pitch angle min (deg)");
        etPitchAngleMax = addLabeledNumberField(content, "Pitch angle max (deg)");
        etFreqMin = addLabeledNumberField(content, "Frequency min (Hz)");
        etFreqMax = addLabeledNumberField(content, "Frequency max (Hz)");
        etVolumeAngleMin = addLabeledNumberField(content, "Volume angle min (deg)");
        etVolumeAngleMax = addLabeledNumberField(content, "Volume angle max (deg)");

        attachDoneSaveBehavior(etPitchAngleMin);
        attachDoneSaveBehavior(etPitchAngleMax);
        attachDoneSaveBehavior(etFreqMin);
        attachDoneSaveBehavior(etFreqMax);
        attachDoneSaveBehavior(etVolumeAngleMin);
        attachDoneSaveBehavior(etVolumeAngleMax);

        tvSavedSummary = new TextView(this);
        tvSavedSummary.setTextSize(14f);
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        summaryLp.topMargin = dp(16);
        content.addView(tvSavedSummary, summaryLp);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        row1Lp.topMargin = dp(18);
        content.addView(row1, row1Lp);

        Button btnSave = new Button(this);
        btnSave.setText("SAVE & RETURN");
        btnSave.setOnClickListener(v -> saveFromFieldsAndReturn());
        row1.addView(btnSave, weightedButtonLp());

        Button btnDefaults = new Button(this);
        btnDefaults.setText("DEFAULTS");
        btnDefaults.setOnClickListener(v -> applyDefaultsToFields());
        LinearLayout.LayoutParams defaultsLp = weightedButtonLp();
        defaultsLp.leftMargin = dp(10);
        row1.addView(btnDefaults, defaultsLp);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        row2Lp.topMargin = dp(10);
        content.addView(row2, row2Lp);

        Button btnReloadSaved = new Button(this);
        btnReloadSaved.setText("RELOAD SAVED");
        btnReloadSaved.setOnClickListener(v -> loadSavedIntoFields());
        row2.addView(btnReloadSaved, weightedButtonLp());

        Button btnBackToPlay = new Button(this);
        btnBackToPlay.setText("BACK TO PLAY");
        btnBackToPlay.setOnClickListener(v -> returnToExistingPlay());
        LinearLayout.LayoutParams playLp = weightedButtonLp();
        playLp.leftMargin = dp(10);
        row2.addView(btnBackToPlay, playLp);

        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        BottomNavBarView bottomNav = new BottomNavBarView(this);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        );
        screenRoot.addView(scroll, scrollLp);
        screenRoot.addView(bottomNav, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        return screenRoot;
    }

    private EditText addLabeledNumberField(LinearLayout parent, String labelText) {
        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextSize(15f);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        labelLp.topMargin = dp(14);
        parent.addView(label, labelLp);

        EditText editText = new EditText(this);
        editText.setSingleLine(true);
        editText.setMaxLines(1);
        editText.setHorizontallyScrolling(true);
        editText.setInputType(
                InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED
        );
        editText.setImeOptions(EditorInfo.IME_ACTION_DONE);

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        inputLp.topMargin = dp(6);
        parent.addView(editText, inputLp);

        return editText;
    }

    private void attachDoneSaveBehavior(EditText editText) {
        editText.setOnEditorActionListener((v, actionId, event) -> {
            boolean imeDone = actionId == EditorInfo.IME_ACTION_DONE;
            boolean enterKey =
                    event != null
                            && event.getAction() == KeyEvent.ACTION_DOWN
                            && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;

            if (imeDone || enterKey) {
                saveFromFieldsAndStayHere();
                hideKeyboardAndClearFocus(v);
                return true;
            }
            return false;
        });
    }

    private void hideKeyboardAndClearFocus(View view) {
        view.clearFocus();
        InputMethodManager imm =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        }
    }

    private LinearLayout.LayoutParams weightedButtonLp() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void loadSavedIntoFields() {
        AppSettings s = settingsRepo.load();
        if (s == null) {
            applyDefaultsToFields();
            tvSavedSummary.setText("No saved settings found yet. Showing defaults.");
            return;
        }

        setField(etPitchAngleMin, s.pitchAngleMinDeg);
        setField(etPitchAngleMax, s.pitchAngleMaxDeg);
        setField(etFreqMin, s.freqMinHz);
        setField(etFreqMax, s.freqMaxHz);
        setField(etVolumeAngleMin, s.volumeAngleMinDeg);
        setField(etVolumeAngleMax, s.volumeAngleMaxDeg);

        tvSavedSummary.setText(buildSummaryText(s));
    }

    private void applyDefaultsToFields() {
        setField(etPitchAngleMin, DEFAULT_PITCH_ANGLE_MIN);
        setField(etPitchAngleMax, DEFAULT_PITCH_ANGLE_MAX);
        setField(etFreqMin, DEFAULT_FREQ_MIN);
        setField(etFreqMax, DEFAULT_FREQ_MAX);
        setField(etVolumeAngleMin, DEFAULT_VOLUME_ANGLE_MIN);
        setField(etVolumeAngleMax, DEFAULT_VOLUME_ANGLE_MAX);

        AppSettings preview = new AppSettings();
        preview.pitchAngleMinDeg = DEFAULT_PITCH_ANGLE_MIN;
        preview.pitchAngleMaxDeg = DEFAULT_PITCH_ANGLE_MAX;
        preview.freqMinHz = DEFAULT_FREQ_MIN;
        preview.freqMaxHz = DEFAULT_FREQ_MAX;
        preview.volumeAngleMinDeg = DEFAULT_VOLUME_ANGLE_MIN;
        preview.volumeAngleMaxDeg = DEFAULT_VOLUME_ANGLE_MAX;

        tvSavedSummary.setText(
                "Defaults loaded into the fields. Tap SAVE & RETURN to store them.\n\n" +
                        buildSummaryText(preview)
        );
    }

    private void saveFromFieldsAndStayHere() {
        try {
            AppSettings s = buildSettingsFromFields();
            settingsRepo.save(s);

            setField(etPitchAngleMin, s.pitchAngleMinDeg);
            setField(etPitchAngleMax, s.pitchAngleMaxDeg);
            setField(etFreqMin, s.freqMinHz);
            setField(etFreqMax, s.freqMaxHz);
            setField(etVolumeAngleMin, s.volumeAngleMinDeg);
            setField(etVolumeAngleMax, s.volumeAngleMaxDeg);

            tvSavedSummary.setText("✓ Saved\n\n" + buildSummaryText(s));
        } catch (Exception e) {
            Toast.makeText(this, "Enter valid numeric values", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveFromFieldsAndReturn() {
        try {
            AppSettings s = buildSettingsFromFields();
            settingsRepo.save(s);

            returnToExistingPlay();
        } catch (Exception e) {
            Toast.makeText(this, "Enter valid numeric values", Toast.LENGTH_SHORT).show();
        }
    }

    private AppSettings buildSettingsFromFields() {
        AppSettings s = new AppSettings();
        s.pitchAngleMinDeg = parseField(etPitchAngleMin);
        s.pitchAngleMaxDeg = parseField(etPitchAngleMax);
        s.freqMinHz = parseField(etFreqMin);
        s.freqMaxHz = parseField(etFreqMax);
        s.volumeAngleMinDeg = parseField(etVolumeAngleMin);
        s.volumeAngleMaxDeg = parseField(etVolumeAngleMax);

        sanitize(s);
        return s;
    }

    private void returnToExistingPlay() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION
        );
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    private void sanitize(AppSettings s) {
        s.pitchAngleMinDeg = clamp(s.pitchAngleMinDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        s.pitchAngleMaxDeg = clamp(s.pitchAngleMaxDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        s.volumeAngleMinDeg = clamp(s.volumeAngleMinDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        s.volumeAngleMaxDeg = clamp(s.volumeAngleMaxDeg, ANGLE_MIN_LIMIT, ANGLE_MAX_LIMIT);
        s.freqMinHz = clamp(s.freqMinHz, FREQ_MIN_LIMIT, FREQ_MAX_LIMIT);
        s.freqMaxHz = clamp(s.freqMaxHz, FREQ_MIN_LIMIT, FREQ_MAX_LIMIT);

        if (s.pitchAngleMaxDeg < s.pitchAngleMinDeg + 0.5f) {
            s.pitchAngleMaxDeg = Math.min(ANGLE_MAX_LIMIT, s.pitchAngleMinDeg + 0.5f);
        }
        if (s.volumeAngleMaxDeg < s.volumeAngleMinDeg + 0.5f) {
            s.volumeAngleMaxDeg = Math.min(ANGLE_MAX_LIMIT, s.volumeAngleMinDeg + 0.5f);
        }
        if (s.freqMaxHz < s.freqMinHz + 1.0f) {
            s.freqMaxHz = Math.min(FREQ_MAX_LIMIT, s.freqMinHz + 1.0f);
        }
    }

    private float parseField(EditText et) {
        return Float.parseFloat(et.getText().toString().trim());
    }

    private void setField(EditText et, float value) {
        et.setText(String.format(Locale.US, "%.2f", value));
    }

    private String buildSummaryText(AppSettings s) {
        return String.format(
                Locale.US,
                "Pitch angle: %.2f° → %.2f°\n" +
                        "Frequency: %.2f Hz → %.2f Hz\n" +
                        "Volume angle: %.2f° → %.2f°",
                s.pitchAngleMinDeg,
                s.pitchAngleMaxDeg,
                s.freqMinHz,
                s.freqMaxHz,
                s.volumeAngleMinDeg,
                s.volumeAngleMaxDeg
        );
    }

    private float clamp(float value, float min, float max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}