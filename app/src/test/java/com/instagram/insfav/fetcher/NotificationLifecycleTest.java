package com.instagram.insfav.fetcher;
import android.content.*;import android.app.Notification;import org.junit.*;import org.junit.runner.RunWith;import org.robolectric.*;import org.robolectric.annotation.Config;import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class NotificationLifecycleTest {
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();c.deleteDatabase("fetcher-queue.db");SessionState.prefs(c).edit().clear().commit();}
 @Test public void completedNotificationExpiresButFatalErrorRemains(){Notification done=DownloadNotification.create(c,"Completed");assertEquals(6000,done.getTimeoutAfter());SessionState.prefs(c).edit().putString("blocked","PROCESSING_ERROR").commit();Notification failed=DownloadNotification.create(c,"Paused: processing error");assertEquals(0,failed.getTimeoutAfter());assertTrue(failed.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("Failed"));}
 @Test public void realRateLimitCarriesCooldownAndDoesNotLookLikeUserPause(){SessionState.prefs(c).edit().putString("blocked","RATE_LIMITED").putInt("blockedHttp",429).putLong("retryAt",System.currentTimeMillis()+60000).putBoolean("background",true).putBoolean("armed",true).putBoolean("userPaused",false).commit();Notification rate=DownloadNotification.create(c,"Rate Limited");assertTrue(rate.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("HTTP 429"));assertTrue(rate.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Automatic resume"));assertEquals(0,rate.getTimeoutAfter());assertTrue(WorkScheduler.enabled(SessionState.prefs(c)));}
}
