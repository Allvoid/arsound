package app.revanced.extension.soundcloud.theme;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.content.res.loader.AssetsProvider;
import android.content.res.loader.ResourcesLoader;
import android.content.res.loader.ResourcesProvider;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Arsound's own looks: themes over SoundCloud's interface, switched at run time.
 * <p>
 * SoundCloud draws everything from a handful of colour tokens ({@code dark_mode_surface},
 * {@code light_mode_primary}, the orange accent...): views through theme attributes, Compose through the colour
 * resources themselves, and its text from a few font files. A {@link ResourcesLoader} with a small resource table
 * built on the phone ({@link ArscWriter}) overrides those tokens, the fonts, the artwork and mini player corners
 * and the loading animation (recoloured to the accent) for every lookup.
 * "SoundCloud" adds no loader and leaves the original look.
 * <p>
 * The themes are described in {@code assets/arsound/themes.json}, put there by the theme patch together with
 * the fonts and a start screen per theme. Two of them, Monet and the user's Custom theme, only have a seed there: their
 * colours are made on the phone ({@link RuntimeThemes}) and may change while the app runs, so a theme is told apart
 * by its {@link Theme#key()}, not only by its id.
 */
public final class ArsoundTheme {
    public static final String KEY = "arsound_theme";
    public static final String SOUNDCLOUD = "soundcloud";
    /** Set while the app's dark mode is forced on by a dark-only theme. */
    private static final String FORCED_NIGHT_KEY = "arsound_theme_forced_night";

    private static final String ASSETS = "arsound/";
    /** Read each time: the app language can change while the app runs (the welcome switch). */
    private static boolean russian() {
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    /** Palette roles and the SoundCloud colour token each one replaces (dark_mode_X and light_mode_X). */
    private static final String[][] TOKENS = {
            {"surface", "surface"}, {"primary", "primary"}, {"secondary", "secondary"}, {"highlight", "highlight"},
            {"error", "error"}, {"overlay", "overlay"}, {"imageBorders", "image_borders"},
    };
    /** Font slots and SoundCloud's font files. */
    private static final String[][] FONTS = {
            {"regular", "soehne_regular_400"}, {"semibold", "soehne_semi_bold_600"}, {"bold", "soehne_bold_900"},
            {"extrabold", "soehne_extrafett_900"}, {"numbers", "roboto_medium_numbers"},
    };

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

        /** Corner radius of covers and cards in dp, the theme's own or SoundCloud's 6. */
        public int cardRadius() {
            JSONObject radii = json.optJSONObject("radii");
            return radii == null ? 6 : radii.optInt("card", 6);
        }

        /** In the app language of the moment. */
        public String name() {
            return json.optJSONObject("name").optString(russian() ? "ru" : "en");
        }

        public String description() {
            return json.optJSONObject("description").optString(russian() ? "ru" : "en");
        }

        /** A colour of the palette: "surface", "primary", "secondary", "highlight", "special", "dialog"... */
        public int color(boolean night, String role) {
            return parse(json.optJSONObject(night ? "dark" : "light").optString(role));
        }

        /** A design token ("bg", "surface", "accent", "muted", "mini", "tabbar"...) of the dark or the light look. */
        public int token(boolean night, String name) {
            JSONObject tokens = json.optJSONObject(night || json.optJSONObject("tokensLight") == null ? "tokens" : "tokensLight");
            return parse(tokens == null ? "" : tokens.optString(name));
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

    private static int parse(String hex) {
        // A role missing from themes.json gives an empty string; a pink placeholder is better than no theme at all.
        if (hex == null || hex.length() < 2) return 0xFFFF00FF;
        String value = hex.substring(1);
        // #rrggbbaa as in the design, to Android's aarrggbb.
        if (value.length() == 8) value = value.substring(6) + value.substring(0, 6);
        else value = "ff" + value;
        return (int) Long.parseLong(value, 16);
    }

    /** The themes as themes.json has them: the runtime ones only as seeds. */
    private static volatile List<Theme> themes;
    private static volatile ResourcesLoader loader;
    /** The table the loader has now, to close it when the palette changes and a new one takes its place. */
    private static ResourcesProvider loaded;
    /** The {@link Theme#key()} the loader's table was built for. */
    private static volatile String loaderKey;
    private static Context appContext;

    private ArsoundTheme() {
    }

    /** The themes to choose from, the runtime ones in their present colours; Monet is left out where it cannot be made. */
    public static List<Theme> all(Context context) {
        List<Theme> result = new ArrayList<>();
        for (Theme theme : seeds(context)) {
            if (theme.runtime == null) {
                result.add(theme);
                continue;
            }
            Theme resolved = RuntimeThemes.resolve(context.getResources(), theme);
            if (resolved != null) result.add(resolved);
        }
        return Collections.unmodifiableList(result);
    }

    private static List<Theme> seeds(Context context) {
        List<Theme> result = themes;
        if (result != null) return result;
        result = new ArrayList<>();
        try (InputStream input = context.getAssets().open(ASSETS + "themes.json")) {
            JSONArray list = new JSONObject(new String(readAll(input), "UTF-8")).getJSONArray("themes");
            for (int i = 0; i < list.length(); i++) result.add(new Theme(list.getJSONObject(i)));
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not read the themes", ex);
        }
        themes = result = Collections.unmodifiableList(result);
        return result;
    }

    /** A theme as themes.json has it (a runtime theme with its neutral seed colours). */
    static Theme seed(Context context, String id) {
        for (Theme theme : seeds(context)) if (theme.id.equals(id)) return theme;
        throw new IllegalArgumentException("No theme " + id);
    }

    /**
     * Decoration of Arsound's own screens by the chosen theme ("decor" in themes.json), or null:
     * for example the colour strips of the settings rows and the greeting on the home screen.
     */
    public static JSONObject decor(Context context) {
        Theme theme = currentTheme(context);
        JSONObject decor = theme == null ? null : theme.json.optJSONObject("decor");
        // In the light look the values of "decorLight" win.
        JSONObject light = theme == null ? null : theme.json.optJSONObject("decorLight");
        boolean night = (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        if (decor == null || light == null || night) return decor;
        try {
            JSONObject merged = new JSONObject(decor.toString());
            for (java.util.Iterator<String> keys = light.keys(); keys.hasNext(); ) {
                String key = keys.next();
                merged.put(key, light.get(key));
            }
            return merged;
        } catch (Exception ex) {
            return decor;
        }
    }

    /** A colour of the decoration, or the fallback when the theme has none. */
    public static int decorColor(JSONObject decor, String name, int fallback) {
        String value = decor == null ? "" : decor.optString(name);
        return value.startsWith("#") ? parse(value) : fallback;
    }

    /**
     * Injection point: the dark veil SoundCloud lays over the "Your likes" bar on the home screen, as Compose's
     * ARGB long. A theme may set a lighter one ("shortcutScrim" in its decoration), so the bar shows its colours.
     */
    public static long shortcutScrim(long original) {
        try {
            JSONObject decor = appContext == null ? null : decor(appContext);
            String value = decor == null ? "" : decor.optString("shortcutScrim");
            if (value.startsWith("#")) return parse(value) & 0xFFFFFFFFL;
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
    public static int libraryRowIcon(android.view.View row) {
        try {
            if (row.getId() == android.view.View.NO_ID) return 0;
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
            Resources resources = appContext.getResources();
            String icon = "arsound_" + theme.id + "__arsound_library_" + row;
            int id = resources.getIdentifier(icon, "drawable", appContext.getPackageName());
            return id != 0 ? id : resources.getIdentifier(icon, "drawable", "com.soundcloud.android");
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
        android.content.res.Configuration override = new android.content.res.Configuration();
        int mode = base.getResources().getConfiguration().uiMode;
        override.uiMode = (mode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        activity.applyOverrideConfiguration(override);
    }

    /** True when the app draws its dark look now. */
    public static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
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
            if (value.startsWith("#")) return parse(value);
        }
        switch (role) {
            case "surface":
                return night ? 0xFF121212 : 0xFFFFFFFF;
            case "primary":
                return night ? 0xFFFFFFFF : 0xFF121212;
            case "secondary":
                return night ? 0xFF999999 : 0xFF666666;
            case "highlight":
                return night ? 0xFF2A2A2A : 0xFFF2F2F2;
            case "error":
                return night ? 0xFFFF6B6B : 0xFFD32F2F;
            case "dialog":
                return night ? 0xFF1E1E1E : 0xFFFFFFFF;
            default:
                return 0xFFFF5500;
        }
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
        return parse(hex);
    }

    /** The setting of the new player: the "playerSkin" part of the themes, a beta that is off by default. */
    public static final String PLAYER_SKIN_KEY = "arsound_player_skin";
    private static final String PLAYER_SKIN_PART = "playerSkin";

    /** Whether the player is drawn in the theme's own style; applies after {@link #restartApp}. */
    public static boolean isPlayerSkinEnabled() {
        return Settings.getBoolean(PLAYER_SKIN_KEY, false);
    }

    /** The chosen theme id, {@link #SOUNDCLOUD} for the original look. */
    public static String current() {
        return Settings.getString(KEY, SOUNDCLOUD);
    }

    private static Theme currentTheme(Context context) {
        return currentTheme(context, context.getResources());
    }

    /** @param resources Where a runtime theme reads the system palette, the newest the caller has. */
    private static Theme currentTheme(Context context, Resources resources) {
        String id = current();
        for (Theme theme : seeds(context)) {
            if (!theme.id.equals(id)) continue;
            return theme.runtime == null ? theme : RuntimeThemes.resolve(resources, theme);
        }
        return null;
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        try {
            int style = 0;
            if (!SOUNDCLOUD.equals(id)) {
                style = activity.getResources().getIdentifier("Arsound.Splash." + id, "style", activity.getPackageName());
                if (style == 0) {
                    style = activity.getResources().getIdentifier("Arsound.Splash." + id, "style", "com.soundcloud.android");
                }
            }
            activity.getSplashScreen().setSplashScreenTheme(style);
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not set the start screen", ex);
        }
    }

    /**
     * Restarts the app so every screen is drawn with the new theme. The colour tokens are read when screens
     * and Compose themes are created, so already open screens would keep a mix of the old and new colours.
     */
    public static void restartApp(Activity activity) {
        android.content.Intent launch = activity.getPackageManager().getLaunchIntentForPackage(activity.getPackageName());
        if (launch == null) return;
        launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK);
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            activity.startActivity(launch);
            Runtime.getRuntime().exit(0);
        }, 400);
    }

    // region Applying

    /** Injection point: the start of {@code Application.onCreate}. */
    public static void onApplicationCreate(Application application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        appContext = application;
        try {
            applyNightMode(application, currentTheme(application));
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not set the dark mode", ex);
        }
        try {
            apply(application.getResources());
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not apply on start", ex);
        }
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                try {
                    apply(activity.getResources());
                    appliedKeys.put(activity, loaderKey);
                } catch (Throwable ex) {
                    Logger.printException(() -> "Theme: could not apply to " + activity, ex);
                }
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
            }
        });
    }

    /**
     * Keeps the app dark for a dark-only theme (or light for a light-only one), through Android's own per-app dark
     * mode (Android 12 and later): every screen, the status bar and SoundCloud's night resources then follow it.
     * Android remembers the mode, so it is given back to the phone's setting once, when another theme is chosen.
     */
    private static void applyNightMode(Context context, Theme theme) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        String wanted = theme == null ? "" : theme.darkOnly ? "yes" : theme.lightOnly ? "no" : "";
        if (wanted.equals(Settings.getString(FORCED_NIGHT_KEY, ""))) return;
        android.app.UiModeManager modes = context.getSystemService(android.app.UiModeManager.class);
        if (modes == null) return;
        modes.setApplicationNightMode("yes".equals(wanted) ? android.app.UiModeManager.MODE_NIGHT_YES
                : "no".equals(wanted) ? android.app.UiModeManager.MODE_NIGHT_NO : android.app.UiModeManager.MODE_NIGHT_AUTO);
        Settings.putString(FORCED_NIGHT_KEY, wanted);
    }

    /** How often the build of a theme is tried in one process; a failure that stays would be repeated for every screen. */
    private static final int MAX_BUILD_ATTEMPTS = 2;
    private static String failedKey;
    private static int failedBuilds;

    /** The theme each live activity was given at its start, to tell one that missed a change of the palette. */
    private static final java.util.Map<Activity, String> appliedKeys =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    /** The palette an activity was last recreated for, so a change is followed once and never in a loop. */
    private static String recreatedFor;

    /**
     * Adds the loader of the chosen theme to these resources; nothing for the original look. When the theme's colours
     * changed since the table was built (the wallpaper changed Monet's palette), the table is built again and put
     * into the same loader, which updates every screen that already has it.
     */
    private static synchronized void apply(Resources resources) throws Exception {
        Theme theme = currentTheme(appContext, resources);
        if (theme == null) return;
        if (loader == null || !theme.key().equals(loaderKey)) {
            // Each try is work on the main thread that gives no theme anyway; a later start tries again.
            if (theme.key().equals(failedKey) && failedBuilds >= MAX_BUILD_ATTEMPTS) return;
            ResourcesProvider provider;
            try {
                provider = provider(theme);
            } catch (Throwable ex) {
                failedBuilds = theme.key().equals(failedKey) ? failedBuilds + 1 : 1;
                failedKey = theme.key();
                throw ex;
            }
            failedKey = null;
            failedBuilds = 0;
            ResourcesProvider replaced = loaded;
            if (loader == null) {
                loader = new ResourcesLoader();
                loader.addProvider(provider);
            } else {
                loader.setProviders(Collections.singletonList(provider));
            }
            loaded = provider;
            loaderKey = theme.key();
            // The loader no longer uses the old table, but the descriptor and the mapping of it stay until the provider
            // is closed: one would be left behind for every change of the palette.
            if (replaced != null) {
                try {
                    replaced.close();
                } catch (Exception ex) {
                    Logger.printException(() -> "Theme: could not close the replaced resource table", ex);
                }
                // The table was swapped in a running process: the spinner may have been read already.
                forgetLottieAnimations();
            }
        }
        // Adding a loader that is already there changes nothing.
        resources.addLoaders(loader);
    }

    /**
     * Lottie keeps the animations it has read (the loading spinner among them) in a cache of its own, by resource id,
     * so after the palette changed in a running process the spinner would keep the old accent until the process ends.
     * The cache is emptied; the next spinner is read again from the new table. The fields of the cache have one-letter
     * names in SoundCloud's build, so they are found by their type, not by name.
     */
    private static void forgetLottieAnimations() {
        try {
            Class<?> cacheClass = Class.forName("com.airbnb.lottie.model.LottieCompositionCache");
            Class<?> lruClass = Class.forName("androidx.collection.LruCache");
            Object cache = null;
            for (java.lang.reflect.Field field : cacheClass.getDeclaredFields()) {
                if (field.getType() != cacheClass || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                cache = field.get(null);
                break;
            }
            if (cache == null) {
                Logger.printInfo(() -> "Theme: Lottie's cache was not found, the spinner keeps its colour until a restart");
                return;
            }
            for (java.lang.reflect.Field field : cacheClass.getDeclaredFields()) {
                if (field.getType() != lruClass || java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object animations = field.get(cache);
                if (animations != null) lruClass.getMethod("evictAll").invoke(animations);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not empty Lottie's cache", ex);
        }
    }

    /**
     * A screen that came back after the wallpaper changed Monet's colours without being recreated by the system:
     * the table is brought up to date (for every screen) and this one is recreated once to draw with it.
     */
    private static void refreshIfStale(Activity activity) {
        try {
            Theme theme = currentTheme(appContext, activity.getResources());
            if (theme == null || theme.runtime == null) return;
            String applied = appliedKeys.get(activity);
            if (applied == null || applied.equals(theme.key()) || theme.key().equals(recreatedFor)) return;
            recreatedFor = theme.key();
            apply(activity.getResources());
            appliedKeys.put(activity, loaderKey);
            activity.recreate();
        } catch (Throwable ex) {
            Logger.printException(() -> "Theme: could not follow the new palette", ex);
        }
    }

    private static ResourcesProvider provider(Theme theme) throws Exception {
        Context context = appContext;
        // The colours of a runtime theme are part of the file name: the table of the old colours may still be
        // mapped by the screens that were drawn with it, and rewriting a mapped file in place could crash them.
        String suffix = theme.runtime == null ? "" : "-" + Integer.toHexString(theme.key().hashCode());
        File file = new File(context.getCacheDir(), "arsound-theme-" + theme.id + suffix + ".arsc");
        // The resource table and the files it names are the same until the app is updated (or the player skin is
        // switched), so they are built once, not at every cold start. The stamp is written last: a build that
        // stopped half way leaves no stamp, and its files are not trusted.
        File stamp = new File(context.getFilesDir(), ASSETS + "theme/" + theme.id + ".stamp");
        String key = cacheKey(context);
        if (key != null && theme.runtime != null) key += "/" + theme.key();
        if (key != null && file.isFile() && key.equals(readText(stamp))) {
            try {
                return load(context, file);
            } catch (Exception ex) {
                // A damaged table is built again, not refused at every start.
                Logger.printException(() -> "Theme: the kept resource table was refused, building it again", ex);
            }
        }
        stamp.delete();
        build(context, theme, file);
        if (theme.runtime != null) removeOldTables(context, theme, file);
        if (key != null) {
            try {
                write(stamp, key.getBytes("UTF-8"));
            } catch (Exception ex) {
                Logger.printException(() -> "Theme: could not remember the built theme", ex);
            }
        }
        return load(context, file);
    }

    /** Deletes the tables of the earlier colours of a runtime theme; a screen that still maps one keeps working. */
    private static void removeOldTables(Context context, Theme theme, File current) {
        File[] files = context.getCacheDir().listFiles();
        if (files == null) return;
        String prefix = "arsound-theme-" + theme.id + "-";
        for (File file : files) {
            if (file.getName().startsWith(prefix) && file.getName().endsWith(".arsc") && !file.equals(current)) file.delete();
        }
    }

    /** What the built files depend on: the installed app (the themes ship inside it) and the player skin switch. */
    private static String cacheKey(Context context) {
        try {
            long updated = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            return updated + "/" + isPlayerSkinEnabled();
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not read when the app was installed", ex);
            return null;
        }
    }

    /** Builds the resource table of a theme into {@code arsc}, with the fonts and the loading animation it names. */
    private static void build(Context context, Theme theme, File arsc) throws Exception {
        // Built before any loader is added, so the original loading animation is read, not a recoloured one.
        Resources resources = context.getResources();
        // The resources keep SoundCloud's package name although the app is installed under another one.
        String packageName = context.getPackageName();
        int anyId = resources.getIdentifier("dark_mode_surface", "color", packageName);
        if (anyId == 0) {
            packageName = "com.soundcloud.android";
            anyId = resources.getIdentifier("dark_mode_surface", "color", packageName);
        }
        if (anyId == 0) throw new IllegalStateException("SoundCloud colour tokens not found");
        ArscWriter table = new ArscWriter(anyId >>> 24, resources.getResourcePackageName(anyId));
        Ids ids = new Ids(resources, packageName);

        for (String[] token : TOKENS) {
            ids.color(table, "dark_mode_" + token[1], theme.color(true, token[0]), null);
            ids.color(table, "light_mode_" + token[1], theme.color(false, token[0]), null);
        }
        ids.color(table, "dialog_dark", theme.color(true, "dialog"), null);
        // The accent is one colour for both modes in SoundCloud; the night value overrides it in dark mode.
        ids.color(table, "shared_colors_special_action", theme.accent(false), theme.accent(true));

        // Any other SoundCloud colour by name, such as the grey scale behind cards and the mini player's gradient.
        JSONObject colors = theme.json.optJSONObject("colors");
        if (colors != null) {
            for (java.util.Iterator<String> names = colors.keys(); names.hasNext(); ) {
                String name = names.next();
                int color = parse(colors.optString(name));
                ids.color(table, name, color, color);
            }
        }

        // Whole resources (drawables, colour lists, layouts): the theme's own copy, put into the app by the
        // theme patch as arsound_<theme>__<name> and listed in theme-resources.json, stands in for SoundCloud's.
        // Files whose name starts with "arsound_" are the theme's additions, used by its other files.
        JSONArray replaced = null;
        JSONArray runtimeColors = null;
        java.util.Set<String> switchedOff = new java.util.HashSet<>();
        try (InputStream input = context.getAssets().open(ASSETS + "theme-resources.json")) {
            JSONObject index = new JSONObject(new String(readAll(input), "UTF-8"));
            replaced = index.optJSONArray(theme.id);
            runtimeColors = index.optJSONArray("_runtimeColors");
            // Parts switched on in the settings ("_optional": part -> its resources) stay out while off.
            JSONObject optional = index.optJSONObject("_optional");
            JSONArray playerSkin = optional == null ? null : optional.optJSONArray(PLAYER_SKIN_PART);
            if (playerSkin != null && !isPlayerSkinEnabled()) {
                for (int i = 0; i < playerSkin.length(); i++) switchedOff.add(playerSkin.optString(i));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not read the theme resources", ex);
        }
        // The files of a runtime theme name colour resources of the app (arsound_rt_*) instead of holding colours; they
        // get the theme's tokens here, in the day and the night value.
        if (theme.runtime != null && runtimeColors != null) {
            for (int i = 0; i < runtimeColors.length(); i++) {
                JSONArray entry = runtimeColors.optJSONArray(i);
                if (entry == null) continue;
                String token = entry.optString(1);
                int percent = entry.optInt(2, -1);
                boolean dark = entry.optBoolean(3);
                ids.color(table, entry.optString(0), theme.runtimeColor(false, token, percent, dark),
                        theme.runtimeColor(true, token, percent, dark));
            }
        }
        if (replaced != null) {
            for (int i = 0; i < replaced.length(); i++) {
                if (switchedOff.contains(replaced.optString(i))) continue;
                String[] typeAndName = replaced.optString(i).split("/", 2);
                if (typeAndName.length != 2 || typeAndName[1].startsWith("arsound_")) continue;
                ids.alias(table, typeAndName[0], typeAndName[1], "arsound_" + theme.id + "__" + typeAndName[1]);
            }
        }

        JSONObject radii = theme.json.optJSONObject("radii");
        if (radii != null) {
            int card = radii.optInt("card", 6);
            ids.dimen(table, "artwork_corner_radius_default", card);
            ids.dimen(table, "artwork_corner_radius_large", card + 4);
            ids.dimen(table, "artwork_corner_radius_medium", Math.max(2, card - 2));
            ids.dimen(table, "mini_player_corner", radii.optInt("miniPlayer", 36));
        }

        // Fonts: files from the app's assets, given to Android through the provider below.
        JSONObject fonts = theme.json.optJSONObject("fonts");
        if (fonts != null) {
            for (String[] slot : FONTS) {
                String file = fonts.optString(slot[0], "");
                if (file.isEmpty()) continue;
                String path = "arsound/fonts/" + file + ".ttf";
                // Android loads a font resource only from a path under res/.
                if (copyAsset(context, path, new File(context.getFilesDir(), path))) ids.file(table, "font", slot[1], "res/" + path);
            }
        }

        // The loading animation: the drawing letter in the accent.
        Originals originals = new Originals(context);
        int animation = resources.getIdentifier("loading_animation", "raw", packageName);
        if (animation != 0) {
            byte[] original = originals.loadingAnimation(resources, animation);
            String day = "arsound/theme/" + theme.id + "-loading.json";
            String night = "arsound/theme/" + theme.id + "-loading-night.json";
            write(new File(context.getFilesDir(), day), tintLottie(original, theme.accent(false)));
            write(new File(context.getFilesDir(), night), tintLottie(original, theme.accent(true)));
            table.put(animation, "raw", "loading_animation", ArscWriter.Value.file(day), false);
            table.put(animation, "raw", "loading_animation", ArscWriter.Value.file(night), true);
        }

        // The pictures behind page titles (the hearts of "Your likes"), recoloured into the accent. Two of them have
        // their own picture for wide screens (tablets, a phone on its side), which would win over a default-only
        // copy: each is read at a narrow and a wide width, whatever the screen is now.
        Resources narrow = resourcesAtWidth(context, 320);
        Resources wide = resourcesAtWidth(context, ArscWriter.WIDE_DP);
        for (String name : HeaderArtwork.NAMES) {
            int picture = resources.getIdentifier(name, "drawable", packageName);
            if (picture == 0) {
                Logger.printInfo(() -> "Theme: no drawable " + name);
                continue;
            }
            try {
                tintPicture(context, table, theme, originals, narrow, picture, name, false);
                if (!sameFile(narrow, wide, picture)) tintPicture(context, table, theme, originals, wide, picture, name, true);
            } catch (Exception ex) {
                Logger.printException(() -> "Theme: could not recolour " + name, ex);
            }
        }

        write(arsc, table.build());
    }

    /** One picture behind a page title in the accent of day and night, for a narrow or a wide screen. */
    private static void tintPicture(Context context, ArscWriter table, Theme theme, Originals originals,
                                    Resources source, int picture, String name, boolean wide) throws Exception {
        byte[] original = originals.picture(source, picture, name, wide);
        if (original == null) return;
        byte[] day = HeaderArtwork.tint(original, theme.accent(false));
        byte[] night = HeaderArtwork.tint(original, theme.accent(true));
        if (day == null || night == null) return;
        // A path under res/, as for the fonts; the provider below reads it from the files folder.
        String base = "arsound/theme/" + theme.id + "-" + name + (wide ? "-wide" : "");
        String dayPath = base + ".png";
        String nightPath = base + "-night.png";
        write(new File(context.getFilesDir(), dayPath), day);
        write(new File(context.getFilesDir(), nightPath), night);
        table.put(picture, "drawable", name, ArscWriter.Value.file("res/" + dayPath), false, wide);
        table.put(picture, "drawable", name, ArscWriter.Value.file("res/" + nightPath), true, wide);
    }

    /** The app's resources as on a screen this many dp wide. */
    private static Resources resourcesAtWidth(Context context, int widthDp) {
        android.content.res.Configuration configuration =
                new android.content.res.Configuration(context.getResources().getConfiguration());
        configuration.screenWidthDp = widthDp;
        return context.createConfigurationContext(configuration).getResources();
    }

    /** True if the resource is the same file on both resources: the app has no picture of its own for one of them. */
    private static boolean sameFile(Resources first, Resources second, int resource) {
        android.util.TypedValue a = new android.util.TypedValue();
        android.util.TypedValue b = new android.util.TypedValue();
        first.getValue(resource, a, true);
        second.getValue(resource, b, true);
        return String.valueOf(a.string).equals(String.valueOf(b.string));
    }

    private static ResourcesProvider load(Context context, File file) throws Exception {
        File filesDir = context.getFilesDir();
        try (ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) {
            return ResourcesProvider.loadFromTable(descriptor, new AssetsProvider() {
                @Override
                public AssetFileDescriptor loadAssetFd(String path, int accessMode) {
                    File asset = new File(filesDir, path.startsWith("res/arsound/") ? path.substring(4) : path);
                    if (!asset.isFile()) return null;
                    try {
                        return new AssetFileDescriptor(
                                ParcelFileDescriptor.open(asset, ParcelFileDescriptor.MODE_READ_ONLY), 0, asset.length());
                    } catch (Exception ex) {
                        return null;
                    }
                }
            });
        }
    }

    /**
     * The pictures a theme recolours (the loading animation, the hearts behind page titles) as the app ships them.
     * Once a loader is attached, every read of those resources gives the recoloured copy of the theme that is on, and
     * tinting that again would darken it. So the first build of an app version, which runs before any loader exists in
     * the process, keeps the originals in files, and a later build in the same process (the palette of Monet changed)
     * tints those.
     */
    private static final class Originals {
        private final File dir;
        /** True while no loader was added in this process: the resources still show the app's own files. */
        private final boolean pristine = loader == null;

        Originals(Context context) {
            long installed = 0;
            try {
                installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            } catch (Exception ex) {
                Logger.printException(() -> "Theme: could not read when the app was installed", ex);
            }
            File parent = new File(context.getFilesDir(), ASSETS + "theme");
            dir = new File(parent, "orig-" + installed);
            // The copies of an older version of the app are no use.
            File[] others = parent.listFiles((folder, name) -> name.startsWith("orig-") && !name.equals(dir.getName()));
            if (others != null) {
                for (File other : others) {
                    File[] files = other.listFiles();
                    if (files != null) for (File file : files) file.delete();
                    other.delete();
                }
            }
        }

        byte[] loadingAnimation(Resources resources, int animation) throws Exception {
            File file = new File(dir, "loading_animation.json");
            byte[] kept = kept(file, true);
            if (kept != null) return kept;
            byte[] bytes = readAll(resources.openRawResource(animation));
            keep(file, bytes);
            return bytes;
        }

        /** The picture as PNG bytes, for a narrow or a wide screen; null if it could not be read. */
        byte[] picture(Resources source, int picture, String name, boolean wide) throws Exception {
            File file = new File(dir, name + (wide ? "-wide" : "") + ".png");
            byte[] kept = kept(file, false);
            if (kept != null) return kept;
            byte[] bytes = HeaderArtwork.original(source, picture);
            if (bytes != null) keep(file, bytes);
            return bytes;
        }

        /**
         * The kept copy, or null when there is none or it is damaged (a write that stopped half way): a damaged copy
         * is removed, so that it is not trusted at every build until the app is updated, and the original is read
         * again from the app.
         */
        private byte[] kept(File file, boolean animation) {
            if (!file.isFile()) return null;
            try {
                byte[] bytes = readAll(new FileInputStream(file));
                if (animation ? isLottie(bytes) : isPng(bytes)) return bytes;
            } catch (Exception ex) {
                Logger.printInfo(() -> "Theme: the kept original " + file.getName() + " could not be read");
            }
            Logger.printInfo(() -> "Theme: the kept original " + file.getName() + " is damaged, reading it again");
            file.delete();
            return null;
        }

        /** True if this is a whole Lottie file: a cut-off one does not parse (it throws). */
        private static boolean isLottie(byte[] bytes) throws Exception {
            new JSONObject(new String(bytes, "UTF-8"));
            return true;
        }

        /** True if the PNG ends with its closing chunk, which the encoder writes last (4 bytes of length, "IEND", 4 of checksum). */
        private static boolean isPng(byte[] bytes) {
            int end = bytes.length - 8;
            return end >= 8 && bytes[end] == 'I' && bytes[end + 1] == 'E' && bytes[end + 2] == 'N' && bytes[end + 3] == 'D';
        }

        private void keep(File file, byte[] bytes) {
            if (!pristine) {
                Logger.printInfo(() -> "Theme: no kept original of " + file.getName() + ", reading it from the themed app");
                return;
            }
            // Written aside and renamed, so a stop half way (no space left, the process killed) never leaves a cut-off
            // copy that the next builds would take for the original.
            File aside = new File(dir, file.getName() + ".tmp");
            try {
                write(aside, bytes);
                if (!aside.renameTo(file)) throw new java.io.IOException("Could not rename " + aside.getName());
            } catch (Exception ex) {
                aside.delete();
                Logger.printException(() -> "Theme: could not keep the original " + file.getName(), ex);
            }
        }
    }

    /** Looks up SoundCloud's resource ids by name and adds overrides for them. */
    private static final class Ids {
        final Resources resources;
        final String packageName;

        Ids(Resources resources, String packageName) {
            this.resources = resources;
            this.packageName = packageName;
        }

        int id(String type, String name) {
            int id = resources.getIdentifier(name, type, packageName);
            if (id == 0) Logger.printInfo(() -> "Theme: no " + type + " " + name);
            return id;
        }

        void color(ArscWriter table, String name, int normal, Integer night) {
            int id = id("color", name);
            if (id == 0) return;
            table.put(id, "color", name, ArscWriter.Value.color(normal), false);
            if (night != null) table.put(id, "color", name, ArscWriter.Value.color(night), true);
        }

        void dimen(ArscWriter table, String name, int dp) {
            int id = id("dimen", name);
            if (id != 0) table.put(id, "dimen", name, ArscWriter.Value.dp(dp), false);
        }

        /** Points SoundCloud's resource at another one of the same type, in both modes. */
        void alias(ArscWriter table, String type, String name, String replacement) {
            int id = id(type, name);
            int target = id(type, replacement);
            if (id == 0 || target == 0) return;
            table.put(id, type, name, ArscWriter.Value.reference(target), false);
            table.put(id, type, name, ArscWriter.Value.reference(target), true);
        }

        void file(ArscWriter table, String type, String name, String path) {
            int id = id(type, name);
            if (id != 0) table.put(id, type, name, ArscWriter.Value.file(path), false);
        }
    }

    // endregion

    // region Files

    /**
     * The loading animation (Lottie) shows the letter as white image frames; each frame is recoloured,
     * keeping its transparency.
     */
    private static byte[] tintLottie(byte[] json, int color) throws Exception {
        JSONObject animation = new JSONObject(new String(json, "UTF-8"));
        JSONArray assets = animation.optJSONArray("assets");
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        String prefix = "data:image/png;base64,";
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            String data = asset.optString("p");
            if (!data.startsWith(prefix)) continue;
            byte[] png = Base64.decode(data.substring(prefix.length()), Base64.DEFAULT);
            Bitmap frame = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (frame == null) continue;
            Bitmap tinted = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
            new Canvas(tinted).drawBitmap(frame, 0, 0, paint);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            tinted.compress(Bitmap.CompressFormat.PNG, 100, output);
            asset.put("p", prefix + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP));
            frame.recycle();
            tinted.recycle();
        }
        return animation.toString().getBytes("UTF-8");
    }

    /** Copies an asset to a file once; the file is kept while it has the same size. */
    private static boolean copyAsset(Context context, String asset, File target) {
        try {
            // The size is known without reading the asset, unless the APK keeps it compressed.
            long size = assetSize(context, asset);
            if (size >= 0 && target.isFile() && target.length() == size) return true;
            try (InputStream input = context.getAssets().open(asset)) {
                byte[] bytes = readAll(input);
                if (!target.isFile() || target.length() != bytes.length) write(target, bytes);
            }
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not copy " + asset, ex);
            return false;
        }
    }

    /** The size of an asset, or -1 when it cannot be told without reading it (a compressed asset has no file descriptor). */
    private static long assetSize(Context context, String asset) {
        try (AssetFileDescriptor descriptor = context.getAssets().openFd(asset)) {
            return descriptor.getLength();
        } catch (Exception ex) {
            return -1;
        }
    }

    /** The text of a small file, or null when there is none. */
    private static String readText(File file) {
        try {
            return new String(readAll(new FileInputStream(file)), "UTF-8");
        } catch (Exception ex) {
            return null;
        }
    }

    private static void write(File file, byte[] bytes) throws Exception {
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }

    private static byte[] readAll(InputStream stream) throws Exception {
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    // endregion
}
