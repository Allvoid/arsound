package app.revanced.extension.soundcloud.theme;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.theme.ArsoundTheme.Theme;

/**
 * Themes whose colours are made on the phone: "Monet" (the palette of Material You, from the wallpaper) and the
 * "Custom theme" (the user's accent and backgrounds, see {@link CustomTheme}).
 * <p>
 * themes.json holds only a seed of each, with the name, the description and a neutral palette that gives the build
 * its start screen and resource names. At run time a seed becomes a {@link Theme} of the same shape as
 * a shipped one, made by {@link PaletteEngine}, so everything that reads a theme (the settings, dialogs, the resource
 * table) works with it as it is. A theme is made again only when its colours change, and the result is kept by its key.
 */
public final class RuntimeThemes {
    public static final String MONET = "monet";
    public static final String CUSTOM = "custom";

    /** A kind of theme whose colours are made on the phone, with what it takes to make one. */
    private enum Kind {
        /** The palette of Material You, read from the system: Android 12 and later. */
        MONET(RuntimeThemes.MONET) {
            @Override
            boolean isAvailable() {
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
            }

            @Override
            Theme make(Resources resources, Theme seed) {
                return monet(resources, seed);
            }
        },
        /** The user's own accent and backgrounds. */
        CUSTOM(RuntimeThemes.CUSTOM) {
            @Override
            Theme make(Resources resources, Theme seed) {
                return custom(seed, CustomTheme.load());
            }
        };

        /** The "runtime" value of the seed in themes.json. */
        final String id;

        Kind(String id) {
            this.id = id;
        }

        /** Whether the phone can make this kind of theme. */
        boolean isAvailable() {
            return true;
        }

        /** The theme in its present colours (kept, while they stay the same). */
        abstract Theme make(Resources resources, Theme seed);

        /** The kind of this "runtime" value, null for one that is not known. */
        static Kind of(String id) {
            for (Kind kind : values()) if (kind.id.equals(id)) return kind;
            return null;
        }
    }

    /** The latest theme made for a seed and the key of the colours it was made for. */
    private static final class Made {
        final String key;
        final Theme theme;

        Made(String key, Theme theme) {
            this.key = key;
            this.theme = theme;
        }
    }

    /** The latest theme of each seed (by the seed's id); the key tells whether its colours are still the ones asked for. */
    private static final Map<String, Made> LATEST = new HashMap<>();

    private RuntimeThemes() {
    }

    /**
     * The theme in its present colours, or null when the phone cannot make it.
     *
     * @param resources Where the system palette is read: the newest ones the caller has, since the palette changes
     *                  with the wallpaper.
     */
    static Theme resolve(Resources resources, Theme seed) {
        Kind kind = Kind.of(seed.runtime);
        if (kind == null) return seed;
        if (!kind.isAvailable()) return null;
        try {
            return kind.make(resources, seed);
        } catch (Throwable ex) {
            Logger.printException(() -> "Runtime theme: could not make " + seed.id + ", using its seed", ex);
            return seed;
        }
    }

    /** The accent of the system palette as #RRGGBB, or null where there is none (before Android 12). */
    public static String systemAccent(Resources resources) {
        return Kind.MONET.isAvailable() ? systemTones(resources).get("accent1_200") : null;
    }

    /** The custom theme in these colours, for the preview of the editor; nothing is kept. */
    public static Theme custom(Context context, CustomTheme settings) {
        return makeCustom(ArsoundTheme.seed(context, CUSTOM), settings);
    }

    private static synchronized Theme custom(Theme seed, CustomTheme settings) {
        String key = CUSTOM + "/" + settings.toJson();
        Theme kept = kept(seed.id, key);
        if (kept != null) return kept;
        Theme made = makeCustom(seed, settings);
        keep(seed.id, key, made);
        return made;
    }

    private static Theme makeCustom(Theme seed, CustomTheme settings) {
        Map<String, String> dark = PaletteEngine.darkFrom(settings.darkBg, settings.accent, null, null);
        Map<String, String> light = PaletteEngine.lightFrom(settings.lightBg, settings.accent, null, null);
        Theme made = make(seed, CUSTOM + "/" + settings.toJson(), dark, light,
                CustomTheme.MODE_DARK.equals(settings.mode), CustomTheme.MODE_LIGHT.equals(settings.mode));
        try {
            made.json.put("radii", new JSONObject().put("card", settings.radius).put("miniPlayer", settings.radius + 6));
            made.json.put("effect", settings.effect);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return made;
    }

    private static synchronized Theme monet(Resources resources, Theme seed) {
        Map<String, String> tones = systemTones(resources);
        String key = MONET + "/" + tones.values();
        Theme kept = kept(seed.id, key);
        if (kept != null) return kept;
        Theme made = make(seed, key, PaletteEngine.monetDark(tones), PaletteEngine.monetLight(tones), false, false);
        keep(seed.id, key, made);
        return made;
    }

    /** The theme made for this seed in these colours, or null if the latest one was made for other colours. */
    private static Theme kept(String id, String key) {
        Made latest = LATEST.get(id);
        return latest != null && latest.key.equals(key) ? latest.theme : null;
    }

    /** Only the newest theme of a seed is kept: an older palette is never asked for again. */
    private static void keep(String id, String key, Theme theme) {
        LATEST.put(id, new Made(key, theme));
    }

    /** The seed with the palettes and everything derived from them put in. */
    private static Theme make(Theme seed, String key, Map<String, String> dark,
                             Map<String, String> light, boolean darkOnly, boolean lightOnly) {
        try {
            JSONObject json = new JSONObject(seed.json.toString());
            for (Map.Entry<String, Object> part : PaletteEngine.theme(dark, light).entrySet()) {
                json.put(part.getKey(), JSONObject.wrap(part.getValue()));
            }
            json.put("darkOnly", darkOnly);
            json.put("lightOnly", lightOnly);
            return new Theme(json, key);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    // region System palette

    /** The tonal colours of the system palette that Monet reads, as #RRGGBB by their names. */
    @TargetApi(Build.VERSION_CODES.S)
    private static Map<String, String> systemTones(Resources resources) {
        Map<String, String> tones = new LinkedHashMap<>();
        for (String name : PaletteEngine.MONET_TONES) {
            tones.put(name, String.format("#%06X", resources.getColor(systemTone(name), null) & 0xFFFFFF));
        }
        return tones;
    }

    /** The resource of {@code system_<name>}; the constants are plain numbers when the extension is compiled. */
    @TargetApi(Build.VERSION_CODES.S)
    private static int systemTone(String name) {
        switch (name) {
            case "neutral1_0":
                return android.R.color.system_neutral1_0;
            case "neutral1_10":
                return android.R.color.system_neutral1_10;
            case "neutral1_50":
                return android.R.color.system_neutral1_50;
            case "neutral1_100":
                return android.R.color.system_neutral1_100;
            case "neutral1_800":
                return android.R.color.system_neutral1_800;
            case "neutral1_900":
                return android.R.color.system_neutral1_900;
            case "neutral1_1000":
                return android.R.color.system_neutral1_1000;
            case "neutral2_100":
                return android.R.color.system_neutral2_100;
            case "neutral2_300":
                return android.R.color.system_neutral2_300;
            case "neutral2_700":
                return android.R.color.system_neutral2_700;
            case "neutral2_800":
                return android.R.color.system_neutral2_800;
            case "accent1_200":
                return android.R.color.system_accent1_200;
            case "accent1_600":
                return android.R.color.system_accent1_600;
            default:
                throw new IllegalArgumentException("Not a tone of the system palette: " + name);
        }
    }

    // endregion
}
