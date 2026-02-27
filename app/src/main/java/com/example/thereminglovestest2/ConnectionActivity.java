package com.example.thereminglovestest2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@SuppressLint("SetTextI18n")

public class ConnectionActivity extends AppCompatActivity {

    // BLE device names (must match Arduino)
    private static final String PITCH_DEVICE_NAME = "ThereminGlove";
    private static final String VOLUME_DEVICE_NAME = "ThereminGloveVol";
    
    // BLE UUIDs (must match Arduino)
    private static final UUID SERVICE_UUID = 
            UUID.fromString("12345678-1234-1234-1234-1234567890ab");
    private static final UUID TX_CHAR_UUID = 
            UUID.fromString("12345678-1234-1234-1234-1234567890ac");
    private static final UUID RX_CHAR_UUID = 
            UUID.fromString("12345678-1234-1234-1234-1234567890ad");
    private static final UUID CCCD_UUID = 
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    
    private static final int REQ_PERMS = 1001;
    private static final long SCAN_TIMEOUT_MS = 12000;

    // UI Elements
    private TextView tvPitchScanStatus, tvVolumeScanStatus;
    private TextView tvPitchFound, tvVolumeFound;
    private TextView tvPitchDeviceName, tvVolumeDeviceName;
    private TextView btnConnectPitch, btnConnectVolume;
    private Button btnScanDevices, btnConnectPitchConfirm;
    private Button btnConnectVolumeConfirm, btnContinue;
    private ImageView btnBack;

    // BLE
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner bleScanner;
    private boolean isScanning = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Real BLE glove clients
    private final GloveClient pitchGlove = new GloveClient("PITCH", PITCH_DEVICE_NAME);
    private final GloveClient volumeGlove = new GloveClient("VOLUME", VOLUME_DEVICE_NAME);

