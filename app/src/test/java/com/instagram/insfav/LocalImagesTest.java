package com.instagram.insfav;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import android.graphics.Bitmap;
import static org.junit.Assert.*;

/** Real Store/LocalImages code against an isolated read-only SAF test provider. No personal files. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 34}, manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class LocalImagesTest {
    private Context context;
    private Store store;
    private LocalImages images;
    private FixtureProvider provider;
    private final Uri root = Uri.parse("content://insfav.test/tree/root");
    private static final String RAW = "{\"extra\":{\"preserve\":true},\"favorite_page_pref\":\"[{\\\"name\\\":\\\"A\\\",\\\"items\\\":[{\\\"pageId\\\":\\\"Example\\\",\\\"name\\\":\\\"One\\\"},{\\\"pageId\\\":\\\"@EXAMPLE\\\",\\\"name\\\":\\\"Again\\\"},{\\\"pageId\\\":\\\"missing\\\",\\\"name\\\":\\\"Missing\\\"}]}]\"}";
    @Before public void setup() throws Exception {
        Context application = RuntimeEnvironment.getApplication(); File isolated = Files.createTempDirectory("insfav-native-test").toFile();
        context = new ContextWrapper(application) {
            @Override public Context getApplicationContext() {return this;}
            @Override public File getFilesDir() {File dir = new File(isolated, "files"); dir.mkdirs(); return dir;}
            @Override public File getCacheDir() {File dir = new File(isolated, "cache"); dir.mkdirs(); return dir;}
        };
        ProviderInfo info = new ProviderInfo(); info.authority = "insfav.test"; info.exported = true; info.grantUriPermissions = true;
        provider = Robolectric.buildContentProvider(FixtureProvider.class).create(info).get();
        provider.directory = new File(isolated, "source"); provider.directory.mkdirs();
        provider.add("root/Example.JPG", "Example.JPG", 32, 32);
        provider.add("root/example.PNG", "example.PNG", 64, 64);
        provider.addBroken("root/broken.png", "broken.png");
        store = new Store(context); store.importDocument(RAW); images = new LocalImages(context, store);
    }
    private void selectAndScan() throws Exception {images.select(root, Intent.FLAG_GRANT_READ_URI_PERMISSION); images.scan((event, value) -> {});}
    @Test public void scanDeduplicatesAndValidatesWithoutChangingJson() throws Exception {
        selectAndScan(); JSONObject result = images.snapshot(true);
        assertEquals(3, result.getInt("total")); assertEquals(2, result.getInt("unique")); assertEquals(1, result.getInt("matched"));
        assertEquals(1, result.getInt("missing")); assertEquals(1, result.getInt("unreadable")); assertEquals(1, result.getInt("duplicates"));
        assertEquals(64, result.getJSONObject("profiles").getJSONObject("example").getInt("width")); assertEquals(RAW, store.original());
    }
    @Test public void persistedIndexIsReusableWithoutRescanning() throws Exception {
        selectAndScan(); int before = provider.childQueries;
        Store restarted = new Store(context); LocalImages reopened = new LocalImages(context, restarted); reopened.checkAccess();
        assertEquals(1, reopened.snapshot(true).getInt("matched")); assertEquals(before, provider.childQueries);
        assertTrue(context.getContentResolver().getPersistedUriPermissions().get(0).isReadPermission());
        assertFalse(context.getContentResolver().getPersistedUriPermissions().get(0).isWritePermission());
    }
    @Test public void missingPermissionProducesWarningAndNoMatchedImages() throws Exception {
        selectAndScan(); context.getContentResolver().releasePersistableUriPermission(root, Intent.FLAG_GRANT_READ_URI_PERMISSION); images.checkAccess();
        JSONObject result = images.snapshot(true); assertEquals("unavailable", result.getString("folderStatus")); assertEquals(0, result.getInt("matched"));
        assertFalse(result.getString("folderMessage").isEmpty()); assertEquals(RAW, store.original());
    }
    @Test public void cacheAndIndexClearNeverModifySourceFiles() throws Exception {
        selectAndScan(); Map<String, byte[]> originals = provider.contents();
        assertTrue(images.image("example", false).length > 0); images.clearCache(); images.clearIndex();
        assertEquals(0, images.snapshot(true).getInt("matched")); assertEquals(RAW, store.original());
        for (String id : originals.keySet()) assertArrayEquals(originals.get(id), Files.readAllBytes(provider.files.get(id).toPath()));
        assertEquals(0, provider.writes);
    }
    @Test public void visibleReadsUseOriginalBytesWithoutRescanOrDerivedCache() throws Exception {
        selectAndScan(); int before = provider.childQueries, opened = provider.opens;
        byte[] first = images.image("example", false); int afterFirst = provider.opens;
        assertTrue(first.length > 0); assertTrue(afterFirst > opened); assertArrayEquals(first, images.image("example", false));
        assertEquals(afterFirst+1, provider.opens); assertEquals(before, provider.childQueries);
        assertArrayEquals(Files.readAllBytes(provider.files.get("root/example.PNG").toPath()),first);
        assertEquals(0,images.snapshot(false).getLong("cacheBytes"));
        assertTrue(images.image("example", true).length > 0); assertEquals(before, provider.childQueries);
    }
    @Test public void folderReplacementDropsOldMappingsAndReleasesOldGrant() throws Exception {
        selectAndScan(); Uri replacement = Uri.parse("content://insfav.test/tree/new"); images.select(replacement, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, images.snapshot(true).getInt("matched")); assertEquals(RAW, store.original());
        assertEquals(replacement, context.getContentResolver().getPersistedUriPermissions().get(0).getUri());
    }
    @Test public void parentSelectionCanResolveHiddenThumbnailChild() throws Exception {
        provider.parentHasThumbnailChild = true; Uri parent = Uri.parse("content://insfav.test/tree/parent");
        images.select(parent, Intent.FLAG_GRANT_READ_URI_PERMISSION); images.scan((event,value)->{});
        assertEquals("root", store.settings.getString("directoryId")); assertEquals(1, images.snapshot(true).getInt("matched"));
    }
    @Test public void removedFolderDoesNotCrashAndKeepsLastIndex() throws Exception {
        selectAndScan(); long scanned = store.index.getLong("scannedAt"); provider.available = false; images.checkAccess();
        assertEquals("unavailable", images.snapshot(false).getString("folderStatus")); assertEquals(scanned, store.index.getLong("scannedAt"));
        assertEquals(0, images.image("example", false).length);
    }
    @Test public void interruptedRescanPreservesLastCompletedIndex() throws Exception {
        selectAndScan(); long revision = store.index.getLong("revision");
        images.scan((event,value) -> {if (event.equals("scan-progress") && value.optString("state").equals("scanning")) images.cancelScan();});
        assertEquals(revision, store.index.getLong("revision")); assertEquals("interrupted", store.settings.getString("scanState"));
        assertEquals(1, images.snapshot(true).getInt("matched"));
    }
    @Test public void migrationDropsRetrievalStateButPreservesOriginalDocument() throws Exception {
        for (String name : new String[]{"queue.json", "network.json", "profiles.json"}) store.write(name,"{\"obsolete\":true}");
        Store upgraded = new Store(context); assertEquals(RAW, upgraded.original());
        for (String name : new String[]{"queue.json", "network.json", "profiles.json"}) assertFalse(new File(upgraded.root,name).exists());
    }
    @Test public void removedImageIsReportedAndManualRescanFindsReadableAlternative() throws Exception {
        selectAndScan(); assertTrue(provider.files.get("root/example.PNG").delete());
        assertEquals(0, images.image("example",false).length); assertEquals(0, images.snapshot(true).getInt("matched"));
        images.scan((event,value)->{}); assertEquals(1, images.snapshot(true).getInt("matched"));
        assertEquals(32, images.snapshot(true).getJSONObject("profiles").getJSONObject("example").getInt("width"));
    }
    @Test public void actualJpegAndWebpSourcesAreDecodedAndMatched() throws Exception {
        provider.add("root/missing.webp", "missing.webp", 128, 128); selectAndScan();
        assertEquals(2, images.snapshot(true).getInt("matched"));
        assertEquals(128, images.snapshot(true).getJSONObject("profiles").getJSONObject("missing").getInt("width"));
        assertTrue(images.image("missing", true).length > 0);
        assertTrue(images.image("example", false).length > 0);
    }
    @Test @Config(application=InsfavApplication.class) public void fetchedThumbnailImmediatelyJoinsExistingCatalogWithoutRescanOrJsonMutation()throws Exception{
        selectAndScan();int queries=provider.childQueries;String before=store.original();
        provider.add("root/missing.webp","missing.webp",128,128);
        InsfavApplication app=(InsfavApplication)RuntimeEnvironment.getApplication();org.robolectric.util.ReflectionHelpers.setField(app,"store",store);
        app.imageWritten("missing",DocumentsContract.buildDocumentUriUsingTree(root,"root/missing.webp"),root.toString());
        assertEquals(2,images.snapshot(true).getInt("matched"));assertTrue(images.image("missing",false).length>0);assertEquals(queries,provider.childQueries);assertEquals(before,store.original());
    }
    @Test public void concurrentRequestsShareOneSourceReadWithoutADiskCopy() throws Exception {
        selectAndScan();int before=provider.opens;provider.delayMs=250;
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(6);
        java.util.concurrent.CyclicBarrier barrier=new java.util.concurrent.CyclicBarrier(6);
        try {
            java.util.List<java.util.concurrent.Future<byte[]>> values=new ArrayList<>();
            for(int i=0;i<6;i++)values.add(pool.submit(()->{barrier.await();return images.image("example",false);}));
            byte[] expected=Files.readAllBytes(provider.files.get("root/example.PNG").toPath());
            for(java.util.concurrent.Future<byte[]> value:values)assertArrayEquals(expected,value.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(before+1,provider.opens);assertEquals(5,images.snapshot(false).getJSONObject("imageIO").getInt("coalescedReads"));assertEquals(0,images.snapshot(false).getInt("cacheBytes"));
        } finally {pool.shutdownNow();}
    }
    @Test public void migrationDeletesOnlyKnownDerivedNamesAndKeepsIndexAndSources() throws Exception {
        selectAndScan();String original=store.original();long indexSize=new File(store.root,"thumbnail-index.json").length();
        File dir=new File(context.getCacheDir(),"local-thumbnails");dir.mkdirs();
        File derived=new File(dir,Store.key("legacy")+".webp"),unknown=new File(dir,"unrelated.txt");Files.write(derived.toPath(),new byte[]{1,2,3});Files.write(unknown.toPath(),new byte[]{4});
        LocalImages upgraded=new LocalImages(context,store);assertFalse(derived.exists());assertTrue(unknown.exists());assertEquals(indexSize,new File(store.root,"thumbnail-index.json").length());assertEquals(original,store.original());assertEquals(0,provider.writes);
    }
    public static final class FixtureProvider extends ContentProvider {
        volatile long delayMs; File directory; final Map<String,File> files = new LinkedHashMap<>(); final Map<String,String> names = new HashMap<>();
        int childQueries, opens, writes; boolean available = true, parentHasThumbnailChild;
        void add(String id,String name,int w,int h) throws Exception {File file = new File(directory, name); Bitmap image = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888); try(FileOutputStream output = new FileOutputStream(file)){if(!image.compress(name.toLowerCase(Locale.ROOT).endsWith(".webp") ? Bitmap.CompressFormat.WEBP : name.toLowerCase(Locale.ROOT).matches(".*\\.jpe?g$") ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG,100,output))throw new IOException("Fixture encoding failed");}finally{image.recycle();} files.put(id,file); names.put(id,name);}
        void addBroken(String id,String name) throws Exception {File file = new File(directory,name); Files.write(file.toPath(), new byte[]{1,2,3}); files.put(id,file); names.put(id,name);}
        Map<String,byte[]> contents() throws Exception {Map<String,byte[]> out=new HashMap<>(); for(String id:files.keySet())out.put(id,Files.readAllBytes(files.get(id).toPath()));return out;}
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort) {
            MatrixCursor cursor = new MatrixCursor(projection); if(!available)return cursor;
            String id=DocumentsContract.getDocumentId(uri);
            if(uri.getLastPathSegment().equals("children")) {
                childQueries++;
                if(id.equals("parent")&&parentHasThumbnailChild)row(cursor,projection,"root",".thumbnails",null,true);
                else if(id.equals("root"))for(String fileId:files.keySet())row(cursor,projection,fileId,names.get(fileId),files.get(fileId),false);
            } else row(cursor,projection,id,id.equals("parent")?"Media":".thumbnails",null,true);
            return cursor;
        }
        private void row(MatrixCursor cursor,String[] columns,String id,String name,File file,boolean dir) {
            Object[] values=new Object[columns.length];
            for(int i=0;i<columns.length;i++)switch(columns[i]) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID:values[i]=id;break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME:values[i]=name;break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE:values[i]=dir?DocumentsContract.Document.MIME_TYPE_DIR:"image/png";break;
                case DocumentsContract.Document.COLUMN_SIZE:values[i]=file==null?0:file.length();break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED:values[i]=1;break;
            }
            cursor.addRow(values);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {
            if(delayMs>0)try{Thread.sleep(delayMs);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            if(!"r".equals(mode)){writes++;throw new FileNotFoundException("Read only");}opens++;
            File file=files.get(DocumentsContract.getDocumentId(uri));if(file==null||!available)throw new FileNotFoundException();
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public String getType(Uri uri){return "image/png";}
        @Override public Uri insert(Uri uri,ContentValues values){writes++;throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String where,String[] args){writes++;throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues values,String where,String[] args){writes++;throw new UnsupportedOperationException();}
    }
}
