package com.instagram.insfav;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private WebView web;
    private Store store;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final ExecutorService imageIo=Executors.newSingleThreadExecutor();
    private volatile int viewerGeneration;
    private String pendingExport;
    private static final int IMPORT=20,EXPORT=21;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);store=Store.get(this);
        try{synchronized(store){if(!RetrievalService.active&&"running".equals(store.queue.optString("state"))){store.queue.put("state","paused");store.queue.put("reason","APP_RESTARTED");store.saveQueue();}}}catch(Exception e){store.log("startup",e.getClass().getSimpleName());}
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(0);
        if(Build.VERSION.SDK_INT>=29)getWindow().setNavigationBarContrastEnforced(false);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);getWindow().getInsetsController().setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}
        FrameLayout root=new FrameLayout(this);root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());}return insets.consumeSystemWindowInsets();});
        web=new WebView(this);web.setBackgroundColor(Color.BLACK);web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setSupportZoom(false);s.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(false);CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);web.addJavascriptInterface(new Bridge(),"Android");
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();if(!"https".equals(uri.getScheme())||!"insfav.local".equals(uri.getHost()))return response("text/plain",new byte[0]);
                String p=uri.getPath();try{
                    if(p.startsWith("/thumb/")||p.startsWith("/full/")){String user=Store.normalize(uri.getLastPathSegment());if(!user.matches("[a-z0-9._]{1,30}"))return response("image/webp",new byte[0]);File file=p.startsWith("/thumb/")?store.thumbnail(user):store.fullImage(user);return new WebResourceResponse("image/webp",null,new FileInputStream(file));}
                    String name="/".equals(p)?"index.html":p.substring(1);if(!name.matches("[a-zA-Z0-9._-]+"))return response("text/plain",new byte[0]);String mime=name.endsWith(".woff2")?"font/woff2":name.endsWith(".ttf")?"font/ttf":name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":"text/html";return new WebResourceResponse(mime,"UTF-8",getAssets().open(name));
                }catch(Exception e){return response("text/plain",new byte[0]);}
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){return true;}
            @Override public boolean onRenderProcessGone(WebView v,RenderProcessGoneDetail detail){store.log("web_renderer","stopped");root.removeView(v);v.destroy();recreate();return true;}
        });
        root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);root.requestApplyInsets();web.loadUrl("https://insfav.local/index.html");
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{store.log("uncaught",error.getClass().getSimpleName());android.os.Process.killProcess(android.os.Process.myPid());});
    }
    private WebResourceResponse response(String mime,byte[] bytes){return new WebResourceResponse(mime,"UTF-8",new ByteArrayInputStream(bytes));}
    private void send(String event,Object data){runOnUiThread(()->{if(web==null||isDestroyed())return;String value=data instanceof JSONObject||data instanceof JSONArray?data.toString():JSONObject.quote(String.valueOf(data));web.evaluateJavascript("window.nativeEvent&&window.nativeEvent("+JSONObject.quote(event)+","+value+")",null);});}
    private void error(Exception e){store.log("operation_error",e.getClass().getSimpleName());send("error",e.getMessage()==null?"Operation failed":e.getMessage());}
    public final class Bridge {
        @JavascriptInterface public void request(String raw){io.execute(()->{try{JSONObject p=new JSONObject(raw);String command=p.getString("command");
            switch(command){
                case "bootstrap":{JSONObject b=store.snapshot();b.put("raw",store.original());b.put("stats",new JSONObject().put("usage",incrementUsage()).put("load",getPreferences(0).getInt("load",0)).put("clear",getPreferences(0).getInt("clear",0)));send("bootstrap",b);break;}
                case "status":send("status",store.snapshot(false));break;
                case "import":runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/json","text/plain","application/octet-stream"});startActivityForResult(i,IMPORT);});break;
                case "export":if(store.original().isEmpty())throw new Exception("No collections to export");export(store.original(),"INSFAV-backup.json","application/json");break;
                case "frontend-error":store.log("web_error",p.optString("code","script"));break;
                case "logs":export(store.logs(),"INSFAV-diagnostics.txt","text/plain");break;
                case "clear-data":ensureIdle();store.clearDocument();getPreferences(0).edit().putInt("clear",getPreferences(0).getInt("clear",0)+1).apply();send("cleared","");break;
                case "clear-thumbs":ensureIdle();store.clearImages(false);send("status",store.snapshot());send("message","Thumbnails cleared. Collections are unchanged.");break;
                case "clear-temp":viewerGeneration++;synchronized(ProfileProvider.NETWORK_LOCK){store.clearImages(true);}send("message","Temporary images cleared.");break;
                case "clear-failures":ensureIdle();store.clearFailures();send("status",store.snapshot());break;
                case "start":startQueue(p.optString("mode","missing"));break;
                case "pause":case "cancel":synchronized(store){store.queue.put("state",command.equals("pause")?"paused":"cancelled");store.queue.put("reason","user");store.saveQueue();}if(RetrievalService.active)startService(new Intent(MainActivity.this,RetrievalService.class).setAction(command));send("status",store.snapshot());break;
                case "resume":synchronized(store){if(!"paused".equals(store.queue.optString("state")))throw new Exception("No paused retrieval to resume");if(store.queue.optLong("retryAfter")>System.currentTimeMillis())throw new Exception("Please wait for the server cooldown before resuming.");store.queue.put("state","running");store.saveQueue();}launchService();break;
                case "open":openInstagram(p.getString("user"));break;
                case "full":fetchFull(p.getString("user"));break;
                case "close-viewer":viewerGeneration++;break;
                default:throw new Exception("Unsupported operation");
            }
        }catch(Exception e){error(e);}});}
    }
    private int incrementUsage(){int n=getPreferences(0).getInt("usage",0)+1;getPreferences(0).edit().putInt("usage",n).apply();return n;}
    private void ensureIdle() throws Exception {synchronized(store){if(RetrievalService.active||"running".equals(store.queue.optString("state")))throw new Exception("Pause retrieval before changing stored data.");}}
    private void startQueue(String mode) throws Exception {
        ensureIdle();if(!mode.equals("missing")&&!mode.equals("refresh")&&!mode.equals("failed"))throw new Exception("Invalid retrieval mode");
        synchronized(store){JSONArray users=new JSONArray();for(String u:store.users()){JSONObject p=store.profiles.optJSONObject(u);if(mode.equals("failed")?(p!=null&&p.has("error")&&!store.thumbnail(u).isFile()):mode.equals("refresh")||!store.thumbnail(u).isFile())users.put(u);}
            store.queue=new JSONObject().put("users",users).put("mode",mode).put("state",users.length()==0?"completed":"running").put("cursor",0).put("success",0).put("failed",0).put("skipped",0).put("elapsedMs",0).put("started",System.currentTimeMillis());store.saveQueue();store.log("queue_start",mode);if(users.length()>0)launchService();}
        send("status",store.snapshot());
    }
    private void launchService(){runOnUiThread(()->{try{if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},7);startForegroundService(new Intent(this,RetrievalService.class).setAction("run"));}catch(Exception e){try{synchronized(store){store.queue.put("state","paused");store.queue.put("reason","ANDROID_START_RESTRICTED");store.saveQueue();}}catch(Exception ignored){}error(e);}});}
    private void openInstagram(String name){String u=Store.normalize(name);if(!u.matches("[a-z0-9._]{1,30}")){send("error","Invalid Instagram username");return;}runOnUiThread(()->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("instagram://user?username="+Uri.encode(u))));}catch(ActivityNotFoundException e){try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://www.instagram.com/"+u+"/")));}catch(ActivityNotFoundException noBrowser){send("error","No app can open this profile.");}}});}
    private void fetchFull(String name){String u=Store.normalize(name);final int generation=++viewerGeneration;
        imageIo.execute(()->{try{if(store.fullImage(u).isFile()){if(generation==viewerGeneration)send("full-ready",u);return;}if(RetrievalService.active){send("full-unavailable",u);return;}ProfileProvider.retrieve(store,u,true,()->generation!=viewerGeneration||isDestroyed());if(generation==viewerGeneration)send("full-ready",u);}catch(InterruptedException ignored){}catch(Exception e){store.log("full_image",e instanceof ProfileProvider.Failure?((ProfileProvider.Failure)e).code:e.getClass().getSimpleName());if(generation==viewerGeneration)send("full-unavailable",u);}});
    }
    private void export(String content,String name,String mime){pendingExport=content;runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name);startActivityForResult(i,EXPORT);});}
    @Override protected void onActivityResult(int request,int result,Intent intent){super.onActivityResult(request,result,intent);if(result!=RESULT_OK||intent==null||intent.getData()==null){pendingExport=null;return;}Uri uri=intent.getData();io.execute(()->{try{
        if(request==IMPORT){ensureIdle();byte[] bytes;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>32*1024*1024)throw new IOException("JSON exceeds 32 MiB");out.write(buffer,0,n);}bytes=out.toByteArray();}store.importDocument(new String(bytes,StandardCharsets.UTF_8));getPreferences(0).edit().putInt("load",getPreferences(0).getInt("load",0)+1).apply();JSONObject s=store.snapshot();s.put("raw",store.original());send("imported",s);}
        else if(request==EXPORT&&pendingExport!=null){try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){out.write(pendingExport.getBytes(StandardCharsets.UTF_8));}pendingExport=null;store.log("export","success");send("message","Export complete.");}
    }catch(Exception e){error(e);}});}
    @Override public void onBackPressed(){web.evaluateJavascript("window.handleBack&&window.handleBack()",value->{if(!"true".equals(value))super.onBackPressed();});}
    @Override protected void onDestroy(){viewerGeneration++;io.shutdownNow();imageIo.shutdownNow();if(web!=null){web.removeJavascriptInterface("Android");web.destroy();web=null;}super.onDestroy();}
}
