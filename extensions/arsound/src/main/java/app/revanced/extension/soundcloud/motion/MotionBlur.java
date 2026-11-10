package app.revanced.extension.soundcloud.motion;

import android.app.Activity;
import android.os.Build;
import android.view.Window;

import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;

/**
 * Entry of the motion blur: a list or a pager that moves fast is blurred along its movement, more the faster it goes.
 * One state per screen, made only for a screen that is on while the blur is switched on.
 */
public final class MotionBlur {
    private static final Map<Activity, MotionBlurRun> RUNS = new WeakHashMap<>();

    private MotionBlur() {
    }

    /** Whether this Android can blur a view with a render effect (12 and later); older ones never blur. */
    public static boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** Screen hook: a screen came to the front. The touch listener goes in only when the blur is on. */
    public static void onActivityResumed(Activity activity) {
        if (!isSupported() || !MotionBlurSettings.enabled()) return;
        install(activity);
    }

    /** Screen hook: a screen left the front; a blur still on a view is taken off. */
    public static void onActivityPaused(Activity activity) {
        MotionBlurRun run = RUNS.get(activity);
        if (run != null) run.stop();
    }

    /** The settings screen changed the switch: apply it to the screen that is open. */
    public static void refresh(Activity activity) {
        MotionBlurRun run = RUNS.get(activity);
        if (!isSupported()) return;
        if (MotionBlurSettings.enabled()) install(activity);
        else if (run != null) run.stop();
    }

    private static void install(Activity activity) {
        try {
            MotionBlurRun run = RUNS.get(activity);
            if (run == null) {
                run = new MotionBlurRun(activity);
                RUNS.put(activity, run);
            }
            if (run.installed()) return;
            Window window = activity.getWindow();
            Window.Callback callback = window == null ? null : window.getCallback();
            if (callback == null) return;
            window.setCallback(new MotionWindowCallback(callback, run::onTouch));
            run.markInstalled();
        } catch (Throwable ex) {
            Logger.printException(() -> "Motion blur could not be installed", ex);
        }
    }
}
