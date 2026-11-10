package app.revanced.extension.soundcloud.player;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.motion.MotionWindowCallback;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The press highlight of the buttons in the mini player (follow, like). They are SoundCloud's own buttons with a touch
 * zone 45 x 52 dp, as tall as the plate, and their highlight is the zone's rounded rectangle, an oval that runs to the
 * edges of the plate. Here the first press on such a button swaps it for a round highlight, a circle a little smaller than
 * the zone, centred, with the button's own highlight turned off. Only buttons in the player's own container whose zone is
 * a tall narrow rectangle of that size are touched, once each, and only on a press: nothing is done while nothing is
 * pressed. The screen sees every touch through {@link MotionWindowCallback}.
 */
public final class MiniPlayerRipple {
    /** The buttons already given the round highlight. */
    private static final Map<View, Boolean> DONE = new WeakHashMap<>();
    private static final Map<Activity, Boolean> WATCHED = new WeakHashMap<>();
    /** The zone is a rectangle this much taller than wide (the buttons' 45 x 52). */
    private static final float MIN_RATIO = 1.1f;
    private static final float MAX_RATIO = 1.5f;

    private MiniPlayerRipple() {
    }

    /** Screen hook: a screen came to the front; its window starts to pass the touches on here, once. */
    public static void onActivityResumed(Activity activity) {
        try {
            if (WATCHED.containsKey(activity)) return;
            Window window = activity.getWindow();
            Window.Callback callback = window == null ? null : window.getCallback();
            if (callback == null) return;
            window.setCallback(new MotionWindowCallback(callback, event -> onTouch(activity, event)));
            WATCHED.put(activity, Boolean.TRUE);
        } catch (Throwable ex) {
            Logger.printException(() -> "Mini player highlight could not be installed", ex);
        }
    }

    private static void onTouch(Activity activity, MotionEvent event) {
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        View decor = activity.getWindow().getDecorView();
        View target = deepestAt(decor, event.getRawX(), event.getRawY(), new int[2]);
        if (target == null) return;
        // The press may land on a child of the button, or the button may be the view itself.
        for (View view = target; view != null; view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (isMiniPlayerButton(view)) {
                tidy(view);
                return;
            }
        }
    }

    /** The deepest visible view under the point, or null. */
    private static View deepestAt(View view, float rawX, float rawY, int[] where) {
        if (view.getVisibility() != View.VISIBLE) return null;
        view.getLocationOnScreen(where);
        if (rawX < where[0] || rawX >= where[0] + view.getWidth() || rawY < where[1] || rawY >= where[1] + view.getHeight()) {
            return null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View found = deepestAt(group.getChildAt(i), rawX, rawY, where);
                if (found != null) return found;
            }
        }
        // A view the touch falls through (an overlay that takes no touches) is not the target.
        return view.isClickable() ? view : null;
    }

    /** A button of SoundCloud's own kind, with a tall narrow zone, inside the player's container. */
    private static boolean isMiniPlayerButton(View view) {
        if (DONE.containsKey(view) || !view.isClickable()) return false;
        if (!isButton(view.getClass())) return false;
        int width = view.getWidth(), height = view.getHeight();
        if (width <= 0 || height <= 0) return false;
        float ratio = height / (float) width;
        if (ratio < MIN_RATIO || ratio > MAX_RATIO || height > Dimens.dp(view.getContext(), 60)) return false;
        int root = Utils.getResourceIdentifier(ResourceType.ID, "player_root");
        if (root == 0) return false;
        for (Object parent = view.getParent(); parent instanceof View; parent = ((View) parent).getParent()) {
            if (((View) parent).getId() == root) return true;
        }
        return false;
    }

    /** A MaterialButton (what SoundCloud's follow and like buttons are), by name: its class is not on the compile path. */
    private static boolean isButton(Class<?> type) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (c.getName().endsWith(".MaterialButton")) return true;
        }
        return false;
    }

    private static void tidy(View button) {
        DONE.put(button, Boolean.TRUE);
        try {
            // The button's own highlight off (its colour is transparent), then a round one over it.
            button.getClass().getMethod("setRippleColor", ColorStateList.class).invoke(button, ColorStateList.valueOf(0));
            int diameter = Math.min(button.getWidth(), button.getHeight()) - Dimens.dp(button.getContext(), 8);
            int insetX = Math.max(0, (button.getWidth() - diameter) / 2);
            int insetY = Math.max(0, (button.getHeight() - diameter) / 2);
            Drawable circle = new InsetDrawable(new ShapeDrawable(new OvalShape()), insetX, insetY, insetX, insetY);
            int ink = ArsoundTheme.palette(button.getContext(), "primary");
            button.setForeground(new RippleDrawable(ColorStateList.valueOf(Colors.withAlpha(ink, 0x30)), null, circle));
            Logger.printInfo(() -> "Mini player: round highlight on " + button.getClass().getSimpleName() + " "
                    + button.getWidth() + "x" + button.getHeight() + " px");
        } catch (Throwable ex) {
            Logger.printException(() -> "Mini player: the round highlight was not set", ex);
        }
    }
}
