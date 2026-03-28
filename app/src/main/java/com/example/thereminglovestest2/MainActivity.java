package com.example.thereminglovestest2;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.widget.FrameLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.thereminglovestest2.databinding.ActivityMainBinding;

import android.view.MotionEvent;
import android.view.Gravity;
import android.graphics.Typeface;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Play screen.
 *
 * BLE stays in BleSessionManager.
 * Audio stays in ThereminAudioEngine / ThereminBackgroundAudioService.
 * The Play screen mostly does three things:
 * 1) read the latest BLE snapshot,
 * 2) map glove angles to frequency/volume,
 * 3) update the visible UI.
 */
public class MainActivity extends AppCompatActivity {
    public static final String EXTRA_AUTOSTART_AUDIO =
            "com.example.thereminglovestest2.extra.AUTOSTART_AUDIO";

    private static final long UI_TICK_MS = 50; // 20fps UI refresh — smooth enough, less main-thread load
    private static final int LOG_MAX_LINES = 200;
    private static final long LOG_FLUSH_MIN_INTERVAL_MS = 600;
    private static final int REQUEST_RECORD_AUDIO = 4109;
    private static final String KEY_BEAT_MASTER_BPM = "beat_master_bpm";
    private static final String KEY_KEYBOARD_SYNTH_MODE = "keyboard_synth_mode";

    private static final String[] TONE_CYCLE = {
        AppSettings.TONE_THEREMIN, AppSettings.TONE_VIOLIN,   AppSettings.TONE_GUITAR,
        AppSettings.TONE_FLUTE,    AppSettings.TONE_TRUMPET,  AppSettings.TONE_SAW,
        AppSettings.TONE_SQUARE,   AppSettings.TONE_TRIANGLE, AppSettings.TONE_PULSE,
        AppSettings.TONE_ORGAN,    AppSettings.TONE_STRING,   AppSettings.TONE_BELL,
        AppSettings.TONE_PAD
    };

    private ActivityMainBinding binding;
    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final NavigationUtils.Poller uiTicker =
            new NavigationUtils.Poller(UI_TICK_MS, this::refreshUiFast);
    private final PlayMappingState play = new PlayMappingState();

    private ThereminAudioEngine audioEngine;
    // Sprint 3: Local drum engine, active only when the foreground audio engine owns playback.
    // When the background service takes over, drums are handled by the service's own DrumEngine.
    private DrumEngine drumEngine;
    private SettingsStore settingsRepo;
    private RecordingManager recordingManager;
    private RecordingRepository recordingRepository;
    private final Handler recordingTimerHandler = new Handler(Looper.getMainLooper());


    // Sprint 3 fields
    private int  octaveShift          = 0;
    private int drumBpm = 120;
    private static final int NUM_BEAT_SLOTS = 8;
    private final boolean[] activeSlots = new boolean[NUM_BEAT_SLOTS];
    // 0=NOTE, 1=MAJOR, 2=MINOR, 3=PENTA, 4=JAZZ (cycles on button tap)
    private int  pianoModeIdx         = 0;
    private int  pianoSynthMode       = DrumEngine.PIANO_SYNTH_KEYS;
    // Active arpeggio roots in melody mode (multi-key harmonic selection)
    private final Set<Integer> activeMelodyNotes = new HashSet<>();
    private long lastAnimatedBassHitMs = 0L; // tracks which bass hit we've already animated
    private int  lastDrumStep = -1;           // tracks drum step to detect new beats for visualizer

    private ActivityResultLauncher<Intent> beatMakerLauncher;

    private boolean bgAudioEnabled = true;
    private boolean isRecordingUiActive;
    private boolean playUiVisible;
    private boolean pendingAutoStartAudio;
    private boolean waitingForServiceToStop;
    private boolean performanceModeActive = false;

    // Beat Maker drag-to-open state
    private float      bmDragStartX = Float.NaN;
    private FrameLayout bmPreview;   // overlay panel that slides in from the right
    private boolean lastAudioRunningState = false; // cache for updateAudioStatusText change-check
    private int lastGlovePitchColor = 0; // cache for setGloveStatus change-guard
    private int lastGloveVolColor   = 0;
    private String lastNavTitle = "";    // cache for setTitleText change-guard
    private String lastFreqText = "";   // cache for tvFreqDisplay change-guard
    private String lastVolText  = "";   // cache for tvVolDisplay change-guard
    private String lastToneText = "";   // cache for tvToneValue change-guard
    private long lastLogFlushMs;
    private long recordingStartElapsedMs;
    private ObjectAnimator recordBlinkAnimator;
    private int baseRootScrollTopPadding;

