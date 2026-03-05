package com.example.thereminglovestest2;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class LibraryActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_base_nav_placeholder);

        TopNavBarView topNavBar = findViewById(R.id.topNavBar);
        TextView titleView = findViewById(R.id.tvPlaceholderTitle);
        TextView bodyView = findViewById(R.id.tvPlaceholderBody);

        if (topNavBar != null) {
            topNavBar.setTitleText("Library");
        }

        if (titleView != null) {
            titleView.setText("Library");
        }

        if (bodyView != null) {
            bodyView.setText("Your recordings, presets, and session history will appear here.");
        }
    }
}
