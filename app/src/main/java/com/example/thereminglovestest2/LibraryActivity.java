package com.example.thereminglovestest2;

import com.example.thereminglovestest2.TopNavBarView;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.util.List;

public class LibraryActivity extends AppCompatActivity implements RecordingListAdapter.OnRecordingActionListener {

    private RecyclerView rvRecordings;
    private TextView tvEmptyState;

    private RecordingRepository repo;
    private RecordingListAdapter adapter;
    private List<RecordingRepository.Recording> recordings;

    private MediaPlayer player;
    private int currentlyPlayingPosition = -1;
    private boolean isPaused = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        TopNavBarView topNavBar = findViewById(R.id.topNavBar);
        topNavBar.setTitleText("Library");

        rvRecordings = findViewById(R.id.rvRecordings);
        tvEmptyState = findViewById(R.id.tvEmptyState);

        repo = new RecordingRepository(this);
        recordings = repo.getAllRecordings();

        adapter = new RecordingListAdapter(recordings, this);
        rvRecordings.setLayoutManager(new LinearLayoutManager(this));
        rvRecordings.setAdapter(adapter);

        updateEmptyState();
    }

    private void updateEmptyState() {
        if (recordings == null || recordings.isEmpty()) {
            tvEmptyState.setVisibility(View.VISIBLE);
            rvRecordings.setVisibility(View.GONE);
        } else {
            tvEmptyState.setVisibility(View.GONE);
            rvRecordings.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onPlayPauseClicked(int position) {
        RecordingRepository.Recording recording = adapter.getItem(position);

        if (currentlyPlayingPosition == position && player != null) {
            if (player.isPlaying()) {
                player.pause();
                isPaused = true;
                adapter.setPlayingState(position, false);
            } else if (isPaused) {
                player.start();
                isPaused = false;
                adapter.setPlayingState(position, true);
            }
            return;
        }

        stopPlayer();

        player = new MediaPlayer();
        try {
            player.setDataSource(recording.filePath);
            player.prepare();
            player.start();

            currentlyPlayingPosition = position;
            isPaused = false;
            adapter.setPlayingState(position, true);

            player.setOnCompletionListener(mp -> {
                stopPlayer();
                adapter.clearPlayingState();
                currentlyPlayingPosition = -1;
                isPaused = false;
            });

        } catch (IOException e) {
            e.printStackTrace();
            stopPlayer();
            adapter.clearPlayingState();
            currentlyPlayingPosition = -1;
            isPaused = false;
        }
    }

    @Override
    public void onRecordingLongPressed(int position) {
        RecordingRepository.Recording recording = adapter.getItem(position);

        new AlertDialog.Builder(this)
                .setTitle("Delete recording")
                .setMessage("Delete this recording?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    if (position == currentlyPlayingPosition) {
                        stopPlayer();
                        adapter.clearPlayingState();
                        currentlyPlayingPosition = -1;
                        isPaused = false;
                    }

                    repo.deleteRecording(recording.id);
                    adapter.removeItem(position);
                    updateEmptyState();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void stopPlayer() {
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.stop();
                }
            } catch (IllegalStateException ignored) {
            }

            player.release();
            player = null;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayer();
        if (adapter != null) {
            adapter.clearPlayingState();
        }
        currentlyPlayingPosition = -1;
        isPaused = false;
    }
}