package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.content.res.Resources;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.theme.ArsoundTheme.Theme;

/** The themes there are: those themes.json (put in the app by the theme patch) lists, the runtime ones in their present colours. */
final class ThemeCatalog {
    /** The themes as themes.json has them: the runtime ones only as seeds. */
    private static volatile List<Theme> themes;

    private ThemeCatalog() {
    }

    /** The themes to choose from, the runtime ones in their present colours; Monet is left out where it cannot be made. */
    static List<Theme> all(Context context) {
        List<Theme> result = new ArrayList<>();
        for (Theme theme : seeds(context)) {
            if (!theme.isRuntime()) {
                result.add(theme);
                continue;
            }
            Theme resolved = RuntimeThemes.resolve(context.getResources(), theme);
            if (resolved != null) result.add(resolved);
        }
        return Collections.unmodifiableList(result);
    }

    /** A theme as themes.json has it (a runtime theme with its neutral seed colours). */
    static Theme seed(Context context, String id) {
        for (Theme theme : seeds(context)) if (theme.id.equals(id)) return theme;
        throw new IllegalArgumentException("No theme " + id);
    }

    /**
     * The theme of this id in its present colours; null for the original look, an unknown id or a theme the phone cannot make.
     *
     * @param resources Where a runtime theme reads the system palette, the newest the caller has.
     */
    static Theme find(Context context, Resources resources, String id) {
        for (Theme theme : seeds(context)) {
            if (!theme.id.equals(id)) continue;
            return theme.isRuntime() ? RuntimeThemes.resolve(resources, theme) : theme;
        }
        return null;
    }

    private static List<Theme> seeds(Context context) {
        List<Theme> result = themes;
        if (result != null) return result;
        result = new ArrayList<>();
        try {
            JSONArray list = new JSONObject(ThemeFiles.readAsset(context, ThemeFiles.ROOT + "themes.json")).getJSONArray("themes");
            for (int i = 0; i < list.length(); i++) result.add(new Theme(list.getJSONObject(i)));
        } catch (Exception ex) {
            Logger.printException(() -> "Theme: could not read the themes", ex);
        }
        themes = result = Collections.unmodifiableList(result);
        return result;
    }
}
