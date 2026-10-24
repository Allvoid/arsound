package app.revanced.extension.soundcloud.screenshot;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicLong;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * SoundCloud's "Share screenshot" sheet, which opens on Android 14+ after every screenshot.
 * <p>
 * Each screen registers its own screenshot callback, and the full screen player keeps the screen under it started,
 * so one screenshot of the player reached two callbacks (each on a thread of its own) and the sheet opened twice.
 * The sheet is off unless turned on in the settings; when it is on, a screenshot opens it once.
 */
@SuppressWarnings("unused")
public final class ScreenshotShare {
    /** Both callbacks of one screenshot arrive within this time; two screenshots by hand take longer. */
    private static final long SAME_SCREENSHOT_MS = 1_500;

    private static final AtomicLong lastShare = new AtomicLong(-SAME_SCREENSHOT_MS);

    /** The callbacks registered with an activity, so only those are unregistered when their screen stops. */
    private static final java.util.Set<Object> registered =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private ScreenshotShare() {
    }

    /**
     * Injection point. A screen is about to watch for screenshots. With the sheet off it does not, and Android does
     * not tell the user that the app detected a screenshot. Turning the sheet on takes effect as screens restart.
     *
     * @return True to register the callback.
     */
    public static boolean register(Object callback) {
        if (!Settings.isScreenshotShareEnabled()) return false;
        synchronized (registered) {
            registered.add(callback);
        }
        return true;
    }

    /**
     * Injection point. A screen stops watching for screenshots.
     *
     * @return True if the callback was registered and must be unregistered.
     */
    public static boolean unregister(Object callback) {
        synchronized (registered) {
            return registered.remove(callback);
        }
    }

    /**
     * Injection point. The start of SoundCloud's screenshot handling, on a callback thread.
     *
     * @return False to skip the sheet for this callback.
     */
    public static boolean shouldShare() {
        if (!Settings.isScreenshotShareEnabled()) return false;
        long now = SystemClock.elapsedRealtime();
        long last = lastShare.get();
        if (now - last < SAME_SCREENSHOT_MS || !lastShare.compareAndSet(last, now)) {
            Logger.printDebug(() -> "Screenshot share: the second callback of one screenshot is skipped");
            return false;
        }
        return true;
    }
}
