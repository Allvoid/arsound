package app.revanced.extension.soundcloud.update;

import android.app.Activity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import app.revanced.extension.shared.Logger;

/**
 * The features that react to a screen coming to the front or leaving it, in the order they were added. The order
 * matters: a feature may look at what an earlier one did on the same screen (the music access prompt waits for the
 * greeting). One failing feature must not crash the screen or stop the ones after it.
 */
final class ScreenHooks {
    private static final class Hook {
        final String name;
        final Consumer<Activity> onResumed;
        final Consumer<Activity> onPaused;

        Hook(String name, Consumer<Activity> onResumed, Consumer<Activity> onPaused) {
            this.name = name;
            this.onResumed = onResumed;
            this.onPaused = onPaused;
        }
    }

    private final List<Hook> hooks = new ArrayList<>();

    /** A feature that only cares about its screen coming to the front. */
    ScreenHooks add(String name, Consumer<Activity> onResumed) {
        return add(name, onResumed, null);
    }

    /** A feature that also wants to know when its screen leaves the front. */
    ScreenHooks add(String name, Consumer<Activity> onResumed, Consumer<Activity> onPaused) {
        hooks.add(new Hook(name, onResumed, onPaused));
        return this;
    }

    void resumed(Activity activity) {
        for (Hook hook : hooks) {
            try {
                hook.onResumed.accept(activity);
            } catch (Exception ex) {
                Logger.printException(() -> "Resume hook failed: " + hook.name, ex);
            }
        }
    }

    void paused(Activity activity) {
        for (Hook hook : hooks) {
            if (hook.onPaused != null) hook.onPaused.accept(activity);
        }
    }
}
