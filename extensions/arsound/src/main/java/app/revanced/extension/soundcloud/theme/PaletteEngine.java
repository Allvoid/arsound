package app.revanced.extension.soundcloud.theme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes a whole theme from a few colours, on the phone: a Java port of {@code tools/branding/theme_from_design.py}
 * (the design tokens of a palette, SoundCloud's grey scale in the theme's tint, the decoration of Arsound's screens)
 * plus what a theme chosen by the user needs on top: the palettes themselves derived from an accent and a background
 * ({@link #darkFrom}, {@link #lightFrom}, {@link #monetDark}, {@link #monetLight}) and contrast safety.
 * <p>
 * Pure Java on purpose, without {@code android.*} and {@code org.json}: it is compiled and checked against
 * {@code themes.json} on a plain JVM ({@code tools/branding/PaletteParity.java}), so the port cannot drift from the
 * script that made the shipped themes. Colours are {@code #RRGGBB} (or {@code #RRGGBBAA}) strings, as in
 * {@code themes.json}; the results are maps and lists in the shape of a theme there.
 */
public final class PaletteEngine {
    /** SoundCloud's grey scale (extended_palette_grey_*); the theme's scale keeps their lightness. */
    private static final String[][] SC_GREYS = {
            {"950", "#272727"}, {"900", "#303030"}, {"800", "#454545"}, {"700", "#595959"}, {"600", "#666666"},
            {"500", "#808080"}, {"400", "#999999"}, {"300", "#C6C6C6"}, {"200", "#DDDDDD"}, {"100", "#F3F3F3"},
            {"50", "#F9F9F9"},
    };
    private static final String[] TILE_NAMES = {"blue", "green", "magenta", "orange", "purple", "red", "teal", "violet", "yellow"};

    /** The parts every runtime theme uses, in the order of the shipped themes. */
    public static final List<String> PARTS = java.util.Collections.unmodifiableList(Arrays.asList(
            "lucideIcons", "homeGreeting", "libraryIcons", "miniPlayer", "playerButtons", "tabBar", "playerSkin"));

    /** How light the background of a dark look may be and how dark the background of a light look: SoundCloud's own
     * white icons and dark text need the extremes. A colour beyond is brought back to the limit. */
    private static final double DARK_BG_MAX_LIGHTNESS = .28;
    private static final double LIGHT_BG_MIN_LIGHTNESS = .90;

    private PaletteEngine() {
    }

    // region Colours as in theme_from_design.py

    /** A CSS colour #rgb, #rrggbb or #rrggbbaa as {r, g, b, a}: whole 0..255 channels and the opacity 0..1. */
    private static double[] rgba(String value) {
        String hex = value.trim().substring(1);
        if (hex.length() == 3) {
            StringBuilder doubled = new StringBuilder();
            for (int i = 0; i < 3; i++) doubled.append(hex.charAt(i)).append(hex.charAt(i));
            hex = doubled.toString();
        }
        double r = Integer.parseInt(hex.substring(0, 2), 16);
        double g = Integer.parseInt(hex.substring(2, 4), 16);
        double b = Integer.parseInt(hex.substring(4, 6), 16);
        double a = hex.length() == 8 ? Integer.parseInt(hex.substring(6, 8), 16) / 255.0 : 1.0;
        return new double[]{r, g, b, a};
    }

    /**
     * #RRGGBB, with the opacity as two more digits when it is below one. Python's {@code round} goes to the even
     * neighbour at .5, so {@link Math#rint} is the same, not {@link Math#round}: otherwise a channel is off by one
     * now and then and the port no longer gives the shipped themes.
     */
    static String hexOf(double r, double g, double b, double a) {
        String text = String.format("#%02X%02X%02X", channel(r), channel(g), channel(b));
        return a >= 0.999 ? text : text + String.format("%02X", (int) Math.rint(a * 255));
    }

    static String hexOf(double r, double g, double b) {
        return hexOf(r, g, b, 1.0);
    }

    /** A channel as a whole number, half to even; the clamp only guards a colour that was never meant to leave 0..255. */
    private static int channel(double value) {
        return (int) Math.max(0, Math.min(255, Math.rint(value)));
    }

    /** {@code one} moved toward {@code two} by {@code amount} (0..1); the opacity of both is dropped. */
    public static String mix(String one, String two, double amount) {
        double[] a = rgba(one), b = rgba(two);
        return hexOf(a[0] + (b[0] - a[0]) * amount, a[1] + (b[1] - a[1]) * amount, a[2] + (b[2] - a[2]) * amount);
    }

    /** HLS lightness of Python's colorsys: (max + min) / 2. */
    static double lightness(String color) {
        return hls(color)[1];
    }

    /** {h, l, s}, all 0..1, like colorsys.rgb_to_hls. */
    static double[] hls(String color) {
        double[] c = rgba(color);
        double r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double sum = max + min, range = max - min;
        double l = sum / 2.0;
        if (min == max) return new double[]{0.0, l, 0.0};
        double s = l <= 0.5 ? range / sum : range / (2.0 - max - min);
        double rc = (max - r) / range, gc = (max - g) / range, bc = (max - b) / range;
        double h;
        if (r == max) h = bc - gc;
        else if (g == max) h = 2.0 + rc - bc;
        else h = 4.0 + gc - rc;
        h = (h / 6.0) % 1.0;
        if (h < 0) h += 1.0;
        return new double[]{h, l, s};
    }

    /** Like colorsys.hls_to_rgb; the result as a colour string. */
    static String fromHls(double h, double l, double s) {
        l = Math.max(0, Math.min(1, l));
        s = Math.max(0, Math.min(1, s));
        if (s == 0.0) return hexOf(l * 255, l * 255, l * 255);
        double m2 = l <= 0.5 ? l * (1.0 + s) : l + s - (l * s);
        double m1 = 2.0 * l - m2;
        return hexOf(hueChannel(m1, m2, h + 1.0 / 3.0) * 255, hueChannel(m1, m2, h) * 255,
                hueChannel(m1, m2, h - 1.0 / 3.0) * 255);
    }

    private static double hueChannel(double m1, double m2, double hue) {
        hue = hue % 1.0;
        if (hue < 0) hue += 1.0;
        if (hue < 1.0 / 6.0) return m1 + (m2 - m1) * hue * 6.0;
        if (hue < 0.5) return m2;
        if (hue < 2.0 / 3.0) return m1 + (m2 - m1) * (2.0 / 3.0 - hue) * 6.0;
        return m1;
    }

    /** SoundCloud's greys in the theme's tint: each grey sits between the theme colours of nearby lightness. */
    static Map<String, String> greyScale(Map<String, String> tokens) {
        List<Object[]> anchors = new ArrayList<>();
        anchors.add(new Object[]{lightness("#121212"), tokens.get("bg")});
        anchors.add(new Object[]{lightness("#272727"), tokens.get("surface")});
        anchors.add(new Object[]{lightness("#454545"), tokens.get("surface2")});
        anchors.add(new Object[]{lightness("#C6C6C6"), tokens.get("muted")});
        anchors.add(new Object[]{lightness("#DDDDDD"), tokens.get("text2")});
        anchors.add(new Object[]{1.0, "#FFFFFF"});
        // Python sorts the pairs: by lightness, then by the colour text.
        anchors.sort((x, y) -> {
            int byLevel = Double.compare((Double) x[0], (Double) y[0]);
            return byLevel != 0 ? byLevel : ((String) x[1]).compareTo((String) y[1]);
        });
        Map<String, String> scale = new LinkedHashMap<>();
        for (String[] grey : SC_GREYS) {
            double level = lightness(grey[1]);
            for (int i = 0; i + 1 < anchors.size(); i++) {
                double low = (Double) anchors.get(i)[0], high = (Double) anchors.get(i + 1)[0];
                if (low <= level && level <= high) {
                    double amount = high > low ? (level - low) / (high - low) : 0;
                    scale.put("extended_palette_grey_" + grey[0],
                            mix((String) anchors.get(i)[1], (String) anchors.get(i + 1)[1], amount));
                    break;
                }
            }
        }
        return scale;
    }

    /**
     * Design tokens for a theme that has only SoundCloud's palette: bg is the surface, cards are the dialog colour,
     * the rest is mixed from them, the text colours and the accent.
     */
    public static Map<String, String> tokensFromPalette(Map<String, String> palette, boolean dark) {
        String bg = palette.get("surface"), card = palette.get("dialog");
        String highlight = palette.get("highlight").substring(0, 7);
        String accent = palette.get("special"), text = palette.get("primary"), muted = palette.get("secondary");
        String surface2 = mix(highlight, text, dark ? 0.08 : 0.04);
        Map<String, String> tokens = new LinkedHashMap<>();
        if (dark) {
            double[] mini = rgba(mix(card, accent, 0.1));
            double[] border = rgba(mix(accent, "#FFFFFF", 0.45));
            tokens.put("bg", bg);
            tokens.put("surface", card);
            tokens.put("surface2", surface2);
            tokens.put("deep", mix(accent, bg, 0.65));
            tokens.put("border", surface2);
            tokens.put("accent", accent);
            tokens.put("pink", mix(accent, "#FFFFFF", 0.45));
            tokens.put("muted", muted);
            tokens.put("text2", mix(muted, text, 0.4));
            tokens.put("tabbar", mix(bg, card, 0.5));
            tokens.put("mini", hexOf(mini[0], mini[1], mini[2], 0.9));
            tokens.put("miniBorder", hexOf(border[0], border[1], border[2], 0.28));
            return tokens;
        }
        double[] mini = rgba(mix(bg, accent, 0.06));
        double[] border = rgba(accent);
        tokens.put("bg", bg);
        tokens.put("surface", card);
        tokens.put("surface2", surface2);
        tokens.put("deep", mix(bg, accent, 0.18));
        tokens.put("border", mix(highlight, text, 0.1));
        tokens.put("accent", accent);
        tokens.put("pink", accent);
        tokens.put("muted", muted);
        tokens.put("text2", mix(muted, text, 0.4));
        tokens.put("tabbar", mix(bg, "#FFFFFF", 0.5));
        tokens.put("mini", hexOf(mini[0], mini[1], mini[2], 0.92));
        tokens.put("miniBorder", hexOf(border[0], border[1], border[2], 0.25));
        return tokens;
    }

    private static Map<String, Object> decorOf(Map<String, String> tokens, String hello) {
        Map<String, Object> decor = new LinkedHashMap<>();
        decor.put("settingsGlow", tokens.get("deep"));
        decor.put("settingsBadge", tokens.get("accent"));
        // One quiet colour for every row.
        decor.put("settingsStrips", new ArrayList<>(Arrays.asList(mix(tokens.get("surface2"), tokens.get("accent"), 0.45))));
        decor.put("homeHelloColor", hello);
        return decor;
    }

    /**
     * Everything that follows from the tokens: SoundCloud colours, decoration (and their light look). The map is in
     * the order of the script: tokens, tokensLight, parts, decor, decorLight, colors.
     */
    public static Map<String, Object> derived(Map<String, String> tokens, Map<String, String> lightTokens, List<String> parts) {
        String accent = tokens.get("accent");
        Map<String, String> colors = greyScale(tokens);
        colors.put("extended_palette_orange_900", accent);
        // Edges of the server's tiles (search genres and other sections): shades of the theme instead of a rainbow.
        String[] shades = {accent, tokens.get("pink"), mix(accent, "#FFFFFF", 0.25), mix(accent, "#000000", 0.25), tokens.get("muted")};
        for (int i = 0; i < TILE_NAMES.length; i++) colors.put("sdui_" + TILE_NAMES[i], shades[i % shades.length]);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tokens", tokens);
        if (lightTokens != null) result.put("tokensLight", lightTokens);
        result.put("parts", new ArrayList<>(parts));
        Map<String, Object> decor = decorOf(tokens, tokens.get("pink"));
        decor.put("shortcutScrim", "#00000040");
        result.put("decor", decor);
        if (lightTokens != null) result.put("decorLight", decorOf(lightTokens, lightTokens.get("accent")));
        result.put("colors", colors);
        return result;
    }

    // endregion

    // region Contrast

    /** WCAG relative luminance of a colour. */
    static double luminance(String color) {
        double[] c = rgba(color);
        double[] linear = new double[3];
        for (int i = 0; i < 3; i++) {
            double channel = c[i] / 255;
            linear[i] = channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2];
    }

    /** WCAG contrast ratio of two colours, 1 (the same) to 21 (black on white). */
    public static double contrast(String one, String two) {
        double a = luminance(one), b = luminance(two);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    /**
     * The colour moved in lightness, keeping its hue and saturation, until it reads on {@code background} with at
     * least this contrast: lighter on a dark background, darker on a light one. A colour that already does stays.
     */
    public static String ensureContrast(String color, String background, double minimum) {
        if (contrast(color, background) >= minimum) return color;
        double[] hls = hls(color);
        // The usual way first; on a mid-tone background that cannot reach the contrast, the other way can.
        boolean lighter = luminance(background) < 0.5;
        String found = moveLightness(hls, background, minimum, lighter);
        if (found == null) found = moveLightness(hls, background, minimum, !lighter);
        if (found != null) return found;
        return contrast("#FFFFFF", background) >= contrast("#000000", background) ? "#FFFFFF" : "#000000";
    }

    /** The first lightness in this direction that reads on the background, or null when none does. */
    private static String moveLightness(double[] hls, String background, double minimum, boolean lighter) {
        for (int step = 1; step <= 100; step++) {
            double level = lighter ? hls[1] + step / 100.0 : hls[1] - step / 100.0;
            if (level < 0 || level > 1) return null;
            String candidate = fromHls(hls[0], level, hls[2]);
            if (contrast(candidate, background) >= minimum) return candidate;
        }
        return null;
    }

    /** White or the dark ink, whichever reads better on this colour: for the glyph on an accent button. */
    public static String onColor(String background, String ink) {
        // White is preferred while it still reads as a graphic (3:1), the ink only for the pale accents.
        return contrast("#FFFFFF", background) >= 3.0 ? "#FFFFFF" : ink;
    }

    // endregion

    // region Palettes of a theme chosen by the user

    private static double clamp(double value, double low, double high) {
        return Math.max(low, Math.min(high, value));
    }

    /**
     * The dark palette from a background and an accent; {@code card} and {@code text} are null to be derived. The
     * background is kept dark, the steps above it (dialog, highlight) keep its hue and saturation like the shipped
     * palettes do, the text is near-white with a touch of the background's hue, and the accent is lightened if it
     * would not read on the cards.
     */
    public static Map<String, String> darkFrom(String bg, String accent, String card, String text) {
        double[] hls = hls(bg);
        double hue = hls[0], saturation = hls[2];
        double level = Math.min(hls[1], DARK_BG_MAX_LIGHTNESS);
        String surface = fromHls(hue, level, saturation);
        String dialog = card != null ? card : fromHls(hue, level + .048, saturation);
        String highlight = card != null ? fromHls(hls(card)[0], hls(card)[1] + .04, hls(card)[2]) : fromHls(hue, level + .088, saturation);
        // A grey background gives no cast to the text.
        String primary = text != null ? text
                : mix("#FFFFFF", fromHls(hue, .5, 1.0), .06 * clamp(saturation / .25, 0, 1));
        String secondary = text != null ? mix(text, surface, .35)
                : fromHls(hue, .70, clamp(saturation * .7, 0, .22));
        Map<String, String> palette = new LinkedHashMap<>();
        palette.put("surface", surface);
        palette.put("primary", primary);
        palette.put("secondary", secondary);
        palette.put("highlight", highlight);
        palette.put("special", ensureContrast(accent, dialog, 3.0));
        palette.put("error", "#FF8A80");
        palette.put("overlay", mix(surface, "#000000", .5) + "B3");
        palette.put("imageBorders", primary + "29");
        palette.put("dialog", dialog);
        return palette;
    }

    /**
     * The light palette: a light background (kept light), white cards, ink with the background's hue, and an accent
     * darkened until it reads on the background.
     */
    public static Map<String, String> lightFrom(String bg, String accent, String card, String text) {
        double[] hls = hls(bg);
        double hue = hls[0], saturation = hls[2];
        double level = Math.max(hls[1], LIGHT_BG_MIN_LIGHTNESS);
        String surface = fromHls(hue, level, saturation);
        String ink = text != null ? text : fromHls(hue, .08, Math.min(.5, saturation));
        String secondary = ensureContrast(mix(ink, "#FFFFFF", .4), surface, 4.5);
        Map<String, String> palette = new LinkedHashMap<>();
        palette.put("surface", surface);
        palette.put("primary", ink);
        palette.put("secondary", secondary);
        palette.put("highlight", fromHls(hue, level - .05, saturation));
        palette.put("special", ensureContrast(accent, surface, 4.5));
        palette.put("error", "#C62E3A");
        palette.put("overlay", ink + "66");
        palette.put("imageBorders", ink + "21");
        palette.put("dialog", card != null ? card : "#FFFFFF");
        return palette;
    }

    /** The names of the tonal colours of the system palette the Monet palettes read ({@code system_<name>}). */
    public static final String[] MONET_TONES = {
            "neutral1_0", "neutral1_10", "neutral1_50", "neutral1_100", "neutral1_800", "neutral1_900", "neutral1_1000",
            "neutral2_100", "neutral2_300", "neutral2_700", "neutral2_800", "accent1_200", "accent1_600",
    };

    /** Material You in the dark look: near-black tinted surfaces, the light accent tone for the accent. */
    public static Map<String, String> monetDark(Map<String, String> tones) {
        String surface = mix(tones.get("neutral1_1000"), tones.get("neutral1_900"), .6);
        String dialog = mix(tones.get("neutral1_900"), tones.get("neutral1_800"), .25);
        Map<String, String> palette = new LinkedHashMap<>();
        palette.put("surface", surface);
        palette.put("primary", tones.get("neutral1_100"));
        palette.put("secondary", ensureContrast(tones.get("neutral2_300"), surface, 4.5));
        palette.put("highlight", tones.get("neutral2_800"));
        palette.put("special", ensureContrast(tones.get("accent1_200"), dialog, 3.0));
        palette.put("error", "#F2B8B5");
        palette.put("overlay", mix(surface, "#000000", .5) + "B3");
        palette.put("imageBorders", tones.get("neutral1_100") + "29");
        palette.put("dialog", dialog);
        return palette;
    }

    /** Material You in the light look. */
    public static Map<String, String> monetLight(Map<String, String> tones) {
        String surface = mix(tones.get("neutral1_10"), tones.get("neutral1_50"), .4);
        Map<String, String> palette = new LinkedHashMap<>();
        palette.put("surface", surface);
        palette.put("primary", tones.get("neutral1_900"));
        palette.put("secondary", ensureContrast(tones.get("neutral2_700"), surface, 4.5));
        palette.put("highlight", tones.get("neutral2_100"));
        palette.put("special", ensureContrast(tones.get("accent1_600"), surface, 4.5));
        palette.put("error", "#B3261E");
        palette.put("overlay", tones.get("neutral1_900") + "66");
        palette.put("imageBorders", tones.get("neutral1_900") + "21");
        palette.put("dialog", tones.get("neutral1_0"));
        return palette;
    }

    /**
     * A whole theme from its two palettes: both of them, their tokens, the decoration and SoundCloud's colours. The
     * "onAccent" token (the glyph on an accent button) is added to both token sets: white, or the dark ink for a pale
     * accent.
     */
    public static Map<String, Object> theme(Map<String, String> dark, Map<String, String> light) {
        Map<String, String> darkTokens = tokensFromPalette(dark, true);
        Map<String, String> lightTokens = tokensFromPalette(light, false);
        String ink = dark.get("surface");
        darkTokens.put("onAccent", onColor(darkTokens.get("accent"), ink));
        lightTokens.put("onAccent", onColor(lightTokens.get("accent"), ink));
        Map<String, Object> theme = new LinkedHashMap<>();
        theme.put("dark", dark);
        theme.put("light", light);
        theme.putAll(derived(darkTokens, lightTokens, PARTS));
        return theme;
    }

    // endregion
}
