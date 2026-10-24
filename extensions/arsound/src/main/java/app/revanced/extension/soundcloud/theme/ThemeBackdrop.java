package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Puts the chosen theme's animation ({@link ThemeEffect}) on the main screen and makes it for Arsound's screens.
 * <p>
 * SoundCloud's screens paint opaque backgrounds, so on the main screen the effect lies over them, faint and
 * transparent to touches. It is placed right above SoundCloud's own views, under Arsound's pills and icons.
 */
public final class ThemeBackdrop {
    private static final String TAG = "arsound_theme_effect";
    /** Over SoundCloud's content the particles are dimmer than on the design's empty background. */
    private static final float MAIN_SCREEN_OPACITY = .55f;

    /** Every effect that is alive (the main screen, the settings, the preview tiles), told when battery saver is switched. */
    private static final java.util.Set<ThemeEffect> live =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static boolean powerSaveWatched;

    private ThemeBackdrop() {
    }

    /** Particle amount chosen in the settings, 1 as in the design. */
    public static float density() {
        return Settings.getLong(Settings.THEME_EFFECTS_DENSITY, 100) / 100f;
    }

    /** The effect of the chosen theme, or null when it has none or the animations are off. */
    public static ThemeEffect forCurrentTheme(Context context, float sizeScale) {
        if (!Settings.isThemeEffectsEnabled()) return null;
        return register(context, ThemeEffect.create(context, ArsoundTheme.effect(context), sizeScale, density(),
                ArsoundTheme.isNight(context), ArsoundTheme.palette(context, "special")));
    }

    /** The effect of any theme in its dark look, for a preview tile. */
    public static ThemeEffect forTheme(Context context, ArsoundTheme.Theme theme, float sizeScale, float density) {
        return register(context, ThemeEffect.create(context, theme.effect(), sizeScale, density, true, theme.accent(true)));
    }

    /** Remembers a new effect, so battery saver reaches it wherever it is shown. */
    private static ThemeEffect register(Context context, ThemeEffect effect) {
        if (effect == null) return null;
        synchronized (live) {
            live.add(effect);
        }
        Context application = context.getApplicationContext();
        watchPowerSave(application != null ? application : context);
        return effect;
    }

    /** Battery saver stops the animation; switching it while the app is open now takes effect at once. */
    private static void watchPowerSave(Context context) {
        if (powerSaveWatched) return;
        powerSaveWatched = true;
        try {
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    // A copy: refreshStill redraws, which could add or drop effects while this goes through them.
                    java.util.List<ThemeEffect> effects;
                    synchronized (live) {
                        effects = new java.util.ArrayList<>(live);
                    }
                    for (ThemeEffect effect : effects) effect.refreshStill(receiverContext);
                }
            }, new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED));
        } catch (Exception ex) {
            Logger.printException(() -> "Theme effect: could not watch battery saver", ex);
        }
    }

    /** Called when an activity comes to the screen: adds, renews or removes the effect over the main screen. */
    public static void onActivityResumed(Activity activity) {
        if (!activity.getClass().getName().endsWith(".MainActivity")) return;
        try {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) return;
            View existing = content.findViewWithTag(TAG);
            // The settings may have changed while another screen was open; the key tells whether to rebuild.
            String key = Settings.isThemeEffectsEnabled() + "/" + ArsoundTheme.effect(activity) + "/" + density()
                    + "/" + ArsoundTheme.isNight(activity);
            if (existing != null) {
                if (key.equals(existing.getTag(TAG.hashCode()))) {
                    // Android's animations may have been switched while another app was open.
                    if (existing.getBackground() instanceof ThemeEffect) {
                        ((ThemeEffect) existing.getBackground()).refreshStill(activity);
                    }
                    return;
                }
                content.removeView(existing);
            }
            ThemeEffect effect = forCurrentTheme(activity, 1f);
            if (effect == null) return;
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
        } catch (Exception ex) {
            Logger.printException(() -> "Theme effect: could not add to the main screen", ex);
        }
    }
}
