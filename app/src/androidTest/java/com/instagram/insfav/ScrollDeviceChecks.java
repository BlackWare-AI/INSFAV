package com.instagram.insfav;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.webkit.WebView;
import org.json.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Emulator-only synthetic collections, actual WebView and actual Android touch input. */
public final class ScrollDeviceChecks {
    public static void run(Instrumentation test) throws Exception {
        Context c=test.getTargetContext();Store store=Store.get(c);String before=store.original();
        org.json.JSONObject originalUi=UiPreferences.read(c);UiPreferences.save(c,new JSONObject().put("columns",0).put("language","en"));
        String originalScale=Settings.System.getString(c.getContentResolver(),Settings.System.FONT_SCALE);
        Activity activity=null;JSONArray results=new JSONArray();
        try {
            JSONArray groups=new JSONArray();
            for(String group:new String[]{"a","b"}) {
                JSONArray items=new JSONArray();for(int i=0;i<2000;i++)items.put(new JSONObject().put("pageId","scroll_"+group+i).put("name","Synthetic long display name "+i));
                groups.put(new JSONObject().put("name","Long collection title for narrow screens "+group+" repeated repeated repeated").put("items",items));
            }
            store.importDocument(groups.toString());
            for(String scale:new String[]{"1.0","1.3","1.5"}) {
                shell(test,"settings put system font_scale "+scale);
                long until=SystemClock.elapsedRealtime()+10000;
                while(Math.abs(c.getResources().getConfiguration().fontScale-Float.parseFloat(scale))>.01f&&SystemClock.elapsedRealtime()<until)Thread.sleep(100);
                require(Math.abs(c.getResources().getConfiguration().fontScale-Float.parseFloat(scale))<.01f,"System font scale was not applied");
                activity=test.startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                Field field=MainActivity.class.getDeclaredField("web");field.setAccessible(true);WebView web=(WebView)field.get(activity);
                await(test,web,"!!window.nativeEvent && document.getElementById('splash').hidden");
                js(test,web,"opened.add(0);opened.add(1);rebuild(true);true");
                await(test,web,"!document.getElementById('main-scroll').hidden");
                JSONObject layout=json(test,web,"(()=>{const list=document.getElementById('list').getBoundingClientRect(),controls=document.getElementById('main-scroll').getBoundingClientRect(),folder=document.querySelector('.folder-wrap'),head=folder.querySelector('button'),count=head.querySelector('.folder-count').getBoundingClientRect(),arrow=head.querySelector('.folder-arrow').getBoundingClientRect(),avatar=document.querySelector('.avatar');return {width:innerWidth,fontSize:getComputedStyle(head.querySelector('.folder-title')).fontSize,rowHeight:document.querySelector('.account').getBoundingClientRect().height,avatarSize:avatar.getBoundingClientRect().width,avatarRadius:getComputedStyle(avatar).borderRadius,folderRadius:getComputedStyle(folder).borderTopLeftRadius,leftGap:controls.left-list.right,rightGap:document.documentElement.getBoundingClientRect().right-controls.right,noTextOverlap:count.right<=arrow.left,horizontalOverflow:document.documentElement.scrollWidth>innerWidth};})()");
                require(layout.getDouble("rightGap")>=3.8,"Controls cross screen edge");
                require(layout.getBoolean("noTextOverlap")&&!layout.getBoolean("horizontalOverflow"),"Large font title overflows");
                require(layout.getDouble("rowHeight")==68&&layout.getDouble("avatarSize")==67.5&&layout.getString("avatarRadius").equals("14px")&&layout.getString("folderRadius").equals("26px"),"List geometry changed");
                tap(test,web,point(test,web,"scroll-end"));await(test,web,"Math.abs(scrollY-(document.scrollingElement.scrollHeight-document.documentElement.clientHeight))<=1");
                await(test,web,"document.querySelector('.card-user:last-child')!==null && [...document.querySelectorAll('.card-user')].at(-1).textContent==='@scroll_b1999'");
                tap(test,web,point(test,web,"scroll-start"));await(test,web,"Math.abs(scrollY)<=1");
                js(test,web,"window.thumbEarlyArmed=null;document.addEventListener('pointerdown',()=>setTimeout(()=>window.thumbEarlyArmed=document.getElementById('scroll-thumb').classList.contains('armed'),50),{once:true,capture:true});true");
                float[] p=point(test,web,"scroll-grip");long down=SystemClock.uptimeMillis();touch(test,down,MotionEvent.ACTION_DOWN,p[0],p[1]);
                try {
                    await(test,web,"document.getElementById('scroll-thumb').classList.contains('armed')");
                    require("true".equals(js(test,web,"Math.abs(scrollY)<=1")),"Arming jumped the page");require("false".equals(js(test,web,"window.thumbEarlyArmed")),"Thumb armed before 100ms");
                    if(scale.equals("1.5"))try(FileOutputStream out=c.openFileOutput("scroll-device-150.png",Context.MODE_PRIVATE)) {Bitmap screenshot=test.getUiAutomation().takeScreenshot();if(screenshot==null)throw new AssertionError("Screenshot unavailable");try{screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}}
                    float ratio=web.getWidth()/(float)layout.getDouble("width");touch(test,down,MotionEvent.ACTION_MOVE,p[0],p[1]+100*ratio);await(test,web,"scrollY>0");
                } finally {touch(test,down,MotionEvent.ACTION_UP,p[0],p[1]);}
                require("false".equals(js(test,web,"document.getElementById('scroll-thumb').classList.contains('armed')")),"Thumb did not relock");
                java.util.concurrent.atomic.AtomicInteger textZoom=new java.util.concurrent.atomic.AtomicInteger();test.runOnMainSync(()->textZoom.set(web.getSettings().getTextZoom()));
                JSONArray matrix=new JSONArray();
                for(int columns:new int[]{2,3,4,5}){
                    js(test,web,"applyUiPreferences({columns:"+columns+",language:'tr'});true");
                    await(test,web,"document.querySelectorAll('.matrix-row').length>0 && document.documentElement.lang==='tr'");
                    JSONObject grid=json(test,web,"(()=>{const r=document.querySelector('.matrix-row'),cells=[...r.querySelectorAll('.matrix-cell')];return {columns:getComputedStyle(r).gridTemplateColumns.trim().split(/\\s+/).length,items:cells.length,cellSizes:cells.map(c=>{const a=c.querySelector('.avatar').getBoundingClientRect();const u=c.querySelector('.matrix-user'),b=u.getBoundingClientRect();return {width:a.width,height:a.height,labelTop:b.top,labelBottom:b.bottom,rowBottom:r.getBoundingClientRect().bottom,labelLineHeight:getComputedStyle(u).lineHeight};}),listWidth:document.getElementById('list').getBoundingClientRect().width,viewportWidth:innerWidth,scrollWidth:document.documentElement.scrollWidth,squares:cells.every(c=>{const a=c.querySelector('.avatar').getBoundingClientRect();return Math.abs(a.width-a.height)<.2;}),labelsBelow:cells.every(c=>c.querySelector('.matrix-user').getBoundingClientRect().top>=c.querySelector('.avatar').getBoundingClientRect().bottom),labelsFit:cells.every(c=>c.querySelector('.matrix-user').getBoundingClientRect().bottom<=r.getBoundingClientRect().bottom),noNames:!r.querySelector('.card-name'),noOverflow:document.documentElement.scrollWidth<=innerWidth};})()");
                    require(grid.getInt("columns")==columns&&grid.getInt("items")>0&&grid.getInt("items")<=columns&&grid.getBoolean("squares")&&grid.getBoolean("labelsBelow")&&grid.getBoolean("labelsFit")&&grid.getBoolean("noNames")&&grid.getBoolean("noOverflow"),"Matrix cell geometry failed at columns="+columns+" scale="+scale+": "+grid);matrix.put(grid);
                }
                awaitBar(test,web,false);
                js(test,web,"openSettings();true");awaitBar(test,web,true);
                tap(test,web,point(test,web,"display-mode"));await(test,web,"!document.getElementById('preference-picker').hidden");
                tap(test,web,point(test,web,"preference-option-5"));await(test,web,"document.getElementById('preference-picker').hidden && uiPreferences.columns===5");
                awaitPrefs(c,5,"tr");
                tap(test,web,point(test,web,"language-choice"));await(test,web,"!document.getElementById('preference-picker').hidden");
                tap(test,web,point(test,web,"preference-option-en"));await(test,web,"document.getElementById('preference-picker').hidden && document.documentElement.lang==='en'");awaitPrefs(c,5,"en");
                tap(test,web,point(test,web,"language-choice"));await(test,web,"!document.getElementById('preference-picker').hidden");
                if(scale.equals("1.5"))try(FileOutputStream out=c.openFileOutput("preference-device-150.png",Context.MODE_PRIVATE)){Bitmap screenshot=test.getUiAutomation().takeScreenshot();try{screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}}
                tap(test,web,point(test,web,"preference-option-tr"));await(test,web,"document.getElementById('preference-picker').hidden && document.documentElement.lang==='tr'");
                awaitPrefs(c,5,"tr");require("true".equals(js(test,web,"document.getElementById('fetcher-title').textContent==='Profil Görseli İndirici'")),"Fetcher title not translated");
                js(test,web,"closeSettings();true");await(test,web,"document.getElementById('settings').hidden");awaitBar(test,web,false);
                if(scale.equals("1.5"))try(FileOutputStream out=c.openFileOutput("matrix-device-150.png",Context.MODE_PRIVATE)){Bitmap screenshot=test.getUiAutomation().takeScreenshot();try{screenshot.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screenshot.recycle();}}
                UiPreferences.save(c,new JSONObject().put("columns",0).put("language","en"));
                js(test,web,"applyUiPreferences({columns:0,language:'en'});true");
                JSONObject result=new JSONObject().put("systemFontScale",Float.parseFloat(scale)).put("layout",layout).put("matrix",matrix).put("amoledPickersAndPersistence",true).put("nativeScrollbarHiddenOnlyOnMain",true).put("holdMilliseconds",100).put("gripWidth",15).put("realTouchHoldAndDrag",true).put("globalStartEnd",true).put("textZoom",textZoom.get());results.put(result);
                Activity current=activity;test.runOnMainSync(current::finish);activity=null;
            }
            JSONObject proof=new JSONObject().put("sdk",Build.VERSION.SDK_INT).put("fixture",true).put("accounts",4000).put("physicalDevice",false).put("results",results);
            try(FileOutputStream out=c.openFileOutput("scroll-ui.json",Context.MODE_PRIVATE)){out.write(proof.toString(2).getBytes(StandardCharsets.UTF_8));}
            Bundle status=new Bundle();status.putString("stream","SCROLL_UI_OK "+proof+"\n");test.sendStatus(0,status);
        } finally {
            if(activity!=null){Activity current=activity;test.runOnMainSync(current::finish);}
            shell(test,originalScale==null?"settings delete system font_scale":"settings put system font_scale "+originalScale);
            UiPreferences.save(c,originalUi);
            if(before.isEmpty())store.clearDocument();else store.importDocument(before);
        }
    }
    private static void awaitPrefs(Context c,int columns,String language)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){if(UiPreferences.read(c).getInt("columns")==columns&&UiPreferences.language(c).equals(language))return;Thread.sleep(30);}throw new AssertionError("UI preference was not persisted");}
    private static void awaitBar(Instrumentation test,WebView web,boolean expected)throws Exception{long until=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<until){java.util.concurrent.atomic.AtomicBoolean enabled=new java.util.concurrent.atomic.AtomicBoolean();test.runOnMainSync(()->enabled.set(web.isVerticalScrollBarEnabled()));if(enabled.get()==expected)return;Thread.sleep(30);}throw new AssertionError("Native scrollbar visibility mismatch");}
    private static void shell(Instrumentation test,String command)throws IOException {try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(test.getUiAutomation().executeShellCommand(command))){in.readAllBytes();}}
    private static String js(Instrumentation test,WebView web,String script)throws Exception {AtomicReference<String> value=new AtomicReference<>();CountDownLatch done=new CountDownLatch(1);test.runOnMainSync(()->web.evaluateJavascript(script,result->{value.set(result);done.countDown();}));if(!done.await(10,TimeUnit.SECONDS))throw new AssertionError("WebView evaluation timed out");return value.get();}
    private static JSONObject json(Instrumentation test,WebView web,String expression)throws Exception {return new JSONObject((String)new JSONTokener(js(test,web,"JSON.stringify("+expression+")")).nextValue());}
    private static void await(Instrumentation test,WebView web,String condition)throws Exception {long until=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<until){if("true".equals(js(test,web,condition)))return;Thread.sleep(50);}throw new AssertionError("Timed out: "+condition+"; layout="+js(test,web,"JSON.stringify({hidden:document.hidden,width:innerWidth,clientWidth:document.documentElement.clientWidth,root:document.documentElement.getBoundingClientRect().toJSON(),list:document.getElementById('list').getBoundingClientRect().toJSON(),scrollY,scrollHeight:document.scrollingElement.scrollHeight,controlsHidden:document.getElementById('main-scroll').hidden,rows:rows.length,surfaces:[...document.querySelectorAll('.screen,.modal-overlay,#splash,#viewer')].map(e=>[e.id,e.hidden])})"));}
    private static float[] point(Instrumentation test,WebView web,String id)throws Exception {
        // A visible DOM node can still be moving/fading or covered in the compositor.
        // Wait for the actual hit target and its ancestors' animations before computing input coordinates.
        String target=JSONObject.quote(id);
        await(test,web,"(()=>{const e=document.getElementById("+target+");if(!e)return false;for(let a=e;a;a=a.parentElement){if(a.inert||a.getAnimations().some(x=>x.playState==='running'))return false;}const r=e.getBoundingClientRect(),h=document.elementFromPoint(r.x+r.width/2,r.y+r.height/2);return r.width>0&&r.height>0&&(h===e||e.contains(h)||(e.id==='scroll-grip'&&h===e.parentElement));})()");
        CountDownLatch drawn=new CountDownLatch(1);
        test.runOnMainSync(()->web.postVisualStateCallback(SystemClock.uptimeMillis(),new WebView.VisualStateCallback(){@Override public void onComplete(long requestId){drawn.countDown();}}));
        if(!drawn.await(10,TimeUnit.SECONDS))throw new AssertionError("Input target was not drawn");
        JSONObject p=json(test,web,"(()=>{const r=document.getElementById("+target+").getBoundingClientRect();return {x:r.x+r.width/2,y:r.y+r.height/2,width:innerWidth};})()");
        int[] origin=new int[2];test.runOnMainSync(()->web.getLocationOnScreen(origin));float ratio=web.getWidth()/(float)p.getDouble("width");
        return new float[]{origin[0]+(float)p.getDouble("x")*ratio,origin[1]+(float)p.getDouble("y")*ratio};
    }
    private static void touch(Instrumentation test,long down,int action,float x,float y){MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{test.sendPointerSync(event);}finally{event.recycle();}}
    private static void tap(Instrumentation test,WebView web,float[] point){long down=SystemClock.uptimeMillis();touch(test,down,MotionEvent.ACTION_DOWN,point[0],point[1]);SystemClock.sleep(60);touch(test,down,MotionEvent.ACTION_UP,point[0],point[1]);}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private ScrollDeviceChecks(){}
}
