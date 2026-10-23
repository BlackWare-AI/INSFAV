package com.instagram.insfav.fetcher;

import android.content.SharedPreferences;
import java.util.UUID;

/** A process restart cannot reuse an old foreground intent or persisted run permission. */
final class ManualRun {
    static final String TOKEN=UUID.randomUUID().toString();
    static void reset(SharedPreferences p){
        boolean previous=p.getBoolean("armed",false)&&!p.getBoolean("userPaused",true);
        SharedPreferences.Editor e=p.edit().putBoolean("armed",false).putBoolean("userPaused",true).remove("manualRunToken");
        if(previous)e.putString("state","Paused: select Start Missing or Resume");
        e.commit();
    }
    static boolean authorized(SharedPreferences p){return TOKEN.equals(p.getString("manualRunToken",""));}
    static boolean accepts(String token){return TOKEN.equals(token);}
    static void finish(SharedPreferences p){p.edit().putBoolean("armed",false).putBoolean("userPaused",true).remove("manualRunToken").commit();}
    private ManualRun(){}
}
