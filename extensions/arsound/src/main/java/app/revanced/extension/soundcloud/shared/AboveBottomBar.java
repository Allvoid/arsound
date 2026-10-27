package app.revanced.extension.soundcloud.shared;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;

import java.lang.ref.WeakReference;

import app.revanced.extension.soundcloud.player.PlayerSheet;

/**
 * Keeps a small element of Arsound above the lowest bar of the main screen: the collapsed player, the bottom
 * navigation, or the screen edge. When the player appears or goes away, the element slides to its new place;
 * while the player is open on the whole screen, it is hidden.
 * <p>
 * Runs before every frame, but only moves the element when the place has changed. The holder is a view added to
 * the bottom of {@code android.R.id.content}; {@code shown} is the part that fades in and out on its own.
 * <p>
 * It remembers the views it looks at, so a frame costs a few measurements and no search of the window. While the
 * player slides, the place changes on every frame, and the element is put there at once instead of starting
 * an animation of its own towards it each time.
 */
public final class AboveBottomBar implements ViewTreeObserver.OnPreDrawListener {
    private static final long MOVE_MS = 250;
    private static final long LOOK_AGAIN_MS = 300;

    private final Context context;
    private final ViewGroup content;
    private final View holder, shown;
    private final Lookup player, navigation;
    private final int[] location = new int[2];
    private ObjectAnimator mover;
    private float target = Float.NaN;
    private boolean playerOpen;

    private AboveBottomBar(Context context, ViewGroup content, View holder, View shown) {
        this.context = context;
        this.content = content;
        this.holder = holder;
        this.shown = shown;
        player = new Lookup(context.getResources().getIdentifier("player_track_pager", "id", context.getPackageName()));
        navigation = new Lookup(context.getResources().getIdentifier("navigation_control_view", "id", context.getPackageName()));
    }

    /** Starts keeping the holder in place until it leaves the window. */
    public static void attach(Context context, ViewGroup content, View holder, View shown) {
        AboveBottomBar placement = new AboveBottomBar(context, content, holder, shown);
        content.getViewTreeObserver().addOnPreDrawListener(placement);
        holder.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                content.getViewTreeObserver().removeOnPreDrawListener(placement);
            }
        });
    }

    @Override
    public boolean onPreDraw() {
        if (shown.getVisibility() != View.VISIBLE) {
            // Placed again from scratch next time, without sliding in from the old place.
            target = Float.NaN;
            return true;
        }
        content.getLocationOnScreen(location);
        int contentTop = location[1];
        int contentBottom = contentTop + content.getHeight();

        int barTop = contentBottom;
        int navigationTop = topOf(navigation);
        if (navigationTop >= 0 && navigationTop < barTop) barTop = navigationTop;

        boolean open = false;
        int playerTop = topOf(player);
        if (playerTop >= 0 && playerTop < barTop) {
            // Higher than the middle of the screen means the player is opened, not collapsed.
            if (playerTop < contentTop + content.getHeight() / 2) open = true;
            else barTop = playerTop;
        }

        if (open != playerOpen) {
            playerOpen = open;
            holder.animate().alpha(open ? 0f : 1f).setDuration(150).start();
        }
        if (open) return true;

        // The holder has a margin for the shadow, which is taken off the gap.
        float wanted = -(contentBottom - barTop + dp(12) - holder.getPaddingBottom());
        if (wanted == target) return true;
        boolean first = Float.isNaN(target);
        target = wanted;
        // Its own animator, so that stopping the move does not stop the fade of the holder.
        if (mover != null) mover.cancel();
        if (first || PlayerSheet.isBusy()) {
            // Moving with the player: a new animation on every frame would only lag behind it.
            holder.setTranslationY(wanted);
        } else {
            mover = ObjectAnimator.ofFloat(holder, View.TRANSLATION_Y, wanted);
            mover.setDuration(MOVE_MS);
            mover.setInterpolator(new DecelerateInterpolator());
            mover.start();
        }
        return true;
    }

    /** Top of a visible view on screen, or -1 when it is not shown. */
    private int topOf(Lookup lookup) {
        View view = lookup.find();
        if (view == null || !view.isShown() || view.getHeight() == 0) return -1;
        view.getLocationOnScreen(location);
        return location[1];
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** A view of the window found by its id, and kept while it stays in the window. */
    private final class Lookup {
        private final int id;
        private WeakReference<View> view = new WeakReference<>(null);
        private long lookedAt;

        Lookup(int id) {
            this.id = id;
        }

        View find() {
            if (id == 0) return null;
            View found = view.get();
            if (found != null && found.isAttachedToWindow()) return found;
            // Not on every frame: a window without the view would be searched through each time.
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lookedAt < LOOK_AGAIN_MS) return null;
            lookedAt = now;
            found = content.getRootView().findViewById(id);
            view = new WeakReference<>(found);
            return found;
        }
    }
}
