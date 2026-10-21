package com.instagram.insfav;

import android.content.*;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.DocumentsContract;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;
import java.io.*;
import java.util.*;

/** Read-only SAF catalog. Scanning is explicit; scrolling only reads indexed documents. */
final class LocalImages {
    interface Listener {void event(String event, JSONObject value);}
    private final ContentResolver resolver;
    private final Store store;
    private final File dataRoot;
    private long storageMeasuredAt;
    private JSONObject storage = new JSONObject();
    private final File cache;
    private final Object cacheLock = new Object();
    private volatile boolean accessible, scanning;
    private volatile String accessMessage = "";
    private volatile JSONObject progress = new JSONObject();
    private volatile CancellationSignal cancellation;
    private volatile long cacheBytes;
    private long lastProgressAt;
    private final ConcurrentHashMap<String, CompletableFuture<byte[]>> reads = new ConcurrentHashMap<>();
    private final Semaphore readSlots = new Semaphore(4, true);
    private final AtomicLong sourceReads = new AtomicLong(), joinedReads = new AtomicLong(), readNanos = new AtomicLong(), sourceBytes = new AtomicLong();
    LocalImages(Context context, Store store) {
        this.resolver = context.getContentResolver(); this.store = store; dataRoot=context.getDataDir();
        cache = new File(context.getCacheDir(), "local-thumbnails"); cacheBytes = size(cache); try {clearCache();} catch (IOException error) {store.log("image_cache_migration", "INCOMPLETE");}
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
        if (!".thumbnails".equalsIgnoreCase(label)) {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, directoryId);
            try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
                if (cursor != null) while (cursor.moveToNext()) if (".thumbnails".equalsIgnoreCase(cursor.getString(1)) && DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {directoryId = cursor.getString(0); label = cursor.getString(1); break;}
            }
        }
        label += " · " + directoryId;
        synchronized (store) {
            old = store.settings.optString("treeUri");
            store.settings.put("treeUri", tree.toString()).put("directoryId", directoryId).put("folderLabel", label).put("scanState", "not_scanned");
            store.saveSettings(); store.replaceIndex(new JSONObject());
        }
        clearCache();
        if (!old.isEmpty() && !old.equals(tree.toString())) try {
            int release=Intent.FLAG_GRANT_READ_URI_PERMISSION;
            for(UriPermission permission:resolver.getPersistedUriPermissions())if(permission.getUri().toString().equals(old)&&permission.isWritePermission())release|=Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            resolver.releasePersistableUriPermission(Uri.parse(old), release);
        } catch (Exception ignored) { }
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
                        int w = 0, h = 0; boolean readable = false;
                        try {
                            if (bytes > 64L * 1024 * 1024) throw new IOException("Image size limit");
                            BitmapFactory.Options bounds = ImageCodec.bounds(resolver, uri); w = bounds.outWidth; h = bounds.outHeight;
                            readable = ImageCodec.validate(resolver, uri, bounds);
                        } catch (SecurityException error) {throw error;}
                        catch (IOException | RuntimeException | OutOfMemoryError error) {store.log("image_validation", "UNREADABLE");}
                        catalog.add(new ThumbnailPolicy.Candidate(name, uri.toString(), w, h, bytes, modified, readable));
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
    private static long treeBytes(File file) {
        if(file.isFile())return file.length();long total=0;File[] entries=file.listFiles();
        if(entries!=null)for(File entry:entries)total+=treeBytes(entry);return total;
    }
    JSONObject storageAudit() throws JSONException {
        JSONObject result=new JSONObject();
        for(String name:new String[]{"files","cache","databases","shared_prefs","app_webview","code_cache"})result.put(name,treeBytes(new File(dataRoot,name)));
        return result.put("thumbnailIndex",new File(store.root,"thumbnail-index.json").length()).put("derivedImages",size(cache)).put("nativeBitmapCache",0);
    }
    JSONObject snapshot(boolean includeProfiles) throws Exception {
        long now=android.os.SystemClock.elapsedRealtime();if(storageMeasuredAt==0||now-storageMeasuredAt>30000){storage=storageAudit();storageMeasuredAt=now;}
        synchronized (store) {
            JSONObject source = store.index.optJSONObject("profiles"); if (source == null) source = new JSONObject();
            boolean usable = accessible && store.settings.optString("treeUri").equals(store.index.optString("treeUri", "invalid"));
            Set<String> users = store.users(); int matched = 0;
            JSONObject profiles = new JSONObject();
            for (String user : users) {
                JSONObject image = source.optJSONObject(user);
                if (usable && image != null && image.optBoolean("readable")) {
                    matched++; if (includeProfiles) profiles.put(user, new JSONObject().put("file", true).put("width", image.optInt("width")).put("height", image.optInt("height")).put("version", fingerprint(image)));
                }
            }
            JSONObject out = new JSONObject().put("total", store.total()).put("unique", users.size()).put("matched", matched).put("missing", users.size() - matched).put("unreadable", store.index.optInt("unreadable")).put("duplicates", store.index.optInt("duplicates")).put("folderBytes", store.index.optLong("bytes")).put("unknownSizes", store.index.optInt("unknownSizes")).put("lastScan", store.index.optLong("scannedAt")).put("folder", store.settings.optString("folderLabel", "Not selected")).put("folderStatus", accessible ? "ready" : store.settings.optString("treeUri").isEmpty() ? "not_selected" : "unavailable").put("folderMessage", accessMessage).put("scanState", scanning ? "scanning" : store.settings.optString("scanState", "idle")).put("scan", progress).put("revision", store.index.optLong("revision")).put("cacheBytes", cacheBytes);
            out.put("storage",storage);
            out.put("imageIO", new JSONObject().put("sourceReads",sourceReads.get()).put("coalescedReads",joinedReads.get()).put("readNanos",readNanos.get()).put("sourceBytes",sourceBytes.get()).put("inflight",reads.size()).put("nativeBitmapCacheBytes",0).put("derivedDiskCacheBytes",cacheBytes));
            if (includeProfiles) out.put("profiles", profiles); return out;
        }
    }
    private static String fingerprint(JSONObject image) {
        return image.optString("modified")+":"+image.optString("size")+":"+image.optString("uri").hashCode();
    }
    String mime(String user) {
        synchronized(store) {
            JSONObject profiles=store.index.optJSONObject("profiles"), image=profiles==null?null:profiles.optJSONObject(user);
            String name=image==null?"":image.optString("name").toLowerCase(Locale.ROOT);
            return name.endsWith(".png")?"image/png":name.endsWith(".jpg")||name.endsWith(".jpeg")?"image/jpeg":"image/webp";
        }
    }
    byte[] image(String user, boolean full) {
        JSONObject image;
        synchronized(store) {
            if(!accessible || !store.settings.optString("treeUri").equals(store.index.optString("treeUri","invalid"))) return new byte[0];
            JSONObject profiles=store.index.optJSONObject("profiles"), value=profiles==null?null:profiles.optJSONObject(user);
            if(value==null || !value.optBoolean("readable")) return new byte[0];
            // Index values are replaced atomically, never retain a mutable metadata record.
            try {image=new JSONObject(value.toString());} catch(JSONException error){return new byte[0];}
        }
        if(full) {
            // Preserve the existing bounded /full/ preview and HD viewer chain.
            synchronized(cacheLock) {try{return ImageCodec.render(resolver,Uri.parse(image.optString("uri")),true);}
            catch(Exception|OutOfMemoryError error){return failed(user,image,error);}}
        }
        String key=user+"|"+fingerprint(image);
        CompletableFuture<byte[]> mine=new CompletableFuture<>(), existing=reads.putIfAbsent(key,mine);
        if(existing!=null) {joinedReads.incrementAndGet();try{return existing.get(30,TimeUnit.SECONDS);}catch(Exception error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();return new byte[0];}}
        byte[] bytes;boolean acquired=false;long started=System.nanoTime();
        try {
            acquired=readSlots.tryAcquire(10,TimeUnit.SECONDS);if(!acquired)throw new IOException("Local read capacity");
            sourceReads.incrementAndGet();
            // Original bytes only: no bitmap, resampling, encoding, or app disk copy.
            try(InputStream input=ImageCodec.open(resolver,Uri.parse(image.optString("uri")));ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);
                bytes=output.toByteArray();sourceBytes.addAndGet(bytes.length);
            }
        } catch(Exception|OutOfMemoryError error) {if(error instanceof InterruptedException)Thread.currentThread().interrupt();bytes=failed(user,image,error);}
        finally {if(acquired)readSlots.release();readNanos.addAndGet(System.nanoTime()-started);}
        mine.complete(bytes);reads.remove(key,mine);return bytes;
    }
    private byte[] failed(String user,JSONObject expected,Throwable error) {
        store.log("image_read",error.getClass().getSimpleName());
        // Temporary provider failures do not permanently condemn an indexed image.
        try {synchronized(store) {
            if(error instanceof SecurityException){accessible=false;accessMessage="Folder permission was lost. Select the folder again.";}
            else if(error instanceof FileNotFoundException){
                JSONObject profiles=store.index.optJSONObject("profiles"), current=profiles==null?null:profiles.optJSONObject(user);
                if(current!=null && current.optBoolean("readable") && fingerprint(current).equals(fingerprint(expected))) {
                    current.put("readable",false);store.index.put("unreadable",store.index.optInt("unreadable")+1);store.replaceIndex(store.index);
                }
            }
        }}catch(Exception ignored){}return new byte[0];
    }
    void clearCache() throws IOException {
        synchronized(cacheLock) {
            File[] files=cache.listFiles();boolean failed=false;
            if(files!=null)for(File file:files)if(file.isFile() && file.getName().matches("[a-f0-9]{64}\\.webp(?:\\.bak|\\.new)?"))if(!file.delete())failed=true;
            cacheBytes=size(cache);if(failed)throw new IOException("Legacy image cache could not be fully cleared.");
        }
    }
    void clearLegacyCache() {
            for (File legacy : new File[]{new File(store.root, "thumbnails"), new File(cache.getParentFile(), "full-resolution")}) {File[] old = legacy.listFiles(); if (old != null) for (File file : old) if (file.isFile()) file.delete();}
    }
    void clearIndex() throws Exception {
        if (scanning) throw new IOException("Wait for the scan to finish.");
        synchronized (store) {store.replaceIndex(new JSONObject()); store.settings.put("scanState", "not_scanned"); store.saveSettings();}
        clearCache(); store.log("thumbnail_index_clear", "success");
    }
}
