package app.revanced.extension.soundcloud.theme.effect;

/**
 * Colours in OKLCH (lightness, chroma, hue), which the soft blobs of «Monet» are made of: the desktop paints them with
 * the hue of the cover at a fixed lightness and chroma, and the phone does the same with the hue of the accent. Pure
 * arithmetic, so it also runs in the JVM tests.
 */
final class BlobColours {
    private BlobColours() {
    }

    /** The hue of an sRGB colour in degrees, 0..360. A grey colour (no chroma) has the hue 0. */
    static double hueOf(int argb) {
        double r = linear(((argb >> 16) & 0xFF) / 255.0);
        double g = linear(((argb >> 8) & 0xFF) / 255.0);
        double b = linear((argb & 0xFF) / 255.0);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        double a = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        double bb = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
        if (Math.hypot(a, bb) < 1e-4) return 0;
        return normalize(Math.toDegrees(Math.atan2(bb, a)));
    }

    /** The colour as 0xAARRGGBB; a colour outside sRGB is pulled in by cutting each channel to its range. */
    static int argb(double lightness, double chroma, double hueDegrees, int alpha) {
        double h = Math.toRadians(hueDegrees);
        double a = chroma * Math.cos(h);
        double b = chroma * Math.sin(h);
        double l = cube(lightness + 0.3963377774 * a + 0.2158037573 * b);
        double m = cube(lightness - 0.1055613458 * a - 0.0638541728 * b);
        double s = cube(lightness - 0.0894841775 * a - 1.2914855480 * b);
        double r = 4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s;
        double g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s;
        double bl = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s;
        return ((alpha & 0xFF) << 24) | (channel(r) << 16) | (channel(g) << 8) | channel(bl);
    }

    /** A hue turned into 0..360. */
    static double normalize(double degrees) {
        double value = degrees % 360;
        return value < 0 ? value + 360 : value;
    }

    private static double cube(double value) {
        return value * value * value;
    }

    private static double linear(double channel) {
        return channel <= 0.04045 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
    }

    private static int channel(double linear) {
        double clamped = Math.max(0, Math.min(1, linear));
        double encoded = clamped <= 0.0031308 ? clamped * 12.92 : 1.055 * Math.pow(clamped, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0, Math.min(1, encoded)) * 255);
    }
}
