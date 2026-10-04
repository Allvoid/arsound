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
 * the fonts and a start screen per theme.
 */
public final class ArsoundTheme {
    public static final String KEY = "arsound_theme";
    public static final String SOUNDCLOUD = "soundcloud";
    /** Set while the app's dark mode is forced on by a dark-only theme. */
    private static final String FORCED_NIGHT_KEY = "arsound_theme_forced_night";

    private static final String ASSETS = "arsound/";
    private static final boolean RUSSIAN = "ru".equals(Locale.getDefault().getLanguage());

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
        public final String name;
        public final String description;
        /** The app stays dark whatever the phone's mode: the design has no light version. */
        public final boolean darkOnly;
        final JSONObject json;

        Theme(JSONObject json) {
            this.json = json;
            id = json.optString("id");
            darkOnly = json.optBoolean("darkOnly");
            name = json.optJSONObject("name").optString(RUSSIAN ? "ru" : "en");
            description = json.optJSONObject("description").optString(RUSSIAN ? "ru" : "en");
        }

        int color(boolean night, String role) {
            return parse(json.optJSONObject(night ? "dark" : "light").optString(role));
        }

        public int accent(boolean night) {
            return color(night, "special");
        }

        public int surface(boolean night) {
            return color(night, "surface");
        }
    }

    private static int parse(String hex) {
        String value = hex.substring(1);
        // #rrggbbaa as in the design, to Android's aarrggbb.
        if (value.length() == 8) value = value.substring(6) + value.substring(0, 6);
        else value = "ff" + value;
        return (int) Long.parseLong(value, 16);
    }

    private static volatile List<Theme> themes;
    private static volatile ResourcesLoader loader;
    private static volatile String loaderTheme;
    private static Context appContext;

    private ArsoundTheme() {
    }

    public static List<Theme> all(Context context) {
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

    /**
     * Decoration of Arsound's own screens by the chosen theme ("decor" in themes.json), or null:
     * for example the colour strips of the settings rows and the greeting on the home screen.
     */
    public static JSONObject decor(Context context) {
        Theme theme = currentTheme(context);
        return theme == null ? null : theme.json.optJSONObject("decor");
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

    /** A colour written as in themes.json: #rrggbb or #rrggbbaa. */
    public static int color(String hex) {
        return parse(hex);
    }

    /** The chosen theme id, {@link #SOUNDCLOUD} for the original look. */
    public static String current() {
        return Settings.getString(KEY, SOUNDCLOUD);
    }

    private static Theme currentTheme(Context context) {
        String id = current();
        for (Theme theme : all(context)) if (theme.id.equals(id)) return theme;
        return null;
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
     * Keeps the app dark for a dark-only theme, through Android's own per-app dark mode (Android 12 and later):
     * every screen, the status bar and SoundCloud's night resources then follow it. Android remembers the mode,
     * so it is given back to the phone's setting once, when another theme is chosen.
     */
    private static void applyNightMode(Context context, Theme theme) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        boolean dark = theme != null && theme.darkOnly;
        boolean forced = !Settings.getString(FORCED_NIGHT_KEY, "").isEmpty();
        if (dark == forced) return;
        android.app.UiModeManager modes = context.getSystemService(android.app.UiModeManager.class);
        if (modes == null) return;
        modes.setApplicationNightMode(dark ? android.app.UiModeManager.MODE_NIGHT_YES : android.app.UiModeManager.MODE_NIGHT_AUTO);
        Settings.putString(FORCED_NIGHT_KEY, dark ? "yes" : "");
    }

    /** Adds the loader of the chosen theme to these resources; nothing for the original look. */
    private static synchronized void apply(Resources resources) throws Exception {
        Theme theme = currentTheme(appContext);
        if (theme == null) return;
        if (loader == null || !theme.id.equals(loaderTheme)) {
            ResourcesLoader created = new ResourcesLoader();
            created.addProvider(provider(theme));
            loader = created;
            loaderTheme = theme.id;
        }
        // Adding a loader that is already there changes nothing.
        resources.addLoaders(loader);
    }

    private static ResourcesProvider provider(Theme theme) throws Exception {
        Context context = appContext;
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
        try (InputStream input = context.getAssets().open(ASSETS + "theme-resources.json")) {
            replaced = new JSONObject(new String(readAll(input), "UTF-8")).optJSONArray(theme.id);
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not read the theme resources", ex);
        }
        if (replaced != null) {
            for (int i = 0; i < replaced.length(); i++) {
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
        int animation = resources.getIdentifier("loading_animation", "raw", packageName);
        if (animation != 0) {
            byte[] original = readAll(resources.openRawResource(animation));
            String day = "arsound/theme/" + theme.id + "-loading.json";
            String night = "arsound/theme/" + theme.id + "-loading-night.json";
            write(new File(context.getFilesDir(), day), tintLottie(original, theme.accent(false)));
            write(new File(context.getFilesDir(), night), tintLottie(original, theme.accent(true)));
            table.put(animation, "raw", "loading_animation", ArscWriter.Value.file(day), false);
            table.put(animation, "raw", "loading_animation", ArscWriter.Value.file(night), true);
        }

        File file = new File(context.getCacheDir(), "arsound-theme-" + theme.id + ".arsc");
        write(file, table.build());
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
        }
        return animation.toString().getBytes("UTF-8");
    }

    /** Copies an asset to a file once; the file is kept while it has the same size. */
    private static boolean copyAsset(Context context, String asset, File target) {
        try (InputStream input = context.getAssets().open(asset)) {
            byte[] bytes = readAll(input);
            if (!target.isFile() || target.length() != bytes.length) write(target, bytes);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not copy " + asset, ex);
            return false;
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
