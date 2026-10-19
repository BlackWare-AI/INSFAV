package com.instagram.insfav;

import android.content.*;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.*;

/** Read-only SAF catalog. Scanning is explicit; scrolling only reads indexed documents. */
final class LocalImages {
    interface Listener {void event(String event, JSONObject value);}
    private final ContentResolver resolver;
    private final Store store;
    private final File cache;
    private final Object cacheLock = new Object();
    private volatile boolean accessible, scanning;
    private volatile String accessMessage = "";
    private volatile JSONObject progress = new JSONObject();
    private volatile CancellationSignal cancellation;
    private volatile long cacheBytes;
    private long lastProgressAt;
    private final Map<String, String> legacySignatures = new HashMap<>();
    private final Set<String> legacyValid = new HashSet<>();
    private synchronized File legacy(String user) {
        File file = new File(new File(store.root, "thumbnails"), Store.key(Store.normalize(user)) + ".webp");
        if (!file.isFile() || file.length() > 256 * 1024) return null;
        String signature = file.length() + ":" + file.lastModified();
        if (!signature.equals(legacySignatures.get(user))) {
            legacySignatures.put(user, signature); legacyValid.remove(user);
            try (InputStream input = new FileInputStream(file)) {
                android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(input, null, bounds);
                if (bounds.outWidth == 128 && bounds.outHeight == 128 && "image/webp".equals(bounds.outMimeType)) {
                    android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                    if (bitmap != null) {bitmap.recycle(); legacyValid.add(user);}
                }
            } catch (Exception | OutOfMemoryError ignored) { }
        }
        return legacyValid.contains(user) ? file : null;
    }
    LocalImages(Context context, Store store) {
        this.resolver = context.getContentResolver(); this.store = store;
        cache = new File(context.getCacheDir(), "local-thumbnails"); cache.mkdirs(); cacheBytes = size(cache);
    }
    static long size(File folder) {long bytes = 0; File[] files = folder.listFiles(); if (files != null) for (File file : files) bytes += file.length(); return bytes;}
    boolean scanning() {return scanning;}
    void checkAccess() {
        String value; synchronized (store) {value = store.settings.optString("treeUri");}
        accessible = false; accessMessage = ""; if (value.isEmpty()) return;
        try {
            Uri tree = Uri.parse(value); boolean permitted = false;
            for (UriPermission permission : resolver.getPersistedUriPermissions()) if (permission.getUri().equals(tree) && permission.isReadPermission()) permitted = true;
            if (!permitted) throw new SecurityException("Folder permission was lost. Select the thumbnail folder again.");
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, store.settings.optString("directoryId", DocumentsContract.getTreeDocumentId(tree)));
            try (Cursor cursor = resolver.query(document, new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst() || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(0))) throw new IOException("Folder was moved, removed, or is unavailable. Select it again.");
            }
            accessible = true;
        } catch (Exception error) {accessMessage = error instanceof SecurityException ? "Folder permission was lost. Select the thumbnail folder again." : "Thumbnail folder is unavailable. Check its location or select it again."; store.log("folder_access", error.getClass().getSimpleName());}
    }
    void select(Uri tree, int flags) throws Exception {
        if (scanning) throw new IOException("Wait for the current scan to finish.");
        int read = flags & Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (read == 0) throw new SecurityException("Read permission was not granted.");
        resolver.takePersistableUriPermission(tree, read);
        String directoryId = DocumentsContract.getTreeDocumentId(tree), label = directoryId, old;
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, directoryId);
        try (Cursor cursor = resolver.query(document, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Selected folder is unavailable.");
            label = cursor.getString(0);
        }
        // Some system pickers hide dot folders. A selected parent may grant its .thumbnails child.
        if (!".thumnails".equals(label) && !".thumbnails".equalsIgnoreCase(label)) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId);
            try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor != null) while (cursor.moveToNext()) if ((".thumnails".equals(cursor.getString(1)) || ".thumbnails".equalsIgnoreCase(cursor.getString(1))) && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {directoryId = cursor.getString(0); label = cursor.getString(1); if (".thumnails".equals(label)) break;}
            }
        }
        label += " · " + directoryId;
        synchronized (store) {
            old = store.settings.optString("treeUri");
            store.settings.put("treeUri", tree.toString()).put("directoryId", directoryId).put("folderLabel", label).put("scanState", "not_scanned");
            store.saveSettings(); store.replaceIndex(new JSONObject());
        }
        clearCache();
        if (!old.isEmpty() && !old.equals(tree.toString())) try {resolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION);} catch (Exception ignored) { }
        checkAccess(); if (!accessible) throw new IOException(accessMessage); store.log("folder_select", "success");
    }
    void cancelScan() {CancellationSignal signal = cancellation; if (signal != null) signal.cancel();}
    void scan(Listener listener) {
        synchronized (this) {if (scanning) return; scanning = true;} cancellation = new CancellationSignal();
        String treeValue; synchronized (store) {treeValue = store.settings.optString("treeUri");}
        long started = android.os.SystemClock.elapsedRealtime();
        try {
            checkAccess(); if (!accessible) throw new IOException(accessMessage.isEmpty() ? "Select a thumbnail folder first." : accessMessage);
            synchronized (store) {store.settings.put("scanState", "scanning"); store.saveSettings();}
            String directoryId; synchronized (store) {directoryId = store.settings.optString("directoryId", DocumentsContract.getTreeDocumentId(Uri.parse(treeValue)));}
            Uri tree = Uri.parse(treeValue), children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId);
            String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_MIME_TYPE};
            Map<String, String> hashUsers = new HashMap<>();
            for (String user : store.users()) hashUsers.put(Store.key(Store.normalize(user)) + ".webp", user);
            ThumbnailPolicy.Catalog catalog = new ThumbnailPolicy.Catalog(); int folderEntries;
            try (Cursor cursor = resolver.query(children, columns, null, null, null, cancellation)) {
                if (cursor == null) throw new IOException("Folder could not be read.");
                int total = cursor.getCount(), processed = 0; folderEntries = total;
                publish(listener, "scanning", 0, total, started);
                while (cursor.moveToNext()) {
                    if (Thread.currentThread().isInterrupted() || cancellation.isCanceled()) throw new InterruptedException();
                    String name = cursor.getString(1);
                    if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(4)) && ThumbnailPolicy.supported(name)) {
                        Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                        long bytes = cursor.isNull(2) ? -1 : cursor.getLong(2), modified = cursor.isNull(3) ? 0 : cursor.getLong(3);
                        int w = 0, h = 0; String mime = ""; boolean readable = false;
                        try {
                            if (bytes > 64L * 1024 * 1024) throw new IOException("Image size limit");
                            BitmapFactory.Options bounds = ImageCodec.bounds(resolver, uri); w = bounds.outWidth; h = bounds.outHeight; mime = bounds.outMimeType;
                            readable = ImageCodec.validate(resolver, uri, bounds);
                        } catch (SecurityException error) {throw error;}
                        catch (IOException | RuntimeException | OutOfMemoryError error) {store.log("image_validation", "UNREADABLE");}
                        String resolvedUser = hashUsers.getOrDefault(name, ThumbnailPolicy.username(name));
                        if (name.matches("[0-9a-f]{64}\\.webp")) readable &= w == 128 && h == 128 && "image/webp".equals(mime);
                        catalog.add(new ThumbnailPolicy.Candidate(name, uri.toString(), w, h, bytes, modified, readable, resolvedUser));
                    }
                    processed++; if (processed % 16 == 0 || processed == total) publish(listener, "scanning", processed, total, started);
                }
            }
            if (cancellation.isCanceled()) throw new InterruptedException();
            JSONObject profiles = new JSONObject();
            for (Map.Entry<String, ThumbnailPolicy.Candidate> entry : catalog.best.entrySet()) {
                ThumbnailPolicy.Candidate c = entry.getValue();
                profiles.put(entry.getKey(), new JSONObject().put("uri", c.uri).put("name", c.name).put("readable", c.readable).put("width", c.width).put("height", c.height).put("size", c.size).put("modified", c.modified));
            }
            long now = System.currentTimeMillis(), revision; synchronized (store) {revision = Math.max(now, store.index.optLong("revision") + 1);}
            JSONObject next = new JSONObject().put("schema", 1).put("treeUri", treeValue).put("revision", revision).put("scannedAt", now).put("profiles", profiles).put("unreadable", catalog.unreadable).put("duplicates", catalog.duplicates).put("files", catalog.files).put("bytes", catalog.bytes).put("unknownSizes", catalog.unknownSizes);
            synchronized (store) {
                if (!treeValue.equals(store.settings.optString("treeUri"))) throw new IOException("Folder changed during scan.");
                store.replaceIndex(next); store.settings.put("scanState", "completed"); store.saveSettings();
            }
            clearCache(); store.log("thumbnail_scan", "success");
            publish(listener, "completed", folderEntries, folderEntries, started);
        } catch (Exception error) {
            String state = error instanceof InterruptedException || cancellation.isCanceled() ? "interrupted" : "failed";
            try {synchronized (store) {store.settings.put("scanState", state); store.saveSettings();}} catch (Exception ignored) { }
            if (error instanceof SecurityException) {accessible = false; accessMessage = "Folder permission was lost. Select the folder again.";}
            try {JSONObject value = new JSONObject().put("state", state).put("message", error instanceof InterruptedException ? "Scan interrupted. The last completed index is preserved." : error.getMessage()); progress = value; listener.event("scan-progress", value);} catch (Exception ignored) { }
            store.log("thumbnail_scan", state);
        } finally {scanning = false; try {listener.event("status", snapshot(true));} catch (Exception ignored) { }}
    }
    private void publish(Listener listener, String state, int processed, int total, long started) throws JSONException {
        long now = android.os.SystemClock.elapsedRealtime();
        if ("scanning".equals(state) && processed > 0 && processed < total && now - lastProgressAt < 100) return;
        lastProgressAt = now;
        JSONObject value = new JSONObject().put("state", state).put("processed", processed).put("total", total).put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started);
        progress = value; listener.event("scan-progress", value);
    }
    JSONObject snapshot(boolean includeProfiles) throws Exception {
        synchronized (store) {
            JSONObject source = store.index.optJSONObject("profiles"); if (source == null) source = new JSONObject();
            boolean usable = accessible && store.settings.optString("treeUri").equals(store.index.optString("treeUri", "invalid"));
            Set<String> users = store.users(); int matched = 0;
            JSONObject profiles = new JSONObject();
            for (String user : users) {
                JSONObject image = source.optJSONObject(user);
                if (usable && image != null && image.optBoolean("readable")) {
                    matched++; if (includeProfiles) profiles.put(user, new JSONObject().put("file", true).put("width", image.optInt("width")).put("height", image.optInt("height")));
                } else if (legacy(user) != null) {
                    matched++; if (includeProfiles) profiles.put(user, new JSONObject().put("file", true).put("width", 128).put("height", 128));
                }
            }
            JSONObject out = new JSONObject().put("total", store.total()).put("unique", users.size()).put("matched", matched).put("missing", users.size() - matched).put("unreadable", store.index.optInt("unreadable")).put("duplicates", store.index.optInt("duplicates")).put("folderBytes", store.index.optLong("bytes")).put("unknownSizes", store.index.optInt("unknownSizes")).put("lastScan", store.index.optLong("scannedAt")).put("folder", store.settings.optString("folderLabel", "Not selected")).put("folderStatus", accessible ? "ready" : store.settings.optString("treeUri").isEmpty() ? "not_selected" : "unavailable").put("folderMessage", accessMessage).put("scanState", scanning ? "scanning" : store.settings.optString("scanState", "idle")).put("scan", progress).put("revision", store.index.optLong("revision")).put("cacheBytes", cacheBytes);
            if (includeProfiles) out.put("profiles", profiles); return out;
        }
    }
    byte[] image(String user, boolean full) {
        byte[] external = externalImage(user, full);
        if (external.length > 0) return external;
        File fallback = legacy(user);
        if (fallback != null) try {
            byte[] bytes = java.nio.file.Files.readAllBytes(fallback.toPath());
            if (!full) return bytes;
            android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap != null) try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); return output.toByteArray();} finally {bitmap.recycle();}
        } catch (IOException | RuntimeException | OutOfMemoryError error) {store.log("legacy_image", "READ_FAILED");}
        return new byte[0];
    }
    private byte[] externalImage(String user, boolean full) {
        synchronized (cacheLock) {
            try {
                JSONObject image; String revision;
                synchronized (store) {
                    if (!accessible || !store.settings.optString("treeUri").equals(store.index.optString("treeUri", "invalid"))) return new byte[0];
                    JSONObject profiles = store.index.optJSONObject("profiles"); image = profiles == null ? null : profiles.optJSONObject(user);
                    if (image == null || !image.optBoolean("readable")) return new byte[0];
                    image = new JSONObject(image.toString()); revision = store.index.optString("revision");
                }
                if (!full && image.optString("name").equals(Store.key(Store.normalize(user)) + ".webp") && image.optInt("width") == 128 && image.optInt("height") == 128) {
                    try (InputStream input = ImageCodec.open(resolver, Uri.parse(image.getString("uri"))); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[4096]; int count;
                        while ((count = input.read(buffer)) != -1) {if (output.size() + count > 256 * 1024) throw new IOException("Thumbnail size limit"); output.write(buffer, 0, count);}
                        return output.toByteArray();
                    }
                }
                File cached = new File(cache, Store.key(user + "|" + revision + "|" + image.getString("uri")) + ".webp");
                if (!full && cached.isFile()) return java.nio.file.Files.readAllBytes(cached.toPath());
                byte[] bytes = ImageCodec.render(resolver, Uri.parse(image.getString("uri")), full);
                if (!full) {
                    AtomicFile file = new AtomicFile(cached); FileOutputStream output = null;
                    try {output = file.startWrite(); output.write(bytes); file.finishWrite(output); cacheBytes += bytes.length; trimCache();}
                    catch (IOException error) {if (output != null) file.failWrite(output); store.log("image_cache", "WRITE_FAILED");}
                }
                return bytes;
            } catch (Exception | OutOfMemoryError error) {
                store.log("image_read", error.getClass().getSimpleName());
                try {synchronized (store) {
                    if (error instanceof SecurityException) {accessible = false; accessMessage = "Folder permission was lost. Select the folder again.";}
                    else {JSONObject profiles = store.index.optJSONObject("profiles"), image = profiles == null ? null : profiles.optJSONObject(user); if (image != null && image.optBoolean("readable")) {image.put("readable", false); store.index.put("unreadable", store.index.optInt("unreadable") + 1); store.replaceIndex(store.index);}}
                }} catch (Exception ignored) { }
                return new byte[0];
            }
        }
    }
    private void trimCache() {
        if (cacheBytes <= 24L * 1024 * 1024) return;
        File[] files = cache.listFiles(); if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File file : files) {if (cacheBytes <= 18L * 1024 * 1024) break; long bytes = file.length(); if (file.delete()) cacheBytes -= bytes;}
    }
    void clearCache() throws IOException {
        synchronized (cacheLock) {File[] files = cache.listFiles(); if (files != null) for (File file : files) if (!file.delete()) throw new IOException("Image cache could not be fully cleared."); cacheBytes = 0;

        }
    }
    synchronized void clearLegacyCache() {
        legacySignatures.clear(); legacyValid.clear();
            for (File legacy : new File[]{new File(store.root, "thumbnails"), new File(cache.getParentFile(), "full-resolution")}) {File[] old = legacy.listFiles(); if (old != null) for (File file : old) if (file.isFile()) file.delete();}
    }
    void clearIndex() throws Exception {
        if (scanning) throw new IOException("Wait for the scan to finish.");
        synchronized (store) {store.replaceIndex(new JSONObject()); store.settings.put("scanState", "not_scanned"); store.saveSettings();}
        clearCache(); store.log("thumbnail_index_clear", "success");
    }
}
