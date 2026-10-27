import app.revanced.extension.soundcloud.theme.PaletteEngine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the Java palette engine (extension theme/PaletteEngine.java, which makes the themes of the user on the
 * phone) against the themes the Python script tools/branding/theme_from_design.py made: for the themes that have only
 * SoundCloud's two palettes in themes.json the engine must give exactly the same tokens, tokensLight, colors, decor
 * and decorLight. Also checks the contrast helpers on extreme colours, and that the text of both looks reads on any
 * background the user can pick.
 * <p>
 * Run it with palette-parity.cmd next to this file. Exit code 0 means every check passed.
 */
@SuppressWarnings("unchecked")
public final class PaletteParity {
    private static final String[] THEMES = {"cobalt", "mint", "sakura", "lime"};
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : "patches/src/main/resources/soundcloud/theme/themes.json";
        Map<String, Object> root = (Map<String, Object>) new Json(new String(Files.readAllBytes(Paths.get(path)),
                StandardCharsets.UTF_8)).value();
        List<Object> themes = (List<Object>) root.get("themes");
        for (String id : THEMES) {
            Map<String, Object> theme = null;
            for (Object candidate : themes) {
                if (id.equals(((Map<String, Object>) candidate).get("id"))) theme = (Map<String, Object>) candidate;
            }
            if (theme == null) {
                fail(id + ": not in themes.json");
                continue;
            }
            Map<String, String> dark = strings(theme.get("dark")), light = strings(theme.get("light"));
            Map<String, String> darkTokens = PaletteEngine.tokensFromPalette(dark, true);
            Map<String, String> lightTokens = PaletteEngine.tokensFromPalette(light, false);
            Map<String, Object> derived = PaletteEngine.derived(darkTokens, lightTokens, strings((List<Object>) theme.get("parts")));
            same(id + " tokens", theme.get("tokens"), darkTokens);
            same(id + " tokensLight", theme.get("tokensLight"), lightTokens);
            same(id + " colors", theme.get("colors"), derived.get("colors"));
            same(id + " decor", theme.get("decor"), derived.get("decor"));
            same(id + " decorLight", theme.get("decorLight"), derived.get("decorLight"));
            same(id + " parts", theme.get("parts"), derived.get("parts"));
        }
        contrastChecks();
        System.out.println(checks + " checks, " + failures + " failed");
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void contrastChecks() {
        String[] backgrounds = {"#000000", "#FFFFFF", "#121212", "#F7F9FC", "#0D1117", "#808080", "#FF5500"};
        String[] colors = {"#000000", "#FFFFFF", "#FF5500", "#4C9AFF", "#A3FF3D", "#808080", "#0D1117"};
        for (String background : backgrounds) {
            for (String color : colors) {
                for (double minimum : new double[]{3.0, 4.5}) {
                    String fixed = PaletteEngine.ensureContrast(color, background, minimum);
                    boolean reachable = PaletteEngine.contrast("#FFFFFF", background) >= minimum
                            || PaletteEngine.contrast("#000000", background) >= minimum;
                    check(!reachable || PaletteEngine.contrast(fixed, background) >= minimum,
                            "ensureContrast(" + color + " on " + background + ", " + minimum + ") gave " + fixed);
                    if (PaletteEngine.contrast(color, background) >= minimum) {
                        check(fixed.equals(color), "a readable colour was changed: " + color + " on " + background);
                    }
                }
            }
        }
        // An accent equal to its background must still be made readable by the palettes.
        for (String same : new String[]{"#121212", "#0D1117", "#FFFFFF", "#000000"}) {
            Map<String, String> dark = PaletteEngine.darkFrom(same, same, null, null);
            Map<String, String> light = PaletteEngine.lightFrom(same, same, null, null);
            check(PaletteEngine.contrast(dark.get("special"), dark.get("dialog")) >= 3.0, "dark accent == background " + same);
            check(PaletteEngine.contrast(light.get("special"), light.get("surface")) >= 4.5, "light accent == background " + same);
            check(PaletteEngine.contrast(dark.get("primary"), dark.get("surface")) >= 7.0, "dark text on " + same);
            check(PaletteEngine.contrast(light.get("primary"), light.get("surface")) >= 7.0, "light text on " + same);
            check(PaletteEngine.contrast(light.get("secondary"), light.get("surface")) >= 4.5, "light secondary on " + same);
        }
        backgroundChecks();
        check("#FFFFFF".equals(PaletteEngine.onColor("#FF5500", "#101010")), "white glyph on SoundCloud orange");
        check("#101010".equals(PaletteEngine.onColor("#A3FF3D", "#101010")), "dark glyph on lime");
        check("#101010".equals(PaletteEngine.onColor("#FFFFFF", "#101010")), "dark glyph on white");
        check("#FFFFFF".equals(PaletteEngine.onColor("#000000", "#101010")), "white glyph on black");
    }

