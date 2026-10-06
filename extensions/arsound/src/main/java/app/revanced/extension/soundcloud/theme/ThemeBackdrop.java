package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.content.Context;
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

    private ThemeBackdrop() {
    }

    /** Particle amount chosen in the settings, 1 as in the design. */
    public static float density() {
        return Settings.getLong(Settings.THEME_EFFECTS_DENSITY, 100) / 100f;
    }

    /** The effect of the chosen theme, or null when it has none or the animations are off. */
    public static ThemeEffect forCurrentTheme(Context context, float sizeScale) {
        if (!Settings.isThemeEffectsEnabled()) return null;
        return ThemeEffect.create(context, ArsoundTheme.effect(context), sizeScale, density(),
                ArsoundTheme.isNight(context), ArsoundTheme.palette(context, "special"));
    }

    /** The effect of any theme in its dark look, for a preview tile. */
    public static ThemeEffect forTheme(Context context, ArsoundTheme.Theme theme, float sizeScale, float density) {
        return ThemeEffect.create(context, theme.effect(), sizeScale, density, true, theme.accent(true));
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
                if (key.equals(existing.getTag(TAG.hashCode()))) return;
                content.removeView(existing);
            }
            ThemeEffect effect = forCurrentTheme(activity, 1f);
            if (effect == null) return;
            View layer = new View(activity);
            layer.setTag(TAG);
            layer.setTag(TAG.hashCode(), key);
            layer.setBackground(effect);
            layer.setAlpha(MAIN_SCREEN_OPACITY);
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
