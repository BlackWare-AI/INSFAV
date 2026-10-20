package com.instagram.insfav.fetcher;

import android.app.*;
import com.instagram.insfav.MainActivity;
import com.instagram.insfav.R;
import android.content.*;
import java.util.*;

final class DownloadNotification {
    static void channel(Context c) { c.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("downloads", "Thumbnail downloads", NotificationManager.IMPORTANCE_LOW)); }
    static Notification create(Context c, String phase) {
        channel(c); int success = 0, failed = 0, remaining = 0, existing = 0, skipped = 0;
        try (QueueStore queue = new QueueStore(c)) {
            Map<String,Integer> counts = queue.counts(); success = counts.getOrDefault("SUCCESS", 0); failed = counts.getOrDefault("FAILED", 0); remaining = counts.getOrDefault("PENDING", 0) + counts.getOrDefault("RETRY_LATER",0); existing = counts.getOrDefault("EXISTING", 0); skipped = counts.getOrDefault("SKIPPED_UNAVAILABLE", 0);
        }
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent pause = PendingIntent.getBroadcast(c, 1, new Intent(c, QueueControlReceiver.class).setAction("pause"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getBroadcast(c, 2, new Intent(c, QueueControlReceiver.class).setAction("stop"), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String counts = String.format(Locale.ROOT, "Downloaded %d · Existing %d · Missing %d · Failed %d · Skipped %d", success, existing, remaining, failed, skipped);
        return new Notification.Builder(c, "downloads").setSmallIcon(R.drawable.ic_fetch).setContentTitle("INSFAV Thumbnail Fetcher").setContentText(phase).setStyle(new Notification.BigTextStyle().bigText(phase + "\n" + counts)).setSubText(counts).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Pause", pause).build()).addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }
}
