package com.instagram.insfav;

import android.app.*;
import android.content.*;
import android.os.*;
import org.json.*;

/** User-initiated foreground work only. Progress survives death; resumption is always manual. */
public final class RetrievalService extends Service {
    static volatile boolean active;
    static volatile long runStarted,elapsedBase;
    private Store store;
    private Thread worker;
    private static final String CHANNEL="profile_retrieval";
    @Override public void onCreate(){super.onCreate();store=Store.get(this);getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Profile retrieval",NotificationManager.IMPORTANCE_LOW));}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        String action=intent==null?"pause":intent.getAction();
        try{
            synchronized(store){
                if("pause".equals(action)||"cancel".equals(action)){
                    store.queue.put("state","cancel".equals(action)?"cancelled":"paused");store.queue.put("reason","user");store.saveQueue();
                    if(worker!=null)worker.interrupt();if(!active)stopSelf();return START_NOT_STICKY;
                }
                if(!"running".equals(store.queue.optString("state"))){stopSelf();return START_NOT_STICKY;}
                startForeground(41,notification());
                if(worker==null||!worker.isAlive()){active=true;worker=new Thread(this::runQueue,"INSFAV-retrieval");worker.start();}
            }
        }catch(Exception e){store.log("service",e.getClass().getSimpleName());stopSelf();}
        return START_NOT_STICKY;
    }
    private boolean stopped(){synchronized(store){return !"running".equals(store.queue.optString("state"));}}
    private void runQueue(){long session=SystemClock.elapsedRealtime(),previous=0;int consecutive=0;
        synchronized(store){runStarted=session;elapsedBase=store.queue.optLong("elapsedMs");}
        try{
            while(!stopped()){
                String user;boolean refresh;int cursor;
                synchronized(store){JSONArray items=store.queue.optJSONArray("users");cursor=store.queue.optInt("cursor");if(items==null||cursor>=items.length()){store.queue.put("state","completed");store.queue.put("current","");store.saveQueue();break;}user=items.getString(cursor);refresh="refresh".equals(store.queue.optString("mode"));store.queue.put("current",user);store.saveQueue();}
                getSystemService(NotificationManager.class).notify(41,notification());
                String outcome="success",error=null;boolean unavailable=false;
                if(!refresh&&store.thumbnail(user).isFile())outcome="skipped";
                else{
                    try{ProfileProvider.retrieve(store,user,false,this::stopped);consecutive=0;}
                    catch(InterruptedException e){if(stopped())break;else continue;}
                    catch(ProfileProvider.Failure e){outcome="failed";error=e.code;unavailable=ProfileProvider.UNAVAILABLE.equals(e.code);
                        synchronized(store){if(ProfileProvider.blocking(e.code)){store.queue.put("state","paused");store.queue.put("reason",e.code);store.queue.put("retryAfter",System.currentTimeMillis()+Math.max(6000,e.retryAfter));}else if(++consecutive>=3){store.queue.put("state","paused");store.queue.put("reason","CONSECUTIVE_FAILURES");}}
                    }catch(Exception e){outcome="failed";error=ProfileProvider.UNKNOWN;store.log("profile_exception",e.getClass().getSimpleName());if(++consecutive>=3)synchronized(store){store.queue.put("state","paused");store.queue.put("reason","CONSECUTIVE_FAILURES");}}
                }
                synchronized(store){if(error!=null)store.putProfile(user,error,unavailable);store.queue.put(outcome,store.queue.optInt(outcome)+1);store.queue.put("cursor",cursor+1);store.queue.put("lastUser",user);store.queue.put("lastProfile",store.profiles.optJSONObject(user));long now=SystemClock.elapsedRealtime()-session;store.queue.put("elapsedMs",store.queue.optLong("elapsedMs")+(now-previous));previous=now;store.saveQueue();store.log("queue_item",error==null?outcome:error);}
                getSystemService(NotificationManager.class).notify(41,notification());
            }
        }catch(Exception e){store.log("queue_error",e.getClass().getSimpleName());try{synchronized(store){store.queue.put("state","paused");store.queue.put("reason","STORAGE_OR_SERVICE_ERROR");store.saveQueue();}}catch(Exception ignored){}}
        finally{
            synchronized(store){active=false;runStarted=0;try{long now=SystemClock.elapsedRealtime()-session;store.queue.put("elapsedMs",store.queue.optLong("elapsedMs")+Math.max(0,now-previous));store.queue.put("current","");store.saveQueue();}catch(Exception ignored){}}
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
    private Notification notification(){synchronized(store){int done=store.queue.optInt("cursor"),total=store.queue.optJSONArray("users")==null?0:store.queue.optJSONArray("users").length();
        Intent open=new Intent(this,MainActivity.class);PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent pause=PendingIntent.getService(this,1,new Intent(this,RetrievalService.class).setAction("pause"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel=PendingIntent.getService(this,2,new Intent(this,RetrievalService.class).setAction("cancel"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(com.instagram.insfav.R.drawable.ic_app).setContentTitle("INSFAV · Profile Images").setContentText(done+" / "+total+" · "+store.queue.optString("state")).setProgress(Math.max(1,total),done,false).setOngoing(true).setContentIntent(content).addAction(new Notification.Action.Builder(null,"Pause",pause).build()).addAction(new Notification.Action.Builder(null,"Cancel",cancel).build()).build();}}
    @Override public void onTimeout(int startId,int type){try{synchronized(store){store.queue.put("state","paused");store.queue.put("reason","ANDROID_TIME_LIMIT");store.saveQueue();}}catch(Exception ignored){}if(worker!=null)worker.interrupt();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();}
    @Override public void onDestroy(){try{synchronized(store){if("running".equals(store.queue.optString("state"))){store.queue.put("state","paused");store.queue.put("reason","SERVICE_STOPPED");store.saveQueue();}}}catch(Exception ignored){}if(worker!=null)worker.interrupt();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
