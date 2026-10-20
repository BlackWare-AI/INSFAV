package com.instagram.insfav.fetcher;
import android.app.*;import android.content.*;import android.graphics.*;import android.os.*;import android.webkit.*;
import org.json.*;import java.io.*;import java.net.*;import java.security.Principal;import javax.net.ssl.*;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
/** Real Chromium page creation/navigation/DOM plus the real CDN reader against offline HTTPS fixtures. */
public final class PoolInstrumentation extends Instrumentation{
 private Bundle status(String s){Bundle b=new Bundle();b.putString("stream",s+"\n");return b;}
 @Override public void onCreate(Bundle args){super.onCreate(args);start();}
 @Override public void onStart(){Bundle result=new Bundle();try{
  Context c=getTargetContext();byte[] png=png();JSONArray matrix=new JSONArray();
  for(int requested:new int[]{1,2,5,10,30}){
   SessionState.prefs(c).edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439).putInt("concurrentWebViews",requested).commit();
   ConcurrentHashMap<String,Long> navStarts=new ConcurrentHashMap<>(),ends=new ConcurrentHashMap<>();AtomicInteger cdn=new AtomicInteger(),peakCdn=new AtomicInteger(),peakViews=new AtomicInteger();AtomicBoolean sampling=new AtomicBoolean(true);AtomicInteger holdMs=new AtomicInteger(3200);
   WebViewProfileProvider p=new WebViewProfileProvider(c,CookieJar.parse("sessionid=OFFLINE_FIXTURE; ds_user_id=123",System.currentTimeMillis()),
    (view,url)->{navStarts.put(url.substring("https://www.instagram.com/".length()).replace("/",""),SystemClock.elapsedRealtime());view.loadUrl(url);},
    url->{
     if(url.startsWith("https://www.instagram.com/")){String u=url.substring("https://www.instagram.com/".length()).replace("/","");String h="<html><head><link rel='canonical' href='"+url+"'></head><body><main><header><img id='avatar' alt=\""+u+"'s profile picture\"></header></main><script>setTimeout(()=>document.getElementById('avatar').src='https://fixture.cdninstagram.com/"+u+".png',"+holdMs.get()+")</script></body></html>";return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(h.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
     return new WebResourceResponse("image/png",null,new ByteArrayInputStream(png));
    },()->new PublicProfileProvider(url->new FixtureConnection(url,png,cdn,peakCdn)));
   FetchBroker b=new FetchBroker(c,full->p);b.beginBatch();int effective=b.capacity(),count=Math.max(10,effective+2);holdMs.set(8000);
   Handler main=new Handler(Looper.getMainLooper());Runnable sample=new Runnable(){public void run(){peakViews.accumulateAndGet(p.activeCount,Math::max);if(sampling.get())main.postDelayed(this,25);}};main.post(sample);
   long start=SystemClock.elapsedRealtime();int success=0,errors=0;
   try{
    List<CompletableFuture<byte[]>> fs=new ArrayList<>();for(int i=0;i<count;i++){String u="fixture_"+requested+"_"+i;CompletableFuture<byte[]> f=b.submit(u,false);f.whenComplete((bytes,error)->ends.put(u,SystemClock.elapsedRealtime()));fs.add(f);}
    for(CompletableFuture<byte[]> f:fs)try{byte[] image=f.get(100,TimeUnit.SECONDS);byte[] thumb=ThumbnailCodec.convert(image);if(!ThumbnailCodec.valid(thumb))throw new AssertionError("Bad thumbnail");Arrays.fill(image,(byte)0);success++;}catch(ExecutionException e){errors++;throw e;}
    long elapsed=SystemClock.elapsedRealtime()-start;boolean overlap=false;for(String a:navStarts.keySet())for(String d:navStarts.keySet())if(!a.equals(d)&&navStarts.get(a)<ends.get(d)&&navStarts.get(d)<ends.get(a))overlap=true;
    if(effective>=2&&(!overlap||peakViews.get()<2))throw new AssertionError("No overlapping real Chromium pages requested="+requested+" peak="+peakViews);
    if(QueueControl.peakAccounts>effective)throw new AssertionError("Exceeded capacity");
    android.os.Debug.MemoryInfo mi=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(mi);android.app.ActivityManager.MemoryInfo system=new android.app.ActivityManager.MemoryInfo();c.getSystemService(android.app.ActivityManager.class).getMemoryInfo(system);
    JSONObject row=new JSONObject().put("sdk",Build.VERSION.SDK_INT).put("requested",requested).put("effective",effective).put("finalEffective",b.capacity()).put("fixtureDomDelayMs",holdMs.get()).put("accounts",count).put("maxActiveAccounts",QueueControl.peakAccounts).put("maxActiveWebViews",peakViews.get()).put("maxCdnReaders",peakCdn.get()).put("intervalOverlap",overlap).put("elapsedMs",elapsed).put("completedPerMinute",success*60000.0/Math.max(1,elapsed)).put("appPssKiB",mi.getTotalPss()).put("systemAvailableBytes",system.availMem).put("success",success).put("skipped",0).put("errors",errors).put("createdViews",p.createdCount).put("reusedViews",p.reusedCount).put("navigationStartsMs",new JSONObject(navStarts)).put("completedMs",new JSONObject(ends));matrix.put(row);sendStatus(0,status(row.toString()));
   }finally{sampling.set(false);b.endBatch();b.cancelAll();long deadline=SystemClock.elapsedRealtime()+5000;while(!b.active.isEmpty()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(25);while(b.provider!=null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(25);}
  }
  try(FileOutputStream out=c.openFileOutput("pool-matrix.json",Context.MODE_PRIVATE)){out.write(matrix.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  background(c,png);
  result.putString("stream","POOL_MATRIX_OK\n");finish(Activity.RESULT_OK,result);
 }catch(Throwable error){result.putString("stream","POOL_MATRIX_FAILED: "+error.getClass().getSimpleName()+" "+error.getMessage()+"\n");finish(Activity.RESULT_CANCELED,result);}}
 void background(Context c,byte[] png)throws Exception{
  c.sendBroadcast(new Intent().setComponent(new ComponentName("com.instagram.insfav.test",FixtureDocuments.Grant.class.getName())));
  long until=SystemClock.elapsedRealtime()+10000;boolean granted=false;
  while(SystemClock.elapsedRealtime()<until&&!granted)try{c.getContentResolver().takePersistableUriPermission(FixtureDocuments.tree(),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);granted=true;}catch(SecurityException e){Thread.sleep(100);}
  if(!granted)throw new AssertionError("Fixture SAF grant missing");
  List<String> users=new ArrayList<>();for(int i=0;i<8;i++)users.add("background_"+i);Set<String> written=ConcurrentHashMap.newKeySet();CountDownLatch navigation=new CountDownLatch(2);
  c.startActivity(new Intent(c,com.instagram.insfav.MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Thread.sleep(1200);
  runOnMainSync(()->Fetcher.initialize(c,new Fetcher.Host(){public Collection<String>users(){return users;}public String tree(){return FixtureDocuments.tree().toString();}public String directory(){return "root";}public long revision(){return written.size();}public boolean hasImage(String u){return written.contains(u);}public void imageWritten(String u,android.net.Uri uri,String tree){written.add(u);}}));
  SessionState.prefs(c).edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439).putInt("concurrentWebViews",2).putBoolean("background",true).putBoolean("armed",true).putBoolean("userPaused",false).putString("tree",FixtureDocuments.tree().toString()).putString("directoryId","root").commit();
  try(QueueStore q=new QueueStore(c)){q.sync(users);}
  WebViewProfileProvider p=new WebViewProfileProvider(c,CookieJar.parse("sessionid=OFFLINE_BACKGROUND; ds_user_id=123",System.currentTimeMillis()),(view,url)->{navigation.countDown();view.loadUrl(url);},url->{if(url.startsWith("https://www.instagram.com/")){String u=url.substring("https://www.instagram.com/".length()).replace("/","");String h="<html><head><link rel='canonical' href='"+url+"'></head><body><main><header><img id='a' alt=\""+u+"'s profile picture\"></header></main><script>setTimeout(()=>document.getElementById('a').src='https://fixture.cdninstagram.com/"+u+".png',4000)</script></body></html>";return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(h.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}return new WebResourceResponse("image/png",null,new ByteArrayInputStream(png));},()->new PublicProfileProvider(url->new FixtureConnection(url,png,new AtomicInteger(),new AtomicInteger())));
  FetchBroker b=new FetchBroker(c,full->p);java.lang.reflect.Field f=FetchBroker.class.getDeclaredField("instance");f.setAccessible(true);f.set(null,b);QueueControl.requested=false;
  FetchService.request(c,"missing");if(!navigation.await(15,TimeUnit.SECONDS))throw new AssertionError("Service did not start two real pages");
  getUiAutomation().executeShellCommand("input keyevent KEYCODE_HOME").close();int beforeHome=written.size();until=SystemClock.elapsedRealtime()+20000;while(written.size()<=beforeHome&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
  if(written.size()<=beforeHome||QueueControl.requested)throw new AssertionError("Background Home interrupted retrieval");int afterHome=written.size();
  runOnMainSync(()->{for(ActivityManager.AppTask task:c.getSystemService(ActivityManager.class).getAppTasks())task.finishAndRemoveTask();});int beforeRemove=written.size();until=SystemClock.elapsedRealtime()+45000;while((FetchService.running||written.size()<8)&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
  if(written.size()!=8||QueueControl.requested)throw new AssertionError("Removing task interrupted queue, writes="+written.size());
  JSONObject out=new JSONObject().put("homeBefore",beforeHome).put("homeAfter",afterHome).put("removeTaskBefore",beforeRemove).put("completed",written.size()).put("userPaused",SessionState.prefs(c).getBoolean("userPaused",false)).put("fixture",true);
  try(FileOutputStream stream=c.openFileOutput("background-fixture.json",Context.MODE_PRIVATE)){stream.write(out.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));}sendStatus(0,status("BACKGROUND_HOME_TASK_OK "+out));
 }
 static byte[] png(){Bitmap b=Bitmap.createBitmap(640,480,Bitmap.Config.ARGB_8888);b.eraseColor(Color.BLUE);ByteArrayOutputStream o=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.PNG,100,o);b.recycle();return o.toByteArray();}
 static final class FixtureConnection extends HttpsURLConnection{
  final byte[] bytes;final AtomicInteger active,peak;
  FixtureConnection(URL u,byte[] b,AtomicInteger a,AtomicInteger p){super(u);bytes=b;active=a;peak=p;}
  public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}public int getResponseCode(){return 200;}public String getContentType(){return "image/png";}public int getContentLength(){return bytes.length;}public long getContentLengthLong(){return bytes.length;}
  public InputStream getInputStream(){return new ByteArrayInputStream(bytes){boolean counted;@Override public synchronized int read(byte[] b,int off,int len){if(!counted){counted=true;peak.accumulateAndGet(active.incrementAndGet(),Math::max);try{Thread.sleep(1400);}catch(InterruptedException e){Thread.currentThread().interrupt();}}return super.read(b,off,len);}@Override public void close()throws IOException{if(counted){counted=false;active.decrementAndGet();}super.close();}};}
  public String getCipherSuite(){return "OFFLINE";}public java.security.cert.Certificate[] getLocalCertificates(){return null;}public java.security.cert.Certificate[] getServerCertificates(){return null;}public Principal getPeerPrincipal(){return null;}public Principal getLocalPrincipal(){return null;}
 }
}
