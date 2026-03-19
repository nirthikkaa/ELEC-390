package com.example.thereminglovestest2;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingListAdapter extends RecyclerView.Adapter<RecordingListAdapter.ViewHolder> {

    public interface OnRecordingActionListener {
        void onPlayPauseClicked(int position);
        void onRecordingLongPressed(int position);
    }

    private final List<RecordingRepository.Recording> recordings;
    private final OnRecordingActionListener listener;

    private int currentlyPlayingPosition = -1;
    private boolean isPlaying = false;

    public RecordingListAdapter(List<RecordingRepository.Recording> recordings,
                                OnRecordingActionListener listener) {
        this.recordings = recordings;
        this.listener = listener;
    }

    public RecordingRepository.Recording getItem(int position) {
        return recordings.get(position);
    }

    public void removeItem(int position) {
        recordings.remove(position);
        notifyItemRemoved(position);
    }

    public boolean isEmpty() {
        return recordings.isEmpty();
    }

    public void setPlayingState(int position, boolean playing) {
        int oldPosition = currentlyPlayingPosition;
        currentlyPlayingPosition = position;
        isPlaying = playing;

        if (oldPosition != -1) {
            notifyItemChanged(oldPosition);
        }
        if (position != -1) {
            notifyItemChanged(position);
        }
    }

    public void clearPlayingState() {
        int oldPosition = currentlyPlayingPosition;
        currentlyPlayingPosition = -1;
        isPlaying = false;

        if (oldPosition != -1) {
            notifyItemChanged(oldPosition);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recording, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        RecordingRepository.Recording recording = recordings.get(position);

        holder.tvRecordingName.setText(recording.displayName);

        long secs = recording.durationMs / 1000;
        String duration = String.format(Locale.getDefault(), "%d:%02d", secs / 60, secs % 60);

        String date = new SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(new Date(recording.createdAtMs));

        holder.tvRecordingMeta.setText(date + " • " + duration);

        boolean rowIsPlaying = (position == currentlyPlayingPosition && isPlaying);
        holder.btnPlayPause.setImageResource(
                rowIsPlaying
                        ? android.R.drawable.ic_media_pause
                        : android.R.drawable.ic_media_play
        );

        holder.btnPlayPause.setOnClickListener(v -> listener.onPlayPauseClicked(position));

        holder.itemView.setOnLongClickListener(v -> {
            listener.onRecordingLongPressed(position);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return recordings.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvRecordingName;
        TextView tvRecordingMeta;
        ImageButton btnPlayPause;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvRecordingName = itemView.findViewById(R.id.tvRecordingName);
            tvRecordingMeta = itemView.findViewById(R.id.tvRecordingMeta);
            btnPlayPause = itemView.findViewById(R.id.btnPlayPause);
        }
    }
}