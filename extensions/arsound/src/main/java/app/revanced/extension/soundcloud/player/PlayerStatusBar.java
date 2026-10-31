package app.revanced.extension.soundcloud.player;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.ImageView;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The icons of the status bar over the open player. SoundCloud keeps its ad banner above the open player, so its
 * status bar lies on black; without the banner the player reaches the top of the screen, and the status bar lies on
 * the cover: the white icons were lost on a light one. While the player is open at rest, the icons turn dark over a
 * light top of the cover; the screen's own icons come back as soon as the player moves.
 * <p>
 * With the lyrics shown the cover is blurred and dimmed under them, and the icons stay as they are. Main thread.
 */
public final class PlayerStatusBar {
    /** The brightness of the cover's top, from 0 to 1, above which the icons turn dark. */
    private static final float LIGHT = 0.62f;
    /** The part of the cover's height that lies under the status bar, a little more. */
    private static final float TOP = 0.12f;
    /** How often the open player is looked at for a new cover: a track can change while it stays open. */
    private static final long CHECK_MS = 1_000;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable check = PlayerStatusBar::check;
    /** The artworks of the player's pages. */
    private static final Set<View> artworks = Collections.newSetFromMap(new WeakHashMap<>());

    /** The window whose icons were changed, and how they were before. */
    private static WeakReference<Window> changed = new WeakReference<>(null);
    private static boolean originalLight;
    /** The cover last measured, and whether its top was light. */
    private static WeakReference<Drawable> measured = new WeakReference<>(null);
    private static boolean measuredLight;
    private static int generation;

    private PlayerStatusBar() {
    }

    /** Called by {@link PlayerArtworkLayers} when a page's artwork is made. */
    static void remember(View artwork) {
        artworks.add(artwork);
    }

    /** Called by {@link PlayerSheet} with every state of the sheet. */
    static void onSheet(int state) {
        handler.removeCallbacks(check);
        if (state == PlayerSheet.EXPANDED) handler.post(check);
        else restore();
    }

    private static void check() {
        handler.removeCallbacks(check);
        if (!PlayerSheet.isExpanded()) {
            restore();
            return;
        }
        handler.postDelayed(check, CHECK_MS);
        try {
            View artwork = currentArtwork();
            Drawable cover = artwork == null ? null : coverOf(artwork);
            Activity activity = artwork == null ? null : activityOf(artwork.getContext());
            if (cover == null || activity == null || lyricsShown(artwork)) {
                if (cover == null && artwork != null) Logger.printDebug(() -> "Player status bar: no picture in the artwork");
                restore();
                return;
            }
            if (cover == measured.get()) {
                apply(activity.getWindow(), measuredLight);
                return;
            }
            Bitmap bitmap = cover instanceof BitmapDrawable ? ((BitmapDrawable) cover).getBitmap() : null;
            if (bitmap == null || bitmap.isRecycled()) {
                Logger.printDebug(() -> "Player status bar: the picture is not a bitmap: " + cover.getClass().getName());
                return;
            }
            int mine = ++generation;
            // A hardware bitmap is copied back from the graphics memory to be read: off the main thread.
            Utils.runOnBackgroundThread(() -> {
                boolean light = topBrightness(bitmap) > LIGHT;
                Utils.runOnMainThread(() -> {
                    if (mine != generation || !PlayerSheet.isExpanded()) return;
                    measured = new WeakReference<>(cover);
                    measuredLight = light;
                    Logger.printInfo(() -> "Player status bar: " + (light ? "dark" : "light") + " icons over the cover");
                    if (!activity.isDestroyed()) apply(activity.getWindow(), light);
                });
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Player status bar: not checked", ex);
        }
    }

    /** The artwork of the page on screen: the one that shows most of itself. */
    private static View currentArtwork() {
        View best = null;
        int bestWidth = 0;
        android.graphics.Rect visible = new android.graphics.Rect();
        for (View artwork : artworks) {
            if (artwork == null || !artwork.isShown() || !artwork.getGlobalVisibleRect(visible)) continue;
            if (visible.width() > bestWidth) {
                bestWidth = visible.width();
                best = artwork;
            }
        }
        return best;
    }

    /**
     * The picture of the artwork: that of its biggest image view a few levels deep (the image view sits inside a
     * wrapper), with the top layer of a fade between two pictures. Null when there is none.
     */
    private static Drawable coverOf(View artwork) {
        ImageView image = biggestImage(artwork, 0);
        Drawable drawable = image == null ? null : image.getDrawable();
        while (drawable instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable layers = (android.graphics.drawable.LayerDrawable) drawable;
            if (layers.getNumberOfLayers() == 0) return null;
            drawable = layers.getDrawable(layers.getNumberOfLayers() - 1);
        }
        return drawable;
    }

    private static ImageView biggestImage(View view, int depth) {
        if (view instanceof ImageView) return ((ImageView) view).getDrawable() != null ? (ImageView) view : null;
        if (depth >= 4 || !(view instanceof ViewGroup)) return null;
        ImageView best = null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ImageView found = biggestImage(group.getChildAt(i), depth + 1);
            if (found != null && (best == null
                    || found.getWidth() * found.getHeight() > best.getWidth() * best.getHeight())) best = found;
        }
        return best;
    }

    /** Whether the lyrics lie over the artwork: the page around it holds a shown lyrics layer. */
    private static boolean lyricsShown(View artwork) {
        View page = artwork;
        for (int up = 0; up < 3 && page.getParent() instanceof View; up++) page = (View) page.getParent();
        return hasShownLyrics(page, 0);
    }

    private static boolean hasShownLyrics(View view, int depth) {
        if (!view.isShown()) return false;
        if (view.getClass().getName().endsWith(".LyricsLayer")) return true;
        if (depth >= 6 || !(view instanceof ViewGroup)) return false;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) if (hasShownLyrics(group.getChildAt(i), depth + 1)) return true;
        return false;
    }

    /** The average brightness of the top of the picture, from 0 to 1. */
    static float topBrightness(Bitmap bitmap) {
        Bitmap readable = bitmap;
        boolean copied = false;
        if (Build.VERSION.SDK_INT >= 26 && bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            readable = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (readable == null) return 0;
            copied = true;
        }
        try {
            int width = readable.getWidth();
            int rows = Math.max(1, Math.round(readable.getHeight() * TOP));
            double sum = 0;
            int count = 0;
            for (int y = 0; y < rows; y += Math.max(1, rows / 6)) {
                for (int x = 0; x < width; x += Math.max(1, width / 24)) {
                    int pixel = readable.getPixel(x, y);
                    sum += 0.2126 * ((pixel >> 16) & 0xFF) + 0.7152 * ((pixel >> 8) & 0xFF) + 0.0722 * (pixel & 0xFF);
                    count++;
                }
            }
            return count == 0 ? 0 : (float) (sum / count / 255);
        } finally {
            if (copied) readable.recycle();
        }
    }

    private static void apply(Window window, boolean light) {
        if (changed.get() != window) {
            restore();
            originalLight = isLight(window);
            changed = new WeakReference<>(window);
        }
        setLight(window, light);
    }

    private static void restore() {
        Window window = changed.get();
        if (window == null) return;
        changed = new WeakReference<>(null);
        setLight(window, originalLight);
    }

    private static boolean isLight(Window window) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            return controller != null
                    && (controller.getSystemBarsAppearance() & WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0;
        }
        //noinspection deprecation
        return (window.getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0;
    }

    @SuppressWarnings("deprecation")
    private static void setLight(Window window, boolean light) {
        if (isLight(window) == light) return;
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) controller.setSystemBarsAppearance(
                    light ? WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS : 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            return;
        }
        View decor = window.getDecorView();
        int flags = decor.getSystemUiVisibility();
        decor.setSystemUiVisibility(light ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
