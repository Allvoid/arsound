package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.app.Application;
import android.content.res.Resources;
import android.os.Bundle;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.player.PlayerSheet;
import app.revanced.extension.soundcloud.theme.ArsoundTheme.Theme;
import app.revanced.extension.soundcloud.wallpaper.WallpaperSurfaces;
import app.revanced.extension.soundcloud.widget.WidgetHeart;

/**
 * Applies the chosen theme to the app as it runs: at the start of the application and before each screen is created,
 * and brings screens that missed a change of the palette up to date. Registered once, by {@link ArsoundTheme#onApplicationCreate}.
 */
final class ThemeLifecycle implements Application.ActivityLifecycleCallbacks {
    private final Application application;
    private final ThemeLoader loader;
    /** The theme each live activity was given at its start, to tell one that missed a change of the palette. */
    private final Map<Activity, String> appliedKeys = Collections.synchronizedMap(new WeakHashMap<>());
    /** The palette an activity was last recreated for, so a change is followed once and never in a loop. */
    private String recreatedFor;

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
        // The widget's heart is fitted to the card of the phone's day or night look; follow the look flipping.
        WidgetHeart.watchLook(application);
        application.registerActivityLifecycleCallbacks(lifecycle);
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
            if (applied == null || applied.equals(theme.key()) || theme.key().equals(recreatedFor)) return;
            recreatedFor = theme.key();
            apply(activity.getResources());
            appliedKeys.put(activity, loader.key());
            activity.recreate();
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not follow the new palette", ex);
        }
    }
}
