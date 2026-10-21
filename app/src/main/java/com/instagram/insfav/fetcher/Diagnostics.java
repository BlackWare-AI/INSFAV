package com.instagram.insfav.fetcher;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Fixed vocabulary and numeric details only. No raw URLs, usernames, exceptions, or headers. */
final class Diagnostics {
    private static final Object FILE_LOCK = new Object();
    private final File current, previous;
    private final Context context;
    Diagnostics(Context context) { this.context = context.getApplicationContext(); current = new File(context.getFilesDir(), "fetcher-diagnostics.txt"); previous = new File(context.getFilesDir(), "fetcher-diagnostics.previous.txt"); }
    void log(String event, String code, long detail) { synchronized(FILE_LOCK){
        if (!event.matches("[A-Z_]+") || !code.matches("[A-Z_0-9]+")) return;
        try {
            if (current.length() > 128 * 1024) { if (previous.exists() && !previous.delete()) return; if (!current.renameTo(previous)) return; }
            try (FileOutputStream output = new FileOutputStream(current, true)) {
                output.write(String.format(Locale.ROOT, "%d %s %s %d%n", System.currentTimeMillis(), event, code, detail).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) { }
    }
    }
    static String storageCode(IOException error) {
        String code = error.getMessage();
        for (String allowed : new String[]{"OUTPUT_PERMISSION_LOST", "OUTPUT_SELECT_PARENT", "OUTPUT_FOLDER_CREATE_FAILED", "OUTPUT_FOLDER_INVALID", "OUTPUT_UNAVAILABLE", "OUTPUT_DUPLICATE_NAMES", "OUTPUT_READ_FAILED", "OUTPUT_SIZE_REJECTED", "PARTIAL_CLEANUP_FAILED", "OUTPUT_INVALID_WEBP", "OUTPUT_CREATE_FAILED", "OUTPUT_WRITE_FAILED", "OUTPUT_READBACK_FAILED", "OUTPUT_REPLACE_FAILED", "ATOMIC_RENAME_UNSUPPORTED", "OUTPUT_FINAL_VERIFY_FAILED", "OUTPUT_FILENAME_CHANGED"}) if (allowed.equals(code)) return allowed;
        return "IO_ERROR";
    }
    static String imageCode(IOException error) {
        String code = error.getMessage();
        for (String allowed : new String[]{"IMAGE_SIZE_REJECTED", "IMAGE_CONTAINER_INVALID", "IMAGE_REJECTED", "IMAGE_DECODE_FAILED", "WEBP_ENCODE_FAILED", "WEBP_VERIFY_FAILED"}) if (allowed.equals(code)) return allowed;
        return "IMAGE_VALIDATION_FAILED";
    }
    static String importCode(Throwable error) {
        String code = error.getMessage();
        for (String allowed : new String[]{"JSON_TOO_LARGE", "JSON_TOO_DEEP", "JSON_OPEN_FAILED", "DUPLICATE_BACKUP_FIELD", "TRAILING_JSON", "BACKUP_FIELD_MISSING", "INVALID_BACKUP_ROOT", "INVALID_JSON", "DUPLICATE_ITEMS", "COLLECTION_ITEMS_MISSING"}) if (allowed.equals(code)) return allowed;
        return error instanceof OutOfMemoryError ? "IMPORT_MEMORY_LIMIT" : "INVALID_OR_UNREADABLE_JSON";
    }
    void export(OutputStream output) throws IOException { synchronized(FILE_LOCK){
        output.write("INSFAV integrated WebView fetcher\nEngine: hidden Android WebView + trusted HTTPS image download.\nAccount identifiers and cookies redacted. No secret values exported.\n".getBytes(StandardCharsets.UTF_8));
        try (QueueStore queue = new QueueStore(context)) {
            output.write(("Requested WebViews: " + SessionState.prefs(context).getInt("concurrentWebViews",1) + "\nEffective WebViews: " + QueueControl.effectiveViews + "\nActive WebViews: " + QueueControl.activeViews + "\nActive Accounts: " + QueueControl.activeAccounts + "\nPeak Active Accounts: " + QueueControl.peakAccounts + "\nCompleted Per Minute: " + QueueControl.completedPerMinute + "\nApp PSS KiB: " + android.os.Debug.getPss() + "\nAndroid SDK: " + android.os.Build.VERSION.SDK_INT + "\nFetcher phases: "+queue.phases()+"\nFetcher full listings: "+FetcherCatalog.listings.get()+"\nFetcher source opens: "+FetcherCatalog.opens.get()+"\nFetcher bitmap decodes: "+FetcherCatalog.decodes.get()+"\nFetcher metadata hits: "+FetcherCatalog.hits.get()+"\nCounters: " + queue.counts() + "\nFailures: " + queue.failures() + "\nSession evidence: " + SessionState.evidence(SessionState.prefs(context)) + "\n").getBytes(StandardCharsets.UTF_8));
        }
        for (File file : new File[]{previous, current}) if (file.exists()) try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[4096]; int n; while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
    }}
}
