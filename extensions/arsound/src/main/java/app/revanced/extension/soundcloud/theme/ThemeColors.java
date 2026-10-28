package app.revanced.extension.soundcloud.theme;

/** Colours of the themes as themes.json writes them, and the original look's colours for Arsound's own screens. Pure, no Android. */
final class ThemeColors {
    /** What a colour missing from themes.json shows as: a pink placeholder is better than no theme at all. */
    static final int MISSING = 0xFFFF00FF;
    /** The orange of SoundCloud. */
    private static final int ORANGE = 0xFFFF5500;

    private ThemeColors() {
    }

    /** A colour written as in themes.json, #rrggbb or #rrggbbaa, as Android's ARGB int. */
    static int parse(String hex) {
        // A role missing from themes.json gives an empty string.
        if (hex == null || hex.length() < 2) return MISSING;
        String value = hex.substring(1);
        // #rrggbbaa as in the design, to Android's aarrggbb.
        if (value.length() == 8) value = value.substring(6) + value.substring(0, 6);
        else value = "ff" + value;
        return (int) Long.parseLong(value, 16);
    }

    /**
     * SoundCloud's own colour for a role of the palette ("surface", "primary", "secondary", "highlight", "error",
     * "dialog"; the accent for any other), in the dark or the light look.
     */
    static int original(String role, boolean night) {
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
                return ORANGE;
        }
    }
}