    // Connection states
    private enum ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }

    private ConnectionState pitchState = ConnectionState.DISCONNECTED;
    private ConnectionState volumeState = ConnectionState.DISCONNECTED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection);

        initViews();
        setupBluetooth();
        setupClickListeners();
        updateUI();
    }

    private void initViews() {
        btnBack = findViewById(R.id.btnBack);
        tvPitchScanStatus = findViewById(R.id.tvPitchScanStatus);
        tvVolumeScanStatus = findViewById(R.id.tvVolumeScanStatus);
        tvPitchFound = findViewById(R.id.tvPitchFound);
        tvVolumeFound = findViewById(R.id.tvVolumeFound);
        tvPitchDeviceName = findViewById(R.id.tvPitchDeviceName);
        tvVolumeDeviceName = findViewById(R.id.tvVolumeDeviceName);
        btnScanDevices = findViewById(R.id.btnScanDevices);
        btnConnectPitch = findViewById(R.id.btnConnectPitch);
        btnConnectPitchConfirm = findViewById(R.id.btnConnectPitchConfirm);
        btnConnectVolume = findViewById(R.id.btnConnectVolume);
        btnConnectVolumeConfirm = findViewById(R.id.btnConnectVolumeConfirm);
        btnContinue = findViewById(R.id.btnContinue);
    }

    private void setupBluetooth() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager != null) {
            bluetoothAdapter = bluetoothManager.getAdapter();
        }

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_LONG).show();
        }
    }

    private void setupClickListeners() {
        btnBack.setOnClickListener(v -> {
            // Go back to welcome screen
            finish();
        });

        btnScanDevices.setOnClickListener(v -> {
            if (isScanning) {
                stopScanning();
            } else {
                startScanning();
            }
        });

        btnConnectPitchConfirm.setOnClickListener(v -> {
            // Connection happens automatically during scan
            Toast.makeText(this, "Connection handled automatically during scan", Toast.LENGTH_SHORT).show();
        });
        btnConnectVolumeConfirm.setOnClickListener(v -> {
            // Connection happens automatically during scan  
            Toast.makeText(this, "Connection handled automatically during scan", Toast.LENGTH_SHORT).show();
        });
        
        btnContinue.setOnClickListener(v -> {
            // Navigate to MainActivity with connection established
            Intent intent = new Intent(ConnectionActivity.this, MainActivity.class);
            intent.putExtra("gloves_connected", true);
            startActivity(intent);
            finish();
        });
    }

    private void startScanning() {
        if (!checkPermissions()) {
            requestRequiredPermissions();
            return;
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show();
            return;
        }

        bleScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bleScanner == null) {
            Toast.makeText(this, "BLE scanner not available", Toast.LENGTH_SHORT).show();
            return;
        }

        // Reset glove scan state
        pitchGlove.seenDuringCurrentScan = false;
        volumeGlove.seenDuringCurrentScan = false;

        isScanning = true;
        btnScanDevices.setText("Stop Scanning");
        tvPitchScanStatus.setText("Scanning...");
        tvVolumeScanStatus.setText("Scanning...");
        updateUI();

        try {
            bleScanner.startScan(scanCallback);
        } catch (SecurityException e) {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_SHORT).show();
            stopScanning();
            return;
        }

        // Auto-stop scanning after timeout
        mainHandler.postDelayed(this::stopScanning, SCAN_TIMEOUT_MS);
    }

    private void stopScanning() {
        if (!isScanning) return;

        isScanning = false;
        btnScanDevices.setText("Scan");
        tvPitchScanStatus.setText("Ready");
        tvVolumeScanStatus.setText("Ready");

        if (bleScanner != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (SecurityException e) {
                // Handle permission issues
            }
        }

        updateUI();
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            String name = safeDeviceName(device);
            if (name == null) return;

            if (PITCH_DEVICE_NAME.equals(name)) {
                pitchGlove.seenDuringCurrentScan = true;
                runOnUiThread(() -> {
                    tvPitchDeviceName.setText(name);
                    tvPitchDeviceName.setVisibility(View.VISIBLE);
                    tvPitchFound.setVisibility(View.VISIBLE);
                    btnConnectPitchConfirm.setEnabled(true);
                    updateUI();
                });
                maybeConnectToGloveDevice(pitchGlove, device);
            } else if (VOLUME_DEVICE_NAME.equals(name)) {
                volumeGlove.seenDuringCurrentScan = true;
                runOnUiThread(() -> {
                    tvVolumeDeviceName.setText(name);
                    tvVolumeDeviceName.setVisibility(View.VISIBLE);
                    tvVolumeFound.setVisibility(View.VISIBLE);
                    btnConnectVolumeConfirm.setEnabled(true);
                    updateUI();
                });
                maybeConnectToGloveDevice(volumeGlove, device);
            }

            // Auto-stop scanning if both devices are connected
            if (pitchGlove.connected && volumeGlove.connected) {
                stopScanning();
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            runOnUiThread(() -> {
                Toast.makeText(ConnectionActivity.this, "Scan failed: " + errorCode, Toast.LENGTH_SHORT).show();
                stopScanning();
            });
        }
    };

    private void connectDevice(BluetoothDevice device, boolean isPitchGlove) {
        // This is now handled automatically by the scan callback
        // when devices are discovered, they connect immediately
    }

    private void updateUI() {
        // Update pitch glove state based on real connection
        pitchState = pitchGlove.connected ? ConnectionState.CONNECTED : 
                    (pitchGlove.connecting ? ConnectionState.CONNECTING : ConnectionState.DISCONNECTED);
        
        // Update volume glove state based on real connection
        volumeState = volumeGlove.connected ? ConnectionState.CONNECTED : 
                     (volumeGlove.connecting ? ConnectionState.CONNECTING : ConnectionState.DISCONNECTED);

        // Update pitch glove buttons
        switch (pitchState) {
            case DISCONNECTED:
                btnConnectPitch.setText("Connect?");
                btnConnectPitch.setTextColor(pitchGlove.seenDuringCurrentScan ? 
                    getColor(android.R.color.black) : getColor(android.R.color.darker_gray));
                btnConnectPitchConfirm.setText("Connect");
                btnConnectPitchConfirm.setEnabled(pitchGlove.seenDuringCurrentScan);
                break;
            case CONNECTING:
                btnConnectPitch.setText("Connecting...");
                btnConnectPitch.setTextColor(getColor(android.R.color.darker_gray));
                btnConnectPitchConfirm.setText("Connecting...");
                btnConnectPitchConfirm.setEnabled(false);
                break;
            case CONNECTED:
                btnConnectPitch.setText("Connected");
                btnConnectPitch.setTextColor(getColor(android.R.color.holo_green_dark));
                btnConnectPitchConfirm.setText("Connected");
                btnConnectPitchConfirm.setEnabled(false);
                break;
        }

        // Update volume glove buttons
        switch (volumeState) {
            case DISCONNECTED:
                btnConnectVolume.setText("Connect?");
                btnConnectVolume.setTextColor(volumeGlove.seenDuringCurrentScan ? 
                    getColor(android.R.color.black) : getColor(android.R.color.darker_gray));
                btnConnectVolumeConfirm.setText("Connect");
                btnConnectVolumeConfirm.setEnabled(volumeGlove.seenDuringCurrentScan);
                break;
            case CONNECTING:
                btnConnectVolume.setText("Connecting...");
                btnConnectVolume.setTextColor(getColor(android.R.color.darker_gray));
                btnConnectVolumeConfirm.setText("Connecting...");
                btnConnectVolumeConfirm.setEnabled(false);
                break;
            case CONNECTED:
                btnConnectVolume.setText("Connected");
                btnConnectVolume.setTextColor(getColor(android.R.color.holo_green_dark));
                btnConnectVolumeConfirm.setText("Connected");
                btnConnectVolumeConfirm.setEnabled(false);
                break;
        }

        // Show continue button if both gloves are connected
        if (pitchGlove.connected && volumeGlove.connected) {
            btnContinue.setVisibility(View.VISIBLE);
        } else {
            btnContinue.setVisibility(View.GONE);
        }
    }

    private boolean checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return hasPermission(Manifest.permission.BLUETOOTH_SCAN)
                    && hasPermission(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            return hasPermission(Manifest.permission.ACCESS_FINE_LOCATION);
        }
    }

    private boolean hasPermission(String perm) {
        return ActivityCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                    },
                    REQ_PERMS
            );
        } else {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQ_PERMS
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Permissions granted - starting scan", Toast.LENGTH_SHORT).show();
                startScanning();
            }
        }
    }

    @SuppressLint("MissingPermission")  
    private void maybeConnectToGloveDevice(GloveClient glove, BluetoothDevice device) {
        if (glove.connected || glove.connecting) return;
        if (glove.gatt != null) return;

        glove.connecting = true;
        glove.lastDeviceAddress = device.getAddress();
        updateUI();

        BluetoothGattCallback callback = createGattCallback(glove);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                glove.gatt = device.connectGatt(this, false, callback, BluetoothDevice.TRANSPORT_LE);
            } else {
                glove.gatt = device.connectGatt(this, false, callback);
            }
        } catch (SecurityException e) {
            glove.connecting = false;
            glove.gatt = null;
            Toast.makeText(this, glove.roleLabel + ": connect permission error", Toast.LENGTH_SHORT).show();
        }
    }

    private BluetoothGattCallback createGattCallback(GloveClient glove) {
        return new BluetoothGattCallback() {
            @Override
            public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    glove.connecting = false;
                    glove.connected = true;
                    runOnUiThread(() -> {
                        Toast.makeText(ConnectionActivity.this, glove.roleLabel + " connected!", Toast.LENGTH_SHORT).show();
                        updateUI();
                    });
                    try {
                        gatt.discoverServices();
                    } catch (SecurityException e) {
                        // Handle permission error
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    glove.connecting = false;
                    glove.connected = false;
                    glove.notificationsEnabled = false;
                    glove.txChar = null;
                    glove.rxChar = null;

                    try { gatt.close(); } catch (Exception ignored) {}
                    if (glove.gatt == gatt) glove.gatt = null;

                    runOnUiThread(() -> {
                        Toast.makeText(ConnectionActivity.this, glove.roleLabel + " disconnected", Toast.LENGTH_SHORT).show();
                        updateUI();
                    });
                }
            }

            @Override
            public void onServicesDiscovered(BluetoothGatt gatt, int status) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    return;
                }

                BluetoothGattService service = gatt.getService(SERVICE_UUID);
                if (service == null) {
                    return;
                }

                glove.txChar = service.getCharacteristic(TX_CHAR_UUID);
                glove.rxChar = service.getCharacteristic(RX_CHAR_UUID);

                if (glove.txChar != null) {
                    enableNotifications(glove, gatt, glove.txChar);
                }
            }

            @Override
            public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
                glove.notificationsEnabled = (status == BluetoothGatt.GATT_SUCCESS);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic,
                                                byte[] value) {
                handleGloveNotification(glove, characteristic, value);
            }

            @Override
            public void onCharacteristicChanged(BluetoothGatt gatt,
                                                BluetoothGattCharacteristic characteristic) {
                handleGloveNotification(glove, characteristic, characteristic.getValue());
            }
        };
    }

    @SuppressLint("MissingPermission")
    private void enableNotifications(GloveClient glove, BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        try {
            boolean localOk = gatt.setCharacteristicNotification(characteristic, true);

            BluetoothGattDescriptor cccd = characteristic.getDescriptor(CCCD_UUID);
            if (cccd == null) {
                return;
            }

            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(cccd);
        } catch (SecurityException e) {
            // Handle permission error
        }
    }

    private void handleGloveNotification(GloveClient glove,
                                         BluetoothGattCharacteristic characteristic,
                                         byte[] value) {
        if (characteristic == null || value == null) return;
        if (!TX_CHAR_UUID.equals(characteristic.getUuid())) return;

        String line = new String(value, StandardCharsets.UTF_8).trim();
        glove.lastPacket = line;

        // Update UI on main thread for any received data
        runOnUiThread(() -> {
            Toast.makeText(ConnectionActivity.this, 
                glove.roleLabel + ": " + line, Toast.LENGTH_SHORT).show();
        });
    }

    private String safeDeviceName(BluetoothDevice device) {
        if (device == null) return null;
        try {
            return device.getName();
        } catch (SecurityException e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private void disconnectGlove(GloveClient glove) {
        glove.connecting = false;
        glove.connected = false;
        glove.notificationsEnabled = false;

        if (glove.gatt != null) {
            try { glove.gatt.disconnect(); } catch (Exception ignored) {}
            try { glove.gatt.close(); } catch (Exception ignored) {}
        }

        glove.gatt = null;
        glove.txChar = null;
        glove.rxChar = null;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScanning();
        disconnectGlove(pitchGlove);
        disconnectGlove(volumeGlove);
    }

    // =========================================================
    // GloveClient - BLE connection holder
    // =========================================================
    private static class GloveClient {
        final String roleLabel;
        final String targetDeviceName;

        BluetoothGatt gatt;
        BluetoothGattCharacteristic txChar;
        BluetoothGattCharacteristic rxChar;

        boolean connecting = false;
        boolean connected = false;
        boolean notificationsEnabled = false;
        boolean seenDuringCurrentScan = false;

        String lastDeviceAddress = "";
        String lastPacket = "(none)";

        GloveClient(String roleLabel, String targetDeviceName) {
            this.roleLabel = roleLabel;
            this.targetDeviceName = targetDeviceName;
        }
    }
}