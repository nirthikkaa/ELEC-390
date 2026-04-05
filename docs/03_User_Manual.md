# Theremin Gloves — User Manual

> This manual is also available inside the app at **Settings → User Manual**.

---

## Quick Start

New here? Follow these five steps and you'll be playing in under two minutes:

1. Power on both gloves.
2. Open **Connect** and tap **Connect All** if they don't auto-connect.
3. Open **Cal**, tap **Pitch Neutral**, then **Volume Neutral**, then **Save & Play**.
4. Open **Play** and press the centre **Play** button.
5. Tilt your pitch glove to change pitch; tilt your volume glove to change loudness.

> **Tip:** Calibration is the most important step — it teaches the app your natural resting wrist position. Do it first, and redo it any time the feel seems off.

---

## 1. What You Need

Before you start, make sure you have:

- An Android phone running Android 12 (API 31) or newer.
- Two Theremin Gloves powered on and within range.
- Bluetooth enabled on your phone.

Your gloves must broadcast these **exact** names:

| Glove | Expected name |
|-------|---------------|
| Pitch | `ThereminGlove` |
| Volume | `ThereminGloveVol` |

> **Tip:** Power on the gloves *before* opening the Connect screen — the app starts scanning automatically when you open that tab.

---

## 2. First Launch

When you open the app for the first time it checks Bluetooth permissions and, if needed, asks you to turn Bluetooth on. First-time users are routed to the **Setup** (`HomeActivity`) screen so they can connect and calibrate before playing. Returning users usually go straight to **Play**.

The five tabs along the bottom of every screen are:

| Tab | What it does |
|-----|-------------|
| **Play** | Where you perform |
| **Connect** | Manage your glove connections |
| **Cal** | Set your neutral wrist positions and playing ranges |
| **Library** | Listen to and organise your recordings |
| **Settings** | Adjust app preferences |

> **Tip:** If the app asks for Bluetooth permission, tap **Allow** — it cannot find your gloves without it.

---

## 3. Connecting Your Gloves

Open the **Connect** tab. The app usually connects automatically — if it doesn't, tap **Connect All**.

**What the status label means:**

| Status | Meaning |
|--------|---------|
| Waiting | Still searching for the glove |
| Connecting… | Found it; establishing the link |
| Connected | Glove is live and sending data |
| Connected · no data | Link is open but data has stopped arriving |
| Bluetooth off | Turn on Bluetooth in your phone's settings |

**If a glove won't connect:**

1. Make sure Bluetooth is turned on.
2. Power the glove off, wait 5 seconds, then power it back on.
3. Move the glove within 2–3 metres of your phone.
4. Tap **Connect All** again.

The app remembers your gloves after the first connection — it reconnects automatically whenever a glove is powered on nearby.

---

## 4. Calibrating

Calibration tells the app what "neutral" means for each hand. Do this before your first performance and any time the feel seems off.

### Step-by-step

1. Go to the **Cal** tab and make sure you are on the **Pitch** tab.
2. Hold your pitch hand in a relaxed, natural resting position.
3. Tap **Pitch Neutral** — the app records that wrist angle as your zero point.
4. Switch to the **Volume** tab.
5. Hold your volume hand in a relaxed, natural resting position.
6. Tap **Volume Neutral**.
7. Adjust the min/max sliders to narrow or widen your gesture range if needed.
8. Tap **Save & Play** when you are happy with the settings.

### Default ranges

| Parameter | Min | Max |
|-----------|-----|-----|
| Pitch angle | 0° | 90° |
| Volume angle | 0° | 90° |
| Frequency | 20 Hz | 2,000 Hz |

### What "neutral" means

Neutral is the wrist orientation you want to treat as the baseline resting position. All live motion is measured relative to that baseline — so choose a position you can comfortably return to between phrases.

> **Tip:** The Cal screen plays live audio using your draft settings so you can *hear* the effect of any change before you save.

---

## 5. Playing the Theremin

Open the **Play** tab and press the **Play** button to start sound.

### Gesture controls

| Glove | Gesture | Effect |
|-------|---------|--------|
| Pitch | Tilt wrist up/down | Raises or lowers pitch |
| Volume | Tilt wrist up/down | Makes sound louder or softer |

Both gloves must be connected. If either disconnects, audio mutes automatically until it reconnects.

### What you see on screen

- **Frequency and volume readout** — real-time numbers as you play.
- **Waveform visualiser** — shows your synth output (not the microphone).
- **Record button** — starts and stops a recording.
- **Tone selector knob** — rotary wheel to pick your sound.
- **Scale lock** — constrains pitch to a musical scale.
- **Octave shift** — moves your range up or down by up to ±2 octaves.
- **Effects panel** — reverb, delay, and distortion toggles.
- **Beat/bass controls** — drum patterns and the **BEATS** button.
- **Stage View** (top bar) — a clean, minimal display for live performance.

> **Tip:** Adjust the **Sensitivity** slider in Settings to make gestures more responsive or more precise.

---

## 6. Tone Selection

Rotate the **tone knob** on the Play screen to cycle through 11 sounds:

