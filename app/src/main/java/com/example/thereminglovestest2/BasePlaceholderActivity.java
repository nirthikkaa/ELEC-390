package com.example.thereminglovestest2;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public abstract class BasePlaceholderActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Build UI in Java so we do NOT depend on activity_placeholder_screen.xml
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TopNavBarView topNavBarView = new TopNavBarView(this);
        LinearLayout.LayoutParams topLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        topLp.bottomMargin = dp(16);
        root.addView(topNavBarView, topLp);

        TextView tvTitle = new TextView(this);
        tvTitle.setTextSize(24f);
        tvTitle.setTypeface(tvTitle.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(tvTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView tvBody = new TextView(this);
        tvBody.setTextSize(16f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bodyLp.topMargin = dp(12);
        root.addView(tvBody, bodyLp);

        String title = getScreenTitle();
        topNavBarView.setTitleText(title);
        tvTitle.setText(title);
        tvBody.setText(getScreenBodyText());

        setContentView(root);
    }

    protected abstract String getScreenTitle();

    protected String getScreenBodyText() {
        return "Placeholder screen for MVP navigation skeleton.\n\nNext steps: wire real features while keeping BLE stable in Live Play.";
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}