    /**
     * Whatever background the user picks, the text of both looks reads on the surface and on every card. The corners
     * of the colour square (saturated greens, yellows, cyans) are where HLS lightness alone lets a bright
     * background through. Backgrounds the editor offers as ready colours must not be changed.
     */
    private static void backgroundChecks() {
        java.util.List<String> backgrounds = new ArrayList<>();
        for (int r = 0; r <= 255; r += 51) {
            for (int g = 0; g <= 255; g += 51) {
                for (int b = 0; b <= 255; b += 51) backgrounds.add(String.format("#%02X%02X%02X", r, g, b));
            }
        }
        // The saturated edge of the square at several lightnesses, which a coarse grid of RGB misses.
        for (int hue = 0; hue < 360; hue += 10) {
            for (float brightness : new float[]{.3f, .56f, 1f}) {
                backgrounds.add(String.format("#%06X", java.awt.Color.HSBtoRGB(hue / 360f, 1f, brightness) & 0xFFFFFF));
            }
        }
        for (String background : backgrounds) {
            Map<String, String> dark = PaletteEngine.darkFrom(background, "#FF5500", null, null);
            for (String under : new String[]{"surface", "dialog", "highlight"}) {
                check(PaletteEngine.contrast(dark.get("primary"), dark.get(under)) >= 7.0,
                        "dark primary on " + under + " of " + background + ": " + PaletteEngine.contrast(dark.get("primary"), dark.get(under)));
                check(PaletteEngine.contrast(dark.get("secondary"), dark.get(under)) >= 4.5,
                        "dark secondary on " + under + " of " + background + ": " + PaletteEngine.contrast(dark.get("secondary"), dark.get(under)));
            }
            check(PaletteEngine.contrast(dark.get("special"), dark.get("dialog")) >= 3.0, "dark accent on the cards of " + background);
            Map<String, String> light = PaletteEngine.lightFrom(background, "#FF5500", null, null);
            for (String under : new String[]{"surface", "dialog", "highlight"}) {
                check(PaletteEngine.contrast(light.get("primary"), light.get(under)) >= 7.0, "light primary on " + under + " of " + background);
                check(PaletteEngine.contrast(light.get("secondary"), light.get(under)) >= 4.5, "light secondary on " + under + " of " + background);
            }
        }
        // The ready backgrounds of the editor keep their colour.
        for (String ready : new String[]{"#121212", "#000000", "#0D1117", "#0A1117", "#0E0809", "#0D110F", "#15121C", "#100C18",
                "#14110F", "#101820"}) {
            check(ready.equals(PaletteEngine.darkFrom(ready, "#FF5500", null, null).get("surface")), "dark background changed: " + ready);
            check(!PaletteEngine.darkTextAdjusted(ready), "dark text moved on a ready background: " + ready);
        }
        for (String ready : new String[]{"#FFFFFF", "#F7F9FC", "#F5F9FB", "#F7FAF8", "#FEF8F9", "#FFF7FA", "#F9F8FC", "#FAF6EE", "#F2F2F2"}) {
            check(ready.equals(PaletteEngine.lightFrom(ready, "#FF5500", null, null).get("surface")), "light background changed: " + ready);
        }
        // The worst case in the report of the review: pure yellow, green and cyan as the dark background.
        for (String bright : new String[]{"#FFFF00", "#00FF00", "#00FFFF"}) {
            String surface = PaletteEngine.darkFrom(bright, "#FF5500", null, null).get("surface");
            check(!bright.equals(surface), "a bright background was kept: " + bright);
        }
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) fail(message);
    }

    private static void fail(String message) {
        failures++;
        System.out.println("FAIL " + message);
    }

    /** Equal as JSON values; maps by content, not by order. */
    private static void same(String name, Object expected, Object actual) {
        checks++;
        String difference = difference(expected, actual);
        if (difference != null) fail(name + ": " + difference);
        else System.out.println("ok   " + name);
    }

    private static String difference(Object expected, Object actual) {
        if (expected instanceof Map && actual instanceof Map) {
            Map<String, Object> a = (Map<String, Object>) expected, b = (Map<String, Object>) actual;
            for (String key : a.keySet()) {
                if (!b.containsKey(key)) return "missing " + key;
                String inner = difference(a.get(key), b.get(key));
                if (inner != null) return key + " " + inner;
            }
            for (String key : b.keySet()) if (!a.containsKey(key)) return "extra " + key;
            return null;
        }
        if (expected instanceof List && actual instanceof List) {
            List<Object> a = (List<Object>) expected, b = (List<Object>) actual;
            if (a.size() != b.size()) return "length " + a.size() + " vs " + b.size();
            for (int i = 0; i < a.size(); i++) {
                String inner = difference(a.get(i), b.get(i));
                if (inner != null) return "[" + i + "] " + inner;
            }
            return null;
        }
        return String.valueOf(expected).equals(String.valueOf(actual)) ? null : "expected " + expected + " but got " + actual;
    }

    private static Map<String, String> strings(Object map) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : ((Map<String, Object>) map).entrySet()) result.put(entry.getKey(), String.valueOf(entry.getValue()));
        return result;
    }

    private static List<String> strings(List<Object> list) {
        List<String> result = new ArrayList<>();
        for (Object item : list) result.add(String.valueOf(item));
        return result;
    }

    /** Just enough JSON for themes.json. */
    private static final class Json {
        private final String text;
        private int at;

        Json(String text) {
            this.text = text;
        }

        Object value() {
            space();
            char c = text.charAt(at);
            if (c == '{') {
                Map<String, Object> map = new LinkedHashMap<>();
                at++;
                space();
                if (text.charAt(at) == '}') {
                    at++;
                    return map;
                }
                while (true) {
                    space();
                    String key = string();
                    space();
                    at++; // the colon
                    map.put(key, value());
                    space();
                    if (text.charAt(at++) == '}') return map;
                }
            }
            if (c == '[') {
                List<Object> list = new ArrayList<>();
                at++;
                space();
                if (text.charAt(at) == ']') {
                    at++;
                    return list;
                }
                while (true) {
                    list.add(value());
                    space();
                    if (text.charAt(at++) == ']') return list;
                }
            }
            if (c == '"') return string();
            int start = at;
            while (at < text.length() && ",}] \n\r\t".indexOf(text.charAt(at)) < 0) at++;
            return text.substring(start, at);
        }

        private String string() {
            StringBuilder out = new StringBuilder();
            at++;
            while (text.charAt(at) != '"') {
                char c = text.charAt(at++);
                if (c == '\\') {
                    char next = text.charAt(at++);
                    if (next == 'u') {
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    } else {
                        out.append(next == 'n' ? '\n' : next == 't' ? '\t' : next);
                    }
                } else {
                    out.append(c);
                }
            }
            at++;
            return out.toString();
        }

        private void space() {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
        }
    }
}
