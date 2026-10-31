package app.revanced.extension.soundcloud.shared;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import app.revanced.extension.shared.Logger;

/**
 * Keeps the app alive while long work runs that the user started and may leave the app during, such as a transfer
 * from Yandex Music that takes twenty minutes: a foreground service with a quiet notification that says what runs.
 * Without it a phone that kills apps in the background (TECNO, vivo) ended the work half way, with no word of it.
 * <p>
 * One piece of work at a time; {@link #begin} with another title takes the notification over. Added to the manifest
 * by the local music patch, with the type "dataSync".
 */
public final class ForegroundWork extends Service {
    private static final String CHANNEL = "arsound_work";
    private static final int NOTIFICATION_ID = 0x41525357;
    /** The notification is changed at most this often: Android drops updates that come faster than five a second. */
    private static final long UPDATE_MS = 1_000;

    private static volatile String title = "";
    private static volatile String line = "";
    private static volatile boolean running;
    private static long lastUpdateAt;

    /** Starts keeping the app alive, with a title and a line about the progress in the notification. */
    public static void begin(Context context, String workTitle, String progress) {
        title = workTitle;
        line = progress;
        running = true;
        try {
            Intent intent = new Intent(context, ForegroundWork.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception ex) {
            // Started from the background, where Android 12 and later refuse it: the work runs on without the notification.
            Logger.printException(() -> "Foreground work: could not start", ex);
        }
    }

    /** A new line about the progress; shown at most once a second. */
    public static synchronized void update(Context context, String progress) {
        line = progress;
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastUpdateAt < UPDATE_MS) return;
        lastUpdateAt = now;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(context));
    }

    /** The work is over: the notification goes and the app may be ended in the background again. */
    public static void end(Context context) {
        running = false;
        try {
            context.stopService(new Intent(context, ForegroundWork.class));
        } catch (Exception ex) {
            Logger.printException(() -> "Foreground work: could not stop", ex);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            createChannel(this);
            Notification notification = notification(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            // The work ended before the service came up.
            if (!running) stopSelf();
        } catch (Exception ex) {
            Logger.printException(() -> "Foreground work: could not go to the foreground", ex);
            stopSelf();
        }
        // The work lives in this process: a service brought back after the process died would have nothing to do.
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static void createChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL) != null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, Lang.text("Фоновая работа Arsound", "Arsound background work"),
                NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static Notification notification(Context context) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, CHANNEL) : new Notification.Builder(context);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(line)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch != null) {
            builder.setContentIntent(android.app.PendingIntent.getActivity(context, 0, launch,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT));
        }
        return builder.build();
    }
}
