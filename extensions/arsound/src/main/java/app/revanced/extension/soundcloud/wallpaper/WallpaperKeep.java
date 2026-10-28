package app.revanced.extension.soundcloud.wallpaper;

/**
 * Whether a downloaded picture can be kept as it is, byte for byte. A JPEG that is not bigger than the app keeps anyway
 * needs no second pass through the encoder, which would only lose a little of it. Pure rules, no Android.
 */
final class WallpaperKeep {
    private WallpaperKeep() {
    }

    /**
     * @param jpeg   Whether the file is a JPEG.
     * @param width  The picture's width in pixels.
     * @param height The picture's height in pixels.
     */
    static boolean canKeepAsIs(boolean jpeg, int width, int height) {
        return jpeg && width > 0 && height > 0
                && Math.max(width, height) <= WallpaperBaker.MAX_SIDE
                && (long) width * height <= WallpaperBaker.MAX_PIXELS;
    }
}
