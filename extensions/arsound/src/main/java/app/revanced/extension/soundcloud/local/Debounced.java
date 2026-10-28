package app.revanced.extension.soundcloud.local;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs an action once, a short time after the last request: many imported files in a row cause one redraw
 * of the likes, not one each. No Android here, so it can be tested on the JVM.
 */
final class Debounced {
    /** One daemon thread for every instance. */
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "arsound-debounce");
        thread.setDaemon(true);
        return thread;
    });

    private final long delayMs;
    private final Runnable action;
    private ScheduledFuture<?> scheduled;

    Debounced(long delayMs, Runnable action) {
        this.delayMs = delayMs;
        this.action = action;
    }

    /** Postpones the action until {@code delayMs} after this call. */
    synchronized void request() {
        if (scheduled != null) scheduled.cancel(false);
        scheduled = TIMER.schedule(action, delayMs, TimeUnit.MILLISECONDS);
    }
}
