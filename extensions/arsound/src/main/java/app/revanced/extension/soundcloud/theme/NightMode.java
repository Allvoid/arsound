package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;

import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.theme.ArsoundTheme.Theme;

/** The dark and the light look of the app: which one is drawn, and keeping one of them for a theme that has only that. */
final class NightMode {
    /** Set while the app's dark mode is forced on by a dark-only theme. */
    private static final String FORCED_NIGHT_KEY = "arsound_theme_forced_night";

    /** How a theme wants the app to follow the phone's dark mode. */
    enum Policy {
        /** The app is dark or light as the phone is. */
        FOLLOW_PHONE("", UiModeManager.MODE_NIGHT_AUTO),
        /** The design has no light version: the app stays dark. */
        ALWAYS_DARK("yes", UiModeManager.MODE_NIGHT_YES),
        /** The user chose a light custom theme: the app stays light. */
        ALWAYS_LIGHT("no", UiModeManager.MODE_NIGHT_NO);

        /** What is kept in the settings for the policy that was given to Android last. */
        final String stored;
        /** The mode of Android's per-app dark mode that carries the policy out. */
        final int uiMode;

        Policy(String stored, int uiMode) {
            this.stored = stored;
            this.uiMode = uiMode;
        }

        static Policy of(Theme theme) {
            if (theme == null) return FOLLOW_PHONE;
            return theme.darkOnly ? ALWAYS_DARK : theme.lightOnly ? ALWAYS_LIGHT : FOLLOW_PHONE;
        }
    }

    private NightMode() {
    }

    /** True when the app draws its dark look now. */
    static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Keeps the app dark for a dark-only theme (or light for a light-only one), through Android's own per-app dark
     * mode (Android 12 and later): every screen, the status bar and SoundCloud's night resources then follow it.
     * Android remembers the mode, so it is given back to the phone's setting once, when another theme is chosen.
     */
    static void apply(Context context, Theme theme) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        Policy policy = Policy.of(theme);
        if (policy.stored.equals(Settings.getString(FORCED_NIGHT_KEY, ""))) return;
        UiModeManager modes = context.getSystemService(UiModeManager.class);
        if (modes == null) return;
        modes.setApplicationNightMode(policy.uiMode);
        Settings.putString(FORCED_NIGHT_KEY, policy.stored);
    }

    /** Gives an activity the dark look whatever the phone's mode; call from {@code attachBaseContext} after the super call. */
    static void forceDark(Activity activity, Context base) {
        Configuration override = new Configuration();
        int mode = base.getResources().getConfiguration().uiMode;
        override.uiMode = (mode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        activity.applyOverrideConfiguration(override);
    }
}
