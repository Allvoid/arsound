package app.revanced.extension.soundcloud.wallpaper;

import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.View;

/** The kinds of view that {@link ScreenBackgrounds} treats differently when it clears a screen's base layers. */
class ViewKind {
    /**
     * A top bar (SoundCloud's app bars are Material's {@code AppBarLayout}, its toolbars {@code Toolbar}s). The
     * library turns the colour of a bar's background into a shape drawable, so that counts as a base layer too.
     */
    static final ViewKind BAR = new ViewKind(true, "AppBarLayout", "Toolbar") {
        @Override
        boolean isBaseLayer(Drawable background, SurfaceColours surfaces) {
            return super.isBaseLayer(background, surfaces)
                    || ClassNames.hasParentNamed(background.getClass(), "MaterialShapeDrawable");
        }
    };
    /**
     * A list or a Compose host: its own background may be a base layer, but what is inside belongs to the rows and
     * to Compose (which takes its base colour from the screen hook, see {@link WallpaperSurfaces}).
     */
    static final ViewKind LEAF = new ViewKind(false, "RecyclerView", "AbsListView", "AbstractComposeView");
    /** Any other view: a container whose fill is the screen's base layer. */
    private static final ViewKind PLAIN = new ViewKind(true);

    /** In the order they are tried. */
    private static final ViewKind[] KNOWN = {BAR, LEAF};

    private final boolean walkedInside;
    private final String[] names;

    private ViewKind(boolean walkedInside, String... names) {
        this.walkedInside = walkedInside;
        this.names = names;
    }

    static ViewKind of(View view) {
        for (ViewKind kind : KNOWN) {
            if (ClassNames.hasParentNamed(view.getClass(), kind.names)) return kind;
        }
        return PLAIN;
    }

    /** True if the views inside are looked at too. */
    boolean walkedInside() {
        return walkedInside;
    }

    /** True if the background is an opaque fill with a surface colour: the base layer that hides the wallpaper. */
    boolean isBaseLayer(Drawable background, SurfaceColours surfaces) {
        return background instanceof ColorDrawable && surfaces.covers(((ColorDrawable) background).getColor());
    }
}
