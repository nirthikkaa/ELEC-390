package com.example.thereminglovestest2;

import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public abstract class BaseNavPlaceholderActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Root = vertical (scrollable content on top, persistent bottom nav at bottom)
        LinearLayout screenRoot = new LinearLayout(this);
        screenRoot.setOrientation(LinearLayout.VERTICAL);

        InsetAwareScrollView scroll = new InsetAwareScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        content.setPadding(pad, pad, pad, pad);

        TopNavBarView top = new TopNavBarView(this);
        top.setTitleText(getScreenTitle());
        content.addView(top, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView title = new TextView(this);
        title.setText(getScreenTitle());
        title.setTextSize(24f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleLp.topMargin = dp(16);
        content.addView(title, titleLp);

        TextView body = new TextView(this);
        body.setText(getScreenBodyText());
        body.setTextSize(16f);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bodyLp.topMargin = dp(10);
        content.addView(body, bodyLp);

        scroll.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // Persistent bottom bar
        BottomNavBarView bottomNav = new BottomNavBarView(this);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        );
        screenRoot.addView(scroll, scrollLp);

        screenRoot.addView(bottomNav, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        setContentView(screenRoot);
    }

    protected abstract String getScreenTitle();

    protected String getScreenBodyText() {
        return "Placeholder screen.\n\nThis is part of the navigation shell step so the BLE Play screen can stay stable while we build the app structure safely.";
    }

    private int dp(int value) {
        float d = getResources().getDisplayMetrics().density;
        return Math.round(value * d);
    }
}