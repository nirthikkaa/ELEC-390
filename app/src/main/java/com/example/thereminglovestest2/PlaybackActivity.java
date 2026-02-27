package com.example.thereminglovestest2;

public class PlaybackActivity extends BasePlaceholderActivity {
    @Override
    protected String getScreenTitle() {
        return "Playback";
    }

    @Override
    protected String getScreenBodyText() {
        return "This screen will handle replay, waveform/tone selection, and future EQ-style visualizations for recorded sessions.";
    }
}