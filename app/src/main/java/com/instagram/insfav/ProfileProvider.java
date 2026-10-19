package com.instagram.insfav;

import android.graphics.*;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** All Instagram network access is serialized, cookie-free, and rate-limited across callers. */
final class ProfileProvider {
    static final Object NETWORK_LOCK=new Object();
    static final String NETWORK="NETWORK",TIMEOUT="TIMEOUT",RATE_LIMIT="HTTP_429",RESTRICTED="ACCESS_RESTRICTED",LOGIN="LOGIN_REQUIRED",MISSING="PROFILE_DATA_MISSING",INVALID="INVALID_IMAGE",UNAVAILABLE="CONFIRMED_UNAVAILABLE",UNKNOWN="UNKNOWN";
    static final class Failure extends Exception {final String code;final long retryAfter;Failure(String c){this(c,blocking(c)?15*60*1000:0);}Failure(String c,long retry){super(c);code=c;retryAfter=retry;}}
    interface Stop {boolean stopped();}
    static int randomDelay(){return 6000+new java.security.SecureRandom().nextInt(19001);}
    static boolean blocking(String c){return RATE_LIMIT.equals(c)||RESTRICTED.equals(c)||LOGIN.equals(c);}
    static void retrieve(Store store,String user,boolean full,Stop stop) throws Exception {
        synchronized(NETWORK_LOCK){
            if(!user.matches("[a-z0-9._]{1,30}"))throw new Failure(MISSING);
            long wait=store.readObject("network.json").optLong("nextAllowed")-System.currentTimeMillis();
            while(wait>0){if(stop.stopped())throw new InterruptedException();Thread.sleep(Math.min(wait,300));wait=store.readObject("network.json").optLong("nextAllowed")-System.currentTimeMillis();}
            if(stop.stopped())throw new InterruptedException();
            long cooldown=randomDelay();
            try{
                Response page=request("https://www.instagram.com/"+user+"/",true);
                String html=new String(page.bytes,StandardCharsets.UTF_8),lower=html.toLowerCase(Locale.ROOT);
                if(page.url.contains("/accounts/login")||lower.contains("<title>login")||lower.contains("<title>log in"))throw new Failure(LOGIN);
                if(page.url.contains("/challenge/")||page.url.contains("/checkpoint/")||lower.contains("<title>challenge"))throw new Failure(RESTRICTED);
                // A generic 404 is not proof of account deletion; retain a conservative missing-data result.
                if(page.status==404)throw new Failure(MISSING);
                if(page.status!=200)throw new Failure(MISSING);
                String url=ProfileParser.image(html,user);if(url==null)throw new Failure(MISSING);
                if(stop.stopped())throw new InterruptedException();
                Response picture=request(url,false);if(picture.status!=200)throw new Failure(INVALID);
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(picture.bytes,0,picture.bytes.length,bounds);
                int w=bounds.outWidth,h=bounds.outHeight;if(w<64||h<64||w>8192||h>8192||(long)w*h>32000000)throw new Failure(INVALID);
                BitmapFactory.Options opt=new BitmapFactory.Options();int target=full?2048:128;opt.inSampleSize=1;while(Math.min(w,h)/(opt.inSampleSize*2)>=target)opt.inSampleSize*=2;
                Bitmap bmp=BitmapFactory.decodeByteArray(picture.bytes,0,picture.bytes.length,opt);if(bmp==null)throw new Failure(INVALID);
                Bitmap result=null;
                try{
                    if(full){if(w<=128&&h<=128)throw new Failure(MISSING);float scale=Math.min(1f,2048f/Math.max(bmp.getWidth(),bmp.getHeight()));result=Bitmap.createScaledBitmap(bmp,Math.max(1,Math.round(bmp.getWidth()*scale)),Math.max(1,Math.round(bmp.getHeight()*scale)),true);}
                    else {int side=Math.min(bmp.getWidth(),bmp.getHeight());Bitmap square=Bitmap.createBitmap(bmp,(bmp.getWidth()-side)/2,(bmp.getHeight()-side)/2,side,side);result=Bitmap.createScaledBitmap(square,128,128,true);if(square!=bmp&&square!=result)square.recycle();}
                    if(stop.stopped())throw new InterruptedException();AtomicFile file=new AtomicFile(full?store.fullImage(user):store.thumbnail(user));FileOutputStream out=null;
                    try{out=file.startWrite();if(!result.compress(Bitmap.CompressFormat.WEBP,full?88:78,out))throw new IOException("Encoding");file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}
                    if(!full)store.putProfile(user,null,false);else store.trimTemporary();
                }finally{if(result!=null&&result!=bmp)result.recycle();bmp.recycle();}
            }catch(Failure e){cooldown=Math.max(cooldown,e.retryAfter);throw e;}catch(SocketTimeoutException e){throw new Failure(TIMEOUT);}catch(IOException e){throw new Failure(NETWORK);}
            finally{store.write("network.json",new org.json.JSONObject().put("nextAllowed",System.currentTimeMillis()+cooldown).toString());}
        }
    }
    static final class Response {byte[] bytes;int status;String url;}
    static Response request(String input,boolean html) throws Exception {
        String url=input;
        for(int redirects=0;redirects<4;redirects++){
            URI uri=URI.create(url);String host=uri.getHost();if(html?!("https".equals(uri.getScheme())&&("www.instagram.com".equals(host)||"instagram.com".equals(host))):!ProfileParser.imageHost(url))throw new Failure(RESTRICTED);
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            try{c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/130.0.0.0 Mobile Safari/537.36");c.setRequestProperty("Accept",html?"text/html":"image/*");
                int status=c.getResponseCode();if(status==429)throw new Failure(RATE_LIMIT,retryAfter(c.getHeaderField("Retry-After")));if(status==401)throw new Failure(LOGIN);if(status==403)throw new Failure(RESTRICTED);
                if(status>=300&&status<400){String loc=c.getHeaderField("Location");if(loc==null)throw new Failure(RESTRICTED);url=new URL(new URL(url),loc).toString();continue;}
                Response r=new Response();r.status=status;r.url=url;if(status!=200&&status!=404){r.bytes=new byte[0];return r;}
                int max=html?3*1024*1024:8*1024*1024;if(c.getContentLengthLong()>max)throw new Failure(html?MISSING:INVALID);
                try(InputStream in=status>=400?c.getErrorStream():c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in!=null){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>max)throw new Failure(html?MISSING:INVALID);out.write(b,0,n);}}r.bytes=out.toByteArray();}return r;
            }finally{c.disconnect();}
        }throw new Failure(RESTRICTED);
    }
    static long retryAfter(String s){if(s==null)return 15*60*1000;try{return Math.max(6000,Long.parseLong(s)*1000);}catch(Exception e){try{return Math.max(6000,java.time.ZonedDateTime.parse(s,java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis());}catch(Exception ignored){return 15*60*1000;}}}
}
