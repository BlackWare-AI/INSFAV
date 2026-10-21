package com.instagram.insfav.fetcher;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class NotificationProgressTest {
 @Test public void existingNeverCountsAsDownloaded(){Map<String,Integer> counts=new HashMap<>();counts.put("SUCCESS",2);counts.put("EXISTING",7);counts.put("FAILED",1);counts.put("PENDING",3);counts.put("RETRY_LATER",1);counts.put("SKIPPED_UNAVAILABLE",2);DownloadNotification.Progress p=new DownloadNotification.Progress(counts,"Downloading","");assertEquals(2,p.downloaded);assertEquals(7,p.existing);assertEquals(16,p.total);assertEquals(12,p.done);assertEquals(75,p.percent);assertEquals(4,p.pending);}
 @Test public void pendingQueueCannotClaimCompletion(){Map<String,Integer> counts=new HashMap<>();counts.put("PENDING",1);assertNotEquals("Completed",new DownloadNotification.Progress(counts,"Completed","").status);}
 @Test public void authenticationAndInterruptionsRemainDistinct(){assertEquals("Authentication Required",new DownloadNotification.Progress(Collections.emptyMap(),"Paused: LOGIN_REQUIRED","LOGIN_REQUIRED").status);assertEquals("Interrupted",new DownloadNotification.Progress(Collections.emptyMap(),"Interrupted; queue preserved","").status);assertEquals("Completed",new DownloadNotification.Progress(Collections.emptyMap(),"Completed","").status);}
}
