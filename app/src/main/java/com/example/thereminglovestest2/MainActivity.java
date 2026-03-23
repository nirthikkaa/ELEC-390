package com.example.thereminglovestest2;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.widget.FrameLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.thereminglovestest2.databinding.ActivityMainBinding;

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

    private static final long UI_TICK_MS = 80;
    private static final int LOG_MAX_LINES = 200;
    private static final long LOG_FLUSH_MIN_INTERVAL_MS = 600;
    private static final int REQUEST_RECORD_AUDIO = 4109;

    private static final String[] TONE_CYCLE = {
        AppSettings.TONE_SINE,    AppSettings.TONE_SQUARE,   AppSettings.TONE_TRIANGLE,
        AppSettings.TONE_SAW,     AppSettings.TONE_PULSE,    AppSettings.TONE_ORGAN,
        AppSettings.TONE_STRING,  AppSettings.TONE_BELL,     AppSettings.TONE_PAD,
        AppSettings.TONE_LEAD,    AppSettings.TONE_KEYS,
        AppSettings.TONE_VOWEL_A, AppSettings.TONE_VOWEL_O,  AppSettings.TONE_VOWEL_I
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
    private int  drumBpm              = 120;
    private int  drumPresetIdx        = 0;
    private int  bassPresetIdx        = 0;
    // 0=NOTE, 1=MAJOR, 2=MINOR, 3=PENTA, 4=JAZZ (cycles on button tap)
    private int  pianoModeIdx         = 0;
    // Active arpeggio roots in melody mode (multi-key harmonic selection)
    private final Set<Integer> activeMelodyNotes = new HashSet<>();
    private long lastAnimatedBassHitMs = 0L; // tracks which bass hit we've already animated

    private boolean bgAudioEnabled = true;
    private boolean isRecordingUiActive;
    private boolean playUiVisible;
    private boolean pendingAutoStartAudio;
    private boolean waitingForServiceToStop;
    private boolean performanceModeActive = false;
    private long lastLogFlushMs;
    private long recordingStartElapsedMs;
    private ObjectAnimator recordBlinkAnimator;

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

        consumeIntent(getIntent());
        loadBgAudioPref();
        play.refreshFreqRangeLimit(this);
        recomputeMappedOutputs();
        wireButtons();

        appendLogSafe("Play opened");
        appendLogSafe("BG audio: " + onOff(bgAudioEnabled));
        updateAudioStatusText();
        updateBleButtonText();
    }

    @Override
    protected void onStart() {
        super.onStart();
        onVisible();
        uiTicker.start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        onVisible();
        android.content.SharedPreferences prefs = getSharedPreferences("theremin_prefs", MODE_PRIVATE);
        performanceModeActive = prefs.getBoolean("performance_mode_active", false);
        applyPerformanceMode(performanceModeActive);
        int densityLevel = prefs.getInt("pixel_density_level", 3);
        binding.thereminVisualizerView.setPixelDensityLevel(densityLevel);
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
        return isAudioRunning() || isServiceOwningAudio();
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

        binding.toneKnob.setToneSequence(TONE_CYCLE);
        binding.toneKnob.setOnToneStepListener(this::cycleTone);
        wireSpring3Controls();
    }

    // Sprint 3: scale lock, octave shift, drum toggle, effects
    private void wireSpring3Controls() {
        // Make effect + drum + bass buttons checkable (toggle behaviour)
        binding.btnDrumToggle.setCheckable(true);
        binding.btnBassToggle.setCheckable(true);
        binding.btnReverb.setCheckable(true);
        binding.btnDelay.setCheckable(true);
        binding.btnDistortion.setCheckable(true);

        // Scale chip group — push to foreground engine AND background service
        binding.chipGroupScale.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            int id = checkedIds.get(0);
            String scale;
            if      (id == R.id.chipMajor)      scale = AppSettings.SCALE_MAJOR;
            else if (id == R.id.chipMinor)      scale = AppSettings.SCALE_MINOR;
            else if (id == R.id.chipPentatonic) scale = AppSettings.SCALE_PENTATONIC;
            else                                scale = AppSettings.SCALE_CHROMATIC;
            if (audioEngine != null) audioEngine.setActiveScale(scale);
            ThereminBackgroundAudioService.setActiveScale(scale);
            saveScaleSetting(scale);
            appendLogSafe("Scale -> " + scale);
        });

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

        // Drum toggle — enable on both foreground engine and background service drum engine
        binding.btnDrumToggle.addOnCheckedChangeListener((btn, isChecked) -> {
            DrumEngine fgDrum = audioEngine != null ? audioEngine.getDrumEngine() : null;
            if (fgDrum != null) {
                fgDrum.setEnabled(isChecked);
            } else if (isChecked) {
                btn.setChecked(false);
                toastSafe("Start audio first");
                return;
            }
            ThereminBackgroundAudioService.setDrumEnabled(isChecked);
            updateDrumButton();
        });

        // Bass toggle — same dual-engine pattern
        binding.btnBassToggle.addOnCheckedChangeListener((btn, isChecked) -> {
            DrumEngine fgDrum = audioEngine != null ? audioEngine.getDrumEngine() : null;
            if (fgDrum != null) {
                fgDrum.setBassEnabled(isChecked);
            } else if (isChecked) {
                btn.setChecked(false);
                toastSafe("Start audio first");
                return;
            }
            ThereminBackgroundAudioService.setBassEnabled(isChecked);
            updateBassButton();
        });

        // Piano mode cycle button: NOTE → MAJOR → MINOR → PENTA → JAZZ → NOTE …
        binding.btnPianoMode.setOnClickListener(v -> {
            pianoModeIdx = (pianoModeIdx + 1) % DrumEngine.ARPEGGIO_PATTERNS.length;
            clearMelodyOnAllEngines(); // clear selection whenever mode changes
            updatePianoModeButton();
            // Stop any running arpeggio when switching to NOTE mode
            if (pianoModeIdx == 0) clearMelodyOnAllEngines();
        });

        // BPM slider (60–200 BPM, progress 0–140)
        binding.sbBpm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                drumBpm = 60 + p;
                applyBpm();
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        // Pattern buttons
        // Drum preset buttons 1–8
        com.google.android.material.button.MaterialButton[] drumBtns = {
            binding.btnDrumPat1, binding.btnDrumPat2, binding.btnDrumPat3, binding.btnDrumPat4,
            binding.btnDrumPat5, binding.btnDrumPat6, binding.btnDrumPat7, binding.btnDrumPat8
        };
        for (int i = 0; i < drumBtns.length; i++) {
            final int idx = i;
            drumBtns[i].setOnClickListener(v -> applyDrumPreset(idx));
        }
        // Bass preset buttons 1–8
        com.google.android.material.button.MaterialButton[] bassBtns = {
            binding.btnBassPat1, binding.btnBassPat2, binding.btnBassPat3, binding.btnBassPat4,
            binding.btnBassPat5, binding.btnBassPat6, binding.btnBassPat7, binding.btnBassPat8
        };
        for (int i = 0; i < bassBtns.length; i++) {
            final int idx = i;
            bassBtns[i].setOnClickListener(v -> applyBassPreset(idx));
        }
        applyDrumPresetHighlight(drumPresetIdx);
        applyBassPresetHighlight(bassPresetIdx);

        // Volume: SYNTH (theremin level) and BEATS (all drums+bass)
        binding.sbSynthVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { applyMixerVol(); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });
        binding.sbBeatVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean u) { applyMixerVol(); }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
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

    private void updateScaleChips(String scale) {
        int id;
        switch (scale) {
            case AppSettings.SCALE_MAJOR:      id = R.id.chipMajor; break;
            case AppSettings.SCALE_MINOR:      id = R.id.chipMinor; break;
            case AppSettings.SCALE_PENTATONIC: id = R.id.chipPentatonic; break;
            default:                           id = R.id.chipChromatic; break;
        }
        binding.chipGroupScale.check(id);
    }

    private void updateOctaveLabel() {
        String label = "Oct " + (octaveShift >= 0 ? "+" + octaveShift : String.valueOf(octaveShift));
        binding.tvOctaveLabel.setText(label);
    }

    private void updateDrumButton() {
        DrumEngine drum = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (drum == null) drum = ThereminBackgroundAudioService.getDrumEngine();
        boolean on = drum != null && drum.isEnabled();
        binding.btnDrumToggle.setChecked(on);
        applyFxLed(binding.btnDrumToggle, on, 0xFF00FF9D);
    }

    private void updateBassButton() {
        DrumEngine drum = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (drum == null) drum = ThereminBackgroundAudioService.getDrumEngine();
        boolean on = drum != null && drum.isBassEnabled();
        binding.btnBassToggle.setChecked(on);
        applyFxLed(binding.btnBassToggle, on, 0xFFFF9D00);
    }

    private void applyBpm() {
        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (fg != null) fg.setBpm(drumBpm);
        DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
        if (bg != null) bg.setBpm(drumBpm);
        binding.tvBpmLabel.setText(String.valueOf(drumBpm));
    }

    private void applyDrumPreset(int idx) {
        drumPresetIdx = idx;
        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (fg != null) fg.setDrumPattern(idx);
        DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
        if (bg != null) bg.setDrumPattern(idx);
        applyDrumPresetHighlight(idx);
    }

    private void applyBassPreset(int idx) {
        bassPresetIdx = idx;
        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (fg != null) fg.setBassPattern(idx);
        DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
        if (bg != null) bg.setBassPattern(idx);
        applyBassPresetHighlight(idx);
    }

    private void applyDrumPresetHighlight(int idx) {
        int active = 0xFF00FF9D, inactive = 0xFF888AAA;
        android.content.res.ColorStateList activeBg = android.content.res.ColorStateList.valueOf(0x3300FF9D);
        android.content.res.ColorStateList clearBg  = android.content.res.ColorStateList.valueOf(0x00000000);
        com.google.android.material.button.MaterialButton[] btns = {
            binding.btnDrumPat1, binding.btnDrumPat2, binding.btnDrumPat3, binding.btnDrumPat4,
            binding.btnDrumPat5, binding.btnDrumPat6, binding.btnDrumPat7, binding.btnDrumPat8
        };
        for (int i = 0; i < btns.length; i++) {
            btns[i].setTextColor(idx == i ? active : inactive);
            btns[i].setBackgroundTintList(idx == i ? activeBg : clearBg);
        }
    }

    private void applyBassPresetHighlight(int idx) {
        int active = 0xFFCC44FF, inactive = 0xFF888AAA;
        android.content.res.ColorStateList activeBg = android.content.res.ColorStateList.valueOf(0x33CC44FF);
        android.content.res.ColorStateList clearBg  = android.content.res.ColorStateList.valueOf(0x00000000);
        com.google.android.material.button.MaterialButton[] btns = {
            binding.btnBassPat1, binding.btnBassPat2, binding.btnBassPat3, binding.btnBassPat4,
            binding.btnBassPat5, binding.btnBassPat6, binding.btnBassPat7, binding.btnBassPat8
        };
        for (int i = 0; i < btns.length; i++) {
            btns[i].setTextColor(idx == i ? active : inactive);
            btns[i].setBackgroundTintList(idx == i ? activeBg : clearBg);
        }
    }

    private static final String[] PIANO_MODE_LABELS = {"NOTE", "MAJOR", "MINOR", "PENTA", "JAZZ"};

    private void updatePianoModeButton() {
        boolean melodic = pianoModeIdx > 0;
        binding.btnPianoMode.setText(PIANO_MODE_LABELS[pianoModeIdx]);
        binding.btnPianoMode.setTextColor(melodic ? 0xFF00CCFF : 0xFF888AAA);
        if (!melodic) binding.tvPianoNote.setText("tap a key");
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
        float synthGain = binding.sbSynthVol.getProgress() / 100f;
        float beatVol   = binding.sbBeatVol.getProgress()  / 100f;
        // Theremin level
        if (audioEngine != null) audioEngine.setMixGain(synthGain);
        ThereminBackgroundAudioService.setMixGain(synthGain);
        // All drum + bass + piano key tracks together
        DrumEngine fg = audioEngine != null ? audioEngine.getDrumEngine() : null;
        if (fg != null) { fg.setKickVolume(beatVol); fg.setSnareVolume(beatVol); fg.setHihatVolume(beatVol * 0.75f); fg.setBassVolume(beatVol); fg.setPianoVolume(beatVol); }
        DrumEngine bg = ThereminBackgroundAudioService.getDrumEngine();
        if (bg != null) { bg.setKickVolume(beatVol); bg.setSnareVolume(beatVol); bg.setHihatVolume(beatVol * 0.75f); bg.setBassVolume(beatVol); bg.setPianoVolume(beatVol); }
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
        updateScaleChips(scale);
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
        binding.sbBpm.setProgress(drumBpm - 60);
        binding.tvBpmLabel.setText(String.valueOf(drumBpm));
        applyDrumPresetHighlight(drumPresetIdx);
        applyBassPresetHighlight(bassPresetIdx);
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
        updateDrumButton();
        updateBassButton();
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
        recordingRepository.saveRecording(filePath, name, durationMs, quality);
        toastSafe(getString(R.string.recording_saved));
        appendLogSafe("Recording saved: " + name);
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
                waitingForServiceToStop = false;
                ThereminBackgroundAudioService.stopIfRunning(this);
                appendLogSafe("Background audio stopped from Play");
            } else {
                persistSettings();
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
        play.setSensitivityMultiplier(AppSettings.levelToMultiplier(settings.sensitivityLevel));
        audioEngine.setToneType(play.currentToneType);
        updateToneButton();
        loadSpring3Settings(settings);
    }

    private void persistSettings() {
        AppSettings settings = store().load();
        play.saveTo(settings);
        store().save(settings);
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
            return;
        }
        binding.thereminVisualizerView.setAudioWave(
                waveSnapshot.samples,
                waveSnapshot.freqHz,
                waveSnapshot.volumeLinear,
                play.freqMinHz,
                play.freqMaxHz
        );
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

        binding.tvFreqDisplay.setText(PlayUiText.frequency(play.mappedFreqHz));
        binding.tvVolDisplay.setText(PlayUiText.volume(play.mappedVolumeLinear));
        binding.tvToneValue.setText(PlayUiText.tone(bothConnected, play.mappedFreqHz, play.mappedVolumeLinear));

        // Update top nav bar: glove dots + dynamic title
        boolean audioOn = isAudioRunning() || isServiceOwningAudio();
        binding.topNavBar.setGloveStatus(gloveColor(snapshot, true), gloveColor(snapshot, false));
        binding.topNavBar.setTitleText(audioOn && bothConnected ? "Ready to Play"
                : audioOn && oneConnected  ? "One Glove Connected"
                : audioOn                  ? "Connect Gloves"
                : "Play");

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
        binding.btnAudioStart.setText("");
        binding.btnAudioStart.setIconResource(running ? R.drawable.ic_pause_theremin : R.drawable.ic_play_theremin);
        binding.btnAudioStart.setContentDescription(running ? "Pause theremin" : "Play theremin");
        binding.tvPlayRemoteLabel.setText(running ? "Pause" : "Play");

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

    private void togglePerformanceMode() {
        performanceModeActive = !performanceModeActive;
        applyPerformanceMode(performanceModeActive);
        getSharedPreferences("theremin_prefs", MODE_PRIVATE)
                .edit().putBoolean("performance_mode_active", performanceModeActive).apply();
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

    private void applyPerformanceMode(boolean active) {
        int hide = active ? View.GONE : View.VISIBLE;

        binding.cardScale.setVisibility(hide);
        binding.cardEffects.setVisibility(hide);
        if (binding.cardDebugLog != null) binding.cardDebugLog.setVisibility(hide);

        binding.btnPerformanceMode.setText(active ? "Exit Stage" : "Stage View");
    }
}
