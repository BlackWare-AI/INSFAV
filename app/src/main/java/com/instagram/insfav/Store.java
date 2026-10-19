package com.instagram.insfav;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Private durable storage. Original import bytes are retained independently of derived state. */
final class Store {
    private static Store instance;
    static synchronized Store get(Context c) { if(instance==null) instance=new Store(c.getApplicationContext()); return instance; }
    final File root, thumbs, temporary;
    JSONObject profiles, queue;
    private LinkedHashSet<String> cachedUsers;
    private int cachedTotal;
    private long metricsAt;
    private JSONObject cachedMetrics;
    private final Map<String,String> keys=new java.util.concurrent.ConcurrentHashMap<>();
    private final File diagnostic;
    private Store(Context c) {
        root=c.getFilesDir(); thumbs=new File(root,"thumbnails"); temporary=new File(c.getCacheDir(),"full-resolution");
        thumbs.mkdirs();temporary.mkdirs();diagnostic=new File(root,"diagnostics.txt");
        profiles=readObject("profiles.json");queue=readObject("queue.json");
    }
    static String normalize(String s) {return s.trim().replaceFirst("^@","").toLowerCase(Locale.ROOT);}
    static String key(String u) {try{byte[] h=MessageDigest.getInstance("SHA-256").digest(u.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:h)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    File thumbnail(String user){return new File(thumbs,keys.computeIfAbsent(user,Store::key)+".webp");}
    File fullImage(String user){return new File(temporary,keys.computeIfAbsent(user,Store::key)+".webp");}
    synchronized String original() {try{return new String(new AtomicFile(new File(root,"document.json")).readFully(),StandardCharsets.UTF_8);}catch(Exception e){return "";}}
    static JSONArray collections(String raw) throws JSONException {
        Object x=new JSONTokener(raw).nextValue();Object c=x instanceof JSONArray?x:((JSONObject)x).get("favorite_page_pref");
        if(c instanceof String)c=new JSONTokener((String)c).nextValue();
        if(!(c instanceof JSONArray))throw new JSONException("Invalid collection array");JSONArray a=(JSONArray)c;
        for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);if(!(o.get("name") instanceof String))throw new JSONException("Invalid collection name");JSONArray items=o.getJSONArray("items");for(int j=0;j<items.length();j++){JSONObject v=items.getJSONObject(j);if(!(v.get("pageId") instanceof String)||v.has("name")&&!(v.get("name") instanceof String))throw new JSONException("Invalid account fields");}}
        return a;
    }
    synchronized void importDocument(String raw) throws Exception {collections(raw);write("document.json",raw);cachedUsers=null;metricsAt=0;queue.put("state","cancelled");saveQueue();log("import","success");}
    synchronized LinkedHashSet<String> users() throws JSONException {
        if(cachedUsers!=null)return new LinkedHashSet<>(cachedUsers);
        String raw=original();cachedUsers=new LinkedHashSet<>();cachedTotal=0;
        if(!raw.isEmpty()){JSONArray a=collections(raw);for(int i=0;i<a.length();i++){JSONArray b=a.getJSONObject(i).getJSONArray("items");cachedTotal+=b.length();for(int j=0;j<b.length();j++){String u=normalize(b.getJSONObject(j).getString("pageId"));if(!u.isEmpty())cachedUsers.add(u);}}}
        return new LinkedHashSet<>(cachedUsers);
    }
    synchronized void clearDocument() throws Exception {new AtomicFile(new File(root,"document.json")).delete();cachedUsers=null;metricsAt=0;queue.put("state","cancelled");saveQueue();log("clear_collections","success");}
    synchronized JSONObject readObject(String name){try{return new JSONObject(new String(new AtomicFile(new File(root,name)).readFully(),StandardCharsets.UTF_8));}catch(Exception e){return new JSONObject();}}
    synchronized void write(String name,String value) throws IOException {AtomicFile a=new AtomicFile(new File(root,name));FileOutputStream f=null;try{f=a.startWrite();f.write(value.getBytes(StandardCharsets.UTF_8));a.finishWrite(f);}catch(IOException e){if(f!=null)a.failWrite(f);throw e;}}
    synchronized void saveProfiles() throws IOException {metricsAt=0;write("profiles.json",profiles.toString());}
    synchronized void saveQueue() throws IOException {write("queue.json",queue.toString());}
    synchronized void putProfile(String user,String error,boolean unavailable) throws Exception {
        JSONObject p=profiles.optJSONObject(user);if(p==null)p=new JSONObject();
        p.put("file",thumbnail(user).isFile());p.put("updated",System.currentTimeMillis());
        if(error==null){p.remove("error");p.remove("unavailable");}else{p.put("error",error);p.put("unavailable",unavailable);p.put("attempts",p.optInt("attempts")+1);}
        profiles.put(user,p);saveProfiles();
    }
    synchronized JSONObject snapshot() throws Exception {return snapshot(true);}
    synchronized JSONObject snapshot(boolean includeProfiles) throws Exception {
        JSONObject out=new JSONObject();
        if(!includeProfiles&&cachedMetrics!=null&&System.currentTimeMillis()-metricsAt<10000){out=new JSONObject(cachedMetrics.toString());JSONObject q=new JSONObject(queue.toString());JSONArray u=q.optJSONArray("users");q.put("total",u==null?0:u.length());q.remove("users");if(RetrievalService.active&&RetrievalService.runStarted>0)q.put("elapsedMs",RetrievalService.elapsedBase+android.os.SystemClock.elapsedRealtime()-RetrievalService.runStarted);out.put("queue",q);return out;}
        LinkedHashSet<String> users=users();int downloaded=0,failed=0;
        JSONObject p=includeProfiles?new JSONObject(profiles.toString()):null;
        for(String u:users){boolean file=thumbnail(u).isFile();JSONObject v=profiles.optJSONObject(u);if(includeProfiles){JSONObject copy=p.optJSONObject(u);if(copy==null){copy=new JSONObject();p.put(u,copy);}copy.put("file",file);}if(file)downloaded++;if(v!=null&&v.has("error"))failed++;}
        JSONObject q=new JSONObject(queue.toString());JSONArray pending=q.optJSONArray("users");q.put("total",pending==null?0:pending.length());q.remove("users");if(RetrievalService.active&&RetrievalService.runStarted>0)q.put("elapsedMs",RetrievalService.elapsedBase+android.os.SystemClock.elapsedRealtime()-RetrievalService.runStarted);
        if(includeProfiles)out.put("profiles",p);out.put("queue",q);out.put("total",cachedTotal);out.put("unique",users.size());out.put("downloaded",downloaded);out.put("failed",failed);out.put("remaining",users.size()-downloaded);out.put("cacheBytes",size(thumbs));cachedMetrics=new JSONObject(out.toString());cachedMetrics.remove("profiles");metricsAt=System.currentTimeMillis();return out;
    }
    static long size(File dir){long n=0;File[] fs=dir.listFiles();if(fs!=null)for(File f:fs)n+=f.length();return n;}
    synchronized void trimTemporary() {
        File[] files=temporary.listFiles();if(files==null)return;
        Arrays.sort(files,Comparator.comparingLong(File::lastModified));long bytes=size(temporary),cutoff=System.currentTimeMillis()-7L*24*60*60*1000;
        for(File f:files)if(bytes>32L*1024*1024||f.lastModified()<cutoff){long n=f.length();if(f.delete())bytes-=n;}
    }
    synchronized void clearImages(boolean full) throws Exception {
        File[] fs=(full?temporary:thumbs).listFiles();if(fs!=null)for(File f:fs)if(!f.delete())throw new IOException("Could not remove image");
        if(!full){Iterator<String> it=profiles.keys();while(it.hasNext())profiles.getJSONObject(it.next()).put("file",false);saveProfiles();}
        log(full?"clear_temporary":"clear_thumbnails","success");
    }
    synchronized void clearFailures() throws Exception {Iterator<String> it=profiles.keys();while(it.hasNext()){JSONObject p=profiles.getJSONObject(it.next());p.remove("error");p.remove("unavailable");p.remove("attempts");}saveProfiles();log("clear_failures","success");}
    synchronized void log(String event,String code){try{if(diagnostic.length()>256*1024){File old=new File(root,"diagnostics.1.txt");old.delete();diagnostic.renameTo(old);}try(FileOutputStream f=new FileOutputStream(diagnostic,true)){String clean=event.replaceAll("[^a-zA-Z0-9_:-]","")+" "+code.replaceAll("[^a-zA-Z0-9_:-]","");f.write((System.currentTimeMillis()+" "+clean+"\n").getBytes(StandardCharsets.UTF_8));}}catch(IOException ignored){}}
    synchronized String logs(){StringBuilder b=new StringBuilder("INSFAV diagnostics — no account identifiers or request URLs\n");for(String n:new String[]{"diagnostics.1.txt","diagnostics.txt"})try{b.append(new String(java.nio.file.Files.readAllBytes(new File(root,n).toPath()),StandardCharsets.UTF_8));}catch(IOException ignored){}return b.toString();}
}
