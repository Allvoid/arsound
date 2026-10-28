package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.content.res.loader.ResourcesLoader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONObject;

import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.widget.WidgetHeart;

/**
 * Arsound's own looks: themes over SoundCloud's interface, switched at run time.
 * <p>
 * SoundCloud draws everything from a handful of colour tokens ({@code dark_mode_surface},
 * {@code light_mode_primary}, the orange accent...): views through theme attributes, Compose through the colour
 * resources themselves, and its text from a few font files. A {@link ResourcesLoader} with a small resource table
 * built on the phone ({@link ThemeTableBuilder}) overrides those tokens, the fonts, the artwork and mini player corners
 * and the loading animation (recoloured to the accent) for every lookup ({@link ThemeLoader}).
 * "SoundCloud" adds no loader and leaves the original look.
 * <p>
 * The themes are described in {@code assets/arsound/themes.json} ({@link ThemeCatalog}), put there by the theme patch
 * together with the fonts and a start screen per theme. Two of them, Monet and the user's Custom theme, only have a seed
 * there: their colours are made on the phone ({@link RuntimeThemes}) and may change while the app runs, so a theme is
 * told apart by its {@link Theme#key()}, not only by its id.
 * <p>
 * This class is what the rest of the app and the patches call; the work is done by the classes named above,
 * {@link ThemeLifecycle} (applying the theme as the app runs), {@link NightMode} and {@link ThemeDecor}.
 */
public final class ArsoundTheme {
    public static final String KEY = "arsound_theme";
    public static final String SOUNDCLOUD = "soundcloud";

    /** The setting of the new player: the "playerSkin" part of the themes, a beta that is off by default. */
    public static final String PLAYER_SKIN_KEY = "arsound_player_skin";

    /** One theme of themes.json: its palette, tokens, decoration, radii and fonts. */
    public static final class Theme {
        public final String id;
        /** The app stays dark whatever the phone's mode: the design has no light version. */
        public final boolean darkOnly;
        /** The app stays light whatever the phone's mode (the user's choice for the custom theme). */
        public final boolean lightOnly;
        /** "monet" or "custom" for a theme whose colours are made on the phone, null for the others. */
        public final String runtime;
        final JSONObject json;
        private final String key;

        Theme(JSONObject json) {
            this(json, null);
        }

        /** @param key What tells this theme's colours from the same theme's other colours, null for its id. */
        Theme(JSONObject json, String key) {
            this.json = json;
            id = json.optString("id");
            darkOnly = json.optBoolean("darkOnly");
            lightOnly = json.optBoolean("lightOnly");
            runtime = json.has("runtime") ? json.optString("runtime") : null;
            this.key = key != null ? key : id;
        }

        /**
         * The id of a shipped theme; for a theme made on the phone also its colours, so the resource table is built
         * again when they change.
         */
        public String key() {
            return key;
        }

        /** True for a theme whose colours are made on the phone (Monet, the custom theme). */
        public boolean isRuntime() {
            return runtime != null;
        }

        /** Corner radius of covers and cards in dp, the theme's own or SoundCloud's 6. */
        public int cardRadius() {
            JSONObject radii = json.optJSONObject("radii");
            return radii == null ? 6 : radii.optInt("card", 6);
        }

        /** In the app language of the moment. */
        public String name() {
            return localized("name");
        }

        public String description() {
            return localized("description");
        }

        private String localized(String field) {
            return json.optJSONObject(field).optString(Lang.russian() ? "ru" : "en");
        }

        /** A colour of the palette: "surface", "primary", "secondary", "highlight", "special", "dialog"... */
        public int color(boolean night, String role) {
            return ThemeColors.parse(json.optJSONObject(night ? "dark" : "light").optString(role));
        }

        /** A design token ("bg", "surface", "accent", "muted", "mini", "tabbar"...) of the dark or the light look. */
        public int token(boolean night, String name) {
            JSONObject tokens = json.optJSONObject(night || json.optJSONObject("tokensLight") == null ? "tokens" : "tokensLight");
            return ThemeColors.parse(tokens == null ? "" : tokens.optString(name));
        }

        /**
         * The value of a colour resource a runtime theme's files name (arsound_rt_*): a token with the opacity
         * in percent instead of its own (-1 keeps it). {@code dark} keeps the dark look in both modes.
         */
        int runtimeColor(boolean night, String token, int percent, boolean dark) {
            int color = token(night || dark, token);
            return percent < 0 ? color : (Math.round(percent * 2.55f) << 24) | (color & 0xFFFFFF);
        }

        public int accent(boolean night) {
            return color(night, "special");
        }

        public int surface(boolean night) {
            return color(night, "surface");
        }

        /** The animated background ({@link ThemeEffect}): the theme id unless themes.json names another. */
        public String effect() {
            return json.optString("effect", id);
        }
    }

    private static Context appContext;

    private ArsoundTheme() {
    }

    /** The themes to choose from, the runtime ones in their present colours; Monet is left out where it cannot be made. */
    public static List<Theme> all(Context context) {
        return ThemeCatalog.all(context);
    }

    /** A theme as themes.json has it (a runtime theme with its neutral seed colours). */
    static Theme seed(Context context, String id) {
        return ThemeCatalog.seed(context, id);
    }

    /**
     * Decoration of Arsound's own screens by the chosen theme ("decor" in themes.json), or null:
     * for example the colour strips of the settings rows and the greeting on the home screen.
     */
    public static JSONObject decor(Context context) {
        return ThemeDecor.of(currentTheme(context), isNight(context));
    }

    /** A colour of the decoration, or the fallback when the theme has none. */
    public static int decorColor(JSONObject decor, String name, int fallback) {
        return ThemeDecor.color(decor, name, fallback);
    }

