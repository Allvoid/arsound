package app.revanced.extension.soundcloud.theme;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.AppResources;

/**
 * The start screen the system shows on the next cold start (the drawing letter on a flat colour), chosen by the theme.
 * <p>
 * Android draws it from the APK's resources before any app code runs, so the app cannot recolour it, only choose which
 * of the styles baked by the theme patch ({@code Arsound.Splash.*}) it uses. A shipped theme has one; a theme whose
 * colours are made on the phone and listed in {@link SplashGrid} (the custom theme) gets the baked pair nearest to its
 * real dark surface and accent. Android remembers the choice ({@code SplashScreen.setSplashScreenTheme}, Android 13).
 */
final class SplashStyle {
    /** The style last given to Android, to ask it again only when the theme's start screen changed. */
    private static final String LAST_KEY = "arsound_splash_style";
    private static final String GRID_ASSET = "arsound/splash-grid.json";
    private static final String STYLE_PREFIX = "Arsound.Splash.";

    /** Read once; null when the patch baked no grid. */
    private static SplashGrid grid;
    private static boolean gridRead;

    private SplashStyle() {
    }

    /** The name of the style for this theme, null for SoundCloud's own start screen. */
    static String nameOf(Context context, ArsoundTheme.Theme theme) {
        if (theme == null) return null;
        String plain = STYLE_PREFIX + theme.id;
        SplashGrid grid = grid(context);
        if (grid == null || !grid.themeId.equals(theme.id)) return plain;
        // The start screen is dark in both modes, so the dark look's colours are the ones to match.
        String cell = grid.styleFor(theme.surface(true), theme.accent(true));
        return identifier(context, cell) != 0 ? cell : plain;
    }

    /** Gives Android the start screen of this theme (null: SoundCloud's own) for the next starts. */
    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    static void apply(Activity activity, ArsoundTheme.Theme theme) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        String name = nameOf(activity, theme);
        activity.getSplashScreen().setSplashScreenTheme(name == null ? 0 : identifier(activity, name));
        Settings.putString(LAST_KEY, name == null ? "" : name);
    }

    /**
     * Makes sure Android has the right start screen for this theme: after an update, after the custom colours were
     * saved by an older build, or when the first try failed. Cheap: nothing is asked of Android when the style is
     * the one given last.
     */
    static void sync(Activity activity, ArsoundTheme.Theme theme) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        String name = nameOf(activity, theme);
        if ((name == null ? "" : name).equals(Settings.getString(LAST_KEY, ""))) return;
        apply(activity, theme);
    }

    private static synchronized SplashGrid grid(Context context) {
        if (gridRead) return grid;
        gridRead = true;
        try {
            JSONObject json = new JSONObject(ThemeFiles.readAsset(context, GRID_ASSET));
            grid = SplashGrid.of(json.getString("theme"), json.getString("cell"),
                    strings(json.getJSONArray("backgrounds")), strings(json.getJSONArray("inks")));
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: no start screen grid, using the plain style", ex);
        }
        return grid;
    }

    private static List<String> strings(JSONArray array) throws Exception {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) result.add(array.getString(i));
        return result;
    }

    /** The resource of a style by name; the resources keep SoundCloud's package name although the app may be installed under another one. */
    private static int identifier(Context context, String style) {
        return AppResources.identifier(context, style, "style");
    }
}
