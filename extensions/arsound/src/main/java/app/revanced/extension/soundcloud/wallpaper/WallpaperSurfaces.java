package app.revanced.extension.soundcloud.wallpaper;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.platform.AndroidCompositionLocals_androidKt;

import com.soundcloud.android.ui.components.compose.Colors;

import app.revanced.extension.shared.Logger;

/**
 * The wallpaper behind SoundCloud's main screens (home, library, search, playlists and the rest that open over the
 * tabs).
 * <p>
 * The window of the main activity carries the picture, and the opaque layers SoundCloud paints over the window are
 * taken away, only while a wallpaper is shown:
 * <ul>
 * <li>the base fills of the views of a screen, when its fragment has made its view ({@link ScreenBackgrounds});</li>
 * <li>the base colour of a Compose screen, which SoundCloud's theme and scaffold take from {@code Colors.j}.</li>
 * </ul>
 * The player, the mini player, the tab bar, dialogs and sheets keep their colours: they are not in the container of
 * the main screens, or not in the window at all.
 */
public final class WallpaperSurfaces {
    /** Compose's {@code Color.Transparent} as the packed long Compose passes around. */
    private static final long TRANSPARENT = 0L;

    /**
     * The main activity built last has the wallpaper. Only a quick way out for the hooks below, which run for every
     * screen and Compose theme; the activity's own record decides.
     */
    private static volatile boolean active;

    private WallpaperSurfaces() {
    }

    /**
     * Called before the main activity is created. The window carries the picture from the start, so the opaque
     * surface is never seen for a moment, and {@link #active} is known before the first screen is composed.
     */
    public static void onActivityPreCreated(Activity activity) {
        try {
            if (!MainScreen.isMain(activity)) return;
            WallpaperDrawable picture = WallpaperStore.background(activity);
            active = picture != null;
            MainScreen.register(activity, active);
            if (picture == null) return;
            Window window = activity.getWindow();
            window.setBackgroundDrawable(picture);
            // SoundCloud's theme paints the status bar with the surface colour.
            window.setStatusBarColor(Color.TRANSPARENT);
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: could not put the picture into the window", ex);
        }
    }

    /**
     * Called when an activity comes to the screen. A wallpaper switched on or off while the main activity was
     * behind another screen (the settings) needs its screens built again: they were drawn with or without the
     * layers that hide the wallpaper. A new picture, dim or blur only redraws (see {@link WallpaperStore}).
     */
    public static void onActivityResumed(Activity activity) {
        try {
            if (!MainScreen.isMain(activity) || activity.isFinishing()) return;
            MainScreen screen = MainScreen.of(activity);
            if (screen != null && screen.hasWallpaper() != WallpaperStore.isActive()) activity.recreate();
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: could not follow the switch", ex);
        }
    }

    /**
     * Injection point. After {@code Fragment.onViewCreated}, with the fragment's view: takes the opaque fills off
     * the screen so that the wallpaper shows through.
     */
    public static void onFragmentViewCreated(View root) {
        if (!active || root == null) return;
        try {
            MainScreen screen = MainScreen.of(root.getContext());
            if (screen == null || !screen.hasWallpaper()) return;
            if (root.getParent() != null) {
                screen.relaxIfOnMainScreen(root);
                return;
            }
            // Not in its container yet: the screen is cleared once it is there.
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    view.removeOnAttachStateChangeListener(this);
                    screen.relaxIfOnMainScreen(view);
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                }
            });
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: could not clear the screen's background", ex);
        }
    }

    /**
     * Injection point, in place of {@code Colors.j} in SoundCloud's Compose theme and scaffold: the colour they
     * fill a screen with. Transparent on a main screen while the wallpaper is shown, SoundCloud's colour anywhere
     * else (dialogs, sheets, the player, the other windows).
     */
    public static long screenSurface(Composer composer) {
        long surface = Colors.j(composer);
        if (!active) return surface;
        try {
            Object host = composer.D(AndroidCompositionLocals_androidKt.getLocalView());
            if (!(host instanceof View)) return surface;
            View view = (View) host;
            MainScreen screen = MainScreen.of(view.getContext());
            if (screen != null && screen.hasWallpaper() && screen.contains(view)) return TRANSPARENT;
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: a Compose screen keeps its colour", ex);
        }
        return surface;
    }
}
