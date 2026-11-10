package app.revanced.extension.soundcloud.settings;

/**
 * The two lines of Arsound's mark (the letter line and the accent line) in colours that read on a tile of a given
 * colour. The mark of the app is a white line and an azure line; the azure one is lost on a blue tile and the pair
 * looks wrong on a red one. A theme puts the logo of the settings on a tile of its own colour, so the lines are chosen
 * for that tile: the letter white, or very dark on a light tile, and the accent a shade of the tile's hue that stands
 * out from both the tile and the letter. Pure: no Android, so it can be tested on the JVM.
 */
final class LogoColors {
    /** The azure of the logo; kept on a grey, black or white tile, which has no hue of its own. */
    static final int AZURE = 0xFF4C9BFF;
    private static final int WHITE = 0xFFFFFFFF;
    /** The letter must stand out from the tile at least this much (WCAG contrast; 3 is the bar for large graphics). */
    static final double LETTER_CONTRAST = 3.0;
    /** The accent line must stand out from the tile and from the letter at least this much. */
    private static final double ACCENT_CONTRAST = 2.0;
    /** Below this saturation a tile has no hue to take a shade of. */
    private static final double GREY = 0.12;

    final int letter;
    final int accent;

    private LogoColors(int letter, int accent) {
        this.letter = letter;
        this.accent = accent;
    }

    /** @param tile The opaque colour of the tile behind the mark. */
    static LogoColors forTile(int tile) {
        tile |= 0xFF000000;
        double[] hsl = toHsl(tile);
        double saturation = Math.max(hsl[1], 0.55);
        int letter = WHITE;
        if (contrast(WHITE, tile) < LETTER_CONTRAST) {
            // A light tile: the letter goes dark, in the tile's hue so it does not look like a hole.
            letter = fromHsl(hsl[0], hsl[1] < GREY ? 0 : Math.min(hsl[1], 0.7), 0.10);
        }
        if (hsl[1] < GREY) {
            // Grey, black or white tile: the logo's own azure when it stands out from both.
            if (Math.min(contrast(AZURE, tile), contrast(AZURE, letter)) >= 1.8) return new LogoColors(letter, AZURE);
            saturation = 0;
        }
        // The shade nearest in lightness to the tile that still stands out from the tile and from the letter, so the
        // line stays a colour of the hue and not black or white; the one that stands out most when none is far enough.
        int accent = tile;
        double best = -1;
        double nearest = Double.MAX_VALUE;
        boolean found = false;
        for (int step = 4; step <= 96; step += 2) {
            double lightness = step / 100.0;
            int candidate = fromHsl(hsl[0], saturation, lightness);
            double score = Math.min(contrast(candidate, tile), contrast(candidate, letter));
            double distance = Math.abs(lightness - hsl[2]);
            if (score >= ACCENT_CONTRAST) {
                if (!found || distance < nearest) {
                    found = true;
                    nearest = distance;
                    accent = candidate;
                }
            } else if (!found && score > best) {
                best = score;
                accent = candidate;
            }
        }
        return new LogoColors(letter, accent);
    }

    /** WCAG contrast ratio of two opaque colours, from 1 (the same) to 21. */
    static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(color >> 16 & 0xFF) + 0.7152 * linear(color >> 8 & 0xFF) + 0.0722 * linear(color & 0xFF);
    }

    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.03928 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /** Hue in degrees, saturation and lightness in 0..1. */
    static double[] toHsl(int color) {
        double r = (color >> 16 & 0xFF) / 255.0;
        double g = (color >> 8 & 0xFF) / 255.0;
        double b = (color & 0xFF) / 255.0;
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double lightness = (max + min) / 2;
        double delta = max - min;
        if (delta == 0) return new double[]{0, 0, lightness};
        double saturation = delta / (1 - Math.abs(2 * lightness - 1));
        double hue;
        if (max == r) hue = ((g - b) / delta) % 6;
        else if (max == g) hue = (b - r) / delta + 2;
        else hue = (r - g) / delta + 4;
        hue *= 60;
        if (hue < 0) hue += 360;
        return new double[]{hue, saturation, lightness};
    }

    static int fromHsl(double hue, double saturation, double lightness) {
        double chroma = (1 - Math.abs(2 * lightness - 1)) * saturation;
        double x = chroma * (1 - Math.abs((hue / 60) % 2 - 1));
        double m = lightness - chroma / 2;
        double r, g, b;
        int sector = (int) (hue / 60) % 6;
        switch (sector) {
            case 0: r = chroma; g = x; b = 0; break;
            case 1: r = x; g = chroma; b = 0; break;
            case 2: r = 0; g = chroma; b = x; break;
            case 3: r = 0; g = x; b = chroma; break;
            case 4: r = x; g = 0; b = chroma; break;
            default: r = chroma; g = 0; b = x; break;
        }
        return 0xFF000000 | channel(r + m) << 16 | channel(g + m) << 8 | channel(b + m);
    }

    private static int channel(double value) {
        return (int) Math.round(Math.max(0, Math.min(1, value)) * 255);
    }
}
