# Theremin Gloves — User Manual

> This manual is also available inside the app at **Settings → User Manual**.

## Quick Start

1. Power on both gloves.
2. Open **Connect** and tap **Connect All** if they do not auto-connect.
3. Open **Cal**, capture **Pitch Neutral**, then **Volume Neutral**, then tap **Save & Play**.
4. Open **Play** and press the center **Play** button.
5. Move the pitch glove to change pitch and the volume glove to change loudness.

---

## 1. Hardware Setup

You need:
- an Android phone running API 31 or higher
- two BLE gloves built around Arduino Nano 33 BLE Sense boards
- the Theremin Gloves app installed on the phone

Required glove names:
- pitch glove: `ThereminGlove`
- volume glove: `ThereminGloveVol`

Power on both gloves before trying to connect. The app looks for those exact BLE names.

---

## 2. First Launch

When the app starts, `LaunchActivity` warms dependencies, checks Bluetooth permissions, and prompts to turn Bluetooth on if needed.

After that, the app routes you to:
- **Setup** on first launch
- **Play** on later launches

The bottom navigation bar contains five tabs: **Play**, **Connect**, **Cal**, **Library**, **Settings**.

---

## 3. Connecting Your Gloves

**Recommended steps:**
1. Open the **Connect** tab.
2. Make sure Bluetooth is on.
3. Power on both gloves.
4. Wait for the app to discover them, or tap **Connect All**.

**Connection status meanings:**

| Status | Meaning |
|---|---|
| `Waiting` | No live connection yet |
| `Connecting…` | Scan / GATT connection in progress |
| `Connected` | Glove is connected and telemetry is arriving |
| `Connected · no data` | Connected but telemetry has gone stale |
| `Bluetooth off` | Phone Bluetooth is disabled |

**If a glove does not appear:**
- Verify Bluetooth is on and the glove is powered on
- Verify the glove name is exactly `ThereminGlove` or `ThereminGloveVol`
- Move the glove closer to the phone
- Use the per-glove reconnect button or **Connect All**

The app keeps a cached device address and tries to reconnect automatically if a glove drops.

---

## 4. Calibrating

Open the **Cal** tab before serious use.

1. Stay on the **Pitch** tab.
2. Hold your pitch hand in a natural relaxed neutral position.
3. Tap **Pitch Neutral**.
4. Switch to the **Volume** tab.
5. Hold your volume hand in its natural neutral position.
6. Tap **Volume Neutral**.
7. Adjust the min/max angle and frequency controls if needed.
8. Tap **Save & Play**.

**Default ranges:**
- Settings defaults: pitch `0°–90°`, volume `0°–90°`, frequency `20–2000 Hz`
- Play reset defaults: pitch `−15°–55°`, volume `−10°–55°`, frequency `880–2000 Hz`

You do not need to disconnect the gloves to recalibrate. The calibration screen can also play live preview audio with your draft settings.

---

## 5. Playing the Theremin

Open the **Play** tab.

- **Pitch glove** — wrist movement changes pitch
- **Volume glove** — wrist movement changes loudness
- Both gloves must be connected for live theremin output

**On-screen features:**
- Live frequency and volume readout
- Large audio visualizer (shows synthesized output, not microphone)
- Record button
- Play transport button
- Rotary tone selector
- Scale lock controls
- Octave shift controls
- Effects controls (Reverb, Delay, Distortion)
- Beat and bass controls
- **BEATS** button for Beat Maker access
- **Stage View** action in the top bar

---

## 6. Tone Selection

The Play screen uses the tone knob to cycle through 11 tones:

| Knob Label | Tone | Character |
|---|---|---|
| `THR` | Theremin | Classic theremin-like, vocal/cello quality |
| `AIR` | Air Pad | Soft ambient pad |
| `CEL` | Cello | Dark bowed-string style |
| `PAD` | Pad | Warm sustained synth pad |
| `CHR` | Choir | Soft vocal pad |
| `FLT` | Flute | Light, smooth flute-like |
| `CLR` | Clarinet | Woody reed-like |
| `TRI` | Triangle | Hollow, cleaner synth |
| `SAW` | Saw | Bright, sharper synth |
| `SQR` | Square | Hollow, odd-harmonic square wave |
| `HEL` | Helicopter | Rhythmic rotor-like special effect |

