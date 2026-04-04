# Theremin Gloves — User Manual

## Quick Start

If you only need the shortest working path:

1. Power on both gloves.
2. Open `Connect` and tap `Connect All` if they do not auto-connect.
3. Open `Cal`, capture `Pitch Neutral`, then `Volume Neutral`, then tap `Save & Play`.
4. Open `Play` and press the center `Play` button.
5. Move the pitch glove to change pitch and the volume glove to change loudness.

## 1. Getting Started — Hardware Setup

You need:
- an Android phone running API 24 or higher
- two BLE gloves built around Arduino Nano 33 BLE Sense boards
- the Theremin Gloves Android app installed on the phone

Required glove names:
- pitch glove: `ThereminGlove`
- volume glove: `ThereminGloveVol`

Power on both gloves before trying to connect. The app looks for those exact BLE names.

## 2. First Launch

When the app starts, `LaunchActivity` shows a small loading screen while it:
- warms the Play screen dependencies
- checks Bluetooth permissions
- prompts to turn Bluetooth on if needed

After that, the app routes you to either:
- `Setup` on first launch
- `Play` on later launches

The bottom navigation bar contains five tabs:
- `Play`
- `Connect`
- `Cal`
- `Library`
- `Settings`

## 3. Connecting Your Gloves

There are two ways to connect:
- automatic connection from the launch/setup flow
- manual connection from the `Connect` tab

Recommended manual steps:
1. Open the `Connect` tab.
2. Make sure Bluetooth is on.
3. Power on both gloves.
4. Wait for the app to discover them, or tap `Connect All`.

What the connection text means:
- `Waiting`: no live connection yet
- `Connecting…`: scan/GATT connection is in progress
- `Connected`: glove is connected and telemetry is arriving
- `Connected • no data`: glove is still connected but telemetry has gone stale
- `Bluetooth off`: phone Bluetooth is disabled

If a glove does not appear:
- verify Bluetooth is on
- verify the glove is powered on
- verify the glove name is exactly `ThereminGlove` or `ThereminGloveVol`
- move the glove closer to the phone
- use the per-glove reconnect button or `Connect All`

Auto-reconnect:
- the app keeps a cached device address
- if a glove drops, `BleSessionManager` tries to reconnect automatically

## 4. Calibrating

Open the `Cal` tab before serious use.

The current calibration flow is tab-based:
1. Stay on the `Pitch` tab.
2. Hold your pitch hand in a natural relaxed neutral position.
3. Tap `Pitch Neutral`.
4. Switch to the `Volume` tab.
5. Hold your volume hand in its natural neutral position.
6. Tap `Volume Neutral`.
7. Adjust the min/max angle and frequency controls if needed.
8. Tap `Save & Play`.

If the calibration guide is enabled, the next button to press glows green.

What “neutral” means:
- it is the wrist orientation you want to treat as the baseline resting position
- live motion is measured relative to that baseline

Recalibrating:
- you do not need to disconnect the gloves
- just reopen `Cal`, capture new neutral positions, and save again

Default ranges to know:
- the persisted Calibration/Settings defaults start at pitch `0°` to `90°`, volume `0°` to `90°`, and frequency `20 Hz` to `2000 Hz`
- the Play screen uses narrower live-control defaults when you reset Play mapping there: pitch `-15°` to `55°`, volume `-10°` to `55°`, and frequency `880 Hz` to `2000 Hz`

Calibration preview:
- the calibration screen can play live preview audio using the current draft settings
- this lets you hear changes before saving

## 5. Playing the Theremin

Open the `Play` tab.

Basic gesture mapping:
- pitch glove: wrist movement changes pitch
- volume glove: wrist movement changes loudness
- both gloves must be connected for live theremin output

On-screen Play features:
- live frequency readout
- live volume readout
- large audio visualizer
- `Record` button
- `Play` transport button
- rotary tone selector
- scale lock controls
- octave shift controls
- effects controls
- beat/bass controls
- `BEATS` button for Beat Maker access
- `Stage View` action in the top bar

The visualizer shows the current synthesized output, not microphone input.

## 6. Tone Selection

The Play screen uses `ToneKnobView` to cycle through the current public tone set:

- `Theremin`: classic theremin-like tone with a vocal/cello quality
- `Air Pad`: soft ambient pad
- `Cello`: dark bowed-string style tone
- `Pad`: warm sustained synth pad
- `Choir`: soft vocal pad
- `Flute`: light, smooth flute-like tone
- `Clarinet`: woody reed-like tone
- `Triangle`: hollow, cleaner synth tone
- `Saw`: bright, sharper synth tone
- `Helicopter`: rhythmic rotor-like special effect tone

Notes:
- the app still contains some hidden legacy tones internally for backward compatibility
- the user-facing selector currently exposes the curated 10-tone list above
- for current docs and presentations, describe these as `tones`, not as the old 9-waveform set

## 7. Effects, Scale Lock, and Beats

Play includes more than basic theremin control.

Scale lock:
- `CHROM`
- `MAJOR`
- `MINOR`
- `PENTA`

Octave shift:
- use the `-` and `+` buttons around the octave label
- range is from `-2` to `+2`

Effects:
- `Reverb`
- `Delay`
- `Distortion`

Beat and keyboard tools:
- 8 beat preset slots
- `BEATS` button to open the Beat Maker workflow
- keyboard mode and synth mode controls
- BPM controls for drum/piano sequencing
- separate synth and beat gain controls

## 8. Recording a Performance

To record:
1. Start audio from the Play screen.
2. Tap `Record`.
3. Perform.
4. Tap the same button again to stop.

What happens during recording:
- the record button changes into a stop state
- a timer appears
- the button blinks

First-time permission:
- the current UI still asks for `RECORD_AUDIO` permission before allowing the first recording
- the saved audio itself comes from the internal synth PCM tap, not from microphone capture

Where recordings go:
- the app keeps a private original copy under `getFilesDir()/recordings/`
- it also tries to create a second user-visible export copy in `Music/Theremin Gloves Recordings`

Quality options:
- `Lossless` WAV
- `High` AAC
- `Medium` AAC
- `Low` AAC

## 9. The Library Screen

The `Library` tab lets you manage saved performances.

Features:
- view recordings with date, duration, and quality badge
- play recordings back without connecting the gloves
- search by name
- rename recordings
- delete recordings
- create folders
- rename/delete folders
- drag recordings onto folders to move them
- multi-select delete and move
- filter by duration
- filter by date added, including a custom date range
- mini-player with playback progress
- playback modes such as single and loop

If there are no recordings, the screen shows an empty-state message instead of a list.

## 10. Settings

The `Settings` tab controls the main persistent app behavior.

Available settings:
- `Keep audio playing when leaving Play`
- `Extended frequency range`
- `Invert pitch glove direction`
- `Invert volume glove direction`
- `Show Calibration Guide Again`
- `Show rename dialog after recording`
- recording compression/quality
- sensitivity response slider

What they do:
- Background audio: lets playback continue through the foreground service when leaving Play
- Extended frequency range: raises the allowed frequency ceiling from `2,000 Hz` to `20,000 Hz`
- Direction toggles: invert the meaning of glove movement
- Calibration guide reset: shows the guided calibration flow again
- Sensitivity: changes how aggressively glove motion maps into sound

Good defaults for a first-time demo:
- leave extended range off
- leave direction toggles at their default values
- keep the rename dialog enabled
- keep the calibration guide available until the user has completed it once

## 11. Troubleshooting

No sound:
- make sure both gloves are connected
- make sure you pressed the Play transport button
- check that the phone volume is up
- if only one glove is connected, the theremin output is intentionally muted

Glove not found:
- turn Bluetooth on
- power-cycle the glove
- check the glove name
- bring the glove closer to the phone

Pitch or volume feels wrong:
- recalibrate
- check the direction toggles in Settings
- review your calibrated min/max ranges

Recording failed:
- grant the requested recording permission
- make sure audio was actually running before pressing Record
- check free storage space

Bluetooth was turned off during use:
- the app will show Bluetooth-off state
- turn Bluetooth back on and let the app reconnect, or return to `Connect` and use `Connect All`

If Library playback works but live theremin sound does not:
- Library uses saved files and does not require gloves
- live theremin output requires both gloves to be connected and the Play transport to be active
- turn Bluetooth back on and let the reconnect flow run again

Library playback does not require gloves:
- you can still listen to saved recordings even with both gloves turned off
