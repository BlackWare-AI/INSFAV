package com.instagram.insfav.fetcher;

import android.content.Context;
import android.content.pm.ServiceInfo;
import android.os.Build;
import androidx.work.*;

/** Bounded two-minute pooled recovery job; never a sleep loop or an exact alarm. */
public final class DownloadWorker extends Worker {
    private volatile QueueEngine engine;
    public DownloadWorker(Context c, WorkerParameters p) { super(c, p); }
    @Override public Result doWork() {
        Context c = getApplicationContext();
        if (!WorkScheduler.enabled(SessionState.prefs(c))) return Result.success();
        if (!QueueControl.lock.compareAndSet(false, true)) return Result.success();
        QueueControl.requested = false;
        try {
            c.getSystemService(android.app.NotificationManager.class).cancel(3);
            setForegroundAsync(getForegroundInfo()).get();
            engine = new QueueEngine(c, state -> { if (!isStopped()) DownloadNotification.publish(c,2,state); }, this::isStopped);
            engine.run("scheduled", true);
        } catch (Exception error) {
            SessionState.prefs(c).edit().putString("blocked", "SYSTEM_RESTRICTED").putString("state", "Paused: Android background restriction; open the app to resume").commit();
            new Diagnostics(c).log("SERVICE", "SYSTEM_RESTRICTED", 0);
        } finally {
            QueueControl.current = ""; QueueControl.lock.set(false); DownloadNotification.finish(c);
            WorkScheduler.plan(c, true);
        }
        return isStopped() ? Result.retry() : Result.success();
    }
    @Override public ForegroundInfo getForegroundInfo() {
        return Build.VERSION.SDK_INT >= 29 ? new ForegroundInfo(2, DownloadNotification.create(getApplicationContext(), QueueControl.phase), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) : new ForegroundInfo(2, DownloadNotification.create(getApplicationContext(), QueueControl.phase));
    }
    @Override public void onStopped() { QueueEngine current = engine; if (current != null) current.cancel(); }
}
