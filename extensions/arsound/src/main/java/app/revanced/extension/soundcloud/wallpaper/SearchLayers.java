package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.view.View;
import android.view.ViewTreeObserver;

import app.revanced.extension.soundcloud.shared.AppResources;

/**
 * The search screen of SoundCloud stacks the suggestions and the results ({@code search_view_flipper}) over the recent
 * searches and the landing page, and lets the opaque surface of the upper layer hide the lower ones. With the
 * wallpaper that surface is clear, so the recent searches showed through the suggestions: rows and covers lay over each
 * other. While the upper layer is shown, the layers below are made fully see-through. That is what being covered
 * looked like; touches reach them as before.
 */
final class SearchLayers implements ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
    private final View upper;
    private final View[] lower;
    private boolean covered;

    private SearchLayers(View upper, View... lower) {
        this.upper = upper;
        this.lower = lower;
    }

    /** Follows the layers of the search screen if the screen's view is that one. */
    static void watch(View root) {
        Context context = root.getContext();
        View upper = root.findViewById(id(context, "search_view_flipper"));
        if (upper == null) return;
        SearchLayers layers = new SearchLayers(upper,
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
        if (cover != covered) {
            covered = cover;
            for (View view : lower) {
                if (view != null) view.setAlpha(cover ? 0f : 1f);
            }
        }
        return true;
    }
}