---

## 7. Effects, Scale Lock & Beats

**Scale lock modes:**

| Mode | Behavior |
|---|---|
| `CHROM` | No lock, full chromatic range |
| `MAJOR` | Snaps to major scale notes |
| `MINOR` | Snaps to minor scale notes |
| `PENTA` | Snaps to pentatonic scale notes |

**Octave shift:** Use the `−` and `+` buttons. Range is `−2` to `+2` octaves.

**Effects:**
- **Reverb** — adds room ambience (Schroeder comb filter)
- **Delay** — adds echo with feedback control (ring buffer)
- **Distortion** — adds saturation/overdrive (tanh normalization)

**Beat tools:**
- 8 preset slots (Rock, Funk, EDM, Hip-Hop, Reggae, Jazz, Trap, Latin/Samba)
- **BEATS** button opens the Beat Maker
- BPM controls for drum and piano sequencing
- Separate synth and beat gain controls

---

## 8. Recording

1. Start audio from the Play screen.
2. Tap **Record**.
3. Perform.
4. Tap **Record** again to stop.

While recording the button shows a stop icon, a timer appears, and the button blinks.

**Quality options:**

| Option | Format |
|---|---|
| Lossless | WAV (uncompressed) |
| High | AAC 320 kbps |
| Medium | AAC 192 kbps |
| Low | AAC 128 kbps |

**Where recordings go:**
- Private copy: `getFilesDir()/recordings/` (app-internal)
- Export copy: `Music/Theremin Gloves Recordings` (user-visible)

---

## 9. The Library

The **Library** tab lets you manage saved performances.

- View recordings with date, duration, and quality badge
- Play recordings back without connecting the gloves
- Search by name
- Rename or delete recordings
- Create and manage folders
- Drag recordings onto folders to move them
- Multi-select delete and move
- Filter by duration or date range
- Mini-player with playback progress and loop mode

Library playback does not require gloves — you can listen to saved recordings with both gloves turned off.

---

## 10. Settings

| Setting | Effect |
|---|---|
| Keep audio playing when leaving Play | Lets playback continue via background service |
| Extended frequency range | Raises ceiling from 2,000 Hz to 20,000 Hz |
| Invert pitch glove direction | Reverses pitch mapping direction |
| Invert volume glove direction | Reverses volume mapping direction |
| Show Calibration Guide Again | Re-enables the guided calibration flow |
| Show rename dialog after recording | Prompts to name each recording |
| Recording quality | Lossless WAV, High, Medium, or Low AAC |
| Sensitivity slider | Changes how aggressively glove motion maps to sound |

**Good defaults for a first-time demo:**
- Leave extended range off
- Leave direction toggles at their default values
- Keep the rename dialog enabled
- Keep the calibration guide available until completed once

---

## 11. Troubleshooting

**No sound:**
- Make sure both gloves are connected
- Press the Play transport button
- Check that phone volume is up
- If only one glove is connected, theremin output is intentionally muted

**Glove not found:**
- Turn Bluetooth on
- Power-cycle the glove
- Check the glove name (`ThereminGlove` / `ThereminGloveVol`)
- Bring the glove closer to the phone

**Pitch or volume feels wrong:**
- Recalibrate
- Check the direction toggles in Settings
- Review your calibrated min/max ranges

**Recording failed:**
- Grant the recording permission when prompted
- Make sure audio was running before pressing Record
- Check free storage space (app requires at least 10 MB free)

**Bluetooth turned off during use:**
- Turn Bluetooth back on and let the app reconnect, or return to **Connect** and tap **Connect All**

**Library playback works but live theremin does not:**
- Library uses saved files — does not require gloves
- Live output requires both gloves connected and the Play transport active
