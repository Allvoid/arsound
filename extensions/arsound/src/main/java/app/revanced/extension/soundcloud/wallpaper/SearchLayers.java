package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.shared.ClearBackground;

/**
 * The search screen of SoundCloud stacks the suggestions and the results ({@code search_view_flipper}) over the recent
 * searches and the landing page, and lets the opaque surface of the upper layer hide the lower ones. With the
 * wallpaper that surface is clear, so the recent searches showed through the suggestions: rows and covers lay over each
 * other. While the upper layer is shown, the layers below are made fully see-through. That is what being covered
 * looked like; touches reach them as before.
 */
final class SearchLayers implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    /** How often the upper layer is looked through for the fill of its empty and failed states. */
    private static final long EMPTY_LOOK_MS = 200;
    private static final int EMPTY_LOOK_DEPTH = 6;

    private final View upper;
    private final View[] lower;
    private final int emptyId;
    private boolean covered;
    private long lastEmptyLook;

    private SearchLayers(int emptyId, View upper, View... lower) {
        this.emptyId = emptyId;
        this.upper = upper;
        this.lower = lower;
    }

    /** Follows the layers of the search screen if the screen's view is that one. */
    static void watch(View root) {
        Context context = root.getContext();
        View upper = root.findViewById(id(context, "search_view_flipper"));
        if (upper == null) return;
        SearchLayers layers = new SearchLayers(id(context, "empty_view_container"), upper,
                root.findViewById(id(context, "recent_search_view")),
                root.findViewById(id(context, "search_landing_page_container")));
        root.addOnAttachStateChangeListener(layers);
        if (root.isAttachedToWindow()) layers.onViewAttachedToWindow(root);
    }

    private static int id(Context context, String name) {
        return AppResources.identifier(context, name, "id");
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        view.getViewTreeObserver().addOnPreDrawListener(this);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        view.getViewTreeObserver().removeOnPreDrawListener(this);
    }

    /** Before each frame: one look at the upper layer, and the lower ones change only when it appears or goes. */
    @Override
    public boolean onPreDraw() {
        boolean cover = upper.getVisibility() == View.VISIBLE;
        if (cover) clearEmptyStates();
        if (cover != covered) {
            covered = cover;
            for (View view : lower) {
                if (view != null) view.setAlpha(cover ? 0f : 1f);
            }
        }
        return true;
    }

    /**
     * The message of a search that found nothing (or failed) is built into the list when it is needed, the first time
     * long after the screen: its container is filled with the surface colour, a plate over the whole wallpaper. Cleared
     * as it comes, and only that container: the rows of a list keep their own fills.
     */
    private void clearEmptyStates() {
        long now = android.os.SystemClock.uptimeMillis();
        if (emptyId == 0 || now - lastEmptyLook < EMPTY_LOOK_MS) return;
        lastEmptyLook = now;
        clearEmptyStates(upper, 0);
    }

    private void clearEmptyStates(View view, int depth) {
        if (view.getId() == emptyId && view.getBackground() instanceof ColorDrawable
                && ((ColorDrawable) view.getBackground()).getAlpha() != 0) {
            ClearBackground.on(view);
        }
        if (depth >= EMPTY_LOOK_DEPTH || !(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) clearEmptyStates(group.getChildAt(i), depth + 1);
    }
}
