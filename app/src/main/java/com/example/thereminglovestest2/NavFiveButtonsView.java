package com.example.thereminglovestest2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

/**
 * Exactly 5 navigation buttons:
 * Play, Connect, Calibration, Library, Settings
 */
public class NavFiveButtonsView extends LinearLayout {

    public NavFiveButtonsView(Context context) {
        super(context);
        init(context);
    }

    public NavFiveButtonsView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public NavFiveButtonsView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        setOrientation(VERTICAL);

        LinearLayout row1 = new LinearLayout(context);
        row1.setOrientation(HORIZONTAL);

        LinearLayout row2 = new LinearLayout(context);
        row2.setOrientation(HORIZONTAL);

        Button btnPlay = makeButton(context, "Play", MainActivity.class);
        Button btnConnect = makeButton(context, "Connect", ConnectGlovesActivity.class);
        Button btnCal = makeButton(context, "Calibrate", CalibrationActivity.class);
        Button btnLibrary = makeButton(context, "Library", LibraryActivity.class);
        Button btnSettings = makeButton(context, "Settings", SettingsActivity.class);

        row1.addView(btnPlay, weightedButtonLp(false));
        row1.addView(btnConnect, weightedButtonLp(true));
        row1.addView(btnCal, weightedButtonLp(true));

        row2.addView(btnLibrary, weightedButtonLp(false));
        row2.addView(btnSettings, weightedButtonLp(true));

        LayoutParams rowLp1 = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        addView(row1, rowLp1);

        LayoutParams rowLp2 = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowLp2.topMargin = dp(8);
        addView(row2, rowLp2);
    }

    private Button makeButton(Context context, String text, Class<? extends Activity> target) {
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);

        Context c = getContext();
        boolean isCurrent = (c instanceof Activity) && ((Activity) c).getClass().equals(target);
        if (isCurrent) {
            button.setText(text + " ✓");
            button.setEnabled(false);
        }

        button.setOnClickListener(v -> openScreen(target));
        return button;
    }

    private void openScreen(Class<? extends Activity> target) {
        Context c = getContext();
        if (!(c instanceof Activity)) return;

        Activity current = (Activity) c;
        if (current.getClass().equals(target)) return;

        current.startActivity(new Intent(current, target));
    }

    private LayoutParams weightedButtonLp(boolean marginStart) {
        LayoutParams lp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        if (marginStart) {
            lp.leftMargin = dp(8);
        }
        return lp;
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}