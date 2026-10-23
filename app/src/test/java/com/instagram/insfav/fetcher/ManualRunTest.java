package com.instagram.insfav.fetcher;
import android.content.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class ManualRunTest {
 Context c;SharedPreferences p;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();p=SessionState.prefs(c);p.edit().clear().putBoolean("background",true).putString("manualRunToken",ManualRun.TOKEN).putBoolean("armed",true).putBoolean("userPaused",false).putLong("retryAt",123456L).commit();}
 @Test public void previousProcessTokenCannotStartEvenBeforeApplicationReset(){p.edit().putString("manualRunToken","previous-process").commit();assertFalse(WorkScheduler.enabled(p));p.edit().remove("manualRunToken").commit();assertFalse(WorkScheduler.enabled(p));p.edit().putString("manualRunToken",ManualRun.TOKEN).commit();assertTrue(WorkScheduler.enabled(p));}
 @Test public void coldProcessRevokesPersistedRunWithoutChangingUserConfiguration(){assertTrue(WorkScheduler.enabled(p));ManualRun.reset(p);assertFalse(WorkScheduler.enabled(p));assertTrue(p.getBoolean("background",false));assertEquals(123456L,p.getLong("retryAt",0));assertTrue(p.getString("state","").contains("Start Missing or Resume"));}
 @Test public void noEligibleProfilesDisarmsInsteadOfSchedulingNotifications(){ManualRun.finish(p);assertFalse(WorkScheduler.enabled(p));assertTrue(p.getBoolean("userPaused",false));}
 @Test public void oldAndNullServiceRequestsAreRejected(){assertFalse(ManualRun.accepts(null));assertFalse(ManualRun.accepts("previous-process"));assertTrue(ManualRun.accepts(ManualRun.TOKEN));FetchService service=Robolectric.buildService(FetchService.class).create().get();assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(null,0,1));assertEquals(android.app.Service.START_NOT_STICKY,service.onStartCommand(new Intent().setAction("resume").putExtra("manualRun","stale"),0,2));assertFalse(QueueControl.lock.get());assertFalse(WorkScheduler.enabled(p));}
 @Test public void notificationLanguageChangesOnlyPresentation()throws Exception{com.instagram.insfav.UiPreferences.save(c,new org.json.JSONObject().put("columns",0).put("language","tr"));assertEquals("İndirilen 5 · Kalan 9",NotificationText.text(c,"Downloaded 5 · Remaining 9"));assertEquals("Duraklat",NotificationText.text(c,"Pause"));assertEquals("WEBVIEW_TIMEOUT",NotificationText.text(c,"WEBVIEW_TIMEOUT"));com.instagram.insfav.UiPreferences.save(c,new org.json.JSONObject().put("columns",0).put("language","en"));assertEquals("Downloaded 5",NotificationText.text(c,"Downloaded 5"));}
 @Test public void resetKeepsSavedQueueOutcomes()throws Exception{try(QueueStore q=new QueueStore(c)){q.sync(java.util.Arrays.asList("first","second","third"));q.mark("first","SUCCESS","VALID_WEBP");q.mark("second","SKIPPED_UNAVAILABLE","PROFILE_PAGE_UNAVAILABLE");q.mark("third","RETRY_LATER","WEBVIEW_TIMEOUT");java.util.Map<String,Integer> before=q.counts();ManualRun.reset(p);assertEquals(before,q.counts());assertEquals(1,q.counts().get("SUCCESS").intValue());}}
}
