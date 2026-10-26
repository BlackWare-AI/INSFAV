package com.instagram.insfav;
import android.app.Application;
import android.net.Uri;
import com.instagram.insfav.fetcher.Fetcher;
import org.json.*;
import java.util.*;
/** Connects the existing read-only catalog to a narrowly scoped, user-authorized writer. */
public final class InsfavApplication extends Application implements Fetcher.Host {
 private Store store;
 @Override public void onCreate(){super.onCreate();store=Store.get(this);Fetcher.initialize(this,this);}
 @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(level==TRIM_MEMORY_RUNNING_LOW||level==TRIM_MEMORY_RUNNING_CRITICAL||level==TRIM_MEMORY_COMPLETE)Fetcher.memoryPressure();}
 @Override public void onLowMemory(){super.onLowMemory();Fetcher.memoryPressure();}
 public Collection<String> users()throws Exception{return new ArrayList<>(store.users());}
 public String tree(){synchronized(store){return store.settings.optString("treeUri");}}
 public String directory(){synchronized(store){return store.settings.optString("directoryId");}}
 public long revision(){synchronized(store){return store.index.optLong("revision");}}
 public JSONObject catalog()throws Exception{synchronized(store){return new JSONObject(store.index.toString());}}
 public boolean hasImage(String user){
  String uri;
  synchronized(store){JSONObject p=store.index.optJSONObject("profiles"),i=p==null?null:p.optJSONObject(user);if(!tree().equals(store.index.optString("treeUri"))||i==null||!i.optBoolean("readable"))return false;uri=i.optString("uri");}
  try{android.graphics.BitmapFactory.Options b=ImageCodec.bounds(getContentResolver(),Uri.parse(uri));return ImageCodec.validate(getContentResolver(),Uri.parse(uri),b);}catch(Exception|OutOfMemoryError error){return false;}
 }
 public void imageWritten(String user,Uri uri,String tree)throws Exception{
  String directory;JSONObject selectedIndex;
  synchronized(store){if(!tree.equals(tree()))return;directory=directory();selectedIndex=store.index;}
  android.graphics.BitmapFactory.Options bounds=ImageCodec.bounds(getContentResolver(),uri);
  if(!ImageCodec.validate(getContentResolver(),uri,bounds))throw new java.io.IOException("Written thumbnail validation failed");
  long size=-1,modified=System.currentTimeMillis();
  try(android.database.Cursor c=getContentResolver().query(uri,new String[]{android.provider.DocumentsContract.Document.COLUMN_SIZE},null,null,null)){if(c!=null&&c.moveToFirst()&&!c.isNull(0))size=c.getLong(0);}
  JSONObject profile=new JSONObject().put("uri",uri.toString()).put("name",user+".webp").put("readable",true).put("width",bounds.outWidth).put("height",bounds.outHeight).put("size",size).put("modified",modified);
  synchronized(store){if(selectedIndex==store.index&&tree.equals(tree())&&directory.equals(directory()))store.updateProfile(user,profile,tree);}
 }
 public void flushImages()throws Exception{store.flushIndex();}
 public long sourceRevision(){synchronized(store){return store.externalRevision;}}
 public void sourceChanged(){synchronized(store){store.externalRevision++;}}
}
