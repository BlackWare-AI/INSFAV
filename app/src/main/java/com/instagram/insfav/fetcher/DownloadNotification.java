package com.instagram.insfav.fetcher;

import android.app.*;
import com.instagram.insfav.MainActivity;
import com.instagram.insfav.R;
import android.content.*;
import java.util.*;

/** Queue-backed progress. Browser workers never manufacture download successes. */
final class DownloadNotification {
    private static long lastAt;
    private static String lastPhase="";
    static void channel(Context c) { c.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("downloads", "Thumbnail downloads", NotificationManager.IMPORTANCE_LOW)); }
    static final class Progress {
        final int downloaded,existing,failed,skipped,pending,total,done,percent;
        final String status;
        Progress(Map<String,Integer> counts,String phase,String blocked) {
            downloaded=counts.getOrDefault("SUCCESS",0);existing=counts.getOrDefault("EXISTING",0);failed=counts.getOrDefault("FAILED",0);
            skipped=counts.getOrDefault("SKIPPED_UNAVAILABLE",0)+counts.getOrDefault("SKIPPED_NO_IMAGE",0);
            pending=counts.getOrDefault("PENDING",0)+counts.getOrDefault("RETRY_LATER",0);
            total=downloaded+existing+failed+skipped+pending;done=total-pending;percent=total==0?0:(int)(100L*done/total);
            status=phase.equals("Completed")&&pending==0?"Completed":
                blocked.matches(".*(COOKIE|LOGIN|CHALLENGE|AUTH).*" )?"Authentication Required":
                phase.startsWith("Paused")?"Paused":phase.startsWith("Interrupted")||phase.startsWith("Stopped")?"Interrupted":
                phase.startsWith("Waiting")||phase.equals("Preparing")||phase.equals("Checking existing thumbnails")||QueueControl.activeAccounts==0?"Waiting":"Running";
        }
        String summary(){return String.format(Locale.ROOT,"Downloaded %d · Existing %d · Failed %d · Skipped %d · Pending %d",downloaded,existing,failed,skipped,pending);}
    }
    static Notification create(Context c,String phase) {
        channel(c);Map<String,Integer> counts;
        try(QueueStore queue=new QueueStore(c)){counts=queue.counts();}
        Progress progress=new Progress(counts,phase,SessionState.prefs(c).getString("blocked",""));
        boolean terminal=progress.status.equals("Completed")||progress.status.equals("Interrupted")||progress.status.equals("Paused")||progress.status.equals("Authentication Required");
        PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        String compact=progress.status+" · "+progress.done+" / "+progress.total+" · "+progress.percent+"%";
        String detail=compact+"\n"+progress.summary()+"\nActive WebViews "+QueueControl.activeViews+" / "+QueueControl.effectiveViews+" · Active accounts "+QueueControl.activeAccounts+
            String.format(Locale.ROOT,"\n%.1f accounts/min",QueueControl.completedPerMinute)+(QueueControl.etaSeconds>=0?" · ETA "+QueueControl.etaSeconds+" s":" · ETA unavailable");
        Notification.Builder builder=new Notification.Builder(c,"downloads").setSmallIcon(R.drawable.ic_fetch).setContentTitle("INSFAV Thumbnail Fetcher").setContentText(compact)
            .setStyle(new Notification.BigTextStyle().bigText(detail)).setSubText(progress.summary()).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(!terminal).setAutoCancel(terminal)
            .setProgress(Math.max(1,progress.total),progress.done,false);
        if(!terminal)for(String action:new String[]{"pause","stop"}){
            PendingIntent control=PendingIntent.getBroadcast(c,action.equals("pause")?1:2,new Intent(c,QueueControlReceiver.class).setAction(action),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(new Notification.Action.Builder(null,action.equals("pause")?"Pause":"Stop",control).build());
        }
        return builder.build();
    }
    static synchronized void publish(Context c,int id,String phase) {
        long now=android.os.SystemClock.elapsedRealtime();
        if(now-lastAt<1000&&phase.equals(lastPhase))return;
        // Changing between busy/waiting on each account must not flood NotificationManager.
        boolean terminal=phase.equals("Completed")||phase.startsWith("Paused")||phase.startsWith("Interrupted")||phase.startsWith("Stopped");
        if(!terminal&&now-lastAt<1000)return;
        lastAt=now;lastPhase=phase;
        try{c.getSystemService(NotificationManager.class).notify(id,create(c,phase));}catch(RuntimeException ignored){}
    }
    static void finish(Context c) {
        try{c.getSystemService(NotificationManager.class).notify(3,Notification.Builder.recoverBuilder(c,create(c,SessionState.prefs(c).getString("state","Interrupted; queue preserved"))).setOngoing(false).setAutoCancel(true).build());}catch(RuntimeException ignored){}
    }
}
