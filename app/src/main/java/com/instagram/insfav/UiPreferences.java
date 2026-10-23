package com.instagram.insfav;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

/** Display preferences are independent of Favorites, SAF and the thumbnail index. */
public final class UiPreferences {
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("insfav-ui",Context.MODE_PRIVATE);}
    public static JSONObject read(Context c)throws org.json.JSONException{return new JSONObject().put("columns",prefs(c).getInt("columns",0)).put("language",language(c));}
    public static String language(Context c){return prefs(c).getString("language","en");}
    public static void save(Context c,JSONObject value)throws Exception{
        int columns=value.getInt("columns");String language=value.getString("language");
        if((columns!=0&&columns!=2&&columns!=3&&columns!=4)||(!language.equals("en")&&!language.equals("tr")))throw new IllegalArgumentException("Invalid display preference");
        if(!prefs(c).edit().putInt("columns",columns).putString("language",language).commit())throw new java.io.IOException("Could not save display preferences");
    }
}
