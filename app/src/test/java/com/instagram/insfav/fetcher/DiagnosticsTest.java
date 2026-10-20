package com.instagram.insfav.fetcher;

import android.content.Context;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class DiagnosticsTest {
    @Test public void exportContainsCodesButNoArbitraryMessages() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); Diagnostics log = new Diagnostics(context);
        log.log("PROFILE", "RATE_LIMITED", 429); log.log("PROFILE", "https://example.test/session?token=SECRET", 0);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); log.export(output); String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("RATE_LIMITED 429")); assertFalse(text.contains("SECRET")); assertFalse(text.contains("https://example.test"));
        assertEquals("IO_ERROR", Diagnostics.storageCode(new IOException("PRIVATE_ACCOUNT")));
    }
}
