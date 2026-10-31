package app.revanced.extension.soundcloud.wallpaper;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

import androidx.compose.runtime.Composer;
import androidx.compose.ui.Modifier;
import androidx.compose.ui.draw.AlphaKt;
import androidx.compose.ui.draw.DrawModifierKt;
import androidx.compose.ui.platform.AndroidCompositionLocals_androidKt;

import com.soundcloud.android.ui.components.compose.Colors;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

import kotlin.jvm.functions.Function1;

/**
 * The wallpaper behind SoundCloud's main screens (home, library, search, playlists and the rest that open over the
 * tabs).
 * <p>
 * The window of the main activity carries the picture, and the opaque layers SoundCloud paints over the window are
 * taken away, only while a wallpaper is shown:
 * <ul>
 * <li>the base fills of the views of a screen, when its fragment has made its view ({@link ScreenBackgrounds});</li>
 * <li>the base colour of a Compose screen, which SoundCloud's theme, scaffold and collapsing title bar ("Your likes")
 * take from {@code Colors.j};</li>
 * <li>the blurred cover behind the title of a playlist or a profile, and the fades of a playlist's header;</li>
 * <li>the solid fill of a highlighted track cell (a Go+ track that plays as a preview);</li>
 * <li>the band of the top bar, as the style chosen in Appearance has it ({@link TopBarStyle}).</li>
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
     * Injection point, in the list adapter that shows a loading, empty or failed list: its item, just made, filled the
     * whole list in the screen's colour (the search while it searched was one opaque rectangle over the wallpaper). On a
     * main screen with the wallpaper its fill goes; anywhere else (dialogs, sheets) it stays.
     *
     * @param parent The list the item is made for.
     */
    public static void onEmptyItem(View item, android.view.ViewGroup parent) {
        if (!active || item == null || parent == null) return;
        try {
            MainScreen screen = MainScreen.of(parent.getContext());
            if (screen == null || !screen.hasWallpaper() || !screen.contains(parent)) return;
            app.revanced.extension.soundcloud.shared.ClearBackground.on(item);
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: could not clear a list's empty item", ex);
        }
    }

    /**
     * Injection point, in place of {@code Colors.j} in SoundCloud's Compose theme and scaffold: the colour they
     * fill a screen with. Transparent on a main screen while the wallpaper is shown, SoundCloud's colour anywhere
     * else (dialogs, sheets, the player, the other windows).
     */
    public static long screenSurface(Composer composer) {
        long surface = Colors.j(composer);
        return active && onWallpaperScreen(composer) ? TRANSPARENT : surface;
    }

    /**
     * Injection point, before the picture of the home screen's content wall backdrop (genre filters over a grid of
     * tracks) is loaded: a tinted texture that lay over the wallpaper like a band. None on a main screen with the
     * wallpaper, SoundCloud's picture anywhere else.
     *
     * @param model What the picture is loaded from (an address or a request).
     */
    public static Object backdropPicture(Object model, Composer composer) {
        return active && onWallpaperScreen(composer) ? null : model;
    }

    /**
     * Injection point, in place of {@code DrawModifierKt.drawWithContent} in the same backdrop: its shade, a gradient
     * that SoundCloud makes from the screen's colour with alphas of its own, so even a transparent colour came out as a
     * dark band. Not drawn at all on a main screen with the wallpaper.
     */
    public static Modifier backdropShade(Modifier modifier, Function1 draw, Composer composer) {
        return active && onWallpaperScreen(composer) ? modifier : DrawModifierKt.drawWithContent(modifier, draw);
    }

    /**
     * Injection point, in the content of SoundCloud's Compose track cell (the likes, playlists): the fill of a track
     * that plays only as a preview for this account (Go+), {@code Colors.d}. Clear on a main screen with the wallpaper,
     * like the other rows; the GO+ mark on the cover still tells the track apart. SoundCloud's colour anywhere else.
     */
    public static long previewCellFill(Composer composer) {
        long fill = Colors.d(composer);
        return active && onWallpaperScreen(composer) ? TRANSPARENT : fill;
    }

    /**
     * Injection point, in the view states of track cells: the background of a highlighted cell, which SoundCloud gives
     * a track that plays only as a preview for this account (Go+). Its solid colour lay over the wallpaper like a band;
     * while the wallpaper is shown the cell has the plain background of the others, and the GO+ mark on the cover still
     * tells the track apart.
     *
     * @param highlight The drawable resource of the highlighted cell.
     */
    public static int trackCellBackground(int highlight) {
        if (!active) return highlight;
        int plain = plainCellBackground;
        if (plain == -1) {
            // Looked up once: the id stays the same while the process lives, and every cell of a list asked for it.
            plain = Utils.getResourceIdentifier(ResourceType.DRAWABLE, "ripple_cell_default_drawable");
            plainCellBackground = plain;
        }
        return plain == 0 ? highlight : plain;
    }

    /** The plain cell background's id, 0 when the app has none, -1 before it is looked up. */
    private static volatile int plainCellBackground = -1;

    /**
     * Injection point, in {@code BlurredHeaderBackgroundKt.a}: the address of the cover SoundCloud blurs behind the
     * title of a playlist or a profile. None on a main screen while the wallpaper is shown: the blurred cover would
     * lie over the top of the wallpaper like a curtain.
     */
    public static String headerPicture(String url, Composer composer) {
        return url != null && active && onWallpaperScreen(composer) ? null : url;
    }

    /**
     * Injection point, in {@code PlaylistDetailsSmallerArtworkHeaderKt.b}: the modifier of the cover a playlist's
     * header draws blurred behind its title. Fully clear on a main screen while the wallpaper is shown, so the blur
     * has nothing to blur; the header's fades are clear there too ({@link #screenSurface}).
     */
    public static Modifier headerBackdrop(Modifier modifier, Composer composer) {
        return active && onWallpaperScreen(composer) ? AlphaKt.alpha(modifier, 0f) : modifier;
    }

    /**
     * Injection point, in {@code PlaylistDetailsSmallerArtworkHeaderKt.b}: the modifier that lays the blur and its
     * 40 % tint over the header. On a main screen with the wallpaper the plain modifier comes back: the tint was a dark
     * square over the picture.
     *
     * @param blurred The modifier with the blur.
     * @param plain   The same modifier without it.
     */
    public static Modifier headerHaze(Modifier blurred, Modifier plain, Composer composer) {
        return active && onWallpaperScreen(composer) ? plain : blurred;
    }

    /**
     * Injection point, in the same header: its fade ({@code Brush}), which SoundCloud runs into the screen's colour at
     * the top and the bottom. On a main screen with the wallpaper a soft shade instead ({@link HeaderShade}), clear at
     * both ends, so the header has no edge.
     */
    public static Object headerShade(Object brush, Composer composer) {
        if (!active || !onWallpaperScreen(composer)) return brush;
        try {
            return HeaderShade.brush(brush.getClass().getClassLoader(), Colors.j(composer));
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: the header keeps its fade", ex);
            return brush;
        }
    }

    // region The top bar

    /** The fill under the next top bar, set by {@link #topBarColour} and taken by {@link #topBarModifier}. Main thread. */
    private static TopBarStyle pendingStyle;
    private static long pendingSurface;
    private static int pendingWidth, pendingHeight;

    /**
     * Injection point, in place of {@code Colors.j} in SoundCloud's top bar ({@code TopAppBarKt}): the colour of the
     * band behind the back button, "Search in …" and the filter. On a main screen with the wallpaper it follows the
     * style chosen in Appearance ({@link TopBarStyle}); for the fade and the glass the band is clear and their fill is
     * laid under the bar through its modifier ({@link #topBarModifier}). SoundCloud's colour anywhere else.
     */
    public static long topBarColour(Composer composer) {
        long surface = Colors.j(composer);
        pendingStyle = null;
        if (!active) return surface;
        View view = wallpaperView(composer);
        if (view == null) return surface;
        TopBarStyle style = TopBarStyle.current();
        switch (style) {
            case CLEAR:
                return TRANSPARENT;
            case TINT:
                return HeaderShade.withAlpha(surface, 0.55f);
            case FADE:
            case GLASS:
                pendingStyle = style;
                pendingSurface = surface;
                View root = view.getRootView();
                pendingWidth = root.getWidth();
                pendingHeight = root.getHeight();
                return TRANSPARENT;
            default:
                return surface;
        }
    }

    /**
     * Injection point, right before SoundCloud's top bar is laid out ({@code AppBarKt.b}): its modifier, with the fade
     * or the glass under the bar when {@link #topBarColour} chose one for it.
     */
    public static Modifier topBarModifier(Modifier modifier) {
        TopBarStyle style = pendingStyle;
        pendingStyle = null;
        if (style == null || modifier == null) return modifier;
        try {
            int width = pendingWidth, height = pendingHeight;
            if (width <= 0 || height <= 0) {
                android.util.DisplayMetrics metrics = android.content.res.Resources.getSystem().getDisplayMetrics();
                width = metrics.widthPixels;
                height = metrics.heightPixels;
            }
            Object result = style == TopBarStyle.FADE
                    ? TopBarLook.fade(modifier, pendingSurface)
                    : TopBarLook.glass(modifier, pendingSurface, (int) (pendingSurface >>> 32), width, height);
            return (Modifier) result;
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: the top bar keeps a plain fill", ex);
            return modifier;
        }
    }

    // endregion

    /** True if the Compose content belongs to a main screen that shows the wallpaper. */
    private static boolean onWallpaperScreen(Composer composer) {
        return wallpaperView(composer) != null;
    }

    /** The view of the Compose content if it belongs to a main screen that shows the wallpaper, else null. */
    private static View wallpaperView(Composer composer) {
        try {
            Object host = composer.D(AndroidCompositionLocals_androidKt.getLocalView());
            if (!(host instanceof View)) return null;
            View view = (View) host;
            MainScreen screen = MainScreen.of(view.getContext());
            return screen != null && screen.hasWallpaper() && screen.contains(view) ? view : null;
        } catch (Throwable ex) {
            Logger.printException(() -> "Wallpaper: a Compose screen keeps its colour", ex);
            return null;
        }
    }
}
