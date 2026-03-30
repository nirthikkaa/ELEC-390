package com.example.thereminglovestest2;

import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class LibraryActivity extends AppCompatActivity
        implements RecordingListAdapter.OnRecordingActionListener {

    private TopNavBarView topNavBar;
    private RecyclerView rvRecordings;
    private TextView tvEmptyState;

    // Multi-select bar
    private View multiSelectBar;
    private TextView tvSelectionCount;
    private MaterialButton btnMoveSelected;
    private MaterialButton btnDeleteSelected;

    // Mini player bar
    private View playerBar;
    private TextView tvPlayerName;
    private TextView tvQualityBadge;
    private TextView tvPlayerTime;
    private SeekBar sbPlayerProgress;
    private ImageButton btnPlayerPrevious;
    private ImageButton btnPlayerPlayPause;
    private ImageButton btnPlayerSkip;

    private RecordingRepository repo;
    private RecordingListAdapter adapter;

    // Data
    private final List<RecordingRepository.Recording> recordings    = new ArrayList<>();
    private final List<RecordingRepository.Recording> allRecordings = new ArrayList<>();
    private final List<RecordingRepository.Folder>    folders       = new ArrayList<>();

    // Folder navigation — -1 means root
    private long   currentFolderId   = -1;
    private String currentFolderName = null;

    // Playback — positions are recording-list indices (not adapter positions)
    private MediaPlayer player;
    private int currentlyPlayingPosition = -1;
    private RecordingRepository.Recording currentlyPlayingRecording;
    private boolean isPaused = false;
    private boolean thereminMutedByUs = false;        // true only when WE muted the background service
    private boolean thereminWasMutedBeforePlayback = false; // snapshot of mute state before we touched it
    private final Handler playerProgressHandler = new Handler(Looper.getMainLooper());

    // Multi-select
    private boolean isMultiSelectMode = false;

    // Playback mode
    private enum PlaybackMode { SEQUENTIAL, SINGLE, LOOP_ONE, LOOP_ALL }
    private PlaybackMode playbackMode = PlaybackMode.SEQUENTIAL;
    private TextView btnPlayerMode;

    // Sorting
    private enum SortOrder { DATE_DESC, DATE_ASC, NAME_ASC, NAME_DESC, DURATION_DESC, DURATION_ASC, SIZE_DESC, SIZE_ASC }
    private static final String[] SORT_LABELS = {
            "Date (newest first)", "Date (oldest first)",
            "Name (A\u2013Z)", "Name (Z\u2013A)",
            "Duration (longest first)", "Duration (shortest first)",
            "Size (largest first)", "Size (smallest first)"
    };
    private SortOrder currentSort = SortOrder.DATE_DESC;

    // Search
    private LinearLayout searchBarLayout;
    private EditText etSearch;
    private String searchQuery = "";

    // Filters
    private enum DurationFilter { ALL, SHORT, MEDIUM, LONG }
    private enum DateFilter     { ALL, TODAY, THIS_WEEK, THIS_MONTH }
    private DurationFilter durationFilter = DurationFilter.ALL;
    private DateFilter     dateFilter     = DateFilter.ALL;

    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            try {
                int pos = player.getCurrentPosition();
                int dur = player.getDuration();
                sbPlayerProgress.setProgress(pos);
                updatePlayerTimeText(pos, dur);
                if (player.isPlaying()) playerProgressHandler.postDelayed(this, 50);
            } catch (Exception ignored) {}
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        topNavBar = findViewById(R.id.topNavBar);
        topNavBar.setTitleText("Library");
        topNavBar.addActionButton(R.drawable.ic_search, "Search", v -> toggleSearchBar());
        topNavBar.addActionButton(R.drawable.ic_filter, "Filter and sort", v -> showFilterSortSheet());
        topNavBar.setMenuClickListener(this::showLibraryMenu);

        searchBarLayout = findViewById(R.id.searchBarLayout);
        etSearch        = findViewById(R.id.etSearch);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                searchQuery = s.toString();
                refreshDisplayedList();
            }
        });

        rvRecordings      = findViewById(R.id.rvRecordings);
        tvEmptyState      = findViewById(R.id.tvEmptyState);
        multiSelectBar    = findViewById(R.id.multiSelectBar);
        tvSelectionCount  = findViewById(R.id.tvSelectionCount);
        btnMoveSelected   = findViewById(R.id.btnMoveSelected);
        btnDeleteSelected = findViewById(R.id.btnDeleteSelected);
        playerBar         = findViewById(R.id.playerBar);
        tvPlayerName      = findViewById(R.id.tvPlayerName);
        tvQualityBadge    = findViewById(R.id.tvQualityBadge);
        tvPlayerTime      = findViewById(R.id.tvPlayerTime);
        sbPlayerProgress   = findViewById(R.id.sbPlayerProgress);
        btnPlayerPrevious  = findViewById(R.id.btnPlayerPrevious);
        btnPlayerPlayPause = findViewById(R.id.btnPlayerPlayPause);
        btnPlayerSkip      = findViewById(R.id.btnPlayerSkip);
        btnPlayerMode      = findViewById(R.id.btnPlayerMode);

        repo = new RecordingRepository(this);

        adapter = new RecordingListAdapter(recordings, this);
        rvRecordings.setLayoutManager(new LinearLayoutManager(this));
        rvRecordings.setAdapter(adapter);

        btnMoveSelected.setOnClickListener(v -> showMoveToFolderDialog(adapter.getSelectedIds()));
        btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());
        btnPlayerPrevious.setOnClickListener(v -> playPreviousTrack());
        btnPlayerPlayPause.setOnClickListener(v -> togglePlayerPlayPause());
        btnPlayerSkip.setOnClickListener(v -> playNextTrack());
        btnPlayerMode.setOnClickListener(v -> cyclePlaybackMode());

        sbPlayerProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            boolean wasPlaying;
            @Override public void onStartTrackingTouch(SeekBar s) {
                try { wasPlaying = player != null && player.isPlaying(); } catch (Exception e) { wasPlaying = false; }
                if (wasPlaying) { player.pause(); playerProgressHandler.removeCallbacks(progressRunnable); }
            }
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (fromUser) updatePlayerTimeText(progress, s.getMax());
            }
            @Override public void onStopTrackingTouch(SeekBar s) {
                if (player != null) {
                    try { player.seekTo(s.getProgress()); } catch (Exception ignored) {}
                    if (wasPlaying) { try { player.start(); playerProgressHandler.post(progressRunnable); } catch (Exception ignored) {} }
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadData();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayer();
        currentlyPlayingPosition = -1;
        currentlyPlayingRecording = null;
        isPaused = false;
    }

    @Override
    public void onBackPressed() {
        if (isMultiSelectMode) { exitMultiSelectMode(); return; }
        if (currentFolderId != -1) { navigateToRoot(); return; }
        super.onBackPressed();
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    private void loadData() {
        allRecordings.clear();
        allRecordings.addAll(repo.getAllRecordings());
        if (currentFolderId == -1) {
            folders.clear();
            folders.addAll(repo.getAllFolders());
        }
        refreshDisplayedList();
    }

    private void refreshDisplayedList() {
        recordings.clear();
        for (RecordingRepository.Recording r : allRecordings) {
            if (r.folderId != currentFolderId) continue;
            if (!matchesSearch(r)) continue;
            if (!matchesDurationFilter(r)) continue;
            if (!matchesDateFilter(r)) continue;
            recordings.add(r);
        }
        applySort();
        adapter.updateFolders(currentFolderId == -1 ? folders : new ArrayList<>());
        updateEmptyState();
    }

    private boolean matchesSearch(RecordingRepository.Recording r) {
        if (searchQuery.isEmpty()) return true;
        return r.displayName.toLowerCase(Locale.getDefault())
                .contains(searchQuery.toLowerCase(Locale.getDefault()));
    }

    private boolean matchesDurationFilter(RecordingRepository.Recording r) {
        switch (durationFilter) {
            case SHORT:  return r.durationMs < 30_000;
            case MEDIUM: return r.durationMs >= 30_000 && r.durationMs <= 120_000;
            case LONG:   return r.durationMs > 120_000;
            default:     return true;
        }
    }

    private boolean matchesDateFilter(RecordingRepository.Recording r) {
        if (dateFilter == DateFilter.ALL) return true;
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        switch (dateFilter) {
            case TODAY: return r.createdAtMs >= cal.getTimeInMillis();
            case THIS_WEEK: {
                cal.set(Calendar.DAY_OF_WEEK, cal.getFirstDayOfWeek());
                return r.createdAtMs >= cal.getTimeInMillis();
            }
            case THIS_MONTH: {
                cal.set(Calendar.DAY_OF_MONTH, 1);
                return r.createdAtMs >= cal.getTimeInMillis();
            }
            default: return true;
        }
    }

    // ── RecordingListAdapter callbacks ────────────────────────────────────────

    @Override
    public void onPlayPauseClicked(int recordingIndex) {
        RecordingRepository.Recording recording = adapter.getItem(recordingIndex);

        if (currentlyPlayingPosition == recordingIndex && player != null) {
            try {
                if (player.isPlaying()) {
                    player.pause(); isPaused = true;
                    thereminMutedByUs = false;
                    ThereminBackgroundAudioService.setThereminMuted(thereminWasMutedBeforePlayback);
                    adapter.setPlayingState(recordingIndex, false);
                    btnPlayerPlayPause.setImageResource(android.R.drawable.ic_media_play);
                    playerProgressHandler.removeCallbacks(progressRunnable);
                } else if (isPaused) {
                    player.start(); isPaused = false;
                    thereminMutedByUs = true;
                    ThereminBackgroundAudioService.setThereminMuted(true);
                    adapter.setPlayingState(recordingIndex, true);
                    btnPlayerPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                    playerProgressHandler.post(progressRunnable);
                }
            } catch (Exception ignored) {}
            return;
        }

        startPlayback(recordingIndex);
    }

    private void startPlayback(int recordingIndex) {
        if (recordingIndex < 0 || recordingIndex >= recordings.size()) return;
        RecordingRepository.Recording recording = recordings.get(recordingIndex);
        stopPlayer();
        player = new MediaPlayer();
        try {
            player.setDataSource(recording.filePath);
            player.prepare();
            player.start();
            thereminWasMutedBeforePlayback = ThereminBackgroundAudioService.isThereminMuted();
            thereminMutedByUs = true;
            ThereminBackgroundAudioService.setThereminMuted(true);

            currentlyPlayingPosition  = recordingIndex;
            currentlyPlayingRecording = recording;
            isPaused = false;
            adapter.setPlayingState(recordingIndex, true);
            showPlayerBar(recording, true);

            player.setOnCompletionListener(mp -> {
                switch (playbackMode) {
                    case LOOP_ONE:
                        try { mp.seekTo(0); mp.start(); } catch (Exception ignored) {}
                        playerProgressHandler.post(progressRunnable);
                        break;
                    case SINGLE:
                        currentlyPlayingPosition  = -1;
                        currentlyPlayingRecording = null;
                        isPaused = false;
                        adapter.clearPlayingState();
                        hidePlayerBar();
                        break;
                    case LOOP_ALL: {
                        int next = currentlyPlayingPosition + 1;
                        startPlayback(next < recordings.size() ? next : 0);
                        break;
                    }
                    default: { // SEQUENTIAL
                        int next = currentlyPlayingPosition + 1;
                        if (next < recordings.size()) {
                            startPlayback(next);
                        } else {
                            currentlyPlayingPosition  = -1;
                            currentlyPlayingRecording = null;
                            isPaused = false;
                            adapter.clearPlayingState();
                            hidePlayerBar();
                        }
                        break;
                    }
                }
            });
        } catch (IOException e) {
            stopPlayer();
            currentlyPlayingPosition  = -1;
            currentlyPlayingRecording = null;
            isPaused = false;
        }
    }

    private void playNextTrack() {
        if (recordings.isEmpty() || currentlyPlayingPosition < 0) return;
        int next = currentlyPlayingPosition + 1;
        if (next < recordings.size()) {
            startPlayback(next);
        } else if (playbackMode == PlaybackMode.LOOP_ALL) {
            startPlayback(0);
        }
    }

    private void playPreviousTrack() {
        if (recordings.isEmpty() || currentlyPlayingPosition < 0) return;
        // If more than 3 s into the track, restart it; otherwise go to the previous
        boolean restartCurrent = false;
        try { restartCurrent = player != null && player.getCurrentPosition() > 3000; }
        catch (Exception ignored) {}
        int target = restartCurrent ? currentlyPlayingPosition
                : Math.max(0, currentlyPlayingPosition - 1);
        startPlayback(target);
    }

    @Override
    public void onMoveClicked(int recordingIndex) {
        RecordingRepository.Recording recording = adapter.getItem(recordingIndex);
        List<Long> ids = new ArrayList<>();
        ids.add(recording.id);
        showMoveToFolderDialog(ids);
    }

    @Override
    public void onDeleteClicked(int recordingIndex) {
        RecordingRepository.Recording recording = adapter.getItem(recordingIndex);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete recording")
                .setMessage("Delete \u201c" + recording.displayName + "\u201d?")
                .setPositiveButton("Delete", (d, w) -> {
                    if (recordingIndex == currentlyPlayingPosition) {
                        stopPlayer();
                        currentlyPlayingPosition  = -1;
                        currentlyPlayingRecording = null;
                    }
                    repo.deleteRecording(recording.id);
                    adapter.removeItem(recordingIndex);
                    allRecordings.remove(recording);
                    updateEmptyState();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onRenameClicked(int recordingIndex) {
        RecordingRepository.Recording recording = adapter.getItem(recordingIndex);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(recording.displayName);
        input.selectAll();
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = dp(20);
        container.addView(input, lp);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Rename recording")
                .setView(container)
                .setPositiveButton("Rename", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) { repo.renameRecording(recording.id, name); loadData(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onItemClicked(int recordingIndex) {
        adapter.toggleSelection(recordingIndex);
        updateSelectionCount();
    }

    @Override
    public void onFolderClicked(int folderIndex) {
        RecordingRepository.Folder folder = folders.get(folderIndex);
        currentFolderId   = folder.id;
        currentFolderName = folder.name;
        stopPlayer();
        currentlyPlayingPosition  = -1;
        currentlyPlayingRecording = null;
        topNavBar.setTitleText(folder.name);
        refreshDisplayedList();
    }

    @Override
    public void onRecordingDroppedOnFolder(long recordingId, int folderIndex) {
        RecordingRepository.Folder folder = folders.get(folderIndex);
        repo.moveRecording(recordingId, folder.id);
        loadData();
    }

    @Override
    public void onFolderMenuClicked(int folderIndex, View anchor) {
        RecordingRepository.Folder folder = folders.get(folderIndex);
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "Rename folder");
        popup.getMenu().add(0, 2, 1, "Delete folder");
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) showRenameFolderDialog(folder);
            else                       confirmDeleteFolder(folder);
            return true;
        });
        popup.show();
    }

    // ── Folder navigation ─────────────────────────────────────────────────────

    private void navigateToRoot() {
        currentFolderId   = -1;
        currentFolderName = null;
        topNavBar.setTitleText("Library");
        stopPlayer();
        currentlyPlayingPosition  = -1;
        currentlyPlayingRecording = null;
        loadData();
    }

    // ── Multi-select ──────────────────────────────────────────────────────────

    private void showLibraryMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 100, 0, isMultiSelectMode ? "Cancel selection" : "Select recordings");
        if (currentFolderId == -1)
            popup.getMenu().add(0, 104, 1, "New folder");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 100: if (isMultiSelectMode) exitMultiSelectMode(); else enterMultiSelectMode(); return true;
                case 104: showCreateFolderDialog(); return true;
            }
            return false;
        });
        popup.show();
    }

    private void enterMultiSelectMode() {
        isMultiSelectMode = true;
        adapter.enterMultiSelectMode();
        multiSelectBar.setVisibility(View.VISIBLE);
        updateSelectionCount();
    }

    private void exitMultiSelectMode() {
        isMultiSelectMode = false;
        adapter.exitMultiSelectMode();
        multiSelectBar.setVisibility(View.GONE);
    }

    private void updateSelectionCount() {
        int count = adapter.getSelectedCount();
        tvSelectionCount.setText(count == 0 ? "Tap items to select" : count + " selected");
        btnMoveSelected.setEnabled(count > 0);
        btnDeleteSelected.setEnabled(count > 0);
    }

    private void confirmDeleteSelected() {
        int count = adapter.getSelectedCount();
        if (count == 0) return;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete recordings")
                .setMessage("Delete " + count + " recording" + (count > 1 ? "s" : "") + "? This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (currentlyPlayingRecording != null
                            && adapter.getSelectedIds().contains(currentlyPlayingRecording.id)) {
                        stopPlayer();
                        currentlyPlayingPosition  = -1;
                        currentlyPlayingRecording = null;
                    }
                    for (long id : adapter.getSelectedIds()) repo.deleteRecording(id);
                    adapter.removeSelected();
                    exitMultiSelectMode();
                    loadData();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ── Folder dialogs ────────────────────────────────────────────────────────

    private void showCreateFolderDialog() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Folder name");
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = dp(20);
        container.addView(input, lp);
        new MaterialAlertDialogBuilder(this)
                .setTitle("New folder")
                .setView(container)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) { repo.createFolder(name); loadData(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showRenameFolderDialog(RecordingRepository.Folder folder) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(folder.name);
        input.selectAll();
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = dp(20);
        container.addView(input, lp);
        new MaterialAlertDialogBuilder(this)
                .setTitle("Rename folder")
                .setView(container)
                .setPositiveButton("Rename", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) { repo.renameFolder(folder.id, name); loadData(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmDeleteFolder(RecordingRepository.Folder folder) {
        int n = folder.recordingCount;
        String msg = n > 0
                ? "Delete \u201c" + folder.name + "\u201d and move its " + n + " recording" + (n > 1 ? "s" : "") + " back to root?"
                : "Delete folder \u201c" + folder.name + "\u201d?";
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete folder")
                .setMessage(msg)
                .setPositiveButton("Delete", (d, w) -> { repo.deleteFolder(folder.id); loadData(); })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showMoveToFolderDialog(List<Long> selectedIds) {
        if (selectedIds.isEmpty()) return;
        List<RecordingRepository.Folder> allFolders = repo.getAllFolders();

        if (allFolders.isEmpty() && currentFolderId == -1) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Move to folder")
                    .setMessage("No folders yet. Create one to organise recordings.")
                    .setPositiveButton("New folder", (d, w) -> showCreateFolderDialog())
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        // Use an array so lambdas below can call dismiss before the variable is returned
        final AlertDialog[] ref = {null};

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, dp(8), 0, dp(8));

        // ── Root section ─────────────────────────────────────────────────────
        layout.addView(buildDialogSectionHeader("Root"));
        TextView rootItem = buildDialogItem("No folder (move to root)");
        rootItem.setOnClickListener(v -> {
            for (long id : selectedIds) repo.moveRecording(id, -1L);
            exitMultiSelectMode();
            loadData();
            if (ref[0] != null) ref[0].dismiss();
        });
        layout.addView(rootItem);

        if (!allFolders.isEmpty()) {
            // ── Divider ──────────────────────────────────────────────────────
            View divider = new View(this);
            LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
            divLp.setMargins(dp(16), dp(8), dp(16), dp(8));
            divider.setLayoutParams(divLp);
            divider.setBackgroundColor(getColor(R.color.app_outline));
            layout.addView(divider);

            // ── Folders section ───────────────────────────────────────────────
            layout.addView(buildDialogSectionHeader("Folders"));
            for (RecordingRepository.Folder f : allFolders) {
                final long fid = f.id;
                TextView item = buildDialogItem(f.name);
                item.setOnClickListener(v -> {
                    for (long id : selectedIds) repo.moveRecording(id, fid);
                    exitMultiSelectMode();
                    loadData();
                    if (ref[0] != null) ref[0].dismiss();
                });
                layout.addView(item);
            }
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);

        int count = selectedIds.size();
        ref[0] = new MaterialAlertDialogBuilder(this)
                .setTitle("Move " + count + " recording" + (count > 1 ? "s" : "") + " to\u2026")
                .setView(scroll)
                .setNegativeButton("Cancel", null)
                .create();
        ref[0].show();
    }

    private TextView buildDialogSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase(Locale.getDefault()));
        tv.setTextColor(getColor(R.color.app_on_surface_variant));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(dp(24), dp(10), dp(24), dp(4));
        return tv;
    }

    private TextView buildDialogItem(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(getColor(R.color.app_on_surface));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setPadding(dp(24), dp(14), dp(24), dp(14));
        TypedValue outValue = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        tv.setBackgroundResource(outValue.resourceId);
        return tv;
    }

    // ── Sorting ───────────────────────────────────────────────────────────────

    private void showSortDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Sort by")
                .setSingleChoiceItems(SORT_LABELS, currentSort.ordinal(), (dialog, which) -> {
                    currentSort = SortOrder.values()[which];
                    refreshDisplayedList();
                    dialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void applySort() {
        Comparator<RecordingRepository.Recording> cmp;
        switch (currentSort) {
            case DATE_ASC:      cmp = Comparator.comparingLong(r -> r.createdAtMs); break;
            case NAME_ASC:      cmp = (a, b) -> a.displayName.compareToIgnoreCase(b.displayName); break;
            case NAME_DESC:     cmp = (a, b) -> b.displayName.compareToIgnoreCase(a.displayName); break;
            case DURATION_DESC: cmp = (a, b) -> Long.compare(b.durationMs, a.durationMs); break;
            case DURATION_ASC:  cmp = Comparator.comparingLong(r -> r.durationMs); break;
            case SIZE_DESC:     cmp = (a, b) -> Long.compare(fileSize(b.filePath), fileSize(a.filePath)); break;
            case SIZE_ASC:      cmp = Comparator.comparingLong(r -> fileSize(r.filePath)); break;
            default:            cmp = (a, b) -> Long.compare(b.createdAtMs, a.createdAtMs); break;
        }
        recordings.sort(cmp);
        if (currentlyPlayingRecording != null) {
            currentlyPlayingPosition = recordings.indexOf(currentlyPlayingRecording);
            if (currentlyPlayingPosition != -1 && adapter != null)
                adapter.setPlayingState(currentlyPlayingPosition, !isPaused);
        }
    }

    // ── Player bar ────────────────────────────────────────────────────────────

    private void showPlayerBar(RecordingRepository.Recording recording, boolean playing) {
        playerBar.setVisibility(View.VISIBLE);
        updateModeButton();
        tvPlayerName.setText(recording.displayName);
        updateQualityBadge(recording);
        int duration = 0;
        try { if (player != null) duration = player.getDuration(); } catch (Exception ignored) {}
        sbPlayerProgress.setMax(Math.max(duration, 1));
        sbPlayerProgress.setProgress(0);
        updatePlayerTimeText(0, duration);
        btnPlayerPlayPause.setImageResource(
                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        if (playing) playerProgressHandler.post(progressRunnable);
    }

    private void updateQualityBadge(RecordingRepository.Recording recording) {
        String q = recording.quality;
        if (q == null && recording.filePath != null) {
            if (recording.filePath.endsWith(".wav")) q = AppSettings.COMPRESSION_LOSSLESS;
            else if (recording.filePath.endsWith(".m4a")) q = AppSettings.COMPRESSION_HIGH;
        }
        if (q == null) { tvQualityBadge.setVisibility(View.GONE); return; }

        String label;
        int bgColor, textColor;
        switch (q) {
            case AppSettings.COMPRESSION_LOSSLESS:
                label = "LOSSLESS"; bgColor = 0xFFD4A520; textColor = 0xFF1A1200; break;
            case AppSettings.COMPRESSION_HIGH:
                label = "HIGH";     bgColor = 0xFFB0B0B8; textColor = 0xFF101010; break;
            case AppSettings.COMPRESSION_MEDIUM:
                label = "MED";      bgColor = 0xFFB06020; textColor = 0xFF1A0A00; break;
            case AppSettings.COMPRESSION_LOW:
                label = "LOW";      bgColor = 0xFF3A3050; textColor = 0xFFB0A8C8; break;
            default:
                tvQualityBadge.setVisibility(View.GONE); return;
        }

        float r = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f,
                getResources().getDisplayMetrics());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(r);
        bg.setColor(bgColor);
        tvQualityBadge.setBackground(bg);
        tvQualityBadge.setText(label);
        tvQualityBadge.setTextColor(textColor);
        tvQualityBadge.setVisibility(View.VISIBLE);
    }

    private void hidePlayerBar() {
        playerBar.setVisibility(View.GONE);
        playerProgressHandler.removeCallbacks(progressRunnable);
    }

    private void cyclePlaybackMode() {
        switch (playbackMode) {
            case SEQUENTIAL: playbackMode = PlaybackMode.SINGLE;   break;
            case SINGLE:     playbackMode = PlaybackMode.LOOP_ONE; break;
            case LOOP_ONE:   playbackMode = PlaybackMode.LOOP_ALL; break;
            default:         playbackMode = PlaybackMode.SEQUENTIAL; break;
        }
        updateModeButton();
    }

    private void updateModeButton() {
        if (btnPlayerMode == null) return;
        switch (playbackMode) {
            case SINGLE:
                btnPlayerMode.setText("1\u00d7");
                btnPlayerMode.setTextColor(getColor(R.color.app_secondary));
                break;
            case LOOP_ONE:
                btnPlayerMode.setText("\u21bb1");
                btnPlayerMode.setTextColor(getColor(R.color.app_primary));
                break;
            case LOOP_ALL:
                btnPlayerMode.setText("\u21bb");
                btnPlayerMode.setTextColor(getColor(R.color.app_primary));
                break;
            default: // SEQUENTIAL
                btnPlayerMode.setText("\u2192");
                btnPlayerMode.setTextColor(getColor(R.color.app_on_surface_variant));
                break;
        }
    }

    private void togglePlayerPlayPause() {
        if (player == null) return;
        try {
            if (player.isPlaying()) {
                player.pause(); isPaused = true;
                thereminMutedByUs = false;
                ThereminBackgroundAudioService.setThereminMuted(thereminWasMutedBeforePlayback);
                adapter.setPlayingState(currentlyPlayingPosition, false);
                btnPlayerPlayPause.setImageResource(android.R.drawable.ic_media_play);
                playerProgressHandler.removeCallbacks(progressRunnable);
            } else if (isPaused) {
                player.start(); isPaused = false;
                thereminMutedByUs = true;
                ThereminBackgroundAudioService.setThereminMuted(true);
                adapter.setPlayingState(currentlyPlayingPosition, true);
                btnPlayerPlayPause.setImageResource(android.R.drawable.ic_media_pause);
                playerProgressHandler.post(progressRunnable);
            }

        } catch (Exception ignored) {}
    }

    private void updatePlayerTimeText(int posMs, int totalMs) {
        tvPlayerTime.setText(formatTime(posMs) + " / " + formatTime(totalMs));
    }

    private String formatTime(int ms) {
        int secs = Math.max(0, ms) / 1000;
        return String.format(Locale.getDefault(), "%d:%02d", secs / 60, secs % 60);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private void stopPlayer() {
        playerProgressHandler.removeCallbacks(progressRunnable);
        if (player != null) {
            try { if (player.isPlaying()) player.stop(); } catch (IllegalStateException ignored) {}
            player.release();
            player = null;
        }
        // Only restore mute state if we were the ones who changed it, and restore
        // exactly what it was before — so a paused theremin stays paused.
        if (thereminMutedByUs) {
            thereminMutedByUs = false;
            ThereminBackgroundAudioService.setThereminMuted(thereminWasMutedBeforePlayback);
        }
        hidePlayerBar();
        if (adapter != null) adapter.clearPlayingState();
    }

    private void updateEmptyState() {
        boolean hasContent = !recordings.isEmpty() || (currentFolderId == -1 && !folders.isEmpty());
        if (!hasContent && currentFolderId != -1) {
            tvEmptyState.setText("This folder is empty.");
        } else {
            tvEmptyState.setText("No recordings yet. Head to the Play screen to record your first performance.");
        }
        tvEmptyState.setVisibility(hasContent ? View.GONE : View.VISIBLE);
        rvRecordings.setVisibility(hasContent ? View.VISIBLE : View.GONE);
    }

    private static long fileSize(String filePath) {
        if (filePath == null) return 0L;
        java.io.File f = new java.io.File(filePath);
        return f.exists() ? f.length() : 0L;
    }

    // ── Search ────────────────────────────────────────────────────────────────

    private void toggleSearchBar() {
        if (searchBarLayout.getVisibility() == View.VISIBLE) {
            searchBarLayout.setVisibility(View.GONE);
            searchQuery = "";
            etSearch.setText("");
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
            refreshDisplayedList();
        } else {
            searchBarLayout.setVisibility(View.VISIBLE);
            etSearch.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    // ── Filter / sort bottom sheet ────────────────────────────────────────────

    private void showFilterSortSheet() {
        BottomSheetDialog sheet = new BottomSheetDialog(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.app_surface));

        // Drag handle
        View handle = new View(this);
        handle.setBackgroundColor(getColor(R.color.app_outline));
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(40), dp(4));
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.topMargin    = dp(10);
        handleLp.bottomMargin = dp(4);
        root.addView(handle, handleLp);

        // Tab row
        int primary          = getColor(R.color.app_primary);
        int onSurfaceVariant = getColor(R.color.app_on_surface_variant);

        LinearLayout tabRow = new LinearLayout(this);
        tabRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView tabFilter = new TextView(this);
        tabFilter.setText("Filter");
        tabFilter.setGravity(Gravity.CENTER);
        tabFilter.setTextSize(15f);
        tabFilter.setPadding(0, dp(14), 0, dp(14));

        TextView tabSort = new TextView(this);
        tabSort.setText("Sort");
        tabSort.setGravity(Gravity.CENTER);
        tabSort.setTextSize(15f);
        tabSort.setPadding(0, dp(14), 0, dp(14));

        tabRow.addView(tabFilter, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tabRow.addView(tabSort,   new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(tabRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Indicator underline
        LinearLayout indRow = new LinearLayout(this);
        indRow.setOrientation(LinearLayout.HORIZONTAL);
        View indFilter = new View(this);
        View indSort   = new View(this);
        indRow.addView(indFilter, new LinearLayout.LayoutParams(0, dp(3), 1f));
        indRow.addView(indSort,   new LinearLayout.LayoutParams(0, dp(3), 1f));
        root.addView(indRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(3)));

        // Separator
        View sep = new View(this);
        sep.setBackgroundColor(getColor(R.color.app_outline));
        root.addView(sep, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        // Content panels
        LinearLayout filterPanel = buildFilterPanel();
        LinearLayout sortPanel   = buildSortPanel();
        sortPanel.setVisibility(View.GONE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout scrollContent = new LinearLayout(this);
        scrollContent.setOrientation(LinearLayout.VERTICAL);
        scrollContent.addView(filterPanel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scrollContent.addView(sortPanel,   new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        scroll.addView(scrollContent);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Tab switching
        tabFilter.setOnClickListener(v -> {
            tabFilter.setTextColor(primary);
            tabFilter.setTypeface(null, Typeface.BOLD);
            tabSort.setTextColor(onSurfaceVariant);
            tabSort.setTypeface(null, Typeface.NORMAL);
            indFilter.setBackgroundColor(primary);
            indSort.setBackground(null);
            filterPanel.setVisibility(View.VISIBLE);
            sortPanel.setVisibility(View.GONE);
        });
        tabSort.setOnClickListener(v -> {
            tabSort.setTextColor(primary);
            tabSort.setTypeface(null, Typeface.BOLD);
            tabFilter.setTextColor(onSurfaceVariant);
            tabFilter.setTypeface(null, Typeface.NORMAL);
            indSort.setBackgroundColor(primary);
            indFilter.setBackground(null);
            sortPanel.setVisibility(View.VISIBLE);
            filterPanel.setVisibility(View.GONE);
        });

        // Initial: Filter tab active
        tabFilter.setTextColor(primary);
        tabFilter.setTypeface(null, Typeface.BOLD);
        tabSort.setTextColor(onSurfaceVariant);
        indFilter.setBackgroundColor(primary);

        sheet.setContentView(root);
        sheet.show();
    }

    private LinearLayout buildFilterPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(32));

        panel.addView(sheetSectionLabel("Duration"));
        RadioGroup durGroup = new RadioGroup(this);
        String[]         durLabels = {"All", "Short  (< 30 s)", "Medium  (30 s\u20132 min)", "Long  (> 2 min)"};
        DurationFilter[] durVals   = {DurationFilter.ALL, DurationFilter.SHORT, DurationFilter.MEDIUM, DurationFilter.LONG};
        for (int i = 0; i < durLabels.length; i++)
            durGroup.addView(makeRadio(durLabels[i], i + 1000, durationFilter == durVals[i]));
        durGroup.setOnCheckedChangeListener((g, id) -> {
            int idx = id - 1000;
            if (idx >= 0 && idx < durVals.length) { durationFilter = durVals[idx]; refreshDisplayedList(); }
        });
        panel.addView(durGroup);

        panel.addView(sheetSectionLabel("Date added"));
        RadioGroup dateGroup = new RadioGroup(this);
        String[]     dateLabels = {"All time", "Today", "This week", "This month"};
        DateFilter[] dateVals   = {DateFilter.ALL, DateFilter.TODAY, DateFilter.THIS_WEEK, DateFilter.THIS_MONTH};
        for (int i = 0; i < dateLabels.length; i++)
            dateGroup.addView(makeRadio(dateLabels[i], i + 2000, dateFilter == dateVals[i]));
        dateGroup.setOnCheckedChangeListener((g, id) -> {
            int idx = id - 2000;
            if (idx >= 0 && idx < dateVals.length) { dateFilter = dateVals[idx]; refreshDisplayedList(); }
        });
        panel.addView(dateGroup);

        return panel;
    }

    private LinearLayout buildSortPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), dp(32));

        panel.addView(sheetSectionLabel("Sort by"));
        RadioGroup group = new RadioGroup(this);
        SortOrder[] vals = SortOrder.values();
        for (int i = 0; i < SORT_LABELS.length; i++)
            group.addView(makeRadio(SORT_LABELS[i], i + 3000, currentSort == vals[i]));
        group.setOnCheckedChangeListener((g, id) -> {
            int idx = id - 3000;
            if (idx >= 0 && idx < vals.length) { currentSort = vals[idx]; refreshDisplayedList(); }
        });
        panel.addView(group);

        return panel;
    }

    private TextView sheetSectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text.toUpperCase(Locale.getDefault()));
        tv.setTextColor(getColor(R.color.app_on_surface_variant));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(16), 0, dp(6));
        return tv;
    }

    private RadioButton makeRadio(String label, int id, boolean checked) {
        RadioButton rb = new RadioButton(this);
        rb.setText(label);
        rb.setId(id);
        rb.setChecked(checked);
        rb.setTextColor(getColor(R.color.app_on_surface));
        rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        rb.setPadding(dp(4), dp(8), dp(4), dp(8));
        return rb;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
