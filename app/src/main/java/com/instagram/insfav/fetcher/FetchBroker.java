package com.instagram.insfav.fetcher;
import android.content.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** Shared cookie lease and reusable WebViews; dispatch pacing never holds active operations. */
final class FetchBroker {
 interface Time{long now();void sleep(long millis)throws InterruptedException;}
 interface Factory{ProfileProvider open(boolean full)throws ProfileProvider.Failure;}
 private static FetchBroker instance;
 static synchronized FetchBroker get(Context c){if(instance==null)instance=new FetchBroker(c,full->new WebViewProfileProvider(c,new CookieVault(c).load()));return instance;}
 final SharedPreferences prefs;final Factory factory;final Time time;final Context context;
 final PriorityBlockingQueue<Request> tasks=new PriorityBlockingQueue<>();final Set<Request> active=ConcurrentHashMap.newKeySet();
 final AtomicLong sequence=new AtomicLong();final ExecutorService workers=Executors.newCachedThreadPool(r->{Thread t=new Thread(r,"insfav-profile-worker");t.setDaemon(true);return t;});
 private volatile String sessionGeneration="";
 volatile ProfileProvider provider;volatile int limit=1;volatile boolean batch;
 static final class Request implements Comparable<Request>{
  final String user;final boolean full;final long sequence;final CompletableFuture<byte[]> result=new CompletableFuture<>();volatile Thread thread;volatile long deadline;
  Request(String user,boolean full,long sequence){this.user=user;this.full=full;this.sequence=sequence;}
  public int compareTo(Request other){int n=Boolean.compare(other.full,full);return n!=0?n:Long.compare(sequence,other.sequence);}
 }
 FetchBroker(Context c,Factory f){this(c,f,new Time(){public long now(){return System.currentTimeMillis();}public void sleep(long m)throws InterruptedException{Thread.sleep(m);}});}
 FetchBroker(Context c,Factory f,Time t){context=c.getApplicationContext();prefs=SessionState.prefs(c);factory=f;time=t;Thread thread=new Thread(this::loop,"insfav-profile-dispatcher");thread.setDaemon(true);thread.start();}
 void beginBatch(){batch=true;limit=ConcurrencyPolicy.initial(context,prefs.getInt("concurrentWebViews",1));QueueControl.effectiveViews=limit;QueueControl.peakAccounts=0;QueueControl.memoryPressure=false;Diagnostics d=new Diagnostics(context);d.log("POOL","REQUESTED_LIMIT",prefs.getInt("concurrentWebViews",1));d.log("POOL","EFFECTIVE_LIMIT",limit);}
 void endBatch(){batch=false;}
 int capacity(){return limit;}
 CompletableFuture<byte[]> submit(String user,boolean full){Request r=new Request(user,full,sequence.incrementAndGet());tasks.add(r);return r.result;}
 void cancelBulk(){cancel(false);}void cancelViewer(){cancel(true);}void cancelAll(){cancel(false);cancel(true);}
 private void cancel(boolean full){for(Request r:tasks)if(r.full==full){tasks.remove(r);r.result.cancel(false);}for(Request r:active)if(r.full==full)cancel(r,false);}
 private void cancel(Request r,boolean timeout){if(timeout)r.result.completeExceptionally(new ProfileProvider.Failure("PROFILE_OPERATION_TIMEOUT"));else r.result.cancel(false);ProfileProvider p=provider;if(p!=null)p.cancel(r.user);Thread thread=r.thread;if(thread!=null)thread.interrupt();}
 private void block(ProfileProvider.Failure e){prefs.edit().putString("sessionEvidence",e.code.startsWith("COOKIE_")?"INVALID":SessionState.evidence(prefs)).putString("blocked",e.code).putLong("retryAt",Math.max(prefs.getLong("retryAt",0),e.retryAt)).putString("state","Paused: "+e.code).commit();ProfileProvider p=provider;if(p!=null)p.cancel();for(Request a:active)if(a.thread!=Thread.currentThread())cancel(a,false);}
 private void loop(){for(;;)try{
  for(Request r:active)if(System.nanoTime()>r.deadline&&!r.result.isDone())cancel(r,true);
  if(QueueControl.memoryPressure){limit=ConcurrencyPolicy.reduced(limit);QueueControl.effectiveViews=limit;QueueControl.memoryPressure=false;ProfileProvider p=provider;if(p!=null)p.trim(limit);new Diagnostics(context).log("POOL","MEMORY_PRESSURE_LIMIT",limit);Fetcher.publish();}
  if(active.isEmpty()&&tasks.isEmpty()&&!batch){ProfileProvider p=provider;provider=null;if(p!=null)p.close();}
  if(provider!=null&&active.isEmpty()&&(provider.blockingFailure()!=null||!sessionGeneration.equals(prefs.getString("cookieGeneration","")))){ProfileProvider p=provider;provider=null;p.close();}
  Request r=tasks.peek();if(r==null||active.size()>=limit){Thread.sleep(20);continue;}
  if(r.result.isCancelled()){tasks.remove(r);continue;}
  String blocked=prefs.getString("blocked","");if(!blocked.isEmpty()){tasks.remove(r);r.result.completeExceptionally(new ProfileProvider.Failure(blocked,0,prefs.getLong("retryAt",0),true));continue;}
  if(!r.full&&!RunPolicy.read(prefs).contains(java.time.ZonedDateTime.now())){tasks.remove(r);r.result.completeExceptionally(new ProfileProvider.Failure("OUTSIDE_WORKING_HOURS"));continue;}
  long now=time.now(),next=Math.max(prefs.getLong("nextRequestAt",0),prefs.getLong("retryAt",0));if(now<next){time.sleep(Math.min(50,next-now));continue;}
  // Interactive work drains existing batch operations, then owns the same session at a safe boundary.
  if((r.full&&!active.isEmpty())||active.stream().anyMatch(a->a.full)){Thread.sleep(20);continue;}
  tasks.remove(r);prefs.edit().putLong("nextRequestAt",now+RunPolicy.read(prefs).delayMillis()).commit();
  try{if(provider==null){provider=factory.open(false);sessionGeneration=prefs.getString("cookieGeneration","");}provider.trim(limit);}catch(ProfileProvider.Failure e){if(FailurePolicy.pause(e))block(e);r.result.completeExceptionally(e);continue;}
  r.deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);active.add(r);QueueControl.activeAccounts=active.size();QueueControl.peakAccounts=Math.max(QueueControl.peakAccounts,active.size());
  workers.execute(()->execute(r));
 }catch(InterruptedException ignored){}catch(RuntimeException|OutOfMemoryError e){QueueControl.memoryPressure=true;}}
 private void execute(Request r){r.thread=Thread.currentThread();byte[] bytes=null;try{
  if(r.result.isCancelled())return;ProfileProvider p=provider;bytes=p.fetch(r.user,r.full);if(!r.result.complete(bytes))Arrays.fill(bytes,(byte)0);
 }catch(ProfileProvider.Failure e){if(FailurePolicy.pause(e))block(e);r.result.completeExceptionally(e);if(e.code.equals("WEBVIEW_RENDERER_GONE"))QueueControl.memoryPressure=true;}
 catch(OutOfMemoryError e){QueueControl.memoryPressure=true;r.result.completeExceptionally(new ProfileProvider.Failure("IMAGE_MEMORY_LIMIT"));}
 catch(RuntimeException e){r.result.completeExceptionally(new ProfileProvider.Failure("PROFILE_PROCESSING_ERROR"));}
 finally{active.remove(r);QueueControl.activeAccounts=active.size();r.thread=null;Thread.interrupted();Fetcher.publish();}}
}
