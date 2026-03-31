package com.example.thereminglovestest2;

import android.content.ClipData;
import android.content.ClipDescription;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.card.MaterialCardView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class RecordingListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_FOLDER    = 0;
    private static final int TYPE_RECORDING = 1;

    private static final int SELECTION_BG = 0x337EA4FF; // primary blue 20% alpha

    public interface OnRecordingActionListener {
        void onPlayPauseClicked(int recordingIndex);
        void onDeleteClicked(int recordingIndex);
        void onRenameClicked(int recordingIndex);
        void onMoveClicked(int recordingIndex);
        void onItemClicked(int recordingIndex);
        void onFolderClicked(int folderIndex);
        void onFolderMenuClicked(int folderIndex, View anchor);
        void onRecordingDroppedOnFolder(long recordingId, int folderIndex);
    }

    private final List<RecordingRepository.Folder>    folders    = new ArrayList<>();
    private final List<RecordingRepository.Recording> recordings;
    private final OnRecordingActionListener            listener;

    private int     currentlyPlayingPosition = -1; // index into recordings list
    private boolean isPlaying               = false;
    private boolean isMultiSelectMode       = false;
    private final Set<Long> selectedIds     = new HashSet<>();

    public RecordingListAdapter(List<RecordingRepository.Recording> recordings,
                                OnRecordingActionListener listener) {
        this.recordings = recordings;
        this.listener   = listener;
    }

    public void updateFolders(List<RecordingRepository.Folder> newFolders) {
        folders.clear();
        folders.addAll(newFolders);
        notifyDataSetChanged();
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    public RecordingRepository.Recording getItem(int recordingIndex) {
        return recordings.get(recordingIndex);
    }

    public void removeItem(int recordingIndex) {
        recordings.remove(recordingIndex);
        notifyItemRemoved(folders.size() + recordingIndex);
    }

    public boolean isEmpty() { return folders.isEmpty() && recordings.isEmpty(); }

    // ── Playback state ────────────────────────────────────────────────────────

    public void setPlayingState(int recordingIndex, boolean playing) {
        int old = currentlyPlayingPosition;
        currentlyPlayingPosition = recordingIndex;
        isPlaying = playing;
        if (old != -1)            notifyItemChanged(folders.size() + old);
        if (recordingIndex != -1) notifyItemChanged(folders.size() + recordingIndex);
    }

    public void clearPlayingState() {
        int old = currentlyPlayingPosition;
        currentlyPlayingPosition = -1;
        isPlaying = false;
        if (old != -1) notifyItemChanged(folders.size() + old);
    }

    // ── Multi-select ──────────────────────────────────────────────────────────

    public void enterMultiSelectMode() { isMultiSelectMode = true;  selectedIds.clear(); notifyDataSetChanged(); }
    public void exitMultiSelectMode()  { isMultiSelectMode = false; selectedIds.clear(); notifyDataSetChanged(); }
    public boolean isInMultiSelectMode() { return isMultiSelectMode; }

    public void toggleSelection(int recordingIndex) {
        long id = recordings.get(recordingIndex).id;
        if (selectedIds.contains(id)) selectedIds.remove(id); else selectedIds.add(id);
        notifyItemChanged(folders.size() + recordingIndex);
    }

    public int        getSelectedCount() { return selectedIds.size(); }
    public List<Long> getSelectedIds()   { return new ArrayList<>(selectedIds); }

    public void removeSelected() {
        for (int i = recordings.size() - 1; i >= 0; i--) {
            if (selectedIds.contains(recordings.get(i).id)) {
                recordings.remove(i);
                notifyItemRemoved(folders.size() + i);
            }
        }
        selectedIds.clear();
    }

    // ── RecyclerView ──────────────────────────────────────────────────────────

    @Override public int getItemCount()           { return folders.size() + recordings.size(); }
    @Override public int getItemViewType(int pos) { return pos < folders.size() ? TYPE_FOLDER : TYPE_RECORDING; }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_FOLDER)
            return new FolderViewHolder(inf.inflate(R.layout.item_folder, parent, false));
        return new RecordingViewHolder(inf.inflate(R.layout.item_recording, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (getItemViewType(position) == TYPE_FOLDER)
            bindFolder((FolderViewHolder) holder, position);
        else
            bindRecording((RecordingViewHolder) holder, position - folders.size());
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder instanceof RecordingViewHolder) {
            RecordingViewHolder h = (RecordingViewHolder) holder;
            h.longPressHandler.removeCallbacks(h.pendingDrag);
            h.pendingDrag = null;
            h.itemRoot.setOnTouchListener(null);
            h.itemRoot.setOnDragListener(null);
            h.itemRoot.setAlpha(1f);
        }
    }

    // ── Bind ──────────────────────────────────────────────────────────────────

    private void bindFolder(FolderViewHolder h, int folderIndex) {
        RecordingRepository.Folder folder = folders.get(folderIndex);
        applyFolderCardState(h.cardView, false);
        h.tvFolderName.setText(folder.name);
        int n = folder.recordingCount;
        h.tvFolderMeta.setText(n + (n == 1 ? " recording" : " recordings"));

        h.itemView.setOnClickListener(v -> {
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < folders.size())
                listener.onFolderClicked(pos);
        });
        h.btnFolderMenu.setOnClickListener(v -> {
            int pos = h.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < folders.size())
                listener.onFolderMenuClicked(pos, v);
        });

        // Accept drag-and-drop of recording items
        h.itemView.setOnDragListener((v, event) -> {
            switch (event.getAction()) {
                case DragEvent.ACTION_DRAG_STARTED:
                    return event.getClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN);
                case DragEvent.ACTION_DRAG_ENTERED:
                    applyFolderCardState(h.cardView, true);
                    return true;
                case DragEvent.ACTION_DRAG_EXITED:
                    applyFolderCardState(h.cardView, false);
                    return true;
                case DragEvent.ACTION_DROP: {
                    applyFolderCardState(h.cardView, false);
                    String idStr = event.getClipData().getItemAt(0).getText().toString();
                    long recordingId = Long.parseLong(idStr);
                    int pos = h.getAdapterPosition();
                    if (pos != RecyclerView.NO_POSITION && pos < folders.size())
                        listener.onRecordingDroppedOnFolder(recordingId, pos);
                    return true;
                }
                case DragEvent.ACTION_DRAG_ENDED:
                    applyFolderCardState(h.cardView, false);
                    return true;
            }
            return false;
        });
    }

    private void bindRecording(RecordingViewHolder h, int recordingIndex) {
        RecordingRepository.Recording r = recordings.get(recordingIndex);

        h.tvRecordingName.setText(r.displayName);

        long secs = r.durationMs / 1000;
        String duration = String.format(Locale.getDefault(), "%d:%02d", secs / 60, secs % 60);
        String date = new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(new Date(r.createdAtMs));
        String size = fileSizeString(r.filePath);
        String meta = date + " \u2022 " + duration + " \u2022 " + size;
        h.tvRecordingMeta.setText(meta);
        applyQualityBadge(h.tvRecordingQuality, r.quality, r.filePath);

        h.itemRoot.setBackgroundColor(
                isMultiSelectMode && selectedIds.contains(r.id) ? SELECTION_BG : Color.TRANSPARENT);

        if (isMultiSelectMode) {
            h.btnPlayPause.setVisibility(View.GONE);
            h.btnMore.setVisibility(View.GONE);
            h.itemRoot.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onItemClicked(pos - folders.size());
            });
            h.itemRoot.setOnTouchListener(null);
            h.itemRoot.setOnDragListener(null);
        } else {
            h.btnPlayPause.setVisibility(View.VISIBLE);
            h.btnMore.setVisibility(View.VISIBLE);

            boolean rowPlaying = (recordingIndex == currentlyPlayingPosition && isPlaying);
            h.btnPlayPause.setImageResource(
                    rowPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);

            h.btnPlayPause.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onPlayPauseClicked(pos - folders.size());
            });
            h.btnMore.setOnClickListener(v -> {
                int pos = h.getAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                int idx = pos - folders.size();
                new MaterialAlertDialogBuilder(h.itemView.getContext())
                        .setItems(new CharSequence[]{"Rename", "Move to\u2026", "Delete"},
                                (dialog, which) -> {
                                    switch (which) {
                                        case 0: listener.onRenameClicked(idx); break;
                                        case 1: listener.onMoveClicked(idx);   break;
                                        case 2: listener.onDeleteClicked(idx); break;
                                    }
                                })
                        .show();
            });
            h.itemRoot.setOnClickListener(null);

            // ── 2-second hold → drag into folder ─────────────────────────────
            h.longPressHandler.removeCallbacks(h.pendingDrag);
            final float[] downPos = {0f, 0f};
            h.pendingDrag = () -> {
                h.itemRoot.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                h.itemRoot.setAlpha(0.5f);
                ClipData clip = ClipData.newPlainText("rec_id", String.valueOf(r.id));
                h.itemRoot.startDragAndDrop(clip, new View.DragShadowBuilder(h.itemRoot),
                        Long.valueOf(r.id), 0);
            };

            h.itemRoot.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downPos[0] = event.getX();
                        downPos[1] = event.getY();
                        h.longPressHandler.postDelayed(h.pendingDrag, 500);
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getX() - downPos[0];
                        float dy = event.getY() - downPos[1];
                        if (dx * dx + dy * dy > 400) // ~20px movement cancels pending drag
                            h.longPressHandler.removeCallbacks(h.pendingDrag);
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        h.longPressHandler.removeCallbacks(h.pendingDrag);
                        return false;
                }
                return false;
            });

            // Reset alpha when drag ends (fired on the view that started the drag)
            h.itemRoot.setOnDragListener((v, event) -> {
                if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return true;
                if (event.getAction() == DragEvent.ACTION_DRAG_ENDED) { v.setAlpha(1f); return true; }
                return false;
            });
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void applyQualityBadge(TextView badge, String quality, String filePath) {
        if (quality == null && filePath != null) {
            if (filePath.endsWith(".wav"))      quality = AppSettings.COMPRESSION_LOSSLESS;
            else if (filePath.endsWith(".m4a")) quality = AppSettings.COMPRESSION_HIGH;
        }
        if (quality == null) { badge.setVisibility(View.GONE); return; }
        String label; int bgColor, textColor;
        switch (quality) {
            case AppSettings.COMPRESSION_LOSSLESS:
                label = "LOSSLESS"; bgColor = 0xFF7EA4FF; textColor = 0xFF081425; break;
            case AppSettings.COMPRESSION_HIGH:
                label = "HIGH"; bgColor = 0xFF43E5FF; textColor = 0xFF082633; break;
            case AppSettings.COMPRESSION_MEDIUM:
                label = "MED";  bgColor = 0xFFFF63C6; textColor = 0xFF371028; break;
            case AppSettings.COMPRESSION_LOW:
                label = "LOW";  bgColor = 0xFF10224D; textColor = 0xFFF6F1FF; break;
            default:
                badge.setVisibility(View.GONE); return;
        }
        float r = badge.getResources().getDisplayMetrics().density * 4f;
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(bgColor);
        badge.setBackground(bg);
        badge.setText(label);
        badge.setTextColor(textColor);
        badge.setVisibility(View.VISIBLE);
    }

    private static void applyFolderCardState(MaterialCardView card, boolean dragHover) {
        if (card == null) return;
        int bg = ContextCompat.getColor(card.getContext(),
                dragHover ? R.color.app_box_inner_surface : R.color.app_box_surface);
        int stroke = ContextCompat.getColor(card.getContext(),
                dragHover ? R.color.app_secondary : R.color.app_outline);
        card.setCardBackgroundColor(bg);
        card.setStrokeColor(stroke);
        card.setStrokeWidth((int) (card.getResources().getDisplayMetrics().density * (dragHover ? 1f : 0f)));
    }

    private static String fileSizeString(String filePath) {
        File f = new File(filePath);
        if (!f.exists()) return "\u2014";
        long bytes = f.length();
        if (bytes < 1024)        return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.getDefault(), "%.0f KB", bytes / 1024f);
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f));
    }

    // ── ViewHolders ───────────────────────────────────────────────────────────

    static class FolderViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardView;
        TextView    tvFolderName, tvFolderMeta;
        ImageButton btnFolderMenu;

        FolderViewHolder(@NonNull View v) {
            super(v);
            cardView      = (MaterialCardView) v;
            tvFolderName  = v.findViewById(R.id.tvFolderName);
            tvFolderMeta  = v.findViewById(R.id.tvFolderMeta);
            btnFolderMenu = v.findViewById(R.id.btnFolderMenu);
        }
    }

    static class RecordingViewHolder extends RecyclerView.ViewHolder {
        View        itemRoot;
        TextView    tvRecordingName, tvRecordingMeta, tvRecordingQuality;
        ImageButton btnPlayPause, btnMore;

        final Handler  longPressHandler = new Handler(Looper.getMainLooper());
        Runnable       pendingDrag;

        RecordingViewHolder(@NonNull View v) {
            super(v);
            itemRoot           = v.findViewById(R.id.itemRoot);
            tvRecordingName    = v.findViewById(R.id.tvRecordingName);
            tvRecordingMeta    = v.findViewById(R.id.tvRecordingMeta);
            tvRecordingQuality = v.findViewById(R.id.tvRecordingQuality);
            btnPlayPause       = v.findViewById(R.id.btnPlayPause);
            btnMore            = v.findViewById(R.id.btnMore);
        }
    }
}