    /**
     * Injection point: the dark veil SoundCloud lays over the "Your likes" bar on the home screen, as Compose's
     * ARGB long. A theme may set a lighter one ("shortcutScrim" in its decoration), so the bar shows its colours.
     */
    public static long shortcutScrim(long original) {
        try {
            JSONObject decor = appContext == null ? null : decor(appContext);
            String value = decor == null ? "" : decor.optString("shortcutScrim");
            if (value.startsWith("#")) return ThemeColors.parse(value) & 0xFFFFFFFFL;
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not read the shortcut veil", ex);
        }
        return original;
    }

    /**
     * Injection point: the icon in front of a row of the Library tab (SoundCloud shows none). A theme with the part
     * "libraryIcons" has one per row, named arsound_<theme>__arsound_library_<row> after the row's view id
     * library_header_<row>. 0 keeps the row without an icon.
     */
    public static int libraryRowIcon(View row) {
        try {
            if (row.getId() == View.NO_ID) return 0;
            return libraryRowIcon(row.getResources().getResourceEntryName(row.getId()).replace("library_header_", ""));
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: no library row icon", ex);
            return 0;
        }
    }

    /**
     * Injection point: the same for a row that is built without its view at hand.
     *
     * @param row The row as named in its view id library_header_<row>, for example "downloads".
     */
    public static int libraryRowIcon(String row) {
        try {
            Theme theme = appContext == null ? null : currentTheme(appContext);
            if (theme == null) return 0;
            return AppResources.identifier(appContext, "arsound_" + theme.id + "__arsound_library_" + row, "drawable");
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: no library row icon", ex);
            return 0;
        }
    }

    /**
     * Keeps an Arsound screen dark, like SoundCloud's own screens, which stay dark whatever the phone's mode.
     * Call from {@code attachBaseContext}, after the super call: the activity then reads night resources.
     */
    public static void forceDark(Activity activity, Context base) {
        // A light theme chosen by the user is light on Arsound's screens too.
        Theme theme = appContext == null ? null : currentTheme(appContext);
        if (theme != null && theme.lightOnly) return;
        NightMode.forceDark(activity, base);
    }

    /** True when the app draws its dark look now. */
    public static boolean isNight(Context context) {
        return NightMode.isNight(context);
    }

    /**
     * A colour of the chosen theme for Arsound's own elements, in the look drawn now: "surface", "primary",
     * "secondary", "highlight", "special" (the accent), "error", "dialog". SoundCloud's own colours for the
     * original look.
     */
    public static int palette(Context context, String role) {
        boolean night = isNight(context);
        Theme theme = currentTheme(context);
        if (theme != null) {
            JSONObject colors = theme.json.optJSONObject(night ? "dark" : "light");
            String value = colors == null ? "" : colors.optString(role);
            if (value.startsWith("#")) return ThemeColors.parse(value);
        }
        return ThemeColors.original(role, night);
    }

    /**
     * The surface and the accent of the chosen theme's dark look, whatever the look drawn now: for what is always
     * dark, like the notification picture. Null for the original look.
     */
    public static int[] darkColors(Context context) {
        Theme theme = currentTheme(context);
        return theme == null ? null : new int[]{theme.surface(true), theme.accent(true)};
    }

    /** The id of the chosen theme's animated background, or null: the theme id unless themes.json names another. */
    public static String effect(Context context) {
        Theme theme = currentTheme(context);
        return theme == null ? null : theme.effect();
    }

    /** A colour written as in themes.json: #rrggbb or #rrggbbaa. */
    public static int color(String hex) {
        return ThemeColors.parse(hex);
    }

    /** Whether the player is drawn in the theme's own style; applies after {@link #restartApp}. */
    public static boolean isPlayerSkinEnabled() {
        return Settings.getBoolean(PLAYER_SKIN_KEY, false);
    }

    /** The chosen theme id, {@link #SOUNDCLOUD} for the original look. */
    public static String current() {
        return Settings.getString(KEY, SOUNDCLOUD);
    }

    static Theme currentTheme(Context context) {
        return currentTheme(context, context.getResources());
    }

    /** @param resources Where a runtime theme reads the system palette, the newest the caller has. */
    static Theme currentTheme(Context context, Resources resources) {
        return ThemeCatalog.find(context, resources, current());
    }

    /**
     * Tells when the chosen theme's colours change: the id for the shipped themes, more for the ones made on the
     * phone. For screens that draw with the palette and must be drawn again when it changes.
     */
    public static String paletteKey(Context context) {
        Theme theme = currentTheme(context);
        return theme == null ? SOUNDCLOUD : theme.key();
    }

    /**
     * Chooses a theme. Its start screen is set right away (Android keeps it for the next starts); the colours
     * apply after {@link #restartApp}.
     */
    public static void setCurrent(Activity activity, String id) {
        Settings.putString(KEY, id);
        try {
            SplashStyle.apply(activity, ThemeCatalog.find(activity, activity.getResources(), id));
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not set the start screen", ex);
        }
    }

    /**
     * Restarts the app so every screen is drawn with the new theme. The colour tokens are read when screens
     * and Compose themes are created, so already open screens would keep a mix of the old and new colours.
     */
    public static void restartApp(Activity activity) {
        // The widget's heart may take the theme's accent, and the widget is not built again by the restart: until the
        // next track it would keep the old theme's colour. The app is still alive here, with the new theme chosen.
        WidgetHeart.refresh(activity);
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            activity.startActivity(launch);
            Runtime.getRuntime().exit(0);
        }, 400);
    }

    /** Injection point: the start of {@code Application.onCreate}. */
    public static void onApplicationCreate(Application application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        appContext = application;
        ThemeLifecycle.start(application);
    }
}
