package com.instagram.insfav;
import android.content.Context;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=28,manifest=Config.NONE)
public class UiPreferencesTest {
 Context c;
 @Before public void setup(){c=RuntimeEnvironment.getApplication();c.getSharedPreferences("insfav-ui",0).edit().clear().commit();}
 @Test public void defaultsPreserveEnglishList()throws Exception{assertEquals(0,UiPreferences.read(c).getInt("columns"));assertEquals("en",UiPreferences.language(c));}
 @Test public void allModesAndLanguagesPersistAcrossReads()throws Exception{for(int n:new int[]{0,2,3,4,5})for(String l:new String[]{"en","tr"}){UiPreferences.save(c,new JSONObject().put("columns",n).put("language",l));assertEquals(n,UiPreferences.read(c).getInt("columns"));assertEquals(l,UiPreferences.language(c));}}
 @Test public void invalidPreferenceDoesNotPartiallyWrite()throws Exception{for(int n:new int[]{-1,1,6}){try{UiPreferences.save(c,new JSONObject().put("columns",n).put("language","tr"));fail();}catch(IllegalArgumentException expected){}assertEquals(0,UiPreferences.read(c).getInt("columns"));assertEquals("en",UiPreferences.language(c));}try{UiPreferences.save(c,new JSONObject().put("columns",4).put("language","xx"));fail();}catch(IllegalArgumentException expected){}assertEquals(0,UiPreferences.read(c).getInt("columns"));}
 @Test public void folderColumnsPersistWithoutChangingAccountLayout()throws Exception{
  assertEquals(0,UiPreferences.read(c).getInt("folderColumns"));
  UiPreferences.save(c,new JSONObject().put("columns",3).put("folderColumns",2).put("language","tr"));
  assertEquals(2,UiPreferences.read(c).getInt("folderColumns"));
  assertEquals(3,UiPreferences.read(c).getInt("columns"));
  assertEquals("tr",UiPreferences.language(c));
  for(int n:new int[]{0,2,3,4,5}){
   UiPreferences.save(c,new JSONObject().put("columns",3).put("folderColumns",n).put("language","tr"));
   assertEquals(n,UiPreferences.read(c).getInt("folderColumns"));
   assertEquals(3,UiPreferences.read(c).getInt("columns"));
  }
  try{UiPreferences.save(c,new JSONObject().put("columns",3).put("folderColumns",6).put("language","tr"));fail();}catch(IllegalArgumentException expected){}
  assertEquals(5,UiPreferences.read(c).getInt("folderColumns"));
 }
}
