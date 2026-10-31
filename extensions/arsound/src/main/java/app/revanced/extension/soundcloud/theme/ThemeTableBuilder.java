package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.TypedValue;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.theme.ArsoundTheme.Theme;

/**
 * Builds the resource table of one theme ({@link ArscWriter}) and the files it names: the colour tokens, the cards'
 * and the mini player's corners, the fonts, the theme's own copies of whole resources, the loading animation and the
 * pictures behind page titles, the last two recoloured into the accent.
 */
final class ThemeTableBuilder {
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
    /** The part of the themes that is switched on in the settings (the new player). */
    private static final String PLAYER_SKIN_PART = "playerSkin";

    private final Context context;
    private final Theme theme;
    /** The resources keep SoundCloud's package name although the app is installed under another one. */
    private final String packageName;
    private final Resources resources;
    private final ArscWriter table;
    private final ResourceOverrides overrides;
    private final boolean pristine;

    /**
     * @param pristine True while no loader was added in this process: the resources still show the app's own files,
     *                 which {@link OriginalAssets} then keeps.
     */
    ThemeTableBuilder(Context context, Theme theme, boolean pristine) {
        this.context = context;
        this.theme = theme;
        this.pristine = pristine;
        // Built before any loader is added, so the original loading animation is read, not a recoloured one.
        this.resources = context.getResources();
        String name = context.getPackageName();
        int anyId = resources.getIdentifier("dark_mode_surface", "color", name);
        if (anyId == 0) {
            name = AppResources.SOUNDCLOUD_PACKAGE;
            anyId = resources.getIdentifier("dark_mode_surface", "color", name);
        }
        if (anyId == 0) throw new IllegalStateException("SoundCloud colour tokens not found");
        this.packageName = name;
        this.table = new ArscWriter(anyId >>> 24, resources.getResourcePackageName(anyId));
        this.overrides = new ResourceOverrides(table, resources, name);
    }

    /** Builds the table into {@code arsc}, with the fonts and the loading animation it names. */
    void build(File arsc) throws Exception {
        addColorTokens();
        addNamedColors();
        addResourceFiles();
        addRadii();
        addFonts();
        OriginalAssets originals = new OriginalAssets(context, pristine);
        addLoadingAnimation(originals);
        addHeaderPictures(originals);
        ThemeFiles.write(arsc, table.build());
    }

    private void addColorTokens() {
        for (String[] token : TOKENS) {
            overrides.color("dark_mode_" + token[1], theme.color(true, token[0]), null);
            overrides.color("light_mode_" + token[1], theme.color(false, token[0]), null);
        }
        overrides.color("dialog_dark", theme.color(true, "dialog"), null);
        // The launcher window that follows the start screen, dark like the start screen in both modes.
        overrides.color("arsound_launch_background", theme.surface(true), theme.surface(true));
        // The accent is one colour for both modes in SoundCloud; the night value overrides it in dark mode.
        overrides.color("shared_colors_special_action", theme.accent(false), theme.accent(true));
    }

    /** Any other SoundCloud colour by name, such as the grey scale behind cards and the mini player's gradient. */
    private void addNamedColors() {
        JSONObject colors = theme.json.optJSONObject("colors");
        if (colors == null) return;
        for (Iterator<String> names = colors.keys(); names.hasNext(); ) {
            String name = names.next();
            int color = ThemeColors.parse(colors.optString(name));
            overrides.color(name, color, color);
        }
    }

