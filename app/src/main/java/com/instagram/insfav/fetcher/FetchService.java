package com.instagram.insfav.fetcher;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.time.ZoneId;

public final class FetchService extends Service {
    static volatile boolean running;
    private volatile QueueEngine engine;
    private volatile Thread worker;
    private volatile boolean owns;
    QueueEngine.Sessions sessions;
    @Override public void onCreate() { super.onCreate(); DownloadNotification.channel(this); }
    @Override public int onStartCommand(Intent intent, int flags, int id) {
        if (intent == null) { stopSelf(id); return START_NOT_STICKY; }
        String action = intent.getAction();
        if ("pause".equals(action) || "stop".equals(action)) { QueueControl.pause(this, "stop".equals(action)); if (!owns) stopSelf(id); return START_NOT_STICKY; }
        if (!("missing".equals(action) || "failed".equals(action) || "resume".equals(action) || "test".equals(action) || "test_one".equals(action))) { stopSelf(id); return START_NOT_STICKY; }
        if (owns || !QueueControl.lock.compareAndSet(false, true)) { if (!owns) stopSelf(id); return START_NOT_STICKY; }
        owns = true; running = true; QueueControl.requested = false;
        try {
            Notification notification = DownloadNotification.create(this, "Preparing");
            if (Build.VERSION.SDK_INT >= 29) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(1, notification);
        } catch (RuntimeException error) { running = false; owns = false; QueueControl.lock.set(false); SessionState.prefs(this).edit().putString("blocked", "SYSTEM_RESTRICTED").putString("state", "Paused: foreground service unavailable").commit(); stopSelf(); return START_NOT_STICKY; }
        worker = new Thread(() -> {
            try {
                QueueEngine.Reporter reporter = state -> { try { getSystemService(NotificationManager.class).notify(1, DownloadNotification.create(this, state)); } catch (RuntimeException ignored) { } };
                engine = sessions == null ? new QueueEngine(this, reporter, () -> false) : new QueueEngine(this, reporter, () -> false, sessions);
                engine.run(action, false);
            } finally {
                running = false; QueueControl.lock.set(false); owns = false;
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
                WorkScheduler.recovery(this); WorkScheduler.plan(this, false);
            }
        }, "thumbnail-foreground"); worker.start(); return START_NOT_STICKY;
    }
    static void request(Context context, String action) { context.startForegroundService(new Intent(context, FetchService.class).setAction(action)); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onTimeout(int id, int type) {
        long next = RunPolicy.read(SessionState.prefs(this)).nextWindow(System.currentTimeMillis(), ZoneId.systemDefault());
        SessionState.prefs(this).edit().putLong("systemNotBefore", next).putString("state", "Android service time limit; next working window scheduled").commit();
        new Diagnostics(this).log("SERVICE", "TIMEOUT", 0);
        QueueEngine current = engine; if (current != null) current.cancel("Android service time limit; next working window scheduled"); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
    }
    @Override public void onDestroy() {
        QueueEngine current = engine; if (owns && current != null) current.cancel();
        Thread thread = worker; if (owns && thread != null) thread.interrupt(); super.onDestroy();
    }
}
