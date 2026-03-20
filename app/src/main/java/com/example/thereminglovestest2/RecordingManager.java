package com.example.thereminglovestest2;

import android.content.Context;
import android.os.StatFs;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Manages audio recording from ThereminAudioEngine via PCM tap.
 * Implements ThereminAudioEngine.PcmListener to receive audio samples in real-time.
 * Saves recordings as WAV files to app-private storage.
 */
public class RecordingManager implements ThereminAudioEngine.PcmListener {
    
    private Context context;
    private File recordingsDir;
    private RecordingRepository repository;
    private ThereminAudioEngine audioEngine;
    private boolean isRecording = false;
    private FileOutputStream fileOut;
    private String currentFilePath;
    private long recordingStartTime;
    private int totalSamplesWritten = 0;
    
    // Audio format constants (must match ThereminAudioEngine settings)
    // NOTE: ThereminAudioEngine uses 48000 Hz, not 44100
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 1;
    private static final int BIT_DEPTH = 16;
    
    public interface RecordingListener {
        void onRecordingError(String message);
        void onRecordingComplete(long recordingId, String filePath, long durationMs);
    }
    
    private RecordingListener listener;
    
    public RecordingManager(Context context, RecordingRepository repository, 
                           ThereminAudioEngine audioEngine) {
        this.context = context;
        this.repository = repository;
        this.audioEngine = audioEngine;
        this.recordingsDir = new File(context.getFilesDir(), "recordings");
        
        // ID-8.1: Create recordings storage directory on first use
        if (!recordingsDir.exists()) {
            boolean created = recordingsDir.mkdirs();
            if (!created && !recordingsDir.exists()) {
                throw new RuntimeException("Failed to create recordings directory");
            }
        }
    }
    
    public void setRecordingListener(RecordingListener listener) {
        this.listener = listener;
    }
    
    /**
     * Check available storage space before recording
     * ID-8.5: Verify at least 10MB free space
     */
    private boolean checkStorageSpace() {
        try {
            StatFs stat = new StatFs(recordingsDir.getPath());
            long availableBytes = stat.getAvailableBytes();
            
            // Need at least 10MB free space
            if (availableBytes < 10 * 1024 * 1024) {
                if (listener != null) {
                    listener.onRecordingError("Not enough storage space. Need at least 10MB free.");
                }
                return false;
            }
            return true;
        } catch (Exception e) {
            if (listener != null) {
                listener.onRecordingError("Could not check storage space: " + e.getMessage());
            }
            return false;
        }
    }
    
    /**
     * Start recording audio from ThereminAudioEngine
     * This registers the RecordingManager as a PcmListener via setPcmListener()
     */
    public void startRecording(String displayName) {
        // ID-8.5: Check storage before starting
        if (!checkStorageSpace()) {
            return;
        }
        
        if (isRecording) {
            if (listener != null) {
                listener.onRecordingError("Recording already in progress");
            }
            return;
        }
        
        try {
            // Create unique file name with timestamp
            long timestamp = System.currentTimeMillis();
            String fileName = displayName + "_" + timestamp + ".wav";
            currentFilePath = new File(recordingsDir, fileName).getAbsolutePath();
            
            fileOut = new FileOutputStream(currentFilePath);
            recordingStartTime = System.currentTimeMillis();
            totalSamplesWritten = 0;
            isRecording = true;
            
            // Write WAV header placeholder (44 bytes) with 0 data size initially
            writeWavHeader(0);
            
            // Register this RecordingManager as a listener to the audio engine
            // Now onPcmSamples() will be called every time the engine produces audio
            audioEngine.setPcmListener(this);
            
        } catch (IOException e) {
            if (listener != null) {
                listener.onRecordingError("Failed to start recording: " + e.getMessage());
            }
            isRecording = false;
            cleanupPartialFile();
        }
    }
    
    /**
     * Stop recording and save to database
     */
    public void stopRecording() {
        if (!isRecording) {
            return;
        }
        
        try {
            isRecording = false;
            
            // Unregister as listener
            if (audioEngine != null) {
                audioEngine.setPcmListener(null);
            }
            
            if (fileOut != null) {
                fileOut.close();
                fileOut = null;
            }
            
            // Update the WAV header with correct data size
            updateWavHeader();
            
            // Calculate duration
            long durationMs = System.currentTimeMillis() - recordingStartTime;
            
            // Save recording metadata to database
            if (repository != null) {
                long recordingId = repository.saveRecording(
                    currentFilePath,
                    new File(currentFilePath).getName(),
                    durationMs
                );
                
                if (listener != null) {
                    listener.onRecordingComplete(recordingId, currentFilePath, durationMs);
                }
            }
        } catch (IOException e) {
            if (listener != null) {
                listener.onRecordingError("Failed to stop recording: " + e.getMessage());
            }
            cleanupPartialFile();
        }
    }
    
