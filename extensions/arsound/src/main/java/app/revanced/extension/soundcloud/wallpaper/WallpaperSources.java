package app.revanced.extension.soundcloud.wallpaper;

import app.revanced.extension.soundcloud.settings.Settings;

/**
 * The wallpaper sources there are, and which one the catalogue shows. The sources are listed on every call rather than in
 * a field, so no source's class has to be ready while another one's is being set up.
 */
public final class WallpaperSources {
    private WallpaperSources() {
    }

    /** Every source, in the order the switch offers them: Wallhaven first. */
    public static WallpaperSource[] all() {
        return new WallpaperSource[]{Wallhaven.SOURCE, CommonsSource.INSTANCE};
    }

    /** The source with this id; Wallhaven for an id nobody knows (a setting from a newer version, or none). */
    public static WallpaperSource byId(String id) {
        for (WallpaperSource source : all()) if (source.id().equals(id)) return source;
        return Wallhaven.SOURCE;
    }

    /**
     * The source whose own host the address is on, or null when it is on none. Every read of a picture asks this first:
     * an address that no source owns is never fetched.
     */
    public static WallpaperSource owning(String address) {
        for (WallpaperSource source : all()) if (source.ownsHost(address)) return source;
        return null;
    }

    /** The source the user chose last; Wallhaven until a choice is made. */
    public static WallpaperSource chosen() {
        return byId(Settings.getString(Settings.WALLPAPER_SOURCE, Wallhaven.SOURCE.id()));
    }

    /** Saves the user's choice of source. */
    public static void choose(WallpaperSource source) {
        Settings.putString(Settings.WALLPAPER_SOURCE, source.id());
    }
}
