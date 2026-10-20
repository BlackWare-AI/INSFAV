package com.instagram.insfav.fetcher;

import android.content.*;
import java.util.*;

/** Only non-secret session generation and verification evidence are stored in preferences. */
final class SessionState {
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("thumbnail-fetcher", Context.MODE_PRIVATE); }
    static void changed(Context c) {
        prefs(c).edit().putString("cookieGeneration", UUID.randomUUID().toString()).putString("verifiedGeneration", "").putInt("verifiedProfiles", 0).putString("sessionEvidence", "UNKNOWN").putBoolean("armed", false).putBoolean("userPaused", true).putString("blocked", "").commit();
        WorkScheduler.cancel(c);
    }
    static String evidence(SharedPreferences p) {
        String value = p.getString("sessionEvidence", "UNKNOWN");
        return value.equals("VERIFIED") || value.equals("UNVERIFIED") || value.equals("INVALID") ? value : "UNKNOWN";
    }
    static void evidence(Context c, String generation, String value) {
        SharedPreferences p = prefs(c);
        if (generation.equals(p.getString("cookieGeneration", ""))) p.edit().putString("sessionEvidence", value).commit();
    }
    static boolean verified(SharedPreferences p) { return !p.getString("cookieGeneration", "").isEmpty() && p.getString("cookieGeneration", "").equals(p.getString("verifiedGeneration", "")) && p.getInt("verifiedProfiles", 0) >= 3; }
    static void verified(Context c, String generation, int count) {
        SharedPreferences p = prefs(c);
        if (generation.equals(p.getString("cookieGeneration", ""))) p.edit().putString("verifiedGeneration", generation).putInt("verifiedProfiles", count).putString("blocked", "").commit();
    }
}
