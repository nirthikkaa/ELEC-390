package com.example.thereminglovestest2;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
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

@SuppressLint("SetTextI18n")

public class ConnectionActivity extends AppCompatActivity {

    // BLE device names (must match Arduino)
    private static final String PITCH_DEVICE_NAME = "ThereminGlove";
    private static final String VOLUME_DEVICE_NAME = "ThereminGloveVol";
    
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

    // Device state
    private BluetoothDevice pitchDevice = null;
    private BluetoothDevice volumeDevice = null;
    private boolean isPitchConnected = false;
    private boolean isVolumeConnected = false;

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

        btnConnectPitchConfirm.setOnClickListener(v -> connectDevice(pitchDevice, true));
        btnConnectVolumeConfirm.setOnClickListener(v -> connectDevice(volumeDevice, false));
        
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

        isScanning = true;
        btnScanDevices.setText("Stop Scanning");
        tvPitchScanStatus.setText("Scanning...");
        tvVolumeScanStatus.setText("Scanning...");

        // Reset found devices
        pitchDevice = null;
        volumeDevice = null;
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
            
            try {
                String deviceName = device.getName();
                if (deviceName == null) return;

                if (PITCH_DEVICE_NAME.equals(deviceName) && pitchDevice == null) {
                    pitchDevice = device;
                    runOnUiThread(() -> {
                        tvPitchDeviceName.setText(deviceName);
                        tvPitchDeviceName.setVisibility(View.VISIBLE);
                        tvPitchFound.setVisibility(View.VISIBLE);
                        btnConnectPitchConfirm.setEnabled(true);
                        updateUI();
                    });
                } else if (VOLUME_DEVICE_NAME.equals(deviceName) && volumeDevice == null) {
                    volumeDevice = device;
                    runOnUiThread(() -> {
                        tvVolumeDeviceName.setText(deviceName);
                        tvVolumeDeviceName.setVisibility(View.VISIBLE);
                        tvVolumeFound.setVisibility(View.VISIBLE);
                        btnConnectVolumeConfirm.setEnabled(true);
                        updateUI();
                    });
                }

                // Auto-stop scanning if both devices found
                if (pitchDevice != null && volumeDevice != null) {
                    stopScanning();
                }
            } catch (SecurityException e) {
                // Handle permission issues silently
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
        if (device == null) return;

        // Update UI to show connecting state
        if (isPitchGlove) {
            pitchState = ConnectionState.CONNECTING;
        } else {
            volumeState = ConnectionState.CONNECTING;
        }
        updateUI();

        // Mock connection for now - in real implementation this would use BluetoothGatt
        mainHandler.postDelayed(() -> {
            // Simulate successful connection
            if (isPitchGlove) {
                pitchState = ConnectionState.CONNECTED;
                isPitchConnected = true;
            } else {
                volumeState = ConnectionState.CONNECTED;
                isVolumeConnected = true;
            }
            updateUI();
        }, 2000);
    }

    private void updateUI() {
        // Update pitch glove buttons
        switch (pitchState) {
            case DISCONNECTED:
                btnConnectPitch.setText("Connect?");
                btnConnectPitch.setTextColor(pitchDevice != null ? 
                    getColor(android.R.color.black) : getColor(android.R.color.darker_gray));
                btnConnectPitchConfirm.setText("Connect");
                btnConnectPitchConfirm.setEnabled(pitchDevice != null);
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
                btnConnectVolume.setTextColor(volumeDevice != null ? 
                    getColor(android.R.color.black) : getColor(android.R.color.darker_gray));
                btnConnectVolumeConfirm.setText("Connect");
                btnConnectVolumeConfirm.setEnabled(volumeDevice != null);
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
        if (isPitchConnected && isVolumeConnected) {
            btnContinue.setVisibility(View.VISIBLE);
        } else {
            btnContinue.setVisibility(View.GONE);
        }
    }

    private boolean checkPermissions() {
        String[] permissions;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = new String[]{
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        } else {
            permissions = new String[]{
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        }

        for (String permission : permissions) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, permissions, REQ_PERMS);
                return false;
            }
        }
        return true;
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
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScanning();
    }
}