package com.example.thereminglovestest2;

import android.content.Context;
import android.graphics.Color;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;

import com.google.android.material.card.MaterialCardView;

/**
 * Play-screen connection card label.
 *
 * This view does NOT trust the raw text passed in by MainActivity because old
 * code can still turn DISCONNECTED into READY (since the word DISCONNECTED
 * contains CONNECTED). Instead, it re-reads live BLE state from BleSessionManager,
 * rewrites its own label, and updates the parent card glow.
 */
public class ConnectionStatusTextView extends AppCompatTextView {

    private boolean internalUpdate = false;

    public ConnectionStatusTextView(@NonNull Context context) {
        super(context);
    }

    public ConnectionStatusTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ConnectionStatusTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(this::refreshFromBleState);
    }

    @Override
    public void setText(CharSequence text, BufferType type) {
        super.setText(text, type);
        if (!internalUpdate) {
            post(this::refreshFromBleState);
        }
    }

    private void refreshFromBleState() {
        boolean isVolume = isVolumeCard();
        String label = isVolume ? "Volume glove" : "Pitch glove";

        BleSessionManager.BleUiSnapshot bleSnapshot = BleSessionManager.getBleUiSnapshot();
        BleSessionManager.CalibrationUiSnapshot calSnapshot = BleSessionManager.getCalibrationUiSnapshot();

        StatusKind kind = StatusKind.DISCONNECTED;
        String detail = "Waiting";

        boolean hostReady = bleSnapshot != null && bleSnapshot.hostReady;
        boolean bluetoothEnabled = hostReady && bleSnapshot.bluetoothEnabled;
        boolean connected = calSnapshot != null
                && calSnapshot.hostReady
                && (isVolume ? calSnapshot.volumeConnected : calSnapshot.pitchConnected);
        String rawLine = hostReady ? (isVolume ? bleSnapshot.volumeConnText : bleSnapshot.pitchConnText) : null;
        boolean connecting = bluetoothEnabled
                && !connected
                && (BleUiText.isConnecting(rawLine) || bleSnapshot.scanning);

        if (hostReady && !bluetoothEnabled) {
            kind = StatusKind.DISCONNECTED;
            detail = "Bluetooth off";
        } else if (connected) {
            kind = StatusKind.CONNECTED;
            detail = "Connected";
        } else if (connecting) {
            kind = StatusKind.CONNECTING;
            detail = "Connecting…";
        }

        internalUpdate = true;
        super.setText(label + "\n" + detail, BufferType.NORMAL);
        internalUpdate = false;

        applyGlow(kind);
    }

    private boolean isVolumeCard() {
        try {
            String entryName = getResources().getResourceEntryName(getId());
            return "tvVolConn".equals(entryName);
        } catch (Exception ignored) {
            CharSequence current = getText();
            return current != null && current.toString().toLowerCase().contains("volume");
        }
    }

    private void applyGlow(StatusKind kind) {
        MaterialCardView parentCard = findParentCard();
        if (parentCard == null) return;

        int bgColor;
        int strokeColor;
        int textColor;

        switch (kind) {
            case CONNECTED:
                bgColor = Color.parseColor("#173426");
                strokeColor = Color.parseColor("#49E37A");
                textColor = Color.parseColor("#F2FFF6");
                break;

            case CONNECTING:
                bgColor = Color.parseColor("#262041");
                strokeColor = Color.parseColor("#8A7DFF");
                textColor = Color.parseColor("#F3F0FF");
                break;

            case DISCONNECTED:
            default:
                bgColor = Color.parseColor("#351822");
                strokeColor = Color.parseColor("#FF647D");
                textColor = Color.parseColor("#FFF2F4");
                break;
        }

        setTextColor(textColor);
        parentCard.setCardBackgroundColor(bgColor);
        parentCard.setStrokeColor(strokeColor);
        parentCard.setCardElevation(0f);
    }

    private @Nullable MaterialCardView findParentCard() {
        if (getParent() instanceof MaterialCardView) {
            return (MaterialCardView) getParent();
        }
        return null;
    }

    private enum StatusKind {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }
}