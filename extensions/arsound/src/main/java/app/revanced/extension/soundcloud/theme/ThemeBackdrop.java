package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.player.PlayerSheet;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Puts the chosen theme's animation ({@link ThemeEffect}) on the main screen and makes it for Arsound's screens.
 * <p>
 * SoundCloud's screens paint opaque backgrounds (all but the ones that show a wallpaper, see WallpaperSurfaces), so on
 * the main screen the effect lies over them, faint and transparent to touches. It is placed right above SoundCloud's
 * own views, under Arsound's pills and icons.
 */
public final class ThemeBackdrop {
    private static final String TAG = "arsound_theme_effect";
    /** Over SoundCloud's content the particles are dimmer than on the design's empty background. */
    private static final float MAIN_SCREEN_OPACITY = .55f;

    /** Every effect that is alive (the main screen, the settings, the preview tiles), told when battery saver is switched. */
    private static final java.util.Set<ThemeEffect> live =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static boolean switchesWatched;
    /** The effect over the main screen, the only one the player sheet's slide has to wait for. */
    private static java.lang.ref.WeakReference<ThemeEffect> mainScreen = new java.lang.ref.WeakReference<>(null);
    /** The view that carries it. */
    private static java.lang.ref.WeakReference<View> mainLayer = new java.lang.ref.WeakReference<>(null);

    private ThemeBackdrop() {
    }

    /**
     * Called when the player sheet starts or stops moving, and when it comes to rest. The effect lies over the sheet and
     * asks for a new frame of its own after every frame, which makes the slide of the player compete with it for the
     * same milliseconds: it stands still while the sheet moves and goes on once it has arrived. Over the open player
     * it is not drawn at all: the player covers the main screen, and the window would be redrawn for nothing.
     */
    public static void onSheet(boolean busy, boolean expanded) {
        View layer = mainLayer.get();
        // First the view: showing it makes its drawable visible again, which the line below may take back.
        if (layer != null) {
            int visibility = expanded ? View.INVISIBLE : View.VISIBLE;
            if (layer.getVisibility() != visibility) layer.setVisibility(visibility);
        }
        ThemeEffect effect = mainScreen.get();
        if (effect != null) effect.setVisible(!busy && !expanded, false);
    }

    /** Particle amount chosen in the settings, 1 as in the design. */
    private static float density() {
        return Settings.getLong(Settings.THEME_EFFECTS_DENSITY, 100) / 100f;
    }

    /** The effect of the chosen theme, or null when it has none or the animations are off. */
    public static ThemeEffect forCurrentTheme(Context context, float sizeScale) {
        if (!Settings.isThemeEffectsEnabled()) return null;
        return register(context, ThemeEffect.create(context, ArsoundTheme.effect(context), sizeScale, density(),
                ArsoundTheme.isNight(context), ArsoundTheme.palette(context, "special"), ThemeEffect.SCREEN_FPS));
    }

    /** The effect of any theme in its dark look, for a preview tile. */
    public static ThemeEffect forTheme(Context context, ArsoundTheme.Theme theme, float sizeScale, float density) {
        return register(context, ThemeEffect.create(context, theme.effect(), sizeScale, density, true, theme.accent(true),
                ThemeEffect.TILE_FPS));
    }

    /** Remembers a new effect, so battery saver and Android's animations reach it wherever it is shown. */
    private static ThemeEffect register(Context context, ThemeEffect effect) {
        if (effect == null) return null;
        synchronized (live) {
            live.add(effect);
        }
        Context application = context.getApplicationContext();
        watchSwitches(application != null ? application : context);
        return effect;
    }

    /**
     * Battery saver and Android's animations stop the animation; switching either while the app is open takes effect
     * at once. The effects are told here instead of reading both on every return to the screen and at the end of every
     * slide of the player, which cost two calls into the system on the main thread each time.
     */
    private static void watchSwitches(final Context context) {
        if (switchesWatched) return;
        switchesWatched = true;
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    refreshAllStill(receiverContext);
                }
            }, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        } catch (Exception ex) {
            Logger.printException(() -> "Theme effect: could not watch battery saver", ex);
        }
        try {
            context.getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(android.provider.Settings.Global.ANIMATOR_DURATION_SCALE),
                    false, new ContentObserver(new Handler(Looper.getMainLooper())) {
                        @Override
                        public void onChange(boolean selfChange) {
                            refreshAllStill(context);
                        }
                    });
        } catch (Exception ex) {
            Logger.printException(() -> "Theme effect: could not watch Android's animations", ex);
        }
    }

    private static void refreshAllStill(Context context) {
        // A copy: refreshStill redraws, which could add or drop effects while this goes through them.
        java.util.List<ThemeEffect> effects;
        synchronized (live) {
            effects = new java.util.ArrayList<>(live);
        }
        for (ThemeEffect effect : effects) effect.refreshStill(context);
    }

    /** Called when an activity comes to the screen: adds, renews or removes the effect over the main screen. */
    public static void onActivityResumed(Activity activity) {
        if (!activity.getClass().getName().endsWith(".MainActivity")) return;
        try {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            View existing = content.findViewWithTag(TAG);
            // The settings may have changed while another screen was open; the key tells whether to rebuild.
            // The palette is part of the key: the effect is drawn in the accent, which a runtime theme can change.
            String key = Settings.isThemeEffectsEnabled() + "/" + ArsoundTheme.effect(activity) + "/" + density()
                    + "/" + ArsoundTheme.isNight(activity) + "/" + ArsoundTheme.paletteKey(activity);
            if (existing != null) {
                if (key.equals(existing.getTag(TAG.hashCode()))) {
                    if (existing.getBackground() instanceof ThemeEffect) {
                        mainScreen = new java.lang.ref.WeakReference<>((ThemeEffect) existing.getBackground());
                        mainLayer = new java.lang.ref.WeakReference<>(existing);
                        onSheet(PlayerSheet.isBusy(), PlayerSheet.isExpanded());
                    }
                    return;
                }
                content.removeView(existing);
            }
            ThemeEffect effect = forCurrentTheme(activity, 1f);
            if (effect == null) return;
            mainScreen = new java.lang.ref.WeakReference<>(effect);
            View layer = new View(activity);
            layer.setTag(TAG);
            layer.setTag(TAG.hashCode(), key);
            // The drawable fades itself. A faded view would be drawn into a buffer of the whole screen on every
            // frame of the animation, which made the full player open with a few frames per second on big screens.
            effect.setAlpha(Math.round(255 * MAIN_SCREEN_OPACITY));
            layer.setBackground(effect);
            layer.setClickable(false);
            layer.setFocusable(false);
            layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            // Right above SoundCloud's own views, so Arsound's pills added later stay on top.
            content.addView(layer, Math.min(1, content.getChildCount()), new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            mainLayer = new java.lang.ref.WeakReference<>(layer);
            // The screen may come back with the player open.
            onSheet(PlayerSheet.isBusy(), PlayerSheet.isExpanded());
        } catch (Exception ex) {
            Logger.printException(() -> "Theme effect: could not add to the main screen", ex);
        }
    }
}
