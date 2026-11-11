package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.player.PlayerSheet;
import app.revanced.extension.soundcloud.theme.ArsoundTheme.Theme;
import app.revanced.extension.soundcloud.wallpaper.WallpaperSurfaces;

/**
 * Applies the chosen theme to the app as it runs: at the start of the application and before each screen is created,
 * and brings screens that missed a change of the palette up to date. Registered once, by {@link ArsoundTheme#onApplicationCreate}.
 */
final class ThemeLifecycle implements Application.ActivityLifecycleCallbacks {
    private final Application application;
    private final ThemeLoader loader;
    /** The one started for the process, for the work that prepares a theme before it is needed. */
    private static volatile ThemeLifecycle started;

    /** The theme each live activity was given at its start, to tell one that missed a change of the palette. */
    private final Map<Activity, String> appliedKeys = Collections.synchronizedMap(new WeakHashMap<>());
    /**
     * The palette each live activity was last recreated for, so a change is followed once per screen: with one mark
     * for all of them, the second screen in the back stack kept the old colours when it came back.
     */
    private final Map<Activity, String> recreatedFor = Collections.synchronizedMap(new WeakHashMap<>());

    private ThemeLifecycle(Application application) {
        this.application = application;
        this.loader = new ThemeLoader(application);
    }

    /** Applies the theme to the application's own resources and to every screen from now on. */
    static void start(Application application) {
        ThemeLifecycle lifecycle = new ThemeLifecycle(application);
        try {
            NightMode.apply(application, ArsoundTheme.currentTheme(application));
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not set the dark mode", ex);
        }
        try {
            lifecycle.apply(application.getResources());
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not apply on start", ex);
        }
        application.registerActivityLifecycleCallbacks(lifecycle);
        started = lifecycle;
    }

    /**
     * Builds the table of the chosen theme off the main thread, if it is not kept yet, so the start that follows (a
     * restart after a theme was chosen) only reads it. Nothing before Android 11, which has no themes. Not on the main
     * thread.
     */
    static void prepareChosen(Context context) {
        ThemeLifecycle lifecycle = started;
        if (lifecycle == null) return;
        try {
            Theme theme = ArsoundTheme.currentTheme(context);
            if (theme != null) lifecycle.loader.prepare(theme);
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not prepare the chosen theme", ex);
        }
    }

    /** Adds the loader of the chosen theme to these resources; nothing for the original look. */
    private void apply(Resources resources) throws Exception {
        Theme theme = ArsoundTheme.currentTheme(application, resources);
        if (theme == null) return;
        loader.apply(resources, theme);
    }

    @Override
    public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
        try {
            apply(activity.getResources());
            appliedKeys.put(activity, loader.key());
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not apply to " + activity, ex);
        }
        WallpaperSurfaces.onActivityPreCreated(activity);
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityResumed(Activity activity) {
        refreshIfStale(activity);
        ThemeBackdrop.onActivityResumed(activity);
        WallpaperSurfaces.onActivityResumed(activity);
        CardSurfaces.onActivityResumed(activity);
        try {
            SplashStyle.sync(activity, ArsoundTheme.currentTheme(activity));
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not keep the start screen", ex);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        if (!activity.getClass().getName().endsWith(".MainActivity")) return;
        try {
            // A slide of the player sheet that this screen was in the middle of will not be finished.
            PlayerSheet.reset();
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: player sheet not reset", ex);
        }
    }

    /**
     * A screen that came back after the wallpaper changed Monet's colours without being recreated by the system:
     * the table is brought up to date (for every screen) and this one is recreated once to draw with it.
     */
    private void refreshIfStale(Activity activity) {
        try {
            Theme theme = ArsoundTheme.currentTheme(application, activity.getResources());
            if (theme == null || !theme.isRuntime()) return;
            String applied = appliedKeys.get(activity);
            if (applied == null || applied.equals(theme.key()) || theme.key().equals(recreatedFor.get(activity))) return;
            recreatedFor.put(activity, theme.key());
            // The table of the new colours is built off the main thread; the screen keeps the old ones until then.
            Utils.runOnBackgroundThread(() -> {
                try {
                    loader.prepare(theme);
                } catch (Throwable ex) {
                    Logger.printException(() -> "Theme: could not build the new palette", ex);
                    return;
                }
                Utils.runOnMainThread(() -> recreateWith(activity, theme));
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not follow the new palette", ex);
        }
    }

    /** Puts the prepared table in place and draws the screen again with it. */
    private void recreateWith(Activity activity, Theme theme) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            apply(activity.getResources());
            // A table that could not be built leaves the loader as it was: recreating then would only loop.
            if (!theme.key().equals(loader.key())) return;
            appliedKeys.put(activity, loader.key());
            activity.recreate();
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not follow the new palette", ex);
        }
    }
}
