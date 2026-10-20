package com.instagram.insfav.fetcher;
import android.content.*;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;
import java.util.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class BrokerTest{
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();SessionState.prefs(c).edit().clear().putInt("minimum",1).putInt("maximum",1).putInt("startMinute",0).putInt("endMinute",1439).commit();}
 @Test public void viewerPrecedesQueuedBatchAfterSafeBoundaryAndNeverRunsInParallel()throws Exception{
  List<String> order=Collections.synchronizedList(new ArrayList<>());AtomicInteger parallel=new AtomicInteger(),peak=new AtomicInteger();CountDownLatch inside=new CountDownLatch(1),release=new CountDownLatch(1);
  FetchBroker broker=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String user)throws Failure{int active=parallel.incrementAndGet();peak.accumulateAndGet(active,Math::max);order.add(user);try{if(user.equals("first")){inside.countDown();release.await(5,TimeUnit.SECONDS);}return new byte[]{1};}catch(InterruptedException e){throw new Failure("REQUEST_CANCELLED");}finally{parallel.decrementAndGet();}}public void cancel(){release.countDown();}});
  CompletableFuture<byte[]> first=broker.submit("first",false);assertTrue(inside.await(2,TimeUnit.SECONDS));CompletableFuture<byte[]> bulk=broker.submit("second",false),view=broker.submit("viewer",true);release.countDown();first.get(5,TimeUnit.SECONDS);view.get(5,TimeUnit.SECONDS);bulk.get(5,TimeUnit.SECONDS);assertEquals(Arrays.asList("first","viewer","second"),order);assertEquals(1,peak.get());
 }
 @Test public void rateLimitCooldownPreventsNextProviderOpening()throws Exception{
  AtomicInteger calls=new AtomicInteger();FetchBroker broker=new FetchBroker(c,full->new ProfileProvider(){public byte[] fetch(String u)throws Failure{calls.incrementAndGet();throw new Failure("RATE_LIMITED",429,System.currentTimeMillis()+60000,true);}public void cancel(){}});
  for(int i=0;i<2;i++)try{broker.submit("example",true).get(3,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){}
  assertEquals(1,calls.get());assertEquals("RATE_LIMITED",SessionState.prefs(c).getString("blocked",""));
 }
 @Test public void closedViewerZeroesAndReleasesRetainedHighResolutionBytes()throws Exception{
  Fetcher.initialize(c,new Fetcher.Host(){public Collection<String> users(){return Collections.emptyList();}public String tree(){return "";}public String directory(){return "";}public long revision(){return 0;}public boolean hasImage(String u){return false;}public void imageWritten(String u,android.net.Uri uri,String tree){}});
  byte[] data=new byte[]{1,2,3};java.lang.reflect.Field bytes=Fetcher.class.getDeclaredField("fullBytes"),token=Fetcher.class.getDeclaredField("viewerToken");bytes.setAccessible(true);token.setAccessible(true);bytes.set(null,data);token.set(null,"token");assertEquals(3,Fetcher.full("token").length);Fetcher.closeViewer();assertArrayEquals(new byte[3],data);assertEquals(0,Fetcher.full("token").length);assertNull(bytes.get(null));
 }
}
