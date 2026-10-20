package com.instagram.insfav.fetcher;
import android.content.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
/** One WebView/CDN operation at a time. Interactive requests precede queued batch work at safe boundaries. */
final class FetchBroker {
 interface Time{long now();void sleep(long millis)throws InterruptedException;}
 interface Factory{ProfileProvider open(boolean full)throws ProfileProvider.Failure;}
 private static FetchBroker instance;
 static synchronized FetchBroker get(Context c){if(instance==null)instance=new FetchBroker(c,(full)->new WebViewProfileProvider(c,new CookieVault(c).load(),full));return instance;}
 final SharedPreferences prefs;final Factory factory;final Time time;final PriorityBlockingQueue<Request> tasks=new PriorityBlockingQueue<>();
 final AtomicLong sequence=new AtomicLong();volatile Request active;volatile ProfileProvider provider;
 static final class Request implements Comparable<Request>{
  final String user;final boolean full;final long sequence;final CompletableFuture<byte[]> result=new CompletableFuture<>();
  Request(String user,boolean full,long sequence){this.user=user;this.full=full;this.sequence=sequence;}
  @Override public int compareTo(Request other){int n=Boolean.compare(other.full,full);return n!=0?n:Long.compare(sequence,other.sequence);}
 }
 FetchBroker(Context c,Factory factory){this(c,factory,new Time(){public long now(){return System.currentTimeMillis();}public void sleep(long millis)throws InterruptedException{Thread.sleep(millis);}});}
 FetchBroker(Context c,Factory factory,Time time){this.prefs=SessionState.prefs(c);this.factory=factory;this.time=time;Thread thread=new Thread(this::loop,"insfav-profile-broker");thread.setDaemon(true);thread.start();}
 CompletableFuture<byte[]> submit(String user,boolean full){Request r=new Request(user,full,sequence.incrementAndGet());tasks.add(r);return r.result;}
 void cancelBulk(){cancel(false);}
 void cancelViewer(){cancel(true);}
 void cancelAll(){cancel(false);cancel(true);}
 private void cancel(boolean full){for(Request r:tasks)if(r.full==full){r.result.cancel(false);tasks.remove(r);}Request a=active;if(a!=null&&a.full==full){a.result.cancel(false);ProfileProvider current=provider;if(current!=null)current.cancel();}}
 private void loop(){for(;;){Request r=null;try{
  r=tasks.take();active=r;if(r.result.isCancelled())continue;
  String blocked=prefs.getString("blocked","");if(!blocked.isEmpty())throw new ProfileProvider.Failure(blocked,0,prefs.getLong("retryAt",0),true);
  long wait=Math.max(prefs.getLong("nextRequestAt",0),prefs.getLong("retryAt",0));
  boolean yielded=false;
  while(time.now()<wait&&!r.result.isCancelled()){
   if(!r.full&&tasks.peek()!=null&&tasks.peek().full){tasks.add(r);yielded=true;break;}
   time.sleep(Math.min(100,Math.max(1,wait-time.now())));
  }
  if(yielded||r.result.isCancelled())continue;
  if(!r.full&&!RunPolicy.read(prefs).contains(java.time.ZonedDateTime.now()))throw new ProfileProvider.Failure("OUTSIDE_WORKING_HOURS");
  // Reserve a bounded interval even if Android kills the process during the request.
  prefs.edit().putLong("nextRequestAt",time.now()+RunPolicy.read(prefs).maximum*1000L).commit();
  try(ProfileProvider session=factory.open(r.full)){
   provider=session;if(r.result.isCancelled()){session.cancel();continue;}
   byte[] bytes=session.fetch(r.user);if(!r.result.complete(bytes))java.util.Arrays.fill(bytes,(byte)0);
  }finally{provider=null;prefs.edit().putLong("nextRequestAt",time.now()+RunPolicy.read(prefs).delayMillis()).commit();}
 }catch(ProfileProvider.Failure error){
  if(FailurePolicy.pause(error)){prefs.edit().putString("sessionEvidence",error.code.startsWith("COOKIE_")?"INVALID":SessionState.evidence(prefs)).putString("blocked",error.code).putLong("retryAt",Math.max(prefs.getLong("retryAt",0),error.retryAt)).putString("state","Paused: "+error.code).commit();}
  if(r!=null)r.result.completeExceptionally(error);
 }catch(Exception|OutOfMemoryError error){if(r!=null)r.result.completeExceptionally(new ProfileProvider.Failure("NETWORK_ERROR"));}
 finally{active=null;provider=null;Fetcher.publish();}
 }}
}
