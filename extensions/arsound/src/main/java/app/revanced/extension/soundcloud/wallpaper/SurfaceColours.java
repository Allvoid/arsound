package app.revanced.extension.soundcloud.wallpaper;

/**
 * The colours SoundCloud's screens fill their background with: the theme's surface and the few names it has (the
 * window colour, Material's surface). A view with one of them as an opaque background is the base layer of a screen,
 * the one that hides the wallpaper; any other colour is a card, a chip or a bar that is meant to stay.
 * <p>
 * Pure logic, without Android, so it is tested on the JVM.
 */
final class SurfaceColours {
    private final int screen;
    private final int[] names;

    /**
     * @param screen The theme's surface colour (its alpha is ignored).
     * @param others The other colours that mean the same surface in SoundCloud's styles.
     */
    SurfaceColours(int screen, int... others) {
        this.screen = screen & 0x00FFFFFF;
        this.names = new int[others.length];
        for (int i = 0; i < others.length; i++) names[i] = others[i] & 0x00FFFFFF;
    }

    /** True for an opaque colour that is one of the surface colours. A translucent one is left as it is. */
    boolean covers(int argb) {
        if ((argb >>> 24) != 0xFF) return false;
        int rgb = argb & 0x00FFFFFF;
        if (rgb == screen) return true;
        for (int name : names) if (name == rgb) return true;
        return false;
    }
}
