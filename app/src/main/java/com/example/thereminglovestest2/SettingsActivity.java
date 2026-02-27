package com.example.thereminglovestest2;

public class SettingsActivity extends BaseNavPlaceholderActivity {

    @Override
    protected String getScreenTitle() {
        return "Settings";
    }

    @Override
    protected String getScreenBodyText() {
        return "Planned settings screen for saved ranges, frequency limits, tone defaults, and other app behavior.\n\nSQLite persistence wiring is next.";
    }
}