package com.example.thereminglovestest2;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Creates a device-visible copy of a recording in Music/Theremin Gloves Recordings.
 */
public final class RecordingExportManager {

    static final String EXPORT_FOLDER = "Theremin Gloves Recordings";

    private RecordingExportManager() {}

    public static String exportRecording(Context context, File sourceFile, String displayName) throws IOException {
        if (context == null || sourceFile == null || !sourceFile.exists()) return null;
        String extension = extensionOf(sourceFile.getName());
        String fileName = sanitizeDisplayName(displayName);
        if (!extension.isEmpty() && !fileName.toLowerCase().endsWith(extension)) fileName += extension;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return exportViaMediaStore(context, sourceFile, fileName);
        }
        return exportLegacyFile(sourceFile, fileName);
    }

    public static void deleteExportedRecording(Context context, String location) {
        if (location == null || location.trim().isEmpty()) return;
        try {
            if (location.startsWith("content://")) {
                if (context != null) context.getContentResolver().delete(Uri.parse(location), null, null);
            } else {
                File file = new File(location);
                if (file.exists()) file.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static String exportViaMediaStore(Context context, File sourceFile, String fileName) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(fileName));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + File.separator + EXPORT_FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create exported recording");
        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Could not open export output");
            copy(in, out);
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException(e.getMessage(), e);
        }
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, ready, null, null);
        return uri.toString();
    }

    private static String exportLegacyFile(File sourceFile, String fileName) throws IOException {
        File musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
        File exportDir = new File(musicDir, EXPORT_FOLDER);
        if (!exportDir.exists() && !exportDir.mkdirs()) {
            throw new IOException("Could not create export folder");
        }
        File target = uniqueFile(exportDir, fileName);
        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = new java.io.FileOutputStream(target)) {
            copy(in, out);
        }
        return target.getAbsolutePath();
    }

    private static File uniqueFile(File dir, String fileName) {
        File candidate = new File(dir, fileName);
        if (!candidate.exists()) return candidate;
        String extension = extensionOf(fileName);
        String base = extension.isEmpty() ? fileName : fileName.substring(0, fileName.length() - extension.length());
        for (int i = 2; i < 1000; i++) {
            candidate = new File(dir, base + " (" + i + ")" + extension);
            if (!candidate.exists()) return candidate;
        }
        return new File(dir, base + "_" + System.currentTimeMillis() + extension);
    }

    private static String sanitizeDisplayName(String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty()) name = "Recording";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String extensionOf(String name) {
        int idx = name.lastIndexOf('.');
        return idx >= 0 ? name.substring(idx) : "";
    }

    private static String mimeTypeOf(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        return "application/octet-stream";
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        out.flush();
    }
}
