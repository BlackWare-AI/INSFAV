package com.instagram.insfav.fetcher;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk={28,34},manifest=Config.NONE)
public class QueueOrderTest {
    Context context;
    @Before public void setup(){context=RuntimeEnvironment.getApplication();context.deleteDatabase("fetcher-queue.db");}

    @Test public void missingAndInterruptedWorkBothStartAtTheBottom(){
        try(QueueStore q=new QueueStore(context)){
            q.sync(Arrays.asList("oldest","middle","newest","NEWEST"));
            assertEquals(Arrays.asList("newest","middle","oldest"),q.eligible());
            assertEquals(q.eligible(),q.pending());assertEquals("newest",q.next());
            assertTrue(q.claim("newest"));q.deferClaims("REQUEST_CANCELLED");
        }
        try(QueueStore q=new QueueStore(context)){
            q.recoverInterrupted();q.prepareMissing();
            assertEquals(Arrays.asList("newest","middle","oldest"),q.eligible());
            assertEquals("newest",q.next());assertEquals(0,q.attempts("newest"));
            // Display/catalog order is unchanged; only work selection is reversed.
            assertEquals(Arrays.asList("oldest","middle","newest"),q.users(false));
        }
    }

    @Test public void jsonAdditionsAreFirstWithoutReopeningSavedOutcomes(){
        try(QueueStore q=new QueueStore(context)){
            q.sync(Arrays.asList("pending","done","existing","gone","failed","held"));
            q.mark("done","SUCCESS","VALID_WEBP");q.mark("existing","EXISTING","LOCAL_IMAGE");
            q.mark("gone","SKIPPED_UNAVAILABLE","PROFILE_PAGE_UNAVAILABLE");
            q.attempted("failed");q.mark("failed","FAILED","PROFILE_PROCESSING_ERROR");
            q.attempted("held");q.mark("held","RETRY_LATER","WEBVIEW_TIMEOUT");
        }
        try(QueueStore q=new QueueStore(context)){
            q.sync(Arrays.asList("pending","done","existing","gone","failed","held","added","ADDED"));
            q.prepareMissing();q.recoverInterrupted();
            assertEquals(Arrays.asList("added","pending"),q.eligible());assertEquals("added",q.next());
            assertEquals(1,q.held());assertEquals(3,q.recordedOutcomes());
            assertEquals(Integer.valueOf(1),q.counts().get("SUCCESS"));
            assertEquals(Integer.valueOf(1),q.counts().get("EXISTING"));
            assertEquals(Integer.valueOf(1),q.counts().get("SKIPPED_UNAVAILABLE"));
            q.retrySkipped();
            assertEquals(Arrays.asList("added","held","failed","gone","pending"),q.eligible());
            assertEquals(0,q.recordedOutcomes());
        }
    }

    @Test public void jsonReorderingUpdatesWorkOrderWithoutChangingOutcomes(){
        try(QueueStore q=new QueueStore(context)){
            q.sync(Arrays.asList("first","second","gone"));
            q.mark("gone","SKIPPED_UNAVAILABLE","PROFILE_PAGE_UNAVAILABLE");
            q.sync(Arrays.asList("second","gone","first"));
            assertEquals(Arrays.asList("first","second"),q.eligible());assertEquals("first",q.next());
            q.sync(Arrays.asList("second","first"));q.sync(Arrays.asList("second","first","gone","new"));
            assertEquals(Arrays.asList("new","first","second"),q.eligible());
            assertEquals(1,q.recordedOutcomes());
        }
    }
}
