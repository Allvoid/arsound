package app.revanced.extension.soundcloud.theme;

/**
 * Which of some colours is nearest to a colour, as the eye sees it: the distance is measured in OKLab, where equal
 * steps look about equally different (a plain RGB distance can call a dark blue closer to a dark brown than to a
 * bluer dark blue). No Android here, so it can be tested on the JVM.
 */
final class SplashColors {
    private SplashColors() {
    }

    /** The colour in OKLab: lightness, then the green-red and the blue-yellow axes. */
    static double[] oklab(int argb) {
        double r = linear((argb >> 16) & 0xFF);
        double g = linear((argb >> 8) & 0xFF);
        double b = linear(argb & 0xFF);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        return new double[]{
                0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
                1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
                0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s,
        };
    }

    /** One sRGB channel (0-255) as light intensity (0-1). */
    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /** How far apart two colours look (the alpha is ignored). */
    static double distance(int first, int second) {
        double[] a = oklab(first);
        double[] b = oklab(second);
        double dl = a[0] - b[0], da = a[1] - b[1], db = a[2] - b[2];
        return Math.sqrt(dl * dl + da * da + db * db);
    }

    /** The index of the palette colour nearest to {@code argb}; of equal ones the first, so an exact match comes first. */
    static int nearest(int[] palette, int argb) {
        if (palette.length == 0) throw new IllegalArgumentException("Empty palette");
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < palette.length; i++) {
            double distance = distance(palette[i], argb);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }
}
