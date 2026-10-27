package app.revanced.extension.soundcloud.theme;

import org.json.JSONObject;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The colours the user chose for the "Custom theme": one accent, a background for the dark look and one for the
 * light look, how the app follows the phone, the roundness of the corners and an animation. The rest of the theme
 * (cards, text, borders, the player) follows from them in {@link PaletteEngine}.
 * <p>
 * Kept as JSON in one setting ({@link Settings#CUSTOM_THEME}). Immutable; the {@code with} methods return a changed copy.
 */
public final class CustomTheme {
    /** Dark in every mode of the phone. */
    public static final String MODE_DARK = "dark";
    /** Light in every mode of the phone. */
    public static final String MODE_LIGHT = "light";
    /** Dark or light as the phone is. */
    public static final String MODE_AUTO = "auto";

    /** No animation. */
    public static final String NO_EFFECT = "none";

    public static final int MAX_RADIUS = 20;

    /** What a theme that was never edited looks like: SoundCloud's own black, white and orange. */
    private static final CustomTheme DEFAULTS = new CustomTheme(MODE_AUTO, "#FF5500", "#121212", "#FFFFFF", 8, NO_EFFECT);

    public final String mode;
    /** #RRGGBB */
    public final String accent, darkBg, lightBg;
    /** Corner radius of covers and cards, dp. */
    public final int radius;
    /** Id of one of the shipped animations, or {@link #NO_EFFECT}. */
    public final String effect;

    private CustomTheme(String mode, String accent, String darkBg, String lightBg, int radius, String effect) {
        this.mode = mode;
        this.accent = accent;
        this.darkBg = darkBg;
        this.lightBg = lightBg;
        this.radius = radius;
        this.effect = effect;
    }

    public static CustomTheme defaults() {
        return DEFAULTS;
    }

    /** True once the user has saved colours; until then the theme is the default look and the editor opens first. */
    public static boolean isSaved() {
        return !Settings.getString(Settings.CUSTOM_THEME, "").isEmpty();
    }

    /** The saved colours, or the defaults. A damaged setting also gives the defaults. */
    public static CustomTheme load() {
        String text = Settings.getString(Settings.CUSTOM_THEME, "");
        if (text.isEmpty()) return DEFAULTS;
        try {
            JSONObject json = new JSONObject(text);
            return new CustomTheme(
                    mode(json.optString("mode", MODE_AUTO)),
                    hex(json.optString("accent"), DEFAULTS.accent),
                    hex(json.optString("darkBg"), DEFAULTS.darkBg),
                    hex(json.optString("lightBg"), DEFAULTS.lightBg),
                    Math.max(0, Math.min(MAX_RADIUS, json.optInt("radius", DEFAULTS.radius))),
                    json.optString("effect", NO_EFFECT));
        } catch (Exception ex) {
            Logger.printException(() -> "Custom theme: the saved colours are damaged, using the defaults", ex);
            return DEFAULTS;
        }
    }

    /** Written at once (not in the background): the app restarts right after it. */
    public void save() {
        Settings.putStringNow(Settings.CUSTOM_THEME, toJson());
    }

    /** The same text for the same colours; also the key of the built resource table. */
    public String toJson() {
        try {
            return new JSONObject().put("v", 1).put("mode", mode).put("accent", accent).put("darkBg", darkBg)
                    .put("lightBg", lightBg).put("radius", radius).put("effect", effect).toString();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public CustomTheme withMode(String mode) {
        return new CustomTheme(mode(mode), accent, darkBg, lightBg, radius, effect);
    }

    public CustomTheme withAccent(String accent) {
        return new CustomTheme(mode, hex(accent, this.accent), darkBg, lightBg, radius, effect);
    }

    public CustomTheme withDarkBg(String darkBg) {
        return new CustomTheme(mode, accent, hex(darkBg, this.darkBg), lightBg, radius, effect);
    }

    public CustomTheme withLightBg(String lightBg) {
        return new CustomTheme(mode, accent, darkBg, hex(lightBg, this.lightBg), radius, effect);
    }

    public CustomTheme withRadius(int radius) {
        return new CustomTheme(mode, accent, darkBg, lightBg, Math.max(0, Math.min(MAX_RADIUS, radius)), effect);
    }

    public CustomTheme withEffect(String effect) {
        return new CustomTheme(mode, accent, darkBg, lightBg, radius, effect == null ? NO_EFFECT : effect);
    }

    private static String mode(String mode) {
        return MODE_DARK.equals(mode) || MODE_LIGHT.equals(mode) ? mode : MODE_AUTO;
    }

    /** #RRGGBB in capitals, or the fallback when this is not a colour. */
    public static String hex(String value, String fallback) {
        if (value == null) return fallback;
        String text = value.trim();
        if (text.startsWith("#")) text = text.substring(1);
        if (text.length() == 3) text = "" + text.charAt(0) + text.charAt(0) + text.charAt(1) + text.charAt(1) + text.charAt(2) + text.charAt(2);
        if (text.length() != 6) return fallback;
        for (int i = 0; i < 6; i++) if (!isHexDigit(text.charAt(i))) return fallback;
        return "#" + text.toUpperCase(java.util.Locale.ROOT);
    }

    /** Only 0-9 and a-f: {@link Character#digit} also takes the digits of other scripts, which no colour parser does. */
    public static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CustomTheme && toJson().equals(((CustomTheme) other).toJson());
    }

    @Override
    public int hashCode() {
        return toJson().hashCode();
    }
}
