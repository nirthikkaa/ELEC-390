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
 * contains CONNECTED). Instead, it re-reads live BLE state from BleHostBridge,
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

        BleHostBridge.BleUiSnapshot bleSnapshot = BleHostBridge.getBleUiSnapshot();
        BleHostBridge.CalibrationUiSnapshot calSnapshot = BleHostBridge.getCalibrationUiSnapshot();

        StatusKind kind = StatusKind.DISCONNECTED;
        String detail = "Waiting";

        if (bleSnapshot != null && bleSnapshot.hostReady && bleSnapshot.bluetoothEnabled) {
            String rawLine = isVolume ? bleSnapshot.volumeConnText : bleSnapshot.pitchConnText;
            String upper = rawLine == null ? "" : rawLine.toUpperCase();
            boolean connected = calSnapshot != null && (isVolume ? calSnapshot.volumeConnected : calSnapshot.pitchConnected);

            if (upper.contains("DISCONNECTED") || upper.contains("OFF") || upper.contains("WAITING")) {
                kind = StatusKind.DISCONNECTED;
                detail = "Waiting";
            } else if (upper.contains("CONNECTING")) {
                kind = StatusKind.CONNECTING;
                detail = "Connecting…";
            } else if (connected || upper.contains("CONNECTED")) {
                kind = StatusKind.CONNECTED;
                detail = "Ready";
            }
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