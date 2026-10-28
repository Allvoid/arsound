package app.revanced.extension.soundcloud.wallpaper;

/**
 * When the full file of a Wallhaven picture may be downloaded just to look at it: to sharpen a cell of the grid, or to
 * fill the preview. The thumbnail Wallhaven gives is only 300 pixels, so the full file is the way to a sharp picture,
 * but it costs traffic: it is kept to small files and to networks that are not metered. Pure rules, no Android.
 */
public final class SharpPolicy {
    /** A cell of the grid takes the full file only up to this size. */
    public static final long GRID_MAX_BYTES = 6L * 1024 * 1024;
    /** The preview downloads the full file on its own up to this size on a network that is not metered. */
    public static final long PREVIEW_MAX_BYTES = 25L * 1024 * 1024;
    /** The same on a metered network (mobile data): a smaller file, bigger ones wait for a tap. */
    public static final long PREVIEW_MAX_BYTES_METERED = 8L * 1024 * 1024;

    private SharpPolicy() {
    }

    /** True when a cell of the grid may load the full file: its size is known and small, and the network is free. */
    public static boolean gridMayLoad(long fileSize, boolean metered) {
        return !metered && fileSize > 0 && fileSize <= GRID_MAX_BYTES;
    }

    /** True when the preview may download the full file without being asked. An unknown size counts as small. */
    public static boolean previewMayLoad(long fileSize, boolean metered) {
        return fileSize <= (metered ? PREVIEW_MAX_BYTES_METERED : PREVIEW_MAX_BYTES);
    }
}
