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
            "The shortest path to playing:\n\n" +
            "• Power on both gloves.\n" +
            "• Open Connect and tap Connect All if they do not auto-connect.\n" +
            "• Open Cal, capture Pitch Neutral, then Volume Neutral, then tap Save & Play.\n" +
            "• Open Play and press the Play button.\n" +
            "• Move the pitch glove to change pitch and the volume glove to change loudness."
        },
        {
            "1. HARDWARE SETUP",
            "You need:\n\n" +
            "• An Android phone running API 36 or higher.\n" +
            "• Two BLE gloves built around Arduino Nano 33 BLE Sense boards.\n" +
            "• The Theremin Gloves app installed.\n\n" +
            "Required glove names:\n\n" +
            "• Pitch glove: ThereminGlove\n" +
            "• Volume glove: ThereminGloveVol\n\n" +
            "Power on both gloves before trying to connect. The app looks for those exact BLE names."
        },
        {
            "2. FIRST LAUNCH",
            "When the app starts it warms dependencies, checks Bluetooth permissions, and " +
            "prompts to turn Bluetooth on if needed.\n\n" +
            "After that, the app routes you to Setup on first launch or Play on later launches.\n\n" +
            "The bottom navigation bar contains five tabs:\n\n" +
            "• Play\n" +
            "• Connect\n" +
            "• Cal\n" +
            "• Library\n" +
            "• Settings"
        },
        {
            "3. CONNECTING YOUR GLOVES",
            "Recommended manual steps:\n\n" +
            "• Open the Connect tab.\n" +
            "• Make sure Bluetooth is on.\n" +
            "• Power on both gloves.\n" +
            "• Wait for the app to discover them, or tap Connect All.\n\n" +
            "Connection status meanings:\n\n" +
            "• Waiting — no live connection yet.\n" +
            "• Connecting… — scan or GATT connection is in progress.\n" +
            "• Connected — glove is connected and telemetry is arriving.\n" +
            "• Connected · no data — connected but telemetry has gone stale.\n" +
            "• Bluetooth off — phone Bluetooth is disabled.\n\n" +
            "If a glove does not appear:\n\n" +
            "• Verify Bluetooth is on and the glove is powered on.\n" +
            "• Verify the glove name is exactly ThereminGlove or ThereminGloveVol.\n" +
            "• Move the glove closer to the phone.\n" +
            "• Use the per-glove reconnect button or Connect All.\n\n" +
            "The app keeps a cached device address and tries to reconnect automatically if a glove drops."
        },
        {
            "4. CALIBRATING",
            "Open the Cal tab before serious use.\n\n" +
            "Steps:\n\n" +
            "• Stay on the Pitch tab.\n" +
            "• Hold your pitch hand in a natural relaxed neutral position.\n" +
            "• Tap Pitch Neutral.\n" +
            "• Switch to the Volume tab.\n" +
            "• Hold your volume hand in its natural neutral position.\n" +
            "• Tap Volume Neutral.\n" +
            "• Adjust the min/max angle and frequency controls if needed.\n" +
            "• Tap Save & Play.\n\n" +
            "Neutral means the wrist orientation you want as the baseline resting position. " +
            "Live motion is measured relative to that baseline.\n\n" +
            "Default ranges:\n\n" +
            "• Settings defaults: pitch 0°–90°, volume 0°–90°, frequency 20–2000 Hz.\n" +
            "• Play reset defaults: pitch −15°–55°, volume −10°–55°, frequency 880–2000 Hz.\n\n" +
            "You do not need to disconnect the gloves to recalibrate. " +
            "The calibration screen can also play live preview audio with your draft settings."
        },
        {
            "5. PLAYING THE THEREMIN",
            "Open the Play tab.\n\n" +
            "Basic gesture mapping:\n\n" +
            "• Pitch glove — wrist movement changes pitch.\n" +
            "• Volume glove — wrist movement changes loudness.\n" +
            "• Both gloves must be connected for live theremin output.\n\n" +
            "On-screen features:\n\n" +
            "• Live frequency and volume readout.\n" +
            "• Large audio visualizer (shows synthesized output, not microphone).\n" +
            "• Record button.\n" +
            "• Play transport button.\n" +
            "• Rotary tone selector.\n" +
            "• Scale lock controls.\n" +
            "• Octave shift controls.\n" +
            "• Effects controls.\n" +
            "• Beat and bass controls.\n" +
            "• BEATS button for Beat Maker access.\n" +
            "• Stage View action in the top bar."
        },
        {
            "6. TONE SELECTION",
            "The Play screen uses the tone knob to cycle through 10 tones:\n\n" +
            "• Theremin — classic theremin-like tone with a vocal/cello quality.\n" +
            "• Air Pad — soft ambient pad.\n" +
            "• Cello — dark bowed-string style tone.\n" +
            "• Pad — warm sustained synth pad.\n" +
            "• Choir — soft vocal pad.\n" +
            "• Flute — light, smooth flute-like tone.\n" +
            "• Clarinet — woody reed-like tone.\n" +
            "• Triangle — hollow, cleaner synth tone.\n" +
            "• Saw — bright, sharper synth tone.\n" +
            "• Helicopter — rhythmic rotor-like special effect tone."
        },
        {
            "7. EFFECTS, SCALE LOCK & BEATS",
            "Scale lock modes:\n\n" +
            "• CHROM — no lock, full chromatic range.\n" +
            "• MAJOR — snaps to major scale notes.\n" +
            "• MINOR — snaps to minor scale notes.\n" +
            "• PENTA — snaps to pentatonic scale notes.\n\n" +
            "Octave shift:\n\n" +
            "• Use the − and + buttons around the octave label.\n" +
            "• Range is from −2 to +2 octaves.\n\n" +
            "Effects:\n\n" +
            "• Reverb — adds room ambience.\n" +
            "• Delay — adds echo with feedback control.\n" +
            "• Distortion — adds saturation/overdrive.\n\n" +
            "Beat tools:\n\n" +
            "• 8 beat preset slots.\n" +
            "• BEATS button opens the Beat Maker.\n" +
            "• BPM controls for drum and piano sequencing.\n" +
            "• Separate synth and beat gain controls."
        },
        {
            "8. RECORDING",
            "To record:\n\n" +
            "• Start audio from the Play screen.\n" +
            "• Tap Record.\n" +
            "• Perform.\n" +
            "• Tap Record again to stop.\n\n" +
            "While recording the button shows a stop icon, a timer appears, and the button blinks.\n\n" +
            "First-time permission:\n\n" +
            "• The app asks for audio permission before the first recording.\n" +
            "• The saved audio comes from the internal synth, not from the microphone.\n\n" +
            "Where recordings go:\n\n" +
            "• Private copy: internal app storage.\n" +
            "• Export copy: Music/Theremin Gloves Recordings (user-visible).\n\n" +
            "Quality options:\n\n" +
            "• Lossless WAV\n" +
            "• High AAC\n" +
            "• Medium AAC\n" +
            "• Low AAC"
        },
        {
            "9. THE LIBRARY",
            "The Library tab lets you manage saved performances.\n\n" +
            "Features:\n\n" +
            "• View recordings with date, duration, and quality badge.\n" +
            "• Play recordings back without connecting the gloves.\n" +
            "• Search by name.\n" +
            "• Rename or delete recordings.\n" +
            "• Create and manage folders.\n" +
            "• Drag recordings onto folders to move them.\n" +
            "• Multi-select delete and move.\n" +
            "• Filter by duration or date range.\n" +
            "• Mini-player with playback progress and loop mode.\n\n" +
            "Library playback does not require gloves — you can listen to saved recordings " +
            "even with both gloves turned off."
        },
        {
            "10. SETTINGS",
            "Available settings:\n\n" +
            "• Keep audio playing when leaving Play — lets playback continue via background service.\n" +
            "• Extended frequency range — raises the ceiling from 2,000 Hz to 20,000 Hz.\n" +
            "• Invert pitch glove direction — reverses the mapping direction for pitch.\n" +
            "• Invert volume glove direction — reverses the mapping direction for volume.\n" +
            "• Show Calibration Guide Again — re-enables the guided calibration flow.\n" +
            "• Show rename dialog after recording — prompts to name each recording.\n" +
            "• Recording quality — Lossless WAV, High, Medium, or Low AAC.\n" +
            "• Sensitivity slider — changes how aggressively glove motion maps to sound.\n\n" +
            "Good defaults for a first-time demo:\n\n" +
            "• Leave extended range off.\n" +
            "• Leave direction toggles at their default values.\n" +
            "• Keep the rename dialog enabled.\n" +
            "• Keep the calibration guide available until it has been completed once."
        },
        {
            "11. TROUBLESHOOTING",
            "No sound:\n\n" +
            "• Make sure both gloves are connected.\n" +
            "• Press the Play transport button.\n" +
            "• Check that phone volume is up.\n" +
            "• If only one glove is connected, theremin output is intentionally muted.\n\n" +
            "Glove not found:\n\n" +
            "• Turn Bluetooth on.\n" +
            "• Power-cycle the glove.\n" +
            "• Check the glove name (ThereminGlove / ThereminGloveVol).\n" +
            "• Bring the glove closer to the phone.\n\n" +
            "Pitch or volume feels wrong:\n\n" +
            "• Recalibrate.\n" +
            "• Check the direction toggles in Settings.\n" +
            "• Review your calibrated min/max ranges.\n\n" +
            "Recording failed:\n\n" +
            "• Grant the recording permission.\n" +
            "• Make sure audio was running before pressing Record.\n" +
            "• Check free storage space.\n\n" +
            "Bluetooth turned off during use:\n\n" +
            "• Turn Bluetooth back on and let the app reconnect, or return to Connect and use Connect All."
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
