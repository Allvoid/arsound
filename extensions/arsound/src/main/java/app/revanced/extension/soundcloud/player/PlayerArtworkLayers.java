package app.revanced.extension.soundcloud.player;

import android.view.View;
import android.view.ViewParent;

import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;

/**
 * Keeps the artwork of the player pages from being redrawn on every frame of the slide.
 * <p>
 * Each player page has its artwork in two material image views, which clear the area outside their rounded corners
 * and so keep a hardware layer, a picture of their own as big as the screen. While the sheet slides, SoundCloud
 * gives them their corners again on every frame, for the page in view and for the pages beside it, which makes
 * the phone draw all of those layers anew each time. On a big screen that is more video memory than the
 * graphics cache holds, so everything else is thrown out of it and loaded again, and the way down stutters.
 * <p>
 * Three things stop that. A corner that is already set is not set again. The layers are kept only while the corners
 * are really rounded: the full player has none, and a square image needs no layer. They are given back when the
 * player has opened fully, not on the way up: the sheet crosses the middle of its way in both directions, and a layer
 * given back there would be made and drawn again as soon as the finger turns. And the pages beside the one in view
 * take no part in the slide, they only get the final look when the sheet has arrived.
 * <p>
 * All of it is switched by the "Smooth player collapse" setting; off, SoundCloud's own behaviour comes back.
 */
@SuppressWarnings("unused")
public final class PlayerArtworkLayers {
    /** The corner last given to the artwork of a page, to see that SoundCloud asks for the same again. Main thread only. */
    private static final Map<View, Integer> corners = new WeakHashMap<>();

    private static int imageId = -1, overlayId = -1;

    private PlayerArtworkLayers() {
    }

    /**
     * Injection point, the start of the artwork's "round the corners". Returns whether SoundCloud still has to do it:
     * false when the corners are already like that.
     */
    public static boolean round(View artwork, int corner) {
        try {
            if (!PlayerSheet.isSmoothEnabled()) {
                restore(artwork);
                return true;
            }
            Integer last = corners.get(artwork);
            if (last != null && last == corner) return false;
            corners.put(artwork, corner);
            // The layer comes before the corners. It goes when there are none to cut, but not in the middle of a
            // slide: that waits for the sheet to rest in sheetRested.
            if (corner > 0) setLayers(artwork, View.LAYER_TYPE_HARDWARE);
            else if (!PlayerSheet.isBusy()) setLayers(artwork, View.LAYER_TYPE_NONE);
        } catch (Exception ex) {
            Logger.printException(() -> "Player artwork: corners not checked", ex);
        }
        return true;
    }

    /** Injection point, the start of the artwork's "square corners". The same as {@link #round} with no corner. */
    public static boolean square(View artwork) {
        return round(artwork, 0);
    }

    /**
     * Injection point, when the page's artwork gets its image view. It starts without corners, so it starts without
     * layers: a page made while the player is open would otherwise keep two screen-sized layers until the next slide.
     */
    public static void created(View artwork) {
        try {
            if (PlayerSheet.isSmoothEnabled()) setLayers(artwork, View.LAYER_TYPE_NONE);
        } catch (Exception ex) {
            Logger.printException(() -> "Player artwork: layers not released", ex);
        }
    }

    /**
     * Called by {@link PlayerSheet} when the sheet has come to rest in a state. In the open player the artwork has no
     * corners, so the layers the slide kept for it are given back; the next way down makes them again.
     */
    static void sheetRested(int state) {
        if (state != PlayerSheet.EXPANDED || !PlayerSheet.isSmoothEnabled()) return;
        try {
            for (Map.Entry<View, Integer> page : corners.entrySet()) {
                if (page.getValue() == 0) setLayers(page.getKey(), View.LAYER_TYPE_NONE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Player artwork: layers not given back", ex);
        }
    }

    /**
     * Injection point, the start of a page's slide to the offset f (0 collapsed, 1 open). Returns whether the page
     * sits this one out: it does when it lies beside the pager's visible area. The ends of the slide, 0 and 1, always
     * go through, so every page ends up in the look of the player's state.
     */
    public static boolean skipSlide(View page, float f) {
        try {
            if (!(f > 0f && f < 1f) || !PlayerSheet.isSmoothEnabled()) return false;
            ViewParent parent = page.getParent();
            if (!(parent instanceof View) || page.getWidth() <= 0) return false;
            View pager = (View) parent;
            if (pager.getWidth() <= 0) return false;
            // The pager keeps the neighbours at a page's width to the side and scrolls its content to the visible one.
            int left = page.getLeft() - pager.getScrollX();
            return left >= pager.getWidth() || left + page.getWidth() <= 0;
        } catch (Exception ex) {
            Logger.printException(() -> "Player artwork: page not checked", ex);
            return false;
        }
    }

    /** Gives the page's artwork the layers it has in SoundCloud, after the setting was switched off. */
    private static void restore(View artwork) {
        corners.remove(artwork);
        setLayers(artwork, View.LAYER_TYPE_HARDWARE);
    }

    private static void setLayers(View artwork, int type) {
        if (imageId < 0) {
            imageId = Utils.getResourceIdentifier(ResourceType.ID, "artwork_image_view");
            overlayId = Utils.getResourceIdentifier(ResourceType.ID, "artwork_overlay_image");
        }
        setLayer(artwork.findViewById(imageId), type);
        setLayer(artwork.findViewById(overlayId), type);
    }

    private static void setLayer(View view, int type) {
        if (view != null && view.getLayerType() != type) view.setLayerType(type, null);
    }
}
