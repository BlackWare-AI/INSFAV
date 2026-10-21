package com.instagram.insfav.fetcher;
import android.content.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class BrokerTest{
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();SessionState.prefs(c).edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439).commit();}
 @Test public void viewerPrecedesQueuedBatchAfterSafeBoundaryAndNeverRunsInParallel()throws Exception{
  List<String> order=Collections.synchronizedList(new ArrayList<>());AtomicInteger parallel=new AtomicInteger(),peak=new AtomicInteger();CountDownLatch inside=new CountDownLatch(1),release=new CountDownLatch(1);
  FetchBroker broker=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String user)throws Failure{int active=parallel.incrementAndGet();peak.accumulateAndGet(active,Math::max);order.add(user);try{if(user.equals("first")){inside.countDown();release.await(5,TimeUnit.SECONDS);}return new byte[]{1};}catch(InterruptedException e){throw new Failure("REQUEST_CANCELLED");}finally{parallel.decrementAndGet();}}public void cancel(){release.countDown();}},new TestTime());
  CompletableFuture<byte[]> first=broker.submit("first",false);assertTrue(inside.await(2,TimeUnit.SECONDS));CompletableFuture<byte[]> bulk=broker.submit("second",false),view=broker.submit("viewer",true);release.countDown();first.get(5,TimeUnit.SECONDS);view.get(5,TimeUnit.SECONDS);bulk.get(5,TimeUnit.SECONDS);assertEquals(Arrays.asList("first","viewer","second"),order);assertEquals(1,peak.get());
 }
 @Test public void rateLimitCooldownPreventsNextProviderOpening()throws Exception{
  AtomicInteger calls=new AtomicInteger();FetchBroker broker=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String u)throws Failure{calls.incrementAndGet();throw new Failure("RATE_LIMITED",429,System.currentTimeMillis()+60000,true);}public void cancel(){}},new TestTime());
  for(int i=0;i<2;i++)try{broker.submit("example",true).get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
  assertEquals(1,calls.get());assertEquals("RATE_LIMITED",SessionState.prefs(c).getString("blocked",""));
 }
 @Test public void closedViewerZeroesAndReleasesRetainedHighResolutionBytes()throws Exception{
  Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){return Collections.emptyList();}public String tree(){return "";}public String directory(){return "";}public long revision(){return 0;}public boolean hasImage(String u){return false;}public void imageWritten(String u,android.net.Uri uri,String tree){}});
  byte[] data=new byte[]{1,2,3};java.lang.reflect.Field bytes=Fetcher.class.getDeclaredField("fullBytes"),token=Fetcher.class.getDeclaredField("viewerToken");bytes.setAccessible(true);token.setAccessible(true);bytes.set(null,data);token.set(null,"token");assertEquals(3,Fetcher.full("token").length);Fetcher.closeViewer();assertArrayEquals(new byte[3],data);assertEquals(0,Fetcher.full("token").length);assertNull(bytes.get(null));
 }
 @Test public void resumeReplacesGloballyFailedSessionEvenWithoutProviderBarrier()throws Exception{
  AtomicInteger opened=new AtomicInteger();FetchBroker b=new FetchBroker(c,full->{boolean bad=opened.incrementAndGet()==1;return new ProfileProvider(){public byte[] fetch(String u)throws Failure{if(bad)throw new Failure("COOKIE_WEBVIEW_SETUP_TIMEOUT",0,0,true);return new byte[]{9};}public void cancel(){}};},new TestTime());b.batch=true;
  try{b.submit("first",false).get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
  SessionState.prefs(c).edit().putString("blocked","").commit();assertArrayEquals(new byte[]{9},b.submit("second",false).get(3,TimeUnit.SECONDS));assertEquals(2,opened.get());b.endBatch();
 }

 @Test public void dispatchHonorsLocalScheduleAcrossMidnightAndTimezoneChanges()throws Exception {
  SharedPreferences p=SessionState.prefs(c);p.edit().putInt("startMinute",1320).putInt("endMinute",360).commit();
  java.util.TimeZone old=java.util.TimeZone.getDefault();java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Istanbul"));
  try {
   java.util.concurrent.atomic.AtomicLong now=new java.util.concurrent.atomic.AtomicLong(java.time.Instant.parse("2026-10-21T18:59:00Z").toEpochMilli());java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
   FetchBroker b=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String u){calls.incrementAndGet();return new byte[]{1};}public void cancel(){}},new FetchBroker.Time(){public long now(){return now.get();}public void sleep(long m){now.addAndGet(m);Thread.yield();}});
   try{b.submit("outside",false).get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}assertEquals(0,calls.get());
   now.set(java.time.Instant.parse("2026-10-21T19:00:00Z").toEpochMilli());b.submit("night",false).get(3,TimeUnit.SECONDS);assertEquals(1,calls.get());
   now.set(java.time.Instant.parse("2026-10-22T03:00:00Z").toEpochMilli());try{b.submit("end",false).get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}assertEquals(1,calls.get());
   java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));b.submit("new_zone",false).get(3,TimeUnit.SECONDS);assertEquals(2,calls.get());b.cancelAll();
  } finally {java.util.TimeZone.setDefault(old);}
 }
}