| Tone | Character |
|------|-----------|
| **Theremin** | Classic singing theremin — the go-to starting point |
| **Air Pad** | Soft and drifting; great for ambient textures |
| **Cello** | Dark and bowed; expressive for melodies |
| **Pad** | Warm and sustained; good for slow, dreamy passages |
| **Choir** | Ethereal vocal quality |
| **Flute** | Light and airy; easy on the ears |
| **Clarinet** | Woody reed tone; expressive mid-range |
| **Triangle** | Hollow, pure tone with few overtones |
| **Saw** | Bright and sharp; classic synthesiser sound |
| **Square** | Hollow odd-harmonic buzz; retro feel |
| **Helicopter** | Rhythmic rotor effect; great for experimenting |

> **Tip:** Try **Theremin** or **Cello** with **Reverb** turned on for a full, rich room sound.

---

## 7. Effects, Scale Lock, and Beats

### Scale lock

Scale lock keeps your pitch on familiar notes — useful if you are still building muscle memory:

| Mode | What it does |
|------|-------------|
| CHROM | No lock; every frequency available |
| MAJOR | Snaps to major scale notes |
| MINOR | Snaps to minor scale notes |
| PENTA | Snaps to pentatonic notes (great for beginners) |

### Octave shift

Use the **−** and **+** buttons to step from −2 to +2 octaves. Useful for finding a comfortable pitch range or for dramatic effect.

### Audio effects

| Effect | What it adds |
|--------|-------------|
| **Reverb** | Makes the sound bloom and decay like a real room |
| **Delay** | Repeating echo; the feedback knob controls how long it rings out |
| **Distortion** | Grit and saturation for a dirtier, more aggressive sound |

### Beat Maker

- 8 built-in drum patterns to choose from.
- Tap **BEATS** to open the full Beat Maker screen with a step sequencer.
- BPM control and separate synth/beat gain sliders.

---

## 8. Recording a Performance

### How to record

1. Start playing from the Play screen.
2. Tap the **Record** button — a timer appears and the button blinks red.
3. Perform.
4. Tap **Record** again to stop and save.

The recording captures the full synthesiser output — drums, effects, and all. It does **not** use the phone's microphone.

Recordings are saved privately on the device. The app also creates a copy in your **Music** folder (*Theremin Gloves Recordings*) so they appear in other music apps.

### Quality options

Set your preferred quality in **Settings** before recording:

| Option | Quality | File size |
|--------|---------|-----------|
| Lossless WAV | Best | Largest |
| High AAC | Near-lossless | Small |
| Medium AAC | Good | Compact |
| Low AAC | Acceptable | Smallest |

> **Tip:** On your first recording the app will ask for audio permission — tap **Allow** to proceed.

---

## 9. The Library Screen

Open the **Library** tab to listen to and manage everything you have recorded.

**What you can do:**

- Tap any recording to play it back.
- **Search** recordings by name.
- Use a recording's **three-dot menu** to **rename**, **move**, or **delete** it.
- **Create folders** and drag a recording onto a folder card to file it quickly.
- Use the **mini-player** at the bottom to pause, seek, and loop.
- **Filter** by length or date to find older sessions quickly.

Library playback works even when the gloves are turned off — you can listen to your saved performances any time.

---

## 10. Settings

Tap the **Settings** tab to customise the app.

| Setting | What it does |
|---------|-------------|
| Background audio | Keeps sound going when you leave the Play screen |
| Extended frequency range | Raises the top pitch from 2,000 Hz to 20,000 Hz |
| Invert pitch direction | Flips which tilt direction raises pitch |
| Invert volume direction | Flips which tilt direction raises volume |
| Show Calibration Guide Again | Re-enables the step-by-step calibration walkthrough |
| Rename dialog after recording | Prompts you to name each recording when you stop |
| Recording quality | WAV (lossless) or AAC at High / Medium / Low |
| Sensitivity | How strongly wrist movement maps to sound |

**Recommended starting point:**

- Leave **Extended frequency range** off until you need very high pitches.
- Keep the **rename dialog** on so recordings are easy to find later.
- Start with **Medium sensitivity** and adjust to taste.

---

## 11. Troubleshooting

### No sound

- Press the **Play** button — audio does not start until you tap it.
- Make sure **both** gloves are connected; sound mutes if either is missing.
- Turn up the phone volume.

### Glove won't connect

1. Check that Bluetooth is on.
2. Power-cycle the glove (off → wait 5 seconds → on).
3. Confirm the glove name is exactly `ThereminGlove` or `ThereminGloveVol`.
4. Move the glove within 2–3 metres of the phone and tap **Connect All**.

### Pitch or volume feels off

- **Recalibrate** — open Cal, capture both neutral positions again, tap Save & Play.
- Check the **direction toggles** in Settings if movement feels backwards.
- Widen or narrow the **min/max sliders** in Cal for a more comfortable range.

### Recording failed

- Tap **Allow** if the app asks for permission.
- Make sure the Play transport is running *before* you tap Record.
- Free up storage space if the phone is nearly full.

### Bluetooth turned off during play

- Turn Bluetooth back on — the app reconnects automatically.
- Or open **Connect** and tap **Connect All** to reconnect right away.

### Library plays fine but theremin doesn't work

- Library uses saved files and does not need the gloves.
- Live theremin requires **both gloves connected** and the **Play button active**.
- Return to Connect, reconnect the gloves, then press Play.
