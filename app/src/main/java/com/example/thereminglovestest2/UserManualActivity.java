package com.example.thereminglovestest2;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import com.example.thereminglovestest2.databinding.ActivityUserManualBinding;

public class UserManualActivity extends AppCompatActivity {

    private ActivityUserManualBinding binding;

    // Each entry: { sectionTitle, bodyText }
    // Body text uses \n• for bullet points, matching CalibrationActivity info dialog style.
    private static final String[][] SECTIONS = {
        {
            "QUICK START",
            "Welcome! Here is the fastest way to start playing:\n\n" +
            "1. Power on both gloves.\n" +
            "2. Open Connect → tap Connect All if they don't auto-connect.\n" +
            "3. Open Cal → tap Pitch Neutral, then Volume Neutral, then Save & Play.\n" +
            "4. Open Play → press the Play button.\n" +
            "5. Tilt your pitch glove to change pitch; tilt your volume glove to change loudness.\n\n" +
            "Tip: Calibration is the most important step — it tells the app what your natural " +
            "resting wrist position is. Do it first and redo it any time the feel seems off."
        },
        {
            "1. WHAT YOU NEED",
            "Before you start, make sure you have:\n\n" +
            "• An Android phone (Android 7 or newer).\n" +
            "• Two Theremin Gloves powered on and within range.\n" +
            "• Bluetooth enabled on your phone.\n\n" +
            "Your gloves must use these exact names:\n\n" +
            "• Pitch glove:  ThereminGlove\n" +
            "• Volume glove: ThereminGloveVol\n\n" +
            "Tip: Power the gloves on before opening the Connect screen — " +
            "the app starts scanning as soon as you open that tab."
        },
        {
            "2. FIRST LAUNCH",
            "When you first open the app it checks Bluetooth permissions and, if needed, " +
            "asks you to turn Bluetooth on. After that you land on the main Play screen.\n\n" +
            "Five tabs run along the bottom of every screen:\n\n" +
            "• Play — where you perform.\n" +
            "• Connect — manage your glove connections.\n" +
            "• Cal — set your neutral wrist positions and playing ranges.\n" +
            "• Library — listen to and organise your recordings.\n" +
            "• Settings — adjust app preferences.\n\n" +
            "Tip: If the app asks for Bluetooth permission, tap Allow — " +
            "it cannot find your gloves without it."
        },
        {
            "3. CONNECTING YOUR GLOVES",
            "Open the Connect tab. The app usually connects automatically — " +
            "if it doesn't, tap Connect All.\n\n" +
            "What the status label means:\n\n" +
            "• Waiting — still searching for the glove.\n" +
            "• Connecting… — found it, establishing the link.\n" +
            "• Connected — glove is live and sending data.\n" +
            "• Connected · no data — link is open but data has stopped arriving.\n" +
            "• Bluetooth off — turn on Bluetooth in your phone's settings.\n\n" +
            "If a glove won't connect:\n\n" +
            "• Make sure Bluetooth is turned on.\n" +
            "• Power the glove off, wait 5 seconds, then power it back on.\n" +
            "• Move the glove within 2–3 metres of your phone.\n" +
            "• Tap Connect All again.\n\n" +
            "The app remembers your gloves — after the first connection it reconnects " +
            "automatically whenever a glove is powered on nearby."
        },
        {
            "4. CALIBRATING",
            "Calibration tells the app what 'neutral' means for each hand. " +
            "Do this before your first performance and any time the feel seems off.\n\n" +
            "Steps:\n\n" +
            "1. Go to the Cal tab and stay on the Pitch tab.\n" +
            "2. Hold your pitch hand in a relaxed, natural resting position.\n" +
            "3. Tap Pitch Neutral — the app locks in that wrist angle as your zero point.\n" +
            "4. Switch to the Volume tab.\n" +
            "5. Hold your volume hand in a relaxed, natural resting position.\n" +
            "6. Tap Volume Neutral.\n" +
            "7. Adjust the min/max sliders to narrow or widen your gesture range if needed.\n" +
            "8. Tap Save & Play when you are happy.\n\n" +
            "Default ranges: pitch 0° to 90°, volume 0° to 90°, frequency 20 Hz to 20 kHz.\n\n" +
            "Tip: The Cal screen plays live audio using your draft settings so you can " +
            "hear the changes before you save."
        },
        {
            "5. PLAYING THE THEREMIN",
            "Open the Play tab and press the Play button to start sound.\n\n" +
            "Your gesture controls:\n\n" +
            "• Pitch glove — tilt your wrist to raise or lower the pitch.\n" +
            "• Volume glove — tilt your wrist to make the sound louder or softer.\n" +
            "• Both gloves must be connected — if either drops, audio mutes automatically.\n\n" +
            "What you see on screen:\n\n" +
            "• Current frequency and volume readout.\n" +
            "• Waveform visualizer (your synth output — not the microphone).\n" +
            "• Record button.\n" +
            "• Tone selector knob.\n" +
            "• Scale lock and octave shift controls.\n" +
            "• Effects panel (reverb, delay, distortion).\n" +
            "• Beat and bass controls plus the BEATS button.\n" +
            "• Stage View in the top bar for a clean performance display.\n\n" +
            "Tip: Adjust the Sensitivity slider in Settings to make gestures " +
            "more responsive or more precise."
        },
        {
            "6. CHOOSING A TONE",
            "Rotate the tone knob on the Play screen to pick from 11 sounds:\n\n" +
            "• Theremin — the classic singing theremin tone.\n" +
            "• Air Pad — soft, drifting; great for ambient textures.\n" +
            "• Cello — dark and bowed; expressive for melodies.\n" +
            "• Pad — warm and sustained; good for slow, dreamy passages.\n" +
            "• Choir — ethereal vocal quality.\n" +
            "• Flute — light and airy; easy on the ears.\n" +
            "• Clarinet — woody reed tone; expressive mid-range.\n" +
            "• Triangle — hollow, pure tone with few overtones.\n" +
            "• Saw — bright and sharp; classic synthesizer sound.\n" +
            "• Square — hollow odd-harmonic buzz; retro feel.\n" +
            "• Helicopter — rhythmic rotor effect; great for experimenting.\n\n" +
            "Tip: Try Theremin or Cello with Reverb turned on for a rich, " +
            "full-room sound."
        },
        {
            "7. EFFECTS, SCALES & BEATS",
            "Scale lock keeps your pitch on familiar notes:\n\n" +
            "• CHROM — no lock; every frequency is available.\n" +
            "• MAJOR — snaps to major scale notes.\n" +
            "• MINOR — snaps to minor scale notes.\n" +
            "• PENTA — snaps to pentatonic notes (great for beginners).\n\n" +
            "Octave shift moves your whole range up or down:\n\n" +
            "• Use the − and + buttons to step from −2 to +2 octaves.\n" +
            "• Useful for finding a comfortable pitch range for your voice.\n\n" +
            "Audio effects:\n\n" +
            "• Reverb — makes the sound bloom and decay like a real room.\n" +
            "• Delay — adds a repeating echo; the feedback knob controls how long it rings.\n" +
            "• Distortion — adds grit and saturation for a dirtier sound.\n\n" +
            "Beat Maker:\n\n" +
            "• 8 built-in drum patterns to choose from.\n" +
            "• Tap BEATS to open the full Beat Maker screen.\n" +
            "• BPM control and separate synth/beat gain sliders."
        },
        {
            "8. RECORDING",
            "To capture a performance:\n\n" +
            "1. Start playing from the Play screen.\n" +
            "2. Tap the Record button — a timer appears and the button blinks.\n" +
            "3. Perform.\n" +
            "4. Tap Record again to stop and save.\n\n" +
            "The recording captures the full synthesizer output — drums, effects, and all. " +
            "It does not use the microphone.\n\n" +
            "Recordings are saved to your Music folder (Theremin Gloves Recordings) " +
            "so they show up in other music apps too.\n\n" +
            "Quality options (set in Settings):\n\n" +
            "• Lossless WAV — best quality, largest file.\n" +
            "• High AAC — near-lossless quality, smaller file.\n" +
            "• Medium AAC — good quality, compact file.\n" +
            "• Low AAC — smallest file; fine for quick sharing.\n\n" +
            "Tip: On your first recording the app will ask for audio permission — " +
            "tap Allow to proceed."
        },
        {
            "9. THE LIBRARY",
            "Open the Library tab to listen to and manage everything you have recorded.\n\n" +
            "What you can do:\n\n" +
            "• Tap any recording to play it back.\n" +
            "• Search recordings by name.\n" +
            "• Rename a recording (long-press) or delete it (swipe left).\n" +
            "• Create folders and drag recordings into them to stay organised.\n" +
            "• Use the mini-player at the bottom to pause, seek, and loop.\n" +
            "• Filter recordings by length or date to find older sessions.\n\n" +
            "Good to know: Library playback works even when the gloves are off — " +
            "you can listen to your saved performances any time."
        },
        {
            "10. SETTINGS",
            "Tap the Settings tab to customise the app.\n\n" +
            "Key options and what they do:\n\n" +
            "• Background audio — keeps sound going when you leave the Play screen.\n" +
            "• Extended frequency range — raises the top pitch from 2,000 Hz to 20,000 Hz.\n" +
            "• Invert pitch/volume direction — flips which wrist direction raises pitch or volume.\n" +
            "• Show Calibration Guide Again — re-enables the step-by-step calibration walkthrough.\n" +
            "• Rename dialog after recording — prompts you to name each recording when you stop.\n" +
            "• Recording quality — WAV (lossless) or AAC at High / Medium / Low.\n" +
            "• Sensitivity — controls how strongly wrist movement maps to sound.\n\n" +
            "Recommended starting point:\n\n" +
            "• Leave extended range off until you need very high pitches.\n" +
            "• Keep the rename dialog on so recordings are easy to find later.\n" +
            "• Start with Medium sensitivity and adjust to taste."
        },
        {
            "11. TROUBLESHOOTING",
            "No sound:\n\n" +
            "• Press the Play button — audio does not start until you tap it.\n" +
            "• Make sure both gloves are connected — sound mutes if either is missing.\n" +
            "• Turn up the phone volume.\n\n" +
            "Glove won't connect:\n\n" +
            "• Check that Bluetooth is on.\n" +
            "• Power-cycle the glove (off, wait a few seconds, back on).\n" +
            "• Confirm the glove name: ThereminGlove or ThereminGloveVol exactly.\n" +
            "• Move the glove within 2–3 metres of the phone and tap Connect All.\n\n" +
            "Pitch or volume feels off:\n\n" +
            "• Recalibrate — open Cal, capture both neutral positions again, tap Save & Play.\n" +
            "• Check the direction toggles in Settings if the movement feels backwards.\n" +
            "• Widen or narrow the min/max sliders in Cal for a more comfortable range.\n\n" +
            "Recording failed:\n\n" +
            "• Tap Allow if the app asks for permission.\n" +
            "• Make sure the Play transport is running before you tap Record.\n" +
            "• Free up storage space if the phone is nearly full.\n\n" +
            "Bluetooth turned off during play:\n\n" +
            "• Turn Bluetooth back on — the app reconnects automatically.\n" +
            "• Or open Connect and tap Connect All to reconnect immediately."
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityUserManualBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.topNavBar.setTitleText("User Manual");
        binding.topNavBar.setBackButtonVisible(true);
        binding.topNavBar.setOnBackClickListener(v -> finish());

        buildSections();
    }

    private void buildSections() {
        int accentColor = Color.parseColor("#00D4AA");
        for (String[] section : SECTIONS) {
            binding.manualContentLayout.addView(
                    buildSectionCard(section[0], section[1], accentColor));
        }
    }

    private View buildSectionCard(String title, String body, int accentColor) {
        int surfaceColor  = getResources().getColor(R.color.app_box_surface, getTheme());
        int onSurface     = getResources().getColor(R.color.app_on_surface, getTheme());
        int onSurfaceVar  = getResources().getColor(R.color.app_on_surface_variant, getTheme());

        // Card
        CardView card = new CardView(this);
        CardView.LayoutParams cardParams = new CardView.LayoutParams(
                CardView.LayoutParams.MATCH_PARENT,
                CardView.LayoutParams.WRAP_CONTENT);
        cardParams.bottomMargin = dp(10);
        card.setLayoutParams(cardParams);
        card.setCardBackgroundColor(surfaceColor);
        card.setRadius(dp(16));
        card.setCardElevation(0);

        // Inner column
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Header row: accent bar + title
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(dp(14), dp(12), dp(14), dp(8));
        headerRow.setLayoutParams(headerParams);

        // Accent bar
        View accent = new View(this);
        accent.setLayoutParams(new LinearLayout.LayoutParams(dp(3), dp(14)));
        accent.setBackgroundColor(accentColor);
        headerRow.addView(accent);

        // Title
        TextView tvTitle = new TextView(this);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.setMarginStart(dp(8));
        tvTitle.setLayoutParams(titleParams);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvTitle.setTextColor(onSurface);
        tvTitle.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tvTitle.setLetterSpacing(0.2f);
        headerRow.addView(tvTitle);
        inner.addView(headerRow);

        // Body text
        TextView tvBody = new TextView(this);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyParams.setMargins(dp(14), 0, dp(14), dp(16));
        tvBody.setLayoutParams(bodyParams);
        tvBody.setText(body);
        tvBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvBody.setTextColor(onSurfaceVar);
        tvBody.setLineSpacing(dp(3), 1f);
        inner.addView(tvBody);

        card.addView(inner);
        return card;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }
}
