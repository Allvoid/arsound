package app.revanced.extension.soundcloud.theme;

import org.json.JSONObject;

import java.util.Iterator;

import app.revanced.extension.soundcloud.theme.ArsoundTheme.Theme;

/** The decoration of Arsound's own screens by a theme ("decor" in themes.json), with the light look's values laid over it. */
final class ThemeDecor {
    private ThemeDecor() {
    }

    /**
     * The decoration of this theme in the dark or the light look, or null for the original look and for a theme
     * without one.
     */
    static JSONObject of(Theme theme, boolean night) {
        JSONObject decor = theme == null ? null : theme.json.optJSONObject("decor");
        // In the light look the values of "decorLight" win.
        JSONObject light = theme == null ? null : theme.json.optJSONObject("decorLight");
        if (decor == null || light == null || night) return decor;
        try {
            JSONObject merged = new JSONObject(decor.toString());
            for (Iterator<String> keys = light.keys(); keys.hasNext(); ) {
                String key = keys.next();
                merged.put(key, light.get(key));
            }
            return merged;
        } catch (Exception ex) {
            return decor;
        }
    }

    /** A colour of the decoration, or the fallback when the theme has none. */
    static int color(JSONObject decor, String name, int fallback) {
        String value = decor == null ? "" : decor.optString(name);
        return value.startsWith("#") ? ThemeColors.parse(value) : fallback;
    }
}