    /**
     * OPTION A: PCM tap from ThereminAudioEngine
     * This method is called by ThereminAudioEngine whenever new audio samples are available
     * Called from the audio thread with ~2048 samples at 48kHz (~43ms per call)
     * 
     * @param samples Array of PCM samples (16-bit signed integers)
     * @param count Number of valid samples in the array
     */
    @Override
    public void onPcmSamples(short[] samples, int count) {
        if (!isRecording || fileOut == null) {
            return;
        }
        
        try {
            // Convert short samples to bytes in little-endian format
            // (required for WAV file format)
            byte[] byteData = new byte[count * 2];
            for (int i = 0; i < count; i++) {
                short sample = samples[i];
                // Little-endian: low byte first, then high byte
                byteData[i * 2] = (byte) (sample & 0xff);
                byteData[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
            }
            
            fileOut.write(byteData);
            totalSamplesWritten += count;
            
        } catch (IOException e) {
            if (listener != null) {
                listener.onRecordingError("Write error during recording: " + e.getMessage());
            }
            stopRecording();
            cleanupPartialFile();
        }
    }
    
    /**
     * Write WAV file header (44 bytes)
     * Format: PCM, 1 channel, 16-bit, 48000 Hz (matches ThereminAudioEngine)
     */
    private void writeWavHeader(int dataSize) throws IOException {
        FileOutputStream header = new FileOutputStream(currentFilePath);
        
        int byteRate = SAMPLE_RATE * CHANNELS * BIT_DEPTH / 8;
        int blockAlign = CHANNELS * BIT_DEPTH / 8;
        
        // RIFF header
        header.write("RIFF".getBytes());
        writeIntLittleEndian(header, dataSize + 36); // File size - 8
        header.write("WAVE".getBytes());
        
        // fmt sub-chunk (describes the audio format)
        header.write("fmt ".getBytes());
        writeIntLittleEndian(header, 16); // Subchunk1Size
        writeShortLittleEndian(header, (short) 1); // AudioFormat (1 = PCM)
        writeShortLittleEndian(header, (short) CHANNELS); // Number of channels
        writeIntLittleEndian(header, SAMPLE_RATE); // Sample rate (48000 Hz)
        writeIntLittleEndian(header, byteRate); // Byte rate
        writeShortLittleEndian(header, (short) blockAlign); // Block align
        writeShortLittleEndian(header, (short) BIT_DEPTH); // Bits per sample
        
        // data sub-chunk (contains the audio samples)
        header.write("data".getBytes());
        writeIntLittleEndian(header, dataSize);
        
        header.close();
    }
    
    /**
     * Update WAV header with correct data size after recording stops
     */
    private void updateWavHeader() throws IOException {
        if (currentFilePath == null) {
            return;
        }
        
        // Calculate data size in bytes
        int dataSize = totalSamplesWritten * 2; // 16-bit = 2 bytes per sample
        
        RandomAccessFile raf = new RandomAccessFile(currentFilePath, "rw");
        
        // Update RIFF size at offset 4
        raf.seek(4);
        writeIntLittleEndianToFile(raf, dataSize + 36);
        
        // Update data chunk size at offset 40
        raf.seek(40);
        writeIntLittleEndianToFile(raf, dataSize);
        
        raf.close();
    }
    
    private void writeIntLittleEndian(FileOutputStream out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
        out.write((value >> 16) & 0xff);
        out.write((value >> 24) & 0xff);
    }
    
    private void writeShortLittleEndian(FileOutputStream out, short value) throws IOException {
        out.write(value & 0xff);
        out.write((value >> 8) & 0xff);
    }
    
    private void writeIntLittleEndianToFile(RandomAccessFile raf, int value) throws IOException {
        raf.write(value & 0xff);
        raf.write((value >> 8) & 0xff);
        raf.write((value >> 16) & 0xff);
        raf.write((value >> 24) & 0xff);
    }
    
    /**
     * ID-8.5: Clean up partial recording file on error
     */
    private void cleanupPartialFile() {
        try {
            if (fileOut != null) {
                fileOut.close();
                fileOut = null;
            }
            File f = new File(currentFilePath);
            if (f.exists()) {
                f.delete();
            }
        } catch (Exception e) {
            // Silent cleanup failure
        }
    }
    
    public boolean isRecording() {
        return isRecording;
    }
    
    public File getRecordingsDirectory() {
        return recordingsDir;
    }
}
