package com.instagram.insfav.fetcher;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** SAF only. No broad storage permission and no queue files in the output folder. */
final class OutputFolder {
    private final ContentResolver resolver;
    private final Uri tree, folder;
    private final Map<String, Uri> files = new HashMap<>();
    OutputFolder(Context context, String treeValue) throws IOException {
        resolver = context.getContentResolver(); tree = Uri.parse(treeValue);
        boolean permission = false;
        for (UriPermission p : resolver.getPersistedUriPermissions()) if (p.getUri().equals(tree) && p.isReadPermission() && p.isWritePermission()) permission = true;
        if (!permission) throw new SecurityException("OUTPUT_PERMISSION_LOST");
        String directory=SessionState.prefs(context).getString("directoryId",DocumentsContract.getTreeDocumentId(tree));
        folder=DocumentsContract.buildDocumentUriUsingTree(tree,directory);
        try(Cursor c=resolver.query(folder,new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},null,null)){
            if(c==null||!c.moveToFirst()||!DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0)))throw new IOException("OUTPUT_FOLDER_INVALID");
        }
        files.putAll(children(folder));
    }
    private Map<String, Uri> children(Uri directory) throws IOException {
        Uri child = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(directory));
        Map<String, Uri> result = new HashMap<>();
        try (Cursor cursor = resolver.query(child, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null)) {
            if (cursor == null) throw new IOException("OUTPUT_UNAVAILABLE");
            while (cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (result.containsKey(name)) throw new IOException("OUTPUT_DUPLICATE_NAMES");
                result.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)));
            }
        }
        return result;
    }
    static byte[] read(InputStream source) throws IOException {
        if (source == null) throw new IOException("OUTPUT_READ_FAILED");
        try (InputStream input = source; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = input.read(buffer)) != -1) { if (bytes.size() + n > ThumbnailCodec.MAX_OUTPUT) throw new IOException("OUTPUT_SIZE_REJECTED"); bytes.write(buffer, 0, n); }
            return bytes.toByteArray();
        }
    }
    boolean valid(String filename) throws IOException {
        Uri file = files.get(filename); if (file == null) return false;
        try { return ThumbnailCodec.valid(read(resolver.openInputStream(file))); }
        catch (FileNotFoundException error) { throw new IOException("OUTPUT_UNAVAILABLE", error); }
        catch (IOException error) { if ("OUTPUT_SIZE_REJECTED".equals(error.getMessage())) return false; throw error; }
    }
    void recover(String filename) throws IOException {
        Uri partial = files.get(filename + ".part");
        if (partial != null) {
            if (!DocumentsContract.deleteDocument(resolver, partial)) throw new IOException("PARTIAL_CLEANUP_FAILED");
            files.remove(filename + ".part");
        }
    }
    private void exactName(Uri file, String name) throws IOException {
        try (Cursor c = resolver.query(file, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null)) {
            if (c == null || !c.moveToFirst() || !name.equals(c.getString(0))) throw new IOException("OUTPUT_FILENAME_CHANGED");
        }
    }
    private static final Object WRITE_LOCK=new Object();
    boolean write(String filename,byte[] bytes)throws IOException{commit(filename,bytes,false);return true;}
    Uri commit(String filename,byte[] bytes,boolean replace)throws IOException{
        synchronized(WRITE_LOCK){
            if(!filename.matches("[a-z0-9._]{1,30}\\.webp")||!ThumbnailCodec.valid(bytes))throw new IOException("OUTPUT_INVALID_WEBP");
            files.clear();files.putAll(children(folder));
            if(!replace&&valid(filename))return files.get(filename);
            String nonce=java.util.UUID.randomUUID().toString();String pendingName=".insfav-"+nonce+".part",backupName=".insfav-"+nonce+".bak";
            Uri pending=null,old=files.get(filename),backup=null,committed=null;
            try{
                pending=DocumentsContract.createDocument(resolver,folder,"application/octet-stream",pendingName);if(pending==null)throw new IOException("OUTPUT_CREATE_FAILED");exactName(pending,pendingName);
                try(OutputStream output=resolver.openOutputStream(pending,"wt")){if(output==null)throw new IOException("OUTPUT_WRITE_FAILED");output.write(bytes);output.flush();}
                if(!ThumbnailCodec.valid(read(resolver.openInputStream(pending))))throw new IOException("OUTPUT_READBACK_FAILED");
                if(old!=null){backup=DocumentsContract.renameDocument(resolver,old,backupName);if(backup==null)throw new IOException("ATOMIC_RENAME_UNSUPPORTED");exactName(backup,backupName);}
                committed=DocumentsContract.renameDocument(resolver,pending,filename);if(committed==null)throw new IOException("ATOMIC_RENAME_UNSUPPORTED");pending=null;exactName(committed,filename);
                if(!ThumbnailCodec.valid(read(resolver.openInputStream(committed))))throw new IOException("OUTPUT_FINAL_VERIFY_FAILED");
                // Only a verified new final file may replace the old valid content.
                if(backup!=null){try{DocumentsContract.deleteDocument(resolver,backup);}catch(Exception ignored){/* Retain backup rather than risk the final file. */}}
                files.put(filename,committed);return committed;
            }catch(Exception error){
                if(committed!=null)try{DocumentsContract.deleteDocument(resolver,committed);}catch(Exception ignored){}
                if(backup!=null)try{DocumentsContract.renameDocument(resolver,backup,filename);}catch(Exception ignored){/* Old bytes stay in the uniquely named backup, never deleted. */}
                throw error instanceof IOException?(IOException)error:new IOException("OUTPUT_REPLACE_FAILED",error);
            }finally{if(pending!=null)try{DocumentsContract.deleteDocument(resolver,pending);}catch(Exception ignored){}}
        }
    }
}
