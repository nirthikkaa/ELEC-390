package com.example.thereminglovestest2;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.SeekBar;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.thereminglovestest2.databinding.ActivityBeatMakerBinding;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Beat Maker — 16-step drum/bass sequencer.
 *
 * The grid is rendered by StepGridView (Canvas-based).
 *
 * DrumEngine only produces PCM via mixInto() — it has no AudioTrack of its own.
 * This activity runs its own audio output thread that continuously calls
 * previewEngine.mixInto() and writes to a local stereo AudioTrack, so drum,
 * piano, and audition sounds are heard without needing ThereminAudioEngine.
 */
public class BeatMakerActivity extends AppCompatActivity
        implements StepGridView.Listener {

    static final String EXTRA_APPLIED    = "beat_maker_applied";
    static final String EXTRA_SLOT_INDEX = "beat_slot_index";

    private static final String PREFS_FILE = "theremin_prefs";
    private static final int    POLL_MS    = 50;
    private static final String KEY_KEYBOARD_SYNTH_MODE = "keyboard_synth_mode";
    private static final String[] NOTE_NAMES = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    private static final String[] PIANO_MODE_LABELS = {"NOTE", "MAJOR", "MINOR", "PENTA", "JAZZ"};

    // slotIndex 0 = default slot (legacy keys), 1-7 = named slots
    private int slotIndex = 0;

    private String keyActive()          { return slotIndex == 0 ? "beat_maker_custom_active" : "beat_slot_" + slotIndex + "_saved"; }
    private static final String KEY_MASTER_BPM = "beat_master_bpm"; // shared across all slots
    private String keyRow(int r)        { return slotIndex == 0 ? "beat_maker_row_" + r      : "beat_slot_" + slotIndex + "_row_" + r; }

    private static final int SAMPLE_RATE  = 48000;
    private static final int BUFFER_FRAMES = 1024; // ~21ms, matches ThereminAudioEngine

    // Maps grid row index → DrumEngine sound index (must match StepGridView.ROW_NAMES order)
    static final int[] ROW_SOUNDS = {
        DrumEngine.SND_KICK,    // 0
        DrumEngine.SND_SNARE,   // 1
        DrumEngine.SND_HIHAT_C, // 2
        DrumEngine.SND_HIHAT_O, // 3
        DrumEngine.SND_CRASH,   // 4
        DrumEngine.SND_CLAP,    // 5
        DrumEngine.SND_BASS_E2, // 6
        DrumEngine.SND_BASS_A2, // 7
        DrumEngine.SND_BASS_D3, // 8
        DrumEngine.SND_TOM_HI,  // 9
        DrumEngine.SND_TOM_LOW, // 10
        DrumEngine.SND_RIM,     // 11
        DrumEngine.SND_SHAKER,  // 12
    };

    private ActivityBeatMakerBinding binding;
    private volatile DrumEngine previewEngine;

    // Audio output thread — pulls PCM from previewEngine and writes to AudioTrack
    private AudioTrack         audioTrack;
    private Thread             audioThread;
    private volatile boolean   audioRunning = false;

    private boolean isPlaying  = false;
    private int     currentBpm = 120;
    private int     keyboardSynthMode = DrumEngine.PIANO_SYNTH_KEYS;
    private int     pianoModeIdx = 0;
    private final int[] pianoSteps = new int[StepGridView.NUM_STEPS];
    private int selectedPianoStep = 0;
    private final Set<Integer> activeMelodyNotes = new HashSet<>();

    private final Handler  pollHandler  = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::pollPlayhead;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityBeatMakerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        slotIndex = getIntent().getIntExtra(EXTRA_SLOT_INDEX, 0);

        binding.topNavBar.setTitleText("Beat Maker");
        binding.topNavBar.setBackButtonVisible(true);
        binding.topNavBar.setOverflowButtonVisible(false);
        applyBottomInset();

        // Wire UI immediately — engine arrives asynchronously on a background thread
        binding.stepGrid.setListener(this);
        Arrays.fill(pianoSteps, -1);
        loadPatternFromPrefs();
        wireBpmSlider();
        wireTransportControls();
        wireKeyboardControls();
        wireSlotButtons();

        // Start audio output first (plays silence until engine is ready)
        startAudioOutput();

        // Construct DrumEngine on a background thread — synthesizeSounds() is CPU-heavy
        new Thread(() -> {
            DrumEngine engine = new DrumEngine(BeatMakerActivity.this);
            engine.start();
            previewEngine = engine; // volatile write — audio thread sees it immediately
            runOnUiThread(() -> {
                // Apply BPM that may have been set before engine was ready
                engine.setBpm(currentBpm);
                engine.setPianoSynthMode(keyboardSynthMode);
                configurePreviewMix(engine);
                applyBeatMakerMode();
                pushPatternToEngine();
            });
        }, "DrumEngineInit").start();
    }

    private void applyBottomInset() {
        View bottomContainer = binding.bottomApplyContainer;
        int left = bottomContainer.getPaddingLeft();
        int top = bottomContainer.getPaddingTop();
        int right = bottomContainer.getPaddingRight();
        int bottom = bottomContainer.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(bottomContainer, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(left, top, right, bottom + sys.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(bottomContainer);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollHandler.removeCallbacks(pollRunnable);
        stopAudioOutput();
        if (previewEngine != null) {
            previewEngine.release();
            previewEngine = null;
        }
    }

    // ── Audio output thread ───────────────────────────────────────────────────

    private void startAudioOutput() {
        int minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        // Buffer size is expressed in bytes: 16-bit PCM * 2 stereo channels.
        int bufBytes = Math.max(minBuf, BUFFER_FRAMES * 4);

        AudioTrack.Builder builder = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        }
        audioTrack = builder.build();
        audioTrack.play();

        audioRunning = true;
        audioThread  = new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            // Keep DrumEngine's preview mix mono and widen it only at the AudioTrack write stage.
            short[] monoBuf = new short[BUFFER_FRAMES];
            short[] stereoBuf = new short[BUFFER_FRAMES * 2];
            AudioTrack track = audioTrack;
            float hpPrevIn = 0f;
            float hpPrevOut = 0f;
            while (audioRunning && track != null) {
                Arrays.fill(monoBuf, (short) 0);
                DrumEngine e = previewEngine; // re-read volatile each iteration
                if (e != null) e.mixInto(monoBuf, BUFFER_FRAMES);
                for (int i = 0; i < BUFFER_FRAMES; i++) {
                    float x = monoBuf[i] / 32768f;
                    // Very light high-pass to remove sub/DC mud from layered bass hits.
                    float hp = x - hpPrevIn + 0.995f * hpPrevOut;
                    hpPrevIn = x;
                    hpPrevOut = hp;
                    float mastered = softLimit(hp * 1.05f);
                    short sample = (short) (mastered * Short.MAX_VALUE);
                    monoBuf[i] = sample;
                    int stereoIndex = i * 2;
                    stereoBuf[stereoIndex] = sample;
                    stereoBuf[stereoIndex + 1] = sample;
                }
                track.write(stereoBuf, 0, stereoBuf.length);
            }
        }, "BeatMakerAudio");
        audioThread.start();
    }

    private void stopAudioOutput() {
        audioRunning = false;
        if (audioThread != null) {
            try { audioThread.join(600); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        if (audioTrack != null) {
            try {
                audioTrack.stop();
                audioTrack.release();
            } catch (IllegalStateException ignored) {}
            audioTrack = null;
        }
    }

    // ── StepGridView.Listener ─────────────────────────────────────────────────

    @Override
    public void onStepChanged(int row, int step, boolean active) {
        pushPatternToEngine();
    }

    @Override
    public void onRowLabelTapped(int row) {
        // auditSound() calls triggerVoice() which puts a hit in the voice pool;
        // the audio thread picks it up on the next buffer fill
        if (previewEngine != null) previewEngine.auditSound(ROW_SOUNDS[row]);
    }

    // ── Slot selector ─────────────────────────────────────────────────────────

    private void wireSlotButtons() {
        com.google.android.material.button.MaterialButton[] btns = {
            binding.btnSlot1, binding.btnSlot2, binding.btnSlot3, binding.btnSlot4,
            binding.btnSlot5, binding.btnSlot6, binding.btnSlot7, binding.btnSlot8
        };
        for (int i = 0; i < 8; i++) {
            final int slot = i;
            btns[i].setOnClickListener(v -> switchToSlot(slot));
        }
        updateSlotHighlights();
    }

    private void switchToSlot(int newSlot) {
        if (newSlot == slotIndex) return;
        savePatternToPrefs();           // auto-save current slot before leaving
        slotIndex = newSlot;
        loadPatternFromPrefs();         // loads grid + currentBpm for new slot
        binding.sbBeatMakerBpm.setProgress(currentBpm - 60);
        binding.tvBeatMakerBpm.setText(String.valueOf(currentBpm));
        if (previewEngine != null) previewEngine.setBpm(currentBpm);
        selectedPianoStep = 0;
        binding.beatMakerPianoSteps.setSelectedStep(selectedPianoStep);
        pushPatternToEngine();
        updateSlotHighlights();
    }

    private void updateSlotHighlights() {
        com.google.android.material.button.MaterialButton[] btns = {
            binding.btnSlot1, binding.btnSlot2, binding.btnSlot3, binding.btnSlot4,
            binding.btnSlot5, binding.btnSlot6, binding.btnSlot7, binding.btnSlot8
        };
        for (int i = 0; i < 8; i++) {
            boolean isActive = (i == slotIndex);
            boolean isSaved  = isSlotSaved(i);
            btns[i].setText("BEAT " + (i + 1) + (isSaved ? " \u25CF" : ""));
            if (isActive) {
                btns[i].setTextColor(0xFF00FF9D);
                btns[i].setStrokeColor(ColorStateList.valueOf(0xFF00FF9D));
            } else if (isSaved) {
                btns[i].setTextColor(0xFF00AA66);
                btns[i].setStrokeColor(ColorStateList.valueOf(0xFF005533));
            } else {
                btns[i].setTextColor(0xFF555777);
                btns[i].setStrokeColor(ColorStateList.valueOf(0xFF333355));
            }
        }
    }

    private boolean isSlotSaved(int slot) {
        SharedPreferences prefs = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);
        String key = slot == 0 ? "beat_maker_custom_active" : "beat_slot_" + slot + "_saved";
        return prefs.getBoolean(key, false);
    }

    // ── Transport ─────────────────────────────────────────────────────────────

    private void wireTransportControls() {
        binding.btnPlayStop.setOnClickListener(v -> {
            if (isPlaying) stopPlayback();
            else           startPlayback();
        });

        binding.btnClearAll.setOnClickListener(v ->
            new AlertDialog.Builder(this)
                .setTitle("Clear All Steps")
                .setMessage("Remove all active steps?")
                .setPositiveButton("Clear", (d, w) -> {
                    binding.stepGrid.clearAll();
                    pushPatternToEngine();
                })
                .setNegativeButton("Cancel", null)
                .show()
        );

        binding.btnApply.setOnClickListener(v -> applyAndReturn());
    }

    private void wireKeyboardControls() {
        SharedPreferences prefs = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);
        keyboardSynthMode = DrumEngine.clampPianoSynthMode(
                prefs.getInt(KEY_KEYBOARD_SYNTH_MODE, keyboardSynthMode));
        updateKeyboardSynthButton();
        updateKeyboardModeButton();
        binding.beatMakerPianoSteps.setNotes(pianoSteps);
        binding.beatMakerPianoSteps.setSelectedStep(selectedPianoStep);
        binding.beatMakerPianoSteps.setListener(step -> {
            if (selectedPianoStep == step && pianoSteps[step] >= 0) {
                pianoSteps[step] = -1;
                binding.tvBeatMakerKeyNote.setText("cleared " + (step + 1));
                pushPatternToEngine();
            } else {
                selectedPianoStep = step;
                binding.tvBeatMakerKeyNote.setText("step " + (step + 1));
            }
            binding.beatMakerPianoSteps.setNotes(pianoSteps);
            binding.beatMakerPianoSteps.setSelectedStep(selectedPianoStep);
        });

        binding.btnBeatMakerSynth.setOnClickListener(v -> {
            keyboardSynthMode = DrumEngine.clampPianoSynthMode(keyboardSynthMode + 1);
            applyKeyboardSynthMode();
        });
        binding.btnBeatMakerSynth.setOnLongClickListener(v -> {
            keyboardSynthMode = DrumEngine.clampPianoSynthMode(keyboardSynthMode - 1);
            applyKeyboardSynthMode();
            return true;
        });
        binding.btnBeatMakerKeyMode.setOnClickListener(v -> {
            pianoModeIdx = (pianoModeIdx + 1) % DrumEngine.ARPEGGIO_PATTERNS.length;
            clearBeatMakerMelody();
            applyBeatMakerMode();
        });
        binding.beatMakerKeyboard.setNoteListener(midiNote -> {
            auditionKeyboardInput(midiNote);
            writePianoStep(midiNote);
        });
    }

    private void startPlayback() {
        if (previewEngine == null) return;
        isPlaying = true;
        pushPatternToEngine();
        previewEngine.setEnabled(true);
        previewEngine.setBassEnabled(true);
        binding.btnPlayStop.setText("STOP");
        binding.btnPlayStop.setTextColor(0xFFFF4444);
        pollHandler.post(pollRunnable);
    }

    private void stopPlayback() {
        isPlaying = false;
        if (previewEngine != null) {
            previewEngine.setEnabled(false);
            previewEngine.setBassEnabled(false);
        }
        clearBeatMakerMelody();
        pollHandler.removeCallbacks(pollRunnable);
        binding.stepGrid.clearPlayhead();
        binding.beatMakerPianoSteps.setPlayheadStep(-1);
        binding.btnPlayStop.setText("PLAY");
        binding.btnPlayStop.setTextColor(0xFF00FF9D);
    }

    private void pollPlayhead() {
        if (!isPlaying || previewEngine == null) return;
        int step = previewEngine.getCurrentStep16();
        binding.stepGrid.setPlayheadStep(step);
        binding.beatMakerPianoSteps.setPlayheadStep(step);
        pollHandler.postDelayed(pollRunnable, POLL_MS);
    }

    // ── BPM ───────────────────────────────────────────────────────────────────

    /** Apply a new BPM value: clamp, update slider + label + engine. */
    private void applyBpm(int bpm) {
        currentBpm = Math.max(60, Math.min(200, bpm));
        binding.sbBeatMakerBpm.setProgress(currentBpm - 60);
        binding.tvBeatMakerBpm.setText(String.valueOf(currentBpm));
        if (previewEngine != null) previewEngine.setBpm(currentBpm);
    }

    private void wireBpmSlider() {
        binding.sbBeatMakerBpm.setMax(140);
        binding.sbBeatMakerBpm.setProgress(currentBpm - 60);
        binding.tvBeatMakerBpm.setText(String.valueOf(currentBpm));

        binding.sbBeatMakerBpm.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(SeekBar sb, int p, boolean user) {
                        currentBpm = 60 + p;
                        binding.tvBeatMakerBpm.setText(String.valueOf(currentBpm));
                        if (previewEngine != null) previewEngine.setBpm(currentBpm);
                    }
                    @Override public void onStartTrackingTouch(SeekBar sb) {}
                    @Override public void onStopTrackingTouch(SeekBar sb) {}
                });

        // −/+ buttons: tap = ±1 BPM, long-press = ±5 BPM
        binding.btnBpmMinus.setOnClickListener(v -> applyBpm(currentBpm - 1));
        binding.btnBpmPlus.setOnClickListener(v -> applyBpm(currentBpm + 1));
        binding.btnBpmMinus.setOnLongClickListener(v -> { applyBpm(currentBpm - 5); return true; });
        binding.btnBpmPlus.setOnLongClickListener(v -> { applyBpm(currentBpm + 5); return true; });
    }

    // ── Engine ────────────────────────────────────────────────────────────────

    private void pushPatternToEngine() {
        if (previewEngine != null)
            previewEngine.setCustomPattern(binding.stepGrid.getSteps(), pianoSteps);
    }

    private void configurePreviewMix(DrumEngine engine) {
        engine.setKickVolume(0.90f);
        engine.setSnareVolume(0.82f);
        engine.setHihatVolume(0.52f);
        engine.setBassVolume(0.68f);
        engine.setTrackVolume(DrumEngine.SND_CLAP, 0.58f);
        engine.setTrackVolume(DrumEngine.SND_CRASH, 0.48f);
        engine.setTrackVolume(DrumEngine.SND_TOM_HI, 0.52f);
        engine.setTrackVolume(DrumEngine.SND_TOM_LOW, 0.52f);
        engine.setTrackVolume(DrumEngine.SND_RIM, 0.50f);
        engine.setTrackVolume(DrumEngine.SND_SHAKER, 0.45f);
        engine.setPianoVolume(0.52f);
    }

    private void applyKeyboardSynthMode() {
        keyboardSynthMode = DrumEngine.clampPianoSynthMode(keyboardSynthMode);
        if (previewEngine != null) previewEngine.setPianoSynthMode(keyboardSynthMode);
        getSharedPreferences(PREFS_FILE, MODE_PRIVATE).edit()
                .putInt(KEY_KEYBOARD_SYNTH_MODE, keyboardSynthMode)
                .apply();
        updateKeyboardSynthButton();
    }

    private void updateKeyboardSynthButton() {
        binding.btnBeatMakerSynth.setText(DrumEngine.PIANO_SYNTH_LABELS[keyboardSynthMode]);
        int color = keyboardSynthMode == DrumEngine.PIANO_SYNTH_KEYS ? 0xFF7EB8FF : 0xFF00CCFF;
        binding.btnBeatMakerSynth.setTextColor(color);
        binding.btnBeatMakerSynth.setStrokeColor(ColorStateList.valueOf(color));
    }

    private static String formatMidiNote(int midiNote) {
        int semitone = Math.floorMod(midiNote, 12);
        int octave = (midiNote / 12) - 1;
        return NOTE_NAMES[semitone] + octave;
    }

    private void updateKeyboardModeButton() {
        binding.btnBeatMakerKeyMode.setText(PIANO_MODE_LABELS[pianoModeIdx]);
        int color = pianoModeIdx == 0 ? 0xFF7EB8FF : 0xFF00CCFF;
        binding.btnBeatMakerKeyMode.setTextColor(color);
        binding.btnBeatMakerKeyMode.setStrokeColor(ColorStateList.valueOf(color));
    }

    private void applyBeatMakerMode() {
        updateKeyboardModeButton();
        binding.beatMakerKeyboard.setActiveMidiNotes(activeMelodyNotes);
    }

    private void clearBeatMakerMelody() {
        activeMelodyNotes.clear();
        if (previewEngine != null) previewEngine.clearMelodyRoot();
        binding.beatMakerKeyboard.setActiveMidiNotes(activeMelodyNotes);
    }

    private void auditionKeyboardInput(int midiNote) {
        if (previewEngine == null) return;
        if (pianoModeIdx == 0) {
            previewEngine.triggerPianoKey(midiNote);
        } else {
            int[] arp = DrumEngine.ARPEGGIO_PATTERNS[pianoModeIdx];
            if (activeMelodyNotes.contains(midiNote)) {
                activeMelodyNotes.remove(midiNote);
                previewEngine.removeMelodyRoot(midiNote);
            } else {
                activeMelodyNotes.add(midiNote);
                previewEngine.addMelodyRoot(midiNote, arp);
            }
            binding.beatMakerKeyboard.setActiveMidiNotes(activeMelodyNotes);
        }
    }

    private void writePianoStep(int midiNote) {
        int targetStep = isPlaying && previewEngine != null
                ? previewEngine.getCurrentStep16()
                : selectedPianoStep;
        targetStep = Math.max(0, Math.min(StepGridView.NUM_STEPS - 1, targetStep));
        if (pianoSteps[targetStep] == midiNote) {
            pianoSteps[targetStep] = -1;
            binding.tvBeatMakerKeyNote.setText("removed " + formatMidiNote(midiNote) + " @ " + (targetStep + 1));
        } else {
            pianoSteps[targetStep] = midiNote;
            binding.tvBeatMakerKeyNote.setText(formatMidiNote(midiNote) + " @ " + (targetStep + 1));
        }
        selectedPianoStep = targetStep;
        binding.beatMakerPianoSteps.setNotes(pianoSteps);
        binding.beatMakerPianoSteps.setSelectedStep(selectedPianoStep);
        pushPatternToEngine();
    }

    // ── Apply & persist ───────────────────────────────────────────────────────

    private void applyAndReturn() {
        stopPlayback();
        savePatternToPrefs();
        Intent result = new Intent();
        result.putExtra(EXTRA_APPLIED, true);
        result.putExtra(EXTRA_SLOT_INDEX, slotIndex);
        setResult(RESULT_OK, result);
        finish();
    }

    private void savePatternToPrefs() {
        SharedPreferences.Editor ed =
                getSharedPreferences(PREFS_FILE, MODE_PRIVATE).edit();
        boolean[][] grid = binding.stepGrid.getSteps();
        for (int r = 0; r < StepGridView.NUM_ROWS; r++)
            ed.putString(keyRow(r), rowToString(grid[r]));
        for (int step = 0; step < StepGridView.NUM_STEPS; step++)
            ed.putInt((slotIndex == 0 ? "beat_maker" : "beat_slot_" + slotIndex) + "_piano_" + step, pianoSteps[step]);
        ed.putInt(KEY_MASTER_BPM, currentBpm); // master — shared across all slots
        ed.putBoolean(keyActive(), true);
        ed.apply();
        updateSlotHighlights();
    }

    private void loadPatternFromPrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS_FILE, MODE_PRIVATE);
        boolean[][] grid = new boolean[StepGridView.NUM_ROWS][StepGridView.NUM_STEPS];
        for (int r = 0; r < StepGridView.NUM_ROWS; r++) {
            String v = prefs.getString(keyRow(r), null);
            if (v != null) grid[r] = rowFromString(v);
        }
        Arrays.fill(pianoSteps, -1);
        String prefix = slotIndex == 0 ? "beat_maker" : "beat_slot_" + slotIndex;
        for (int step = 0; step < StepGridView.NUM_STEPS; step++)
            pianoSteps[step] = prefs.getInt(prefix + "_piano_" + step, -1);
        currentBpm = prefs.getInt(KEY_MASTER_BPM, 120); // master BPM, same for all slots
        binding.stepGrid.setSteps(grid);
        binding.beatMakerPianoSteps.setNotes(pianoSteps);
    }

    // ── Persistence helpers ───────────────────────────────────────────────────

    private static String rowToString(boolean[] row) {
        StringBuilder sb = new StringBuilder(31);
        for (int i = 0; i < StepGridView.NUM_STEPS; i++) {
            if (i > 0) sb.append(',');
            sb.append(row[i] ? '1' : '0');
        }
        return sb.toString();
    }

    private static boolean[] rowFromString(String s) {
        boolean[] row = new boolean[StepGridView.NUM_STEPS];
        if (s == null || s.isEmpty()) return row;
        String[] parts = s.split(",");
        for (int i = 0; i < Math.min(StepGridView.NUM_STEPS, parts.length); i++)
            row[i] = "1".equals(parts[i].trim());
        return row;
    }

    private static float softLimit(float x) {
        float sign = Math.signum(x);
        float abs = Math.abs(x);
        if (abs <= 0.72f) return x;
        float compressed = 0.72f + (abs - 0.72f) * 0.22f;
        return sign * Math.min(0.92f, compressed);
    }
}