    /**
     * Whole resources (drawables, colour lists, layouts): the theme's own copy, put into the app by the
     * theme patch as arsound_&lt;theme&gt;__&lt;name&gt; and listed in theme-resources.json, stands in for SoundCloud's.
     * Files whose name starts with "arsound_" are the theme's additions, used by its other files.
     */
    private void addResourceFiles() {
        JSONArray replaced = null;
        JSONArray runtimeColors = null;
        Set<String> switchedOff = new HashSet<>();
        try {
            JSONObject index = new JSONObject(ThemeFiles.readAsset(context, ThemeFiles.ROOT + "theme-resources.json"));
            replaced = index.optJSONArray(theme.id);
            runtimeColors = index.optJSONArray("_runtimeColors");
            // Parts switched on in the settings ("_optional": part -> its resources) stay out while off.
            JSONObject optional = index.optJSONObject("_optional");
            JSONArray playerSkin = optional == null ? null : optional.optJSONArray(PLAYER_SKIN_PART);
            if (playerSkin != null && !ArsoundTheme.isPlayerSkinEnabled()) {
                for (int i = 0; i < playerSkin.length(); i++) switchedOff.add(playerSkin.optString(i));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not read the theme resources", ex);
        }
        // The files of a runtime theme name colour resources of the app (arsound_rt_*) instead of holding colours; they
        // get the theme's tokens here, in the day and the night value.
        if (theme.isRuntime() && runtimeColors != null) addRuntimeColors(runtimeColors);
        if (replaced != null) addReplacements(replaced, switchedOff);
    }

    private void addRuntimeColors(JSONArray runtimeColors) {
        for (int i = 0; i < runtimeColors.length(); i++) {
            JSONArray entry = runtimeColors.optJSONArray(i);
            if (entry == null) continue;
            String token = entry.optString(1);
            int percent = entry.optInt(2, -1);
            boolean dark = entry.optBoolean(3);
            overrides.color(entry.optString(0), theme.runtimeColor(false, token, percent, dark),
                    theme.runtimeColor(true, token, percent, dark));
        }
    }

    private void addReplacements(JSONArray replaced, Set<String> switchedOff) {
        for (int i = 0; i < replaced.length(); i++) {
            if (switchedOff.contains(replaced.optString(i))) continue;
            String[] typeAndName = replaced.optString(i).split("/", 2);
            if (typeAndName.length != 2 || typeAndName[1].startsWith("arsound_")) continue;
            overrides.alias(typeAndName[0], typeAndName[1], "arsound_" + theme.id + "__" + typeAndName[1]);
        }
    }

    private void addRadii() {
        JSONObject radii = theme.json.optJSONObject("radii");
        if (radii == null) return;
        int card = radii.optInt("card", 6);
        overrides.dimen("artwork_corner_radius_default", card);
        overrides.dimen("artwork_corner_radius_large", card + 4);
        overrides.dimen("artwork_corner_radius_medium", Math.max(2, card - 2));
        overrides.dimen("mini_player_corner", radii.optInt("miniPlayer", 36));
    }

    /** Fonts: files from the app's assets, given to Android through the provider of the loader. */
    private void addFonts() {
        JSONObject fonts = theme.json.optJSONObject("fonts");
        if (fonts == null) return;
        for (String[] slot : FONTS) {
            String file = fonts.optString(slot[0], "");
            if (file.isEmpty()) continue;
            String path = ThemeFiles.ROOT + "fonts/" + file + ".ttf";
            // Android loads a font resource only from a path under res/.
            if (ThemeFiles.copyAsset(context, path, new File(context.getFilesDir(), path))) {
                overrides.file("font", slot[1], "res/" + path);
            }
        }
    }

    /** The loading animation: the drawing letter in the accent. */
    private void addLoadingAnimation(OriginalAssets originals) throws Exception {
        int animation = resources.getIdentifier("loading_animation", "raw", packageName);
        if (animation == 0) return;
        byte[] original = originals.loadingAnimation(resources, animation);
        String day = ThemeFiles.ROOT + "theme/" + theme.id + "-loading.json";
        String night = ThemeFiles.ROOT + "theme/" + theme.id + "-loading-night.json";
        byte[][] looks = LoadingAnimation.tint(original, new int[]{theme.accent(false), theme.accent(true)});
        ThemeFiles.write(new File(context.getFilesDir(), day), looks[0]);
        ThemeFiles.write(new File(context.getFilesDir(), night), looks[1]);
        table.put(animation, "raw", "loading_animation", ArscWriter.Value.file(day), false);
        table.put(animation, "raw", "loading_animation", ArscWriter.Value.file(night), true);
    }

    /**
     * The pictures behind page titles (the hearts of "Your likes"), recoloured into the accent. Two of them have
     * their own picture for wide screens (tablets, a phone on its side), which would win over a default-only
     * copy: each is read at a narrow and a wide width, whatever the screen is now.
     */
    private void addHeaderPictures(OriginalAssets originals) {
        Resources narrow = resourcesAtWidth(320);
        Resources wide = resourcesAtWidth(ArscWriter.WIDE_DP);
        for (String name : HeaderArtwork.NAMES) {
            int picture = resources.getIdentifier(name, "drawable", packageName);
            if (picture == 0) {
                Logger.printInfo(() -> "Theme: no drawable " + name);
                continue;
            }
            try {
                addHeaderPicture(originals, narrow, picture, name, false);
                if (!sameFile(narrow, wide, picture)) addHeaderPicture(originals, wide, picture, name, true);
            } catch (Exception ex) {
                Logger.printException(() -> "Theme: could not recolour " + name, ex);
            }
        }
    }

    /** One picture behind a page title in the accent of day and night, for a narrow or a wide screen. */
    private void addHeaderPicture(OriginalAssets originals, Resources source, int picture, String name, boolean wide)
            throws Exception {
        byte[] original = originals.picture(source, picture, name, wide);
        if (original == null) return;
        byte[][] looks = HeaderArtwork.tint(original, new int[]{theme.accent(false), theme.accent(true)}, name);
        if (looks == null) return;
        byte[] day = looks[0];
        byte[] night = looks[1];
        // A path under res/, as for the fonts; the provider of the loader reads it from the files folder.
        String base = ThemeFiles.ROOT + "theme/" + theme.id + "-" + name + (wide ? "-wide" : "");
        String dayPath = base + ".png";
        String nightPath = base + "-night.png";
        ThemeFiles.write(new File(context.getFilesDir(), dayPath), day);
        ThemeFiles.write(new File(context.getFilesDir(), nightPath), night);
        table.put(picture, "drawable", name, ArscWriter.Value.file("res/" + dayPath), false, wide);
        table.put(picture, "drawable", name, ArscWriter.Value.file("res/" + nightPath), true, wide);
    }

    /** The app's resources as on a screen this many dp wide. */
    private Resources resourcesAtWidth(int widthDp) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.screenWidthDp = widthDp;
        return context.createConfigurationContext(configuration).getResources();
    }

    /** True if the resource is the same file on both resources: the app has no picture of its own for one of them. */
    private static boolean sameFile(Resources first, Resources second, int resource) {
        TypedValue a = new TypedValue();
        TypedValue b = new TypedValue();
        first.getValue(resource, a, true);
        second.getValue(resource, b, true);
        return String.valueOf(a.string).equals(String.valueOf(b.string));
    }
}
