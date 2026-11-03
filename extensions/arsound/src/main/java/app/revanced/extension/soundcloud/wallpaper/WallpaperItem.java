package app.revanced.extension.soundcloud.wallpaper;

/**
 * One picture of a catalogue's results, of whichever source ({@link #source}): Wallhaven or Wikimedia Commons. The grid,
 * its loaders ({@code ThumbnailLoader}, {@code SharpLoader}) and the preview work with it.
 */
public final class WallpaperItem {
    /** Where the picture comes from: it knows the hosts to fetch from and the lines the preview shows. */
    public final WallpaperSource source;
    public final String id;
    public final String purity;
    public final String resolution;
    public final String thumbnail;
    public final String fullUrl;
    /**
     * The size in bytes of the full file, which the grid may fetch to make a cell sharp ({@code SharpPolicy}); 0 when the
     * source offers no such file (its thumbnail is as wide as a cell, so there is nothing to sharpen).
     */
    public final long fileSize;
    /** How many bytes the preview downloads: {@link #fileSize} when that is known, else an estimate, else 0. */
    public final long previewBytes;
    public final int views;
    public final int favorites;
    /** The address of the picture's page on the site, or an empty string. */
    public final String pageUrl;
    /** Who made the picture, the licence and a short description; empty for Wallhaven, which gives none. */
    public final String author, licence, description;

    WallpaperItem(WallpaperSource source, String id, String purity, String resolution, String thumbnail,
                  String fullUrl, long fileSize, long previewBytes, int views, int favorites, String pageUrl,
                  String author, String licence, String description) {
        this.source = source;
        this.id = id;
        this.purity = purity;
        this.resolution = resolution;
        this.thumbnail = thumbnail;
        this.fullUrl = fullUrl;
        this.fileSize = fileSize;
        this.previewBytes = previewBytes;
        this.views = views;
        this.favorites = favorites;
        this.pageUrl = pageUrl;
        this.author = author;
        this.licence = licence;
        this.description = description;
    }

    public boolean isSafe() {
        return "sfw".equals(purity);
    }

    /** True for a picture wider than it is tall: in a grid of tall cells only its middle shows. */
    public boolean isWide() {
        return WallhavenRules.isWide(resolution);
    }

    /** What a screen reader says of the picture: its description when it has one, else its size. */
    public String label() {
        return description.isEmpty() ? resolution : description;
    }
}
