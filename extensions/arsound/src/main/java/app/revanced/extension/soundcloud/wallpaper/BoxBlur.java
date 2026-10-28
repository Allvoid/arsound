package app.revanced.extension.soundcloud.wallpaper;

/**
 * A blur of ARGB pixels by a box of the given radius, run three times, which is close to a Gaussian one. Pure
 * arithmetic with no {@code android.graphics}, so it also runs in the JVM tests.
 */
final class BoxBlur {
    private BoxBlur() {
    }

    /** Blurs the picture's colours three times with a box of {@code radius}; the edges repeat their last pixel. */
    static void apply(int[] pixels, int width, int height, int radius) {
        int[] line = new int[Math.max(width, height)];
        int[] blurred = new int[line.length];
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < height; y++) {
                System.arraycopy(pixels, y * width, line, 0, width);
                blurLine(line, blurred, width, radius);
                System.arraycopy(blurred, 0, pixels, y * width, width);
            }
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) line[y] = pixels[y * width + x];
                blurLine(line, blurred, height, radius);
                for (int y = 0; y < height; y++) pixels[y * width + x] = blurred[y];
            }
        }
    }

    /** One row or column with a sliding sum per colour, so the cost does not depend on the radius. */
    static void blurLine(int[] source, int[] target, int length, int radius) {
        int red = 0, green = 0, blue = 0;
        int window = radius * 2 + 1;
        for (int i = -radius; i <= radius; i++) {
            int pixel = source[Math.max(0, Math.min(length - 1, i))];
            red += (pixel >> 16) & 0xFF;
            green += (pixel >> 8) & 0xFF;
            blue += pixel & 0xFF;
        }
        for (int i = 0; i < length; i++) {
            // Rounded, not cut off: six passes of cutting would darken the picture.
            int half = window / 2;
            target[i] = 0xFF000000 | ((red + half) / window << 16) | ((green + half) / window << 8) | ((blue + half) / window);
            int leaving = source[Math.max(0, i - radius)];
            int entering = source[Math.min(length - 1, i + radius + 1)];
            red += ((entering >> 16) & 0xFF) - ((leaving >> 16) & 0xFF);
            green += ((entering >> 8) & 0xFF) - ((leaving >> 8) & 0xFF);
            blue += (entering & 0xFF) - (leaving & 0xFF);
        }
    }
}
