package app.revanced.extension.soundcloud.player;

/**
 * The picture behind the whole cover ({@link WholeCover}): the cover shrunk to a few dozen pixels, blurred and
 * darkened. Stretched over the page it gives a soft wash in the cover's colours, and the title, the buttons and the
 * white waveform stay readable on it. Pure arithmetic on ARGB pixels, so it is tested without Android.
 */
final class CoverBackdrop {
    /** The side of the small picture, in pixels. */
    static final int SIDE = 24;
    /** How far one pass of the blur reaches, in pixels of the small picture. */
    static final int RADIUS = 3;
    /** How much of the brightness stays after darkening. */
    static final float KEEP = 0.55f;

    private CoverBackdrop() {
    }

    /**
     * Blurs a square picture of {@code side} by {@code side} pixels twice (across, then down) and darkens it. The
     * pixels are changed in place and returned, all of them opaque.
     */
    static int[] process(int[] pixels, int side) {
        if (pixels.length != side * side || side == 0) throw new IllegalArgumentException("Not a square of " + side);
        int[] spare = new int[pixels.length];
        for (int pass = 0; pass < 2; pass++) {
            blur(pixels, spare, side, true);
            blur(spare, pixels, side, false);
        }
        for (int i = 0; i < pixels.length; i++) pixels[i] = darken(pixels[i]);
        return pixels;
    }

    /**
     * One pass of a box blur along the rows ({@code across}) or the columns: each pixel becomes the average of the
     * {@link #RADIUS} pixels on either side of it, the edge pixel standing in for those past the edge.
     */
    private static void blur(int[] from, int[] to, int side, boolean across) {
        int span = 2 * RADIUS + 1;
        for (int line = 0; line < side; line++) {
            for (int at = 0; at < side; at++) {
                int r = 0, g = 0, b = 0;
                for (int k = -RADIUS; k <= RADIUS; k++) {
                    int along = Math.min(side - 1, Math.max(0, at + k));
                    int pixel = from[across ? line * side + along : along * side + line];
                    r += (pixel >> 16) & 0xFF;
                    g += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                }
                to[across ? line * side + at : at * side + line] =
                        0xFF000000 | (r / span) << 16 | (g / span) << 8 | (b / span);
            }
        }
    }

    /** The pixel with {@link #KEEP} of its brightness, opaque. */
    static int darken(int pixel) {
        int r = Math.round(((pixel >> 16) & 0xFF) * KEEP);
        int g = Math.round(((pixel >> 8) & 0xFF) * KEEP);
        int b = Math.round((pixel & 0xFF) * KEEP);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }
}
