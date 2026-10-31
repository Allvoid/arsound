package app.revanced.extension.soundcloud.player;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;

/**
 * Paces the updates of the media notification. SoundCloud rebuilds it on every change of the track and of the playback
 * state; skipping tracks quickly asked for about 16 updates a second, and Android drops what comes over 5 a second, so
 * the notification could stay on a track that was no longer playing. An update that comes sooner than {@link #GAP_MS}
 * after the last one waits, and only the newest of the waiting ones is made, at the end of the gap: the last state is
 * always shown.
 * <p>
 * Starting the notification (which also makes the service a foreground one) is never held back; stopping it drops the
 * update that waits, so a stopped notification does not come back.
 */
@SuppressWarnings("unused")
public final class NotificationPace {
    /** Four updates a second at most, under Android's limit of five. */
    private static final long GAP_MS = 250;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private static long lastAt;
    private static Runnable waiting;
    /** Set while the waiting update runs, so it goes through. */
    private static boolean passing;
    private static Method update;

    private NotificationPace() {
    }

    /**
     * Injection point, at the start of {@code MediaNotificationManager.d()}, which rebuilds the notification.
     *
     * @return True when the update waits: the method returns without doing it.
     */
    public static synchronized boolean defer(Object manager) {
        if (passing) return false;
        long now = SystemClock.uptimeMillis();
        long wait = lastAt + GAP_MS - now;
        if (wait <= 0) {
            lastAt = now;
            return false;
        }
        if (waiting == null) {
            waiting = () -> runWaiting(manager);
            handler.postDelayed(waiting, wait);
            Logger.printDebug(() -> "Notification: an update waits " + wait + " ms");
        }
        return true;
    }

    private static void runWaiting(Object manager) {
        synchronized (NotificationPace.class) {
            waiting = null;
            lastAt = SystemClock.uptimeMillis();
            passing = true;
        }
        try {
            Method method = update;
            if (method == null) {
                method = manager.getClass().getMethod("d");
                update = method;
            }
            method.invoke(manager);
        } catch (Throwable ex) {
            Logger.printException(() -> "Notification: the waiting update failed", ex);
        } finally {
            synchronized (NotificationPace.class) {
                passing = false;
            }
        }
    }

    /** Injection point, at the start of {@code MediaNotificationManager.b()}: the notification is posted afresh. */
    public static synchronized void onStart() {
        dropWaiting();
        lastAt = SystemClock.uptimeMillis();
    }

    /** Injection point, at the start of {@code MediaNotificationManager.c()}: the notification goes. */
    public static synchronized void onStop() {
        dropWaiting();
    }

    private static void dropWaiting() {
        if (waiting == null) return;
        handler.removeCallbacks(waiting);
        waiting = null;
    }
}