    private final Runnable recordingTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isRecordingUiActive) return;
            long elapsedMs = SystemClock.elapsedRealtime() - recordingStartElapsedMs;
            binding.tvRecordingTimer.setText(formatRecordingDuration(elapsedMs));
            recordingTimerHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.topNavBar.setBackButtonVisible(false);
        binding.topNavBar.setOverflowButtonVisible(false);
        binding.topNavBar.setTitleText("Play");

        BleSessionManager.initialize(getApplicationContext());
        audioEngine = new ThereminAudioEngine();
        // Sprint 3: Create and start the local drum engine for foreground playback.
        drumEngine = new DrumEngine(this);
        drumEngine.start();
        audioEngine.setDrumEngine(drumEngine);
        recordingManager = new RecordingManager(this);
        recordingRepository = new RecordingRepository(this);
        recordingManager.setAudioEngine(audioEngine);
        setupRecordingCallbacks();
        ensureRecordAudioPermission();

        beatMakerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null && data.getBooleanExtra(BeatMakerActivity.EXTRA_APPLIED, false)) {
                            // Pattern saved to prefs — refresh highlights so the dot appears,
                            // but do NOT auto-activate the slot; user must tap the button to engage.
                            updatePresetSlotHighlights();
                        }
                    }
                });

        consumeIntent(getIntent());
        loadBgAudioPref();
        play.refreshFreqRangeLimit(this);
        recomputeMappedOutputs();
        wireButtons();
        baseRootScrollTopPadding = binding.rootScroll.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootScroll, (v, insets) -> {
            int sideInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).left;
            int rightInset = insets.getInsets(WindowInsetsCompat.Type.systemBars()).right;
            // TopNavBarView handles its own camera/status-bar insets; scroll content needs no extra top gap.
            v.setPadding(sideInset, baseRootScrollTopPadding, rightInset, v.getPaddingBottom());
            return insets;
        });
        ViewCompat.requestApplyInsets(binding.rootScroll);

        appendLogSafe("Play opened");
        appendLogSafe("BG audio: " + onOff(bgAudioEnabled));
        updateAudioStatusText();
        updateBleButtonText();
    }

    @Override
    protected void onStart() {
        super.onStart();
        uiTicker.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Reset any drag translation left over if the user cancelled a swipe or returned from BeatMaker.
        binding.cardVisualizer.setTranslationX(0);
        onVisible(); // single call here; onStart no longer duplicates it
        android.content.SharedPreferences prefs = getSharedPreferences("theremin_prefs", MODE_PRIVATE);
        // Stage mode is never restored on launch — always start in normal view.
        applyPerformanceMode(performanceModeActive);
        int densityLevel = prefs.getInt("pixel_density_level", 3);
        binding.thereminVisualizerView.setPixelDensityLevel(densityLevel);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && performanceModeActive) {
            applySystemUiMode(true);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntent(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        playUiVisible = false;
        if (!bgAudioEnabled && !isChangingConfigurations() && isAudioRunning()) {
            audioEngine.stop();
            appendLogSafe("onPause -> audio stopped (background off)");
        } else if (bgAudioEnabled && isAudioRunning()) {
            appendLogSafe("onPause -> audio kept running in background");
        }
        maybeMoveAudioToBackgroundService();
    }

    @Override
    protected void onStop() {
        super.onStop();
        playUiVisible = false;
        uiTicker.stop();
        if (!isChangingConfigurations() && !bgAudioEnabled && isAudioRunning()) audioEngine.stop();
        if (!isChangingConfigurations()) pendingAutoStartAudio = false;
    }

    @Override
    protected void onDestroy() {
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        stopRecordBlink();
        if (recordingManager != null) recordingManager.release();
        // Sprint 3: Release local drum engine SoundPool resources.
        if (drumEngine != null) { drumEngine.release(); drumEngine = null; }
        super.onDestroy();
        uiTicker.stop();
        if (audioEngine != null && (!bgAudioEnabled || isFinishing())) audioEngine.shutdown();
    }

    private void onVisible() {
        playUiVisible = true;
        requestAudioBackFromBackgroundService();
        refreshPlayUiState();
    }

    private void refreshPlayUiState() {
        loadBgAudioPref();
        play.refreshFreqRangeLimit(this);
        reloadMappingSettingsFromRepository();
        syncAudioTargetsFromSharedBleState();
        updateAudioStatusText();
        updateBleButtonText();
    }

    private SettingsStore store() {
        if (settingsRepo == null) settingsRepo = new SettingsStore(this);
        return settingsRepo;
    }

    private boolean isAudioRunning() {
        return audioEngine != null && audioEngine.isRunning();
    }

    private boolean isServiceOwningAudio() {
        return bgAudioEnabled && ThereminBackgroundAudioService.isServiceActive();
    }

    private boolean isAnyAudioRunning() {
        if (isServiceOwningAudio()) return !ThereminBackgroundAudioService.isThereminMuted();
        return isAudioRunning();
    }

    private void loadBgAudioPref() {
        bgAudioEnabled = SettingsStore.isBgAudioEnabled(this);
    }

    private void saveBgAudioPref() {
        SettingsStore.setBgAudioEnabled(this, bgAudioEnabled);
    }

    private void maybeMoveAudioToBackgroundService() {
        if (!bgAudioEnabled || isChangingConfigurations()) return;
        if (ThereminBackgroundAudioService.isServiceActive()) {
            waitingForServiceToStop = false;
            return;
        }
        if (!isAudioRunning()) return;
        try {
            pushAudioTargetsToEngine();
            ThereminBackgroundAudioService.startIfNeeded(this);
            ThereminBackgroundAudioService.setRecordingManager(recordingManager);
            audioEngine.stop();
            waitingForServiceToStop = false;
            appendLogSafe("Play hidden -> audio handed to background service");
        } catch (Exception e) {
            appendLogSafe("Background service failed: " + e.getClass().getSimpleName());
        }
    }

    private void requestAudioBackFromBackgroundService() {
        if (bgAudioEnabled || !ThereminBackgroundAudioService.isServiceActive()) {
            waitingForServiceToStop = false;
            return;
        }
        waitingForServiceToStop = true;
        ThereminBackgroundAudioService.stopIfRunning(this);
        appendLogSafe("Play visible -> reclaiming audio from background service");
    }

    private void finishAudioTakebackIfReady() {
        if (!waitingForServiceToStop || ThereminBackgroundAudioService.isServiceActive()) return;
        waitingForServiceToStop = false;
        if (isAudioRunning()) return;
        ThereminBackgroundAudioService.setRecordingManager(null);
        recordingManager.setAudioEngine(audioEngine);
        pushAudioTargetsToEngine();
        audioEngine.start();
        appendLogSafe("Play visible -> audio returned from background service");
    }

    private void wireButtons() {
        binding.btnDisconnectAll.setVisibility(View.GONE);

        binding.btnAudioStart.setOnClickListener(v -> toggleAudio());
        binding.btnRecord.setOnClickListener(v -> onRecordButtonPressed());
        binding.btnPerformanceMode.setOnClickListener(v -> togglePerformanceMode());
        binding.btnExitStage.setOnClickListener(v -> togglePerformanceMode());

        binding.toneKnob.setToneSequence(TONE_CYCLE);
        binding.toneKnob.setOnToneStepListener(this::cycleTone);

        // Swipe left on the visualizer → drag-synchronized open of Beat Maker
        buildBeatMakerPreview();
        binding.thereminVisualizerView.setOnTouchListener((v, event) -> {
            int screenW = binding.getRoot().getWidth();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    bmDragStartX = event.getRawX();
                    bmPreview.setVisibility(android.view.View.VISIBLE);
                    bmPreview.setTranslationX(screenW);
                    binding.cardVisualizer.setTranslationX(0);
                    break;
                case MotionEvent.ACTION_MOVE: {
                    float drag = Math.max(0f, bmDragStartX - event.getRawX());
                    bmPreview.setTranslationX(screenW - drag);
                    binding.cardVisualizer.setTranslationX(-drag * 0.4f); // slight parallax
                    break;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float drag = Float.isNaN(bmDragStartX) ? 0 : Math.max(0f, bmDragStartX - event.getRawX());
                    boolean commit = event.getActionMasked() == MotionEvent.ACTION_UP
                            && drag > screenW * 0.25f;
                    if (commit) {
                        launchBeatMakerFromSwipe();
                    } else {
                        binding.cardVisualizer.animate().translationX(0).setDuration(180).start();
                        bmPreview.animate().translationX(screenW).setDuration(180)
                                .withEndAction(() -> bmPreview.setVisibility(android.view.View.GONE))
                                .start();
                    }
                    bmDragStartX = Float.NaN;
                    break;
                }
            }
            return true;
        });

        wireSpring3Controls();
    }

    // Sprint 3: scale lock, octave shift, effects
    private void wireSpring3Controls() {
        // Make effect buttons checkable (toggle behaviour)
        binding.btnReverb.setCheckable(true);
        binding.btnDelay.setCheckable(true);
        binding.btnDistortion.setCheckable(true);

        // Scale buttons — push to foreground engine AND background service
        View.OnClickListener scaleClick = v -> {
            String scale;
            if      (v.getId() == R.id.btnScaleMajor)      scale = AppSettings.SCALE_MAJOR;
            else if (v.getId() == R.id.btnScaleMinor)      scale = AppSettings.SCALE_MINOR;
            else if (v.getId() == R.id.btnScalePentatonic) scale = AppSettings.SCALE_PENTATONIC;
            else                                           scale = AppSettings.SCALE_CHROMATIC;
            if (audioEngine != null) audioEngine.setActiveScale(scale);
            ThereminBackgroundAudioService.setActiveScale(scale);
            saveScaleSetting(scale);
            updateScaleButtons(scale);
            appendLogSafe("Scale -> " + scale);
        };
        binding.btnScaleChromatic.setOnClickListener(scaleClick);
        binding.btnScaleMajor.setOnClickListener(scaleClick);
        binding.btnScaleMinor.setOnClickListener(scaleClick);
        binding.btnScalePentatonic.setOnClickListener(scaleClick);

        // Octave shift buttons
        binding.btnOctaveDown.setOnClickListener(v -> {
            if (octaveShift > -2) {
                octaveShift--;
                play.setOctaveShift(octaveShift);
                ThereminBackgroundAudioService.setOctaveShift(octaveShift);
                updateOctaveLabel();
                AppSettings s = store().load(); s.octaveShift = octaveShift; store().save(s);
                appendLogSafe("Octave -> " + octaveShift);
            }
        });
        binding.btnOctaveUp.setOnClickListener(v -> {
            if (octaveShift < 2) {
                octaveShift++;
                play.setOctaveShift(octaveShift);
                ThereminBackgroundAudioService.setOctaveShift(octaveShift);
                updateOctaveLabel();
                AppSettings s = store().load(); s.octaveShift = octaveShift; store().save(s);
                appendLogSafe("Octave -> " + octaveShift);
            }
        });

        // Beat Maker — open step sequencer for the first active slot (or 0)
        binding.btnBeatMaker.setOnClickListener(v -> {
            int editSlot = 0;
            for (int i = 0; i < NUM_BEAT_SLOTS; i++) { if (activeSlots[i]) { editSlot = i; break; } }
            Intent intent = new Intent(this, BeatMakerActivity.class);
            intent.putExtra(BeatMakerActivity.EXTRA_SLOT_INDEX, editSlot);
            beatMakerLauncher.launch(intent);
        });

        // Piano mode cycle button: NOTE → MAJOR → MINOR → PENTA → JAZZ → NOTE …
        binding.btnPianoMode.setOnClickListener(v -> {
            pianoModeIdx = (pianoModeIdx + 1) % DrumEngine.ARPEGGIO_PATTERNS.length;
            clearMelodyOnAllEngines(); // clear selection whenever mode changes
            updatePianoModeButton();
            // Stop any running arpeggio when switching to NOTE mode
            if (pianoModeIdx == 0) clearMelodyOnAllEngines();
        });
        binding.btnPianoSynth.setOnClickListener(v -> {
            pianoSynthMode = DrumEngine.clampPianoSynthMode(pianoSynthMode + 1);
            applyKeyboardSynthMode();
        });
        binding.btnPianoSynth.setOnLongClickListener(v -> {
            pianoSynthMode = DrumEngine.clampPianoSynthMode(pianoSynthMode - 1);
            applyKeyboardSynthMode();
            return true;
        });

        // Beat Maker preset slots — tap to toggle (layer), long-press to edit in Beat Maker
        com.google.android.material.button.MaterialButton[] presetBtns = {
            binding.btnBeatPreset1, binding.btnBeatPreset2,
            binding.btnBeatPreset3, binding.btnBeatPreset4,
            binding.btnBeatPreset5, binding.btnBeatPreset6,
            binding.btnBeatPreset7, binding.btnBeatPreset8
        };
        for (int i = 0; i < presetBtns.length; i++) {
            final int slotIdx = i;
            presetBtns[i].setOnClickListener(v -> togglePresetSlot(slotIdx));
            presetBtns[i].setOnLongClickListener(v -> {
                Intent intent = new Intent(this, BeatMakerActivity.class);
                intent.putExtra(BeatMakerActivity.EXTRA_SLOT_INDEX, slotIdx);
                beatMakerLauncher.launch(intent);
                return true;
            });
        }
        updatePresetSlotHighlights();

        // Volume: SYNTH (theremin master gain) — Material Slider with 10% snap points
        binding.sbSynthVol.setValue(70f);
        binding.sbSynthVol.addOnChangeListener((slider, value, fromUser) -> applyMixerVol());
        // Volume: BEATS (drum/bass gain) — Material Slider with 10% snap points
        binding.sbBeatVol.setValue(100f);
        binding.sbBeatVol.addOnChangeListener((slider, value, fromUser) -> applyBeatVol());

        // BPM adjuster — slider + ±1/±5 buttons
        binding.sbDrumBpm.setMax(140); // range 60–200
        binding.sbDrumBpm.setProgress(drumBpm - 60);
        binding.tvDrumBpm.setText(String.valueOf(drumBpm));
        binding.sbDrumBpm.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean user) {
                if (!user) return;
                drumBpm = 60 + p;
                applyBpm();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        binding.btnDrumBpmMinus.setOnClickListener(v -> {
            drumBpm = Math.max(60, drumBpm - 1);
            applyBpm();
        });
        binding.btnDrumBpmPlus.setOnClickListener(v -> {
            drumBpm = Math.min(200, drumBpm + 1);
            applyBpm();
        });
        binding.btnDrumBpmMinus.setOnLongClickListener(v -> {
            drumBpm = Math.max(60, drumBpm - 5);
            applyBpm();
            return true;
        });
        binding.btnDrumBpmPlus.setOnLongClickListener(v -> {
            drumBpm = Math.min(200, drumBpm + 5);
            applyBpm();
            return true;
        });
        binding.sbPianoBpm.setMax(140);
        binding.sbPianoBpm.setProgress(drumBpm - 60);
        binding.tvPianoBpm.setText(String.valueOf(drumBpm));
        binding.sbPianoBpm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                if (!user) return;
                drumBpm = 60 + p;
                applyBpm();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        binding.btnPianoBpmMinus.setOnClickListener(v -> adjustBpm(-1));
        binding.btnPianoBpmPlus.setOnClickListener(v -> adjustBpm(1));
        binding.btnPianoBpmMinus.setOnLongClickListener(v -> {
            adjustBpm(-5);
            return true;
        });
        binding.btnPianoBpmPlus.setOnLongClickListener(v -> {
            adjustBpm(5);
            return true;
        });

        // Effects — reverb (push to foreground engine AND background service)
        binding.btnReverb.addOnCheckedChangeListener((btn, on) -> {
            applyFxLed(binding.btnReverb, on, 0xFFCC44FF, on ? "ON" : "OFF");
            if (audioEngine != null) audioEngine.setReverbEnabled(on);
            ThereminBackgroundAudioService.setReverbEnabled(on);
            saveEffectSettings();
        });
        binding.sbReverbMix.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                binding.tvReverbVal.setText(p + "%");
                float mix = p / 100f;
                if (audioEngine != null) audioEngine.setReverbMix(mix);
                ThereminBackgroundAudioService.setReverbMix(mix);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveEffectSettings(); }
        });

        // Effects — delay
        binding.btnDelay.addOnCheckedChangeListener((btn, on) -> {
            applyFxLed(binding.btnDelay, on, 0xFF00E5FF, on ? "ON" : "OFF");
            if (audioEngine != null) audioEngine.setDelayEnabled(on);
            ThereminBackgroundAudioService.setDelayEnabled(on);
            saveEffectSettings();
        });
        binding.sbDelayFeedback.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                binding.tvDelayVal.setText(p + "%");
                float fb = p / 90f * 0.9f;
                if (audioEngine != null) audioEngine.setDelayFeedback(fb);
                ThereminBackgroundAudioService.setDelayFeedback(fb);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveEffectSettings(); }
        });

        // Effects — distortion
        binding.btnDistortion.addOnCheckedChangeListener((btn, on) -> {
            applyFxLed(binding.btnDistortion, on, 0xFFFF6600, on ? "ON" : "OFF");
            if (audioEngine != null) audioEngine.setDistortionEnabled(on);
            ThereminBackgroundAudioService.setDistortionEnabled(on);
            saveEffectSettings();
        });
        binding.sbDistortionGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) {
                binding.tvDistortionVal.setText(String.format(Locale.US, "%.0f\u00d7", 1f + p / 100f * 9f));
                float gain = 1f + p / 100f * 9f;
                if (audioEngine != null) audioEngine.setDistortionGain(gain);
                ThereminBackgroundAudioService.setDistortionGain(gain);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) { saveEffectSettings(); }
        });

        // Piano keyboard
        binding.pianoKeyboard.setNoteListener(midiNote -> {
            DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
            DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
            if (pianoModeIdx == 0) {
                // NOTE mode: one-shot pluck, no persistent selection
                if (fg != null) fg.triggerPianoKey(midiNote);
                if (bg != null) bg.triggerPianoKey(midiNote);
                String[] NAMES = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
                binding.tvPianoNote.setText(NAMES[(midiNote - 48) % 12] + ((midiNote - 48) / 12 + 3));
            } else {
                // MELODY mode: tap toggles this root in/out of the harmonic chord set
                int[] arp = DrumEngine.ARPEGGIO_PATTERNS[pianoModeIdx];
                if (activeMelodyNotes.contains(midiNote)) {
                    // Deselect: remove this root
                    activeMelodyNotes.remove(midiNote);
                    if (fg != null) fg.removeMelodyRoot(midiNote);
                    if (bg != null) bg.removeMelodyRoot(midiNote);
                } else {
                    // Select: add this root to the chord
                    activeMelodyNotes.add(midiNote);
                    if (fg != null) fg.addMelodyRoot(midiNote, arp);
                    if (bg != null) bg.addMelodyRoot(midiNote, arp);
                }
                updatePianoKeyHighlights();
            }
        });
    }

    private void updateScaleButtons(String scale) {
        com.google.android.material.button.MaterialButton[] scaleBtns = {
            binding.btnScaleChromatic, binding.btnScaleMajor,
            binding.btnScaleMinor, binding.btnScalePentatonic
        };
        String[] scales = {
            AppSettings.SCALE_CHROMATIC, AppSettings.SCALE_MAJOR,
            AppSettings.SCALE_MINOR, AppSettings.SCALE_PENTATONIC
        };
        for (int i = 0; i < scaleBtns.length; i++) {
            boolean active = scales[i].equals(scale);
            scaleBtns[i].setTextColor(active ? 0xFF00FF9D : 0xFF555777);
            scaleBtns[i].setBackgroundColor(active ? 0x1400FF9D : 0x00000000);
        }
    }

    private void updateOctaveLabel() {
        String label = "Oct " + (octaveShift >= 0 ? "+" + octaveShift : String.valueOf(octaveShift));
        binding.tvOctaveLabel.setText(label);
    }

    private void applyBpm() {
        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (fg != null) fg.setBpm(drumBpm);
        DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
        if (bg != null) bg.setBpm(drumBpm);
        ThereminBackgroundAudioService.setDrumBpm(drumBpm);
        getSharedPreferences("theremin_prefs", MODE_PRIVATE).edit()
                .putInt(KEY_BEAT_MASTER_BPM, drumBpm)
                .apply();
        binding.tvDrumBpm.setText(String.valueOf(drumBpm));
        binding.sbDrumBpm.setProgress(drumBpm - 60);
        binding.tvPianoBpm.setText(String.valueOf(drumBpm));
        binding.sbPianoBpm.setProgress(drumBpm - 60);
    }

    private void applyClapTone(float tone) {
        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (fg != null) fg.setClapTone(tone);
        ThereminBackgroundAudioService.setClapTone(tone);
        DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
        if (bg != null) bg.setClapTone(tone);
    }

    /**
     * Toggle a preset slot on/off and push the merged grid of all active slots to the engines.
     * Multiple slots can be active simultaneously — their grids are OR-ed together.
     */
    private void togglePresetSlot(int slotIdx) {
        SharedPreferences prefs = getSharedPreferences("theremin_prefs", MODE_PRIVATE);
        String prefix = slotIdx == 0 ? "beat_maker" : "beat_slot_" + slotIdx;
        boolean saved = slotIdx == 0
                ? prefs.getBoolean(prefix + "_custom_active", false)
                : prefs.getBoolean(prefix + "_saved", false);
        if (!saved && !activeSlots[slotIdx]) {
            Toast.makeText(this,
                    "Slot " + (slotIdx + 1) + " is empty — long-press to program it",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        activeSlots[slotIdx] = !activeSlots[slotIdx];
        pushMergedPattern();
        updatePresetSlotHighlights();
    }

    /**
     * Merge all active slot grids (OR) and push the result to both drum engines.
     * If no slots are active, clears the custom pattern (reverts to built-in).
     */
    private void pushMergedPattern() {
        SharedPreferences prefs = getSharedPreferences("theremin_prefs", MODE_PRIVATE);
        boolean[][] merged = new boolean[StepGridView.NUM_ROWS][16];
        int[] mergedPiano = new int[16];
        java.util.Arrays.fill(mergedPiano, -1);
        boolean anyActive = false;
        int firstActiveBpm = drumBpm;
        boolean foundBpm = false;

        for (int slot = 0; slot < NUM_BEAT_SLOTS; slot++) {
            if (!activeSlots[slot]) continue;
            String prefix = slot == 0 ? "beat_maker" : "beat_slot_" + slot;
            boolean saved = slot == 0
                    ? prefs.getBoolean(prefix + "_custom_active", false)
                    : prefs.getBoolean(prefix + "_saved", false);
            if (!saved) { activeSlots[slot] = false; continue; }
            anyActive = true;
            if (!foundBpm) {
                firstActiveBpm = prefs.getInt(KEY_BEAT_MASTER_BPM, drumBpm);
                foundBpm = true;
            }
            for (int r = 0; r < StepGridView.NUM_ROWS; r++) {
                String val = prefs.getString(prefix + "_row_" + r, null);
                if (val == null) continue;
                String[] parts = val.split(",");
                for (int s = 0; s < Math.min(16, parts.length); s++)
                    if ("1".equals(parts[s].trim())) merged[r][s] = true;
            }
            for (int step = 0; step < 16; step++) {
                if (mergedPiano[step] >= 0) continue;
                int midi = prefs.getInt(prefix + "_piano_" + step, -1);
                if (midi >= 0) mergedPiano[step] = midi;
            }
        }

        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
        if (anyActive) {
            if (fg != null) { fg.setCustomPattern(merged, mergedPiano); fg.setEnabled(true); fg.setBassEnabled(true); }
            if (bg != null) { bg.setCustomPattern(merged, mergedPiano); bg.setEnabled(true); bg.setBassEnabled(true); }
            // Cache custom pattern and BPM so the background service applies them if started later.
            ThereminBackgroundAudioService.setCustomPattern(merged, mergedPiano);
            ThereminBackgroundAudioService.setDrumEnabled(true);
            ThereminBackgroundAudioService.setBassEnabled(true);
            if (firstActiveBpm != drumBpm) { drumBpm = firstActiveBpm; applyBpm(); }
            else { ThereminBackgroundAudioService.setDrumBpm(drumBpm); }
        } else {
            if (fg != null) { fg.clearCustomPattern(); fg.setEnabled(false); fg.setBassEnabled(false); }
            if (bg != null) { bg.clearCustomPattern(); bg.setEnabled(false); bg.setBassEnabled(false); }
            ThereminBackgroundAudioService.setCustomPattern(null);
            ThereminBackgroundAudioService.setDrumEnabled(false);
            ThereminBackgroundAudioService.setBassEnabled(false);
        }
    }

    /** Refresh all 8 preset slot buttons to reflect active/saved/empty state. */
    private void updatePresetSlotHighlights() {
        android.content.res.ColorStateList activeBg =
                android.content.res.ColorStateList.valueOf(0x3300FF9D);
        android.content.res.ColorStateList clearBg =
                android.content.res.ColorStateList.valueOf(0x00000000);
        com.google.android.material.button.MaterialButton[] presetBtns = {
            binding.btnBeatPreset1, binding.btnBeatPreset2,
            binding.btnBeatPreset3, binding.btnBeatPreset4,
            binding.btnBeatPreset5, binding.btnBeatPreset6,
            binding.btnBeatPreset7, binding.btnBeatPreset8
        };
        SharedPreferences prefs = getSharedPreferences("theremin_prefs", MODE_PRIVATE);
        for (int i = 0; i < presetBtns.length; i++) {
            String prefix = i == 0 ? "beat_maker" : "beat_slot_" + i;
            boolean saved  = i == 0
                    ? prefs.getBoolean(prefix + "_custom_active", false)
                    : prefs.getBoolean(prefix + "_saved", false);
            boolean isActive = activeSlots[i];
            presetBtns[i].setText(saved ? "● " + (i + 1) : String.valueOf(i + 1));
            presetBtns[i].setTextColor(isActive ? 0xFF00FF9D : (saved ? 0xFF888AAA : 0xFF444466));
            presetBtns[i].setStrokeColor(isActive
                    ? android.content.res.ColorStateList.valueOf(0xFF00FF9D)
                    : android.content.res.ColorStateList.valueOf(saved ? 0xFF555577 : 0xFF333355));
            presetBtns[i].setBackgroundTintList(isActive ? activeBg : clearBg);
        }
    }

    private static final String[] PIANO_MODE_LABELS = {"NOTE", "MAJOR", "MINOR", "PENTA", "JAZZ"};

    private void updatePianoModeButton() {
        boolean melodic = pianoModeIdx > 0;
        binding.btnPianoMode.setText(PIANO_MODE_LABELS[pianoModeIdx]);
        binding.btnPianoMode.setTextColor(melodic ? 0xFF00CCFF : 0xFF888AAA);
        if (!melodic) binding.tvPianoNote.setText("tap a key");
    }

    private void updatePianoSynthButton() {
        binding.btnPianoSynth.setText(DrumEngine.PIANO_SYNTH_LABELS[pianoSynthMode]);
        int color = pianoSynthMode == DrumEngine.PIANO_SYNTH_KEYS ? 0xFF7EB8FF : 0xFF00CCFF;
        binding.btnPianoSynth.setTextColor(color);
        binding.btnPianoSynth.setStrokeColor(
                android.content.res.ColorStateList.valueOf(color));
    }

    private void applyKeyboardSynthMode() {
        pianoSynthMode = DrumEngine.clampPianoSynthMode(pianoSynthMode);
        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (fg != null) fg.setPianoSynthMode(pianoSynthMode);
        DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
        if (bg != null) bg.setPianoSynthMode(pianoSynthMode);
        ThereminBackgroundAudioService.setKeyboardSynthMode(pianoSynthMode);
        getSharedPreferences("theremin_prefs", MODE_PRIVATE).edit()
                .putInt(KEY_KEYBOARD_SYNTH_MODE, pianoSynthMode)
                .apply();
        updatePianoSynthButton();
    }

    private void adjustBpm(int delta) {
        drumBpm = Math.max(60, Math.min(200, drumBpm + delta));
        applyBpm();
    }

    private void clearMelodyOnAllEngines() {
        activeMelodyNotes.clear();
        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (fg != null) fg.clearMelodyRoot();
        DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
        if (bg != null) bg.clearMelodyRoot();
        updatePianoKeyHighlights();
    }

    private void updatePianoKeyHighlights() {
        binding.pianoKeyboard.setActiveMidiNotes(activeMelodyNotes);
        if (activeMelodyNotes.isEmpty()) {
            binding.tvPianoNote.setText("tap a key");
        } else {
            String[] NAMES = {"C","C#","D","D#","E","F","F#","G","G#","A","A#","B"};
            StringBuilder sb = new StringBuilder();
            for (int midi : activeMelodyNotes) {
                if (sb.length() > 0) sb.append('+');
                sb.append(NAMES[(midi - 48) % 12]).append((midi - 48) / 12 + 3);
            }
            binding.tvPianoNote.setText(sb);
        }
    }

    private void applyMixerVol() {
        float synthGain = binding.sbSynthVol.getValue() / 100f;
        if (audioEngine != null) audioEngine.setMixGain(synthGain);
        ThereminBackgroundAudioService.setMixGain(synthGain);
    }

    private void applyBeatVol() {
        float beatGain = binding.sbBeatVol.getValue() / 100f;
        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (fg != null) fg.setDrumGain(beatGain);
        ThereminBackgroundAudioService.setDrumGain(beatGain);
    }

    private void saveEffectSettings() {
        AppSettings s = store().load();
        s.reverbEnabled     = binding.btnReverb.isChecked();
        s.reverbMix         = binding.sbReverbMix.getProgress() / 100f;
        s.delayEnabled      = binding.btnDelay.isChecked();
        s.delayFeedback     = binding.sbDelayFeedback.getProgress() / 90f * 0.9f;
        s.delayMix          = 0.4f;
        s.distortionEnabled = binding.btnDistortion.isChecked();
        s.distortionGain    = 1f + binding.sbDistortionGain.getProgress() / 100f * 9f;
        store().save(s);
    }

    private void saveScaleSetting(String scale) {
        AppSettings s = store().load();
        s.activeScale = scale;
        store().save(s);
    }

    private void loadSpring3Settings(AppSettings s) {
        String scale = s.activeScale != null ? s.activeScale : AppSettings.SCALE_CHROMATIC;
        updateScaleButtons(scale);
        if (audioEngine != null) audioEngine.setActiveScale(scale);
        ThereminBackgroundAudioService.setActiveScale(scale);

        octaveShift = s.octaveShift;
        play.setOctaveShift(octaveShift);
        updateOctaveLabel();
        ThereminBackgroundAudioService.setOctaveShift(octaveShift);

        // Effects UI state — set progress first so listeners update value labels + engines
        int reverbProg = (int)(s.reverbMix * 100);
        int delayProg  = (int)(s.delayFeedback / 0.9f * 90);
        int distProg   = (int)((s.distortionGain - 1f) / 9f * 100);
        binding.sbReverbMix.setProgress(reverbProg);
        binding.sbDelayFeedback.setProgress(delayProg);
        binding.sbDistortionGain.setProgress(distProg);
        binding.tvReverbVal.setText(reverbProg + "%");
        binding.tvDelayVal.setText(delayProg + "%");
        binding.tvDistortionVal.setText(String.format(Locale.US, "%.0f\u00d7", s.distortionGain));
        binding.btnReverb.setChecked(s.reverbEnabled);
        binding.btnDelay.setChecked(s.delayEnabled);
        binding.btnDistortion.setChecked(s.distortionEnabled);
        applyFxLed(binding.btnReverb,     s.reverbEnabled,     0xFFCC44FF, s.reverbEnabled     ? "ON" : "OFF");
        applyFxLed(binding.btnDelay,      s.delayEnabled,      0xFF00E5FF, s.delayEnabled      ? "ON" : "OFF");
        applyFxLed(binding.btnDistortion, s.distortionEnabled, 0xFFFF6600, s.distortionEnabled ? "ON" : "OFF");
        updatePresetSlotHighlights();
        if (audioEngine != null) {
            audioEngine.setReverbEnabled(s.reverbEnabled);
            audioEngine.setReverbMix(s.reverbMix);
            audioEngine.setDelayEnabled(s.delayEnabled);
            audioEngine.setDelayFeedback(s.delayFeedback);
            audioEngine.setDelayMix(s.delayMix);
            audioEngine.setDistortionEnabled(s.distortionEnabled);
            audioEngine.setDistortionGain(s.distortionGain);
        }
        ThereminBackgroundAudioService.setReverbEnabled(s.reverbEnabled);
        ThereminBackgroundAudioService.setReverbMix(s.reverbMix);
        ThereminBackgroundAudioService.setDelayEnabled(s.delayEnabled);
        ThereminBackgroundAudioService.setDelayFeedback(s.delayFeedback);
        ThereminBackgroundAudioService.setDelayMix(s.delayMix);
        ThereminBackgroundAudioService.setDistortionEnabled(s.distortionEnabled);
        ThereminBackgroundAudioService.setDistortionGain(s.distortionGain);
    }

    private void setupRecordingCallbacks() {
        recordingManager.setListener(new RecordingManager.RecordingListener() {
            @Override
            public void onRecordingStarted() {
                runOnUiThread(() -> {
                    if (!isRecordingUiActive) startRecordingUi();
                });
            }

            @Override
            public void onRecordingStopped(String filePath, long durationMs) {
                runOnUiThread(() -> handleSavedRecording(filePath, durationMs));
            }

            @Override
            public void onRecordingError(String error) {
                runOnUiThread(() -> {
                    stopRecordingUi();
                    toastSafe(error == null || error.trim().isEmpty()
                            ? getString(R.string.recording_unavailable)
                            : error);
                    appendLogSafe("Recording error: " + error);
                });
            }
        });
    }

    private void ensureRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
        }
    }

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void onRecordButtonPressed() {
        if (isRecordingUiActive) {
            recordingManager.stopRecording();
            stopRecordingUi();
            // Toast shown later in handleSavedRecording() only if file is valid
            appendLogSafe("Recording stopped");
            return;
        }

        if (!hasRecordAudioPermission()) {
            ensureRecordAudioPermission();
            if (!hasRecordAudioPermission()) {
                toastSafe(getString(R.string.recording_unavailable));
                return;
            }
        }

        // Block recording if the theremin isn't playing
        if (!isAnyAudioRunning()) {
            toastSafe("Press Play first to start recording");
            return;
        }

        // Wire PCM tap to whichever engine is actually producing audio right now.
        if (isServiceOwningAudio()) {
            ThereminBackgroundAudioService.setRecordingManager(recordingManager);
            appendLogSafe("Recording: tapped background service engine");
        } else {
            recordingManager.setAudioEngine(audioEngine);
        }

        recordingManager.startRecording();
        startRecordingUi();
        appendLogSafe("Recording started");
    }

    private void startRecordingUi() {
        isRecordingUiActive = true;
        recordingStartElapsedMs = SystemClock.elapsedRealtime();
        binding.tvRecordLabel.setText("Stop");
        // Swap circle icon → square icon
        binding.btnRecord.setIconResource(R.drawable.ic_stop_recording);
        binding.tvRecordingTimer.setText(getString(R.string.recording_timer_zero));
        binding.tvRecordingTimer.setVisibility(View.VISIBLE);
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        recordingTimerHandler.postDelayed(recordingTimerRunnable, 1000);
        startRecordBlink();
    }

    private void stopRecordingUi() {
        isRecordingUiActive = false;
        recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        stopRecordBlink();
        if (binding == null) return;
        binding.tvRecordLabel.setText("Record");
        // Swap square icon → circle icon
        binding.btnRecord.setIconResource(R.drawable.ic_record);
        binding.tvRecordingTimer.setText(getString(R.string.recording_timer_zero));
        binding.tvRecordingTimer.setVisibility(View.GONE);
    }

    private void startRecordBlink() {
        stopRecordBlink();
        recordBlinkAnimator = ObjectAnimator.ofFloat(binding.btnRecord, "alpha", 1f, 0.3f);
        recordBlinkAnimator.setDuration(600);
        recordBlinkAnimator.setRepeatCount(ObjectAnimator.INFINITE);
        recordBlinkAnimator.setRepeatMode(ObjectAnimator.REVERSE);
        recordBlinkAnimator.start();
    }

    private void stopRecordBlink() {
        if (recordBlinkAnimator != null) {
            recordBlinkAnimator.cancel();
            recordBlinkAnimator = null;
        }
        if (binding != null) binding.btnRecord.setAlpha(1f);
    }

    private void handleSavedRecording(String filePath, long durationMs) {
        if (filePath == null || filePath.trim().isEmpty()) {
            toastSafe(getString(R.string.recording_failed_empty));
            appendLogSafe("Recording failed: empty file path");
            return;
        }

        File file = new File(filePath);
        if (!file.exists() || file.length() == 0) {
            toastSafe(getString(R.string.recording_failed_empty));
            appendLogSafe("Recording failed: file missing or empty");
            return;
        }

        String defaultName = "Recording " + new SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
                .format(new Date());

        if (!SettingsStore.isRenameDialogEnabled(this)) {
            commitRecording(filePath, defaultName, durationMs);
            return;
        }

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(defaultName);
        input.selectAll();

        int margin = (int) (20 * getResources().getDisplayMetrics().density);
        FrameLayout container = new FrameLayout(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = margin;
        container.addView(input, lp);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Name your recording")
                .setView(container)
                .setCancelable(false)
                .setPositiveButton("Save", (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = defaultName;
                    commitRecording(filePath, name, durationMs);
                })
                .setNegativeButton("Rename later", (d, w) ->
                        commitRecording(filePath, defaultName, durationMs))
                .show();
    }

    private void commitRecording(String filePath, String name, long durationMs) {
        // Derive quality from file extension (.wav = lossless) or from the current setting.
        String quality = filePath != null && filePath.endsWith(".wav")
                ? AppSettings.COMPRESSION_LOSSLESS
                : SettingsStore.getAudioCompression(this);
        String exportedPath = null;
        try {
            exportedPath = RecordingExportManager.exportRecording(this, new File(filePath), name);
        } catch (Exception e) {
            appendLogSafe("Recording export failed: " + e.getClass().getSimpleName());
        }
        recordingRepository.saveRecording(filePath, name, durationMs, quality, exportedPath);
        toastSafe(exportedPath != null
                ? "Recording saved to Music/" + RecordingExportManager.EXPORT_FOLDER
                : getString(R.string.recording_saved));
        appendLogSafe("Recording saved: " + name + (exportedPath != null ? " (exported)" : ""));
    }

    private String formatRecordingDuration(long elapsedMs) {
        long totalSeconds = Math.max(0L, elapsedMs / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_RECORD_AUDIO) return;

        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            toastSafe(getString(R.string.recording_unavailable));
            appendLogSafe("RECORD_AUDIO denied");
        }
    }

    private void toggleAudio() {
        BleSnapshot snapshot = getSnapshot();
        if (snapshot != null && !snapshot.isBluetoothOn()) {
            toastSafe("Bluetooth is OFF");
            return;
        }
        if (bgAudioEnabled) {
            if (ThereminBackgroundAudioService.isServiceActive()) {
                // Mute/unmute is instant — AudioTrack stays alive, volume goes to 0 in ≤20ms.
                // Avoids the 200-400ms service stop/start cycle entirely.
                boolean nowMuted = !ThereminBackgroundAudioService.isThereminMuted();
                ThereminBackgroundAudioService.setThereminMuted(nowMuted);
                appendLogSafe(nowMuted ? "theremin paused (muted)" : "theremin playing (unmuted)");
            } else {
                // Service not running — start it. Save settings on a background thread
                // so the main thread isn't blocked by SQLite before the service can start.
                new Thread(this::persistSettings).start();
                ThereminBackgroundAudioService.setThereminMuted(false); // ensure unmuted on fresh start
                ThereminBackgroundAudioService.startIfNeeded(this);
                if (isAudioRunning()) audioEngine.stop();
                appendLogSafe("Background audio started from Play");
            }
        } else if (isAudioRunning()) {
            audioEngine.stop();
        } else if (ThereminBackgroundAudioService.isServiceActive()) {
            waitingForServiceToStop = true;
            ThereminBackgroundAudioService.stopIfRunning(this);
            appendLogSafe("Play tapped while background service was still stopping");
        } else {
            audioEngine.start();
        }
        updateAudioStatusText();
    }

    private void toggleBackgroundAudio() {
        bgAudioEnabled = !bgAudioEnabled;
        saveBgAudioPref();
        appendLogSafe("BG audio toggled -> " + onOff(bgAudioEnabled));
        toastSafe("Background audio: " + onOff(bgAudioEnabled));

        if (bgAudioEnabled) {
            if (isAudioRunning()) {
                persistSettings();
                ThereminBackgroundAudioService.startIfNeeded(this);
                audioEngine.stop();
                appendLogSafe("Play audio moved to background owner");
            }
            updateAudioStatusText();
            return;
        }

        if (!playUiVisible && isAudioRunning()) {
            audioEngine.stop();
            appendLogSafe("BG audio disabled while app in background -> audio stopped");
        }
        waitingForServiceToStop = false;
        ThereminBackgroundAudioService.stopIfRunning(this);
        updateAudioStatusText();
    }

    private void cycleTone(int delta) {
        int idx = 0;
        for (int i = 0; i < TONE_CYCLE.length; i++) {
            if (TONE_CYCLE[i].equals(play.currentToneType)) { idx = i; break; }
        }
        idx = (idx + delta + TONE_CYCLE.length) % TONE_CYCLE.length;
        play.currentToneType = TONE_CYCLE[idx];
        persistSettings();
        pushAudioTargetsToEngine();
        updateToneButton();
    }

    private void updateToneButton() {
        if (binding == null) return;
        String tone = play.currentToneType;
        binding.toneKnob.setToneSequence(TONE_CYCLE);
        binding.toneKnob.setCurrentTone(tone);
        binding.tvToneLabel.setText(AppSettings.prettyToneType(tone));
    }

    private void onBleTogglePressed() {
        BleSnapshot snapshot = getSnapshot();
        if (snapshot == null || !snapshot.isBluetoothOn()) {
            toastSafe("Bluetooth is OFF");
            return;
        }
        if (snapshot.areBothGlovesConnected()) {
            toastSafe("Both gloves are already connected");
            return;
        }
        toastSafe("Connecting missing glove(s)...");
        BleSessionManager.requestConnectMissingGloves();
        refreshUiFast();
        updateBleButtonText();
    }

    private void updateBleButtonText() {
        BleSnapshot snapshot = getSnapshot();
        String label = "Connect";
        String description = "Connect gloves";

        if (snapshot != null && snapshot.hostReady) {
            if (!snapshot.bluetoothEnabled) {
                label = "Bluetooth Off";
                description = "Bluetooth is off";
            } else if (snapshot.areBothGlovesConnected()) {
                label = "Connected";
                description = "Both gloves are connected";
            } else if (snapshot.isAnyGloveConnecting() || snapshot.isAnyGloveConnected()) {
                label = "Reconnect";
                description = "Reconnect missing gloves";
            }
        }

    }

    private void reloadMappingSettingsFromRepository() {
        AppSettings settings = store().load();
        play.load(settings);
        float sensitivityMult = AppSettings.levelToMultiplier(settings.sensitivityLevel);
        play.setSensitivityMultiplier(sensitivityMult);
        ThereminBackgroundAudioService.setSensitivityMultiplier(sensitivityMult);
        audioEngine.setToneType(play.currentToneType);
        updateToneButton();
        loadSpring3Settings(settings);
        loadKeyboardPrefs();
        loadCustomBeatPattern();
    }

    /** Refresh preset-slot highlights on startup; does NOT auto-activate any slot. */
    private void loadCustomBeatPattern() {
        // Only update the visual dot indicators — slots must be tapped explicitly to engage.
        updatePresetSlotHighlights();
    }

    private void persistSettings() {
        AppSettings settings = store().load();
        play.saveTo(settings);
        store().save(settings);
    }

    private void loadKeyboardPrefs() {
        SharedPreferences prefs = getSharedPreferences("theremin_prefs", MODE_PRIVATE);
        drumBpm = prefs.getInt(KEY_BEAT_MASTER_BPM, drumBpm);
        pianoSynthMode = DrumEngine.clampPianoSynthMode(
                prefs.getInt(KEY_KEYBOARD_SYNTH_MODE, pianoSynthMode));
        applyBpm();
        applyKeyboardSynthMode();
    }

    private void recomputeMappedOutputs() {
        recomputeMappedOutputs(getSnapshot());
    }

    private void recomputeMappedOutputs(BleSnapshot snapshot) {
        play.recompute(snapshot);
        pushAudioTargetsToEngine();
    }

    private void pushAudioTargetsToEngine() {
        audioEngine.setToneType(play.currentToneType);
        audioEngine.setTargets(play.audioTargetFreqHz, play.audioTargetVolumeLinear);
        if (isServiceOwningAudio()) {
            ThereminBackgroundAudioService.setToneTypeNow(play.currentToneType);
        }
    }

    private void consumeIntent(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_AUTOSTART_AUDIO, false)) return;
        pendingAutoStartAudio = true;
        intent.removeExtra(EXTRA_AUTOSTART_AUDIO);
    }

    private void syncAudioTargetsFromSharedBleState() {
        BleSnapshot snapshot = getSnapshot();
        play.syncLive(snapshot);
        play.recompute(snapshot);
    }

    private BleSnapshot getSnapshot() {
        return BleSessionManager.getSnapshot();
    }

    private void updateVisualizer() {
        ThereminAudioEngine.VisualizerSnapshot waveSnapshot =
                isServiceOwningAudio() && !isAudioRunning()
                        ? ThereminBackgroundAudioService.getVisualizerSnapshot()
                        : audioEngine.getVisualizerSnapshot();
        if (waveSnapshot == null) {
            binding.thereminVisualizerView.setAudioWave(
                    null, play.mappedFreqHz, play.mappedVolumeLinear, play.freqMinHz, play.freqMaxHz);
        } else {
            binding.thereminVisualizerView.setAudioWave(
                    waveSnapshot.samples,
                    waveSnapshot.freqHz,
                    waveSnapshot.volumeLinear,
                    play.freqMinHz,
                    play.freqMaxHz
            );
        }
        // Pulse the pixel grid on every new drum/bass step so all sounds drive the visualizer
        DrumEngine drum = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (drum == null) drum = ThereminBackgroundAudioService.getDrumEngine();
        if (drum != null && drum.isEnabled()) {
            int step = drum.getCurrentStep16();
            if (step != lastDrumStep) {
                lastDrumStep = step;
                // On-beat steps (0,4,8,12) get a stronger kick than off-beat 16ths
                float strength = (step % 4 == 0) ? 0.75f : 0.38f;
                binding.thereminVisualizerView.pulse(strength);
            }
        }
    }

    private void maybeStartAudioAfterCalibration(boolean bothConnected) {
        if (!pendingAutoStartAudio || !bothConnected || waitingForServiceToStop) return;
        if (bgAudioEnabled) {
            if (!ThereminBackgroundAudioService.isServiceActive()) {
                persistSettings();
                ThereminBackgroundAudioService.startIfNeeded(this);
                appendLogSafe("Calibration returned to Play -> background audio kept alive");
            }
            pendingAutoStartAudio = false;
            return;
        }
        if (isAudioRunning() || ThereminBackgroundAudioService.isServiceActive()) return;
        pushAudioTargetsToEngine();
        audioEngine.start();
        pendingAutoStartAudio = false;
        appendLogSafe("Calibration returned to Play -> audio started");
    }

    private void refreshUiFast() {
        finishAudioTakebackIfReady();
        BleSnapshot snapshot = getSnapshot();
        boolean bothConnected = snapshot != null && snapshot.areBothGlovesConnected();
        boolean oneConnected = snapshot != null && snapshot.isAnyGloveConnected();
        boolean connecting = snapshot != null && snapshot.isAnyGloveConnecting();

        play.syncLive(snapshot);
        play.recompute(snapshot);
        // When foreground engine is active (no service), push updated targets every tick.
        if (!isServiceOwningAudio() && isAudioRunning()) {
            audioEngine.setTargets(play.audioTargetFreqHz, play.audioTargetVolumeLinear);
        }

        String freqText = PlayUiText.frequency(play.mappedFreqHz);
        String volText  = PlayUiText.volume(play.mappedVolumeLinear);
        String toneText = PlayUiText.tone(bothConnected, play.mappedFreqHz, play.mappedVolumeLinear);
        if (!freqText.equals(lastFreqText)) { lastFreqText = freqText; binding.tvFreqDisplay.setText(freqText); }
        if (!volText.equals(lastVolText))   { lastVolText  = volText;  binding.tvVolDisplay.setText(volText); }
        if (performanceModeActive) binding.topNavBar.setStageFreqVol(
                PlayUiText.stageVol(play.mappedVolumeLinear),
                PlayUiText.stageFreq(play.mappedFreqHz));
        if (!toneText.equals(lastToneText)) { lastToneText = toneText; binding.tvToneValue.setText(toneText); }

        // Update top nav bar: glove dots + dynamic title — only when state actually changes
        boolean audioOn = isAudioRunning() || isServiceOwningAudio();
        int pitchColor = gloveColor(snapshot, true);
        int volColor   = gloveColor(snapshot, false);
        if (pitchColor != lastGlovePitchColor || volColor != lastGloveVolColor) {
            lastGlovePitchColor = pitchColor;
            lastGloveVolColor   = volColor;
            binding.topNavBar.setGloveStatus(pitchColor, volColor);
        }
        String newTitle = audioOn && bothConnected ? "Ready to Play"
                : audioOn && oneConnected  ? "One Glove Connected"
                : audioOn                  ? "Connect Gloves"
                : "Play";
        if (!newTitle.equals(lastNavTitle)) {
            lastNavTitle = newTitle;
            binding.topNavBar.setTitleText(newTitle);
        }

        maybeStartAudioAfterCalibration(bothConnected);
        updateAudioStatusText();
        updateBleButtonText();
        updateVisualizer();

        // Sprint 3: Pulse the play hero card on each bass hit
        long bassHitMs = getLastBassHitMs();
        if (bassHitMs > lastAnimatedBassHitMs) {
            lastAnimatedBassHitMs = bassHitMs;
            pulseBassAnimation();
        }
    }

    private long getLastBassHitMs() {
        DrumEngine drum = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (drum == null) drum = ThereminBackgroundAudioService.getDrumEngine();
        return drum != null ? drum.getLastBassHitMs() : 0L;
    }

    private void pulseBassAnimation() {
        android.view.View target = binding.thereminVisualizerView;
        target.animate().cancel();
        target.animate()
            .scaleX(1.025f).scaleY(1.025f)
            .setDuration(80)
            .withEndAction(() ->
                target.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(120)
                    .start())
            .start();
    }



    /** Returns the ARGB tint color for a glove hand icon in the top nav bar. */
    private static int gloveColor(BleSnapshot snapshot, boolean isPitch) {
        if (snapshot != null && snapshot.isGloveConnected(isPitch)) return 0xFF49E37A; // green
        if (snapshot != null && snapshot.isAnyGloveConnecting())    return 0xFFFFAA00; // amber
        return 0xFFFF4444; // red
    }





    private void updateAudioStatusText() {
        boolean running = isAnyAudioRunning();
        if (running == lastAudioRunningState) return; // skip redundant view updates at 20fps
        lastAudioRunningState = running;
        binding.btnAudioStart.setText("");
        binding.btnAudioStart.setIconResource(running ? R.drawable.ic_pause_theremin : R.drawable.ic_play_theremin);
        binding.btnAudioStart.setContentDescription(running ? "Pause theremin" : "Play theremin");
        binding.tvPlayRemoteLabel.setText(running ? "Pause" : "Play");
        // Pause foreground drum engine whenever theremin stops.
        if (drumEngine != null) drumEngine.setPaused(!running);
    }

    private void appendLogSafe(String msg) {
        long now = SystemClock.elapsedRealtime();
        synchronized (logLines) {
            logLines.addLast(msg);
            while (logLines.size() > LOG_MAX_LINES) logLines.removeFirst();
            if (now - lastLogFlushMs < LOG_FLUSH_MIN_INTERVAL_MS) return;
            lastLogFlushMs = now;

            StringBuilder sb = new StringBuilder(logLines.size() * 24);
            for (String line : logLines) sb.append(line).append('\n');
            String all = sb.toString();
            runOnUiThread(() -> binding.tvLog.setText(all));
        }
    }

    private void toastSafe(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private String yesNo(boolean value) {
        return value ? "YES" : "NO";
    }

    /** Creates the Beat Maker drag-preview overlay and attaches it as a full-screen content overlay. */
    private void buildBeatMakerPreview() {
        bmPreview = new FrameLayout(this);
        bmPreview.setBackgroundColor(0xFF0F0A1E); // matches BeatMaker dark background
        bmPreview.setVisibility(android.view.View.GONE);

        // "Beat Maker" label centered in the panel
        TextView lbl = new TextView(this);
        lbl.setText("Beat Maker");
        lbl.setTextSize(26f);
        lbl.setTypeface(lbl.getTypeface(), Typeface.BOLD);
        lbl.setTextColor(0xFF00FF9D); // BeatMaker accent green
        lbl.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams lblLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        bmPreview.addView(lbl, lblLp);

        // Overlay on top of all content
        addContentView(bmPreview, new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void launchBeatMakerFromSwipe() {
        int editSlot = 0;
        for (int i = 0; i < NUM_BEAT_SLOTS; i++) { if (activeSlots[i]) { editSlot = i; break; } }
        // Cancel any in-flight animations, reset views instantly, launch with no transition.
        bmPreview.animate().cancel();
        binding.cardVisualizer.animate().cancel();
        binding.cardVisualizer.setTranslationX(0);
        bmPreview.setVisibility(android.view.View.GONE);
        Intent bm = new Intent(this, BeatMakerActivity.class);
        bm.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
        bm.putExtra(BeatMakerActivity.EXTRA_SLOT_INDEX, editSlot);
        beatMakerLauncher.launch(bm,
                androidx.core.app.ActivityOptionsCompat.makeCustomAnimation(this, 0, 0));
    }

    private void togglePerformanceMode() {
        performanceModeActive = !performanceModeActive;
        applyPerformanceMode(performanceModeActive);
    }

    /** Lights the DJ-console LED button: filled neon bg when on, transparent + border when off.
     *  For effects buttons also flips text ON/OFF; for other buttons preserves existing text. */
    private void applyFxLed(com.google.android.material.button.MaterialButton btn, boolean on, int neonArgb) {
        applyFxLed(btn, on, neonArgb, null);
    }

    private void applyFxLed(com.google.android.material.button.MaterialButton btn, boolean on, int neonArgb, String label) {
        if (on) {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(neonArgb & 0x55FFFFFF | 0x44000000));
            btn.setTextColor(neonArgb);
        } else {
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0x00000000));
            btn.setTextColor(0xFF888AAA);
        }
        if (label != null) btn.setText(label);
    }

    // Saved state for restoring after stage mode exits.
    private int savedVisualizerHeightPx    = -1;
    private int savedVizMarginTopPx        = -1;
    private int savedScrollContentPadTopPx = -1;

    private void applyPerformanceMode(boolean active) {
        binding.topNavBar.setStageMode(active);
        binding.bottomNavBar.setVisibility(active ? View.GONE : View.VISIBLE);
        binding.cardPlayHero.setVisibility(active ? View.GONE : View.VISIBLE);
        // cardControls stays visible — it holds record, play, and tone knob.
        binding.btnExitStage.setVisibility(active ? View.VISIBLE : View.GONE);

        if (active) {
            binding.rootScroll.scrollTo(0, 0); // reset any scroll offset before expanding
            if (savedVisualizerHeightPx < 0)
                savedVisualizerHeightPx = binding.thereminVisualizerView.getLayoutParams().height;
            // Wait for cardPlayHero GONE layout pass, then expand.
            binding.rootScroll.post(this::expandVisualizerForStage);
        } else {
            restoreVisualizerHeight();
        }

        applySystemUiMode(active);
        ViewCompat.requestApplyInsets(binding.rootScroll);
    }

    /**
     * Expands the visualizer to fill the viewport flush under the nav bar.
     * Pass 1: zero top padding/margin and request layout.
     * Pass 2 (posted): measure updated heights and set visualizer height.
     */
    private void expandVisualizerForStage() {
        int scrollH = binding.rootScroll.getHeight();
        if (scrollH == 0) { binding.rootScroll.post(this::expandVisualizerForStage); return; }

        boolean needsLayout = false;

        // Zero out top padding on the scroll content LinearLayout.
        android.view.View content = binding.rootScroll.getChildAt(0);
        if (content != null && savedScrollContentPadTopPx < 0) {
            savedScrollContentPadTopPx = content.getPaddingTop();
            content.setPadding(content.getPaddingLeft(), 0,
                               content.getPaddingRight(), content.getPaddingBottom());
            needsLayout = true;
        }

        // Zero out the cardVisualizer top margin.
        android.widget.LinearLayout.LayoutParams vizCardLp =
            (android.widget.LinearLayout.LayoutParams) binding.cardVisualizer.getLayoutParams();
        if (savedVizMarginTopPx < 0) {
            savedVizMarginTopPx = vizCardLp.topMargin;
            vizCardLp.topMargin = 0;
            binding.cardVisualizer.setLayoutParams(vizCardLp);
            needsLayout = true;
        }

        if (needsLayout) {
            // Wait for the layout pass triggered by setLayoutParams before measuring heights.
            binding.rootScroll.post(this::applyVisualizerStageHeight);
        } else {
            applyVisualizerStageHeight();
        }
    }

    /** Pass 2: set the visualizer height after margins/paddings have been laid out. */
    private void applyVisualizerStageHeight() {
        int scrollH   = binding.rootScroll.getHeight();
        int scrollPad = binding.rootScroll.getPaddingTop();
        int ctrlsH    = binding.cardControls.getHeight();
        int newH = scrollH - scrollPad - ctrlsH - dp(10) - dp(4); // 10dp = cardControls marginTop
        newH = Math.max(dp(200), newH);

        android.view.ViewGroup.LayoutParams lp = binding.thereminVisualizerView.getLayoutParams();
        lp.height = newH;
        binding.thereminVisualizerView.setLayoutParams(lp);
    }

    private void restoreVisualizerHeight() {
        if (savedVisualizerHeightPx < 0) return;

        // Restore visualizer height.
        android.view.ViewGroup.LayoutParams lp = binding.thereminVisualizerView.getLayoutParams();
        lp.height = savedVisualizerHeightPx;
        binding.thereminVisualizerView.setLayoutParams(lp);
        savedVisualizerHeightPx = -1;

        // Restore cardVisualizer top margin.
        if (savedVizMarginTopPx >= 0) {
            android.widget.LinearLayout.LayoutParams vizCardLp =
                (android.widget.LinearLayout.LayoutParams) binding.cardVisualizer.getLayoutParams();
            vizCardLp.topMargin = savedVizMarginTopPx;
            binding.cardVisualizer.setLayoutParams(vizCardLp);
            savedVizMarginTopPx = -1;
        }

        // Restore scroll content top padding.
        if (savedScrollContentPadTopPx >= 0) {
            android.view.View content = binding.rootScroll.getChildAt(0);
            if (content != null)
                content.setPadding(content.getPaddingLeft(), savedScrollContentPadTopPx,
                                   content.getPaddingRight(), content.getPaddingBottom());
            savedScrollContentPadTopPx = -1;
        }
    }

    private void applySystemUiMode(boolean fullscreen) {
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller == null) return;

        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (fullscreen) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
