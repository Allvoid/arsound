package app.revanced.extension.soundcloud.shared;

/**
 * Small colour calculations on ARGB ints. Pure arithmetic with no {@code android.graphics.Color}, so it also runs in
 * the JVM tests.
 */
public final class Colors {
    private Colors() {
    }

    /** How bright the eye sees the colour, 0 for black to 1 for white (the 0.299, 0.587, 0.114 weighting). */
    public static double brightness(int color) {
        return (0.299 * ((color >> 16) & 0xFF) + 0.587 * ((color >> 8) & 0xFF) + 0.114 * (color & 0xFF)) / 255;
    }

    /** {@code darkInk} on a colour brighter than the threshold, {@code lightInk} on any other. */
    public static int ink(int color, double threshold, int darkInk, int lightInk) {
        return brightness(color) > threshold ? darkInk : lightInk;
    }

    /** White or near-black, whichever reads on this colour: for text on an accent button. */
    public static int contrast(int color) {
        return ink(color, 0.6, 0xFF111111, 0xFFFFFFFF);
    }

    /** The ink that stands out from a text colour: for what sits on a fill in that colour. */
    public static int inverse(int color) {
        return ink(color, 0.5, 0xFF121212, 0xFFFFFFFF);
    }

    /** The colour with its alpha replaced, 0 (clear) to 255 (solid). */
    public static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** The solid colour a part of the way from one colour to another: 0 gives {@code from}, 1 gives {@code to}. */
    public static int blend(int from, int to, float amount) {
        int r = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * amount);
        int g = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * amount);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
