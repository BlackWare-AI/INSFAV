package com.instagram.insfav.fetcher;

import android.content.*;
import java.util.*;

/** Only non-secret session generation and verification evidence are stored in preferences. */
final class SessionState {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("thumbnail-fetcher", Context.MODE_PRIVATE); }
    static void changed(Context c) {
        prefs(c).edit().putString("cookieGeneration", UUID.randomUUID().toString()).putString("sessionEvidence", "UNKNOWN").putBoolean("armed", false).putBoolean("userPaused", true).putString("blocked", "").commit();
        WorkScheduler.cancel(c);
    }
    static String evidence(SharedPreferences p) {
        String value = p.getString("sessionEvidence", "UNKNOWN");
        return value.equals("VERIFIED") || value.equals("UNVERIFIED") || value.equals("INVALID") ? value : "UNKNOWN";
    }
}
