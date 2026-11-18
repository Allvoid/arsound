package app.revanced.extension.soundcloud.theme;

import android.graphics.Color;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The colour of the app's text, chosen in Appearance next to the font ({@link FontChoice}). Nothing is chosen by
 * default: the text keeps the theme's (or SoundCloud's) colour. A chosen colour goes into the theme's resource table
 * in place of the "primary" colour token, which SoundCloud's text styles use, and its dimmer copy in place of
 * "secondary" (captions and hints); it is the same for the day and the night look.
 * <p>
 * Arsound's own screens ask {@link ArsoundTheme#palette} for their colours and get the same text colour there.
 */
public final class TextColour {
    public static final String KEY = "theme_text_colour";
    /** Captions and hints are the chosen colour at this opacity, as they are dimmer than the main text. */
    private static final int SECONDARY_ALPHA = 0xB3;

    private TextColour() {
    }

    /** The chosen colour as #RRGGBB, or an empty text when none is chosen (the theme's own). */
    public static String hex() {
        String value = Settings.getString(KEY, "");
        return isHex(value) ? value.toUpperCase(java.util.Locale.ROOT) : "";
    }

    public static boolean isSet() {
        return !hex().isEmpty();
    }

    /** What the resource table depends on: a change of the colour makes a new table. Empty when none is chosen. */
    static String key() {
        return hex();
    }

    /** The main text colour, opaque. Only while {@link #isSet}. */
    static int primary() {
        return 0xFF000000 | (Color.parseColor(hex()) & 0xFFFFFF);
    }

    /** The colour of captions and hints: the chosen one, a little see-through. Only while {@link #isSet}. */
    static int secondary() {
        return (SECONDARY_ALPHA << 24) | (Color.parseColor(hex()) & 0xFFFFFF);
    }

    /** The colour for a role of the palette, or {@code fallback} when the role is not text or no colour is chosen. */
    static int forRole(String role, int fallback) {
        if (!isSet()) return fallback;
        if ("primary".equals(role)) return primary();
        if ("secondary".equals(role)) return secondary();
        return fallback;
    }

    public static void set(String hex) {
        Settings.putStringNow(KEY, hex == null ? "" : hex);
    }

    private static boolean isHex(String value) {
        if (value == null || value.length() != 7 || value.charAt(0) != '#') return false;
        for (int i = 1; i < 7; i++) if (Character.digit(value.charAt(i), 16) < 0) return false;
        return true;
    }
}
