package app.revanced.extension.soundcloud.wallpaper;

import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;

/**
 * Clears the opaque base layers of a screen's views, so that the wallpaper in the window shows through.
 * <p>
 * Only the structure is walked: the containers from the screen's root down, a few levels deep. The rows of lists and
 * Compose content are never entered, so cards and cells keep their colours.
 */
final class ScreenBackgrounds {
    /** SoundCloud's screens are a few containers deep before the list or the Compose host begins. */
    private static final int MAX_DEPTH = 10;

    private ScreenBackgrounds() {
    }

    /** Takes the opaque surface fill off the view and the structure below it, top bars included. */
    static void relax(View root, SurfaceColours surfaces) {
        relax(root, surfaces, 0);
    }

    private static void relax(View view, SurfaceColours surfaces, int depth) {
        ViewKind kind = ViewKind.of(view);
        Drawable background = view.getBackground();
        if (background != null && kind.isBaseLayer(background, surfaces)) view.setBackground(null);
        if (!kind.walkedInside() || depth >= MAX_DEPTH || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            relax(group.getChildAt(i), surfaces, depth + 1);
        }
    }
}
