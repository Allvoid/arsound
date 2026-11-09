package app.revanced.extension.soundcloud.motion;

import android.app.Activity;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.theme.ThemeEffect;

/**
 * The motion blur of one screen. Watches the touches the screen gets (through {@link MotionWindowCallback}); once a
 * finger has moved far enough to scroll, it finds the view that scrolls under it, and for as long as the finger or the
 * fling that follows it is fast it puts a blur on that view, long along the movement and short across it. No work is
 * done for a tap, and none at all while the blur is off or the finger is slow.
 * <p>
 * Everything runs on the main thread. The blur is a render effect, so the graphics chip does the work; the effect is
 * made again only when its radius moves by half a pixel.
 */
final class MotionBlurRun {
    private static final int IGNORED = -1;
    private static final int UNDECIDED = 0;
    private static final int VERTICAL = 1;
    private static final int HORIZONTAL = 2;

    /** A finger that has not moved for this long is not going fast any more, whatever the last samples say. */
    private static final long STILL_FINGER_MS = 60;
    /** How fast the shown speed falls while the finger is down and while the fling runs on. */
    private static final long FINGER_HALF_LIFE_MS = 150;
    private static final long FLING_HALF_LIFE_MS = 330;

    private final WeakReference<Activity> activity;
    private boolean installed;

    private int axis = IGNORED;
    private boolean touching;
    private MotionBlurSettings settings;
    private VelocityTracker tracker;
    private float downX;
    private float downY;
    private long lastMoveAt;
    private long lastFrameAt;
    /** The speed along the axis, in pixels per second, as the blur follows it. */
    private float speedPx;
    private float density = 1f;
    private float appliedAlong;
    private float appliedAcross;
    private WeakReference<View> target = new WeakReference<>(null);
    private boolean looping;
    private final Runnable frame = this::onFrame;

    MotionBlurRun(Activity activity) {
        this.activity = new WeakReference<>(activity);
    }

    boolean installed() {
        return installed;
    }

    void markInstalled() {
        installed = true;
    }

    /** A touch of the screen, before the screen gets it. */
    void onTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(event);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Two fingers are a pinch or a drag of something else, not a scroll.
                if (axis != IGNORED) abandon();
                break;
            case MotionEvent.ACTION_MOVE:
                move(event);
                break;
            case MotionEvent.ACTION_UP:
                end(event);
                break;
            case MotionEvent.ACTION_CANCEL:
                // The touch was taken away (a system gesture): nothing flies on.
                abandon();
                break;
            default:
                break;
        }
    }

    /** The screen left the front: the blur goes off at once. */
    void stop() {
        abandon();
    }

    private void begin(MotionEvent event) {
        // A finger on a fling stops it, so the blur stops with it.
        abandon();
        Activity screen = activity.get();
        if (screen == null || !MotionBlur.isSupported()) return;
        MotionBlurSettings read = MotionBlurSettings.read();
        if (read == null || !(read.scroll || read.swipes) || ThemeEffect.isStill(screen)) return;
        settings = read;
        axis = UNDECIDED;
        touching = true;
        density = screen.getResources().getDisplayMetrics().density;
        downX = event.getRawX();
        downY = event.getRawY();
        tracker = VelocityTracker.obtain();
        tracker.addMovement(event);
        lastMoveAt = event.getEventTime();
    }

    private void move(MotionEvent event) {
        if (axis == IGNORED || tracker == null) return;
        tracker.addMovement(event);
        lastMoveAt = event.getEventTime();
        if (axis != UNDECIDED) return;
        Activity screen = activity.get();
        if (screen == null) {
            abandon();
            return;
        }
        float dx = event.getRawX() - downX;
        float dy = event.getRawY() - downY;
        float slop = ViewConfiguration.get(screen).getScaledTouchSlop();
        if (Math.max(Math.abs(dx), Math.abs(dy)) < slop) return;

        boolean vertical = Math.abs(dy) >= Math.abs(dx);
        if (!(vertical ? settings.scroll : settings.swipes)) {
            axis = IGNORED;
            release();
            return;
        }
        View scrolling = findScrolling(screen, vertical);
        if (scrolling == null) {
            Logger.printDebug(() -> "Motion blur: nothing scrolls under the finger");
            axis = IGNORED;
            release();
            return;
        }
        axis = vertical ? VERTICAL : HORIZONTAL;
        Logger.printDebug(() -> "Motion blur follows " + scrolling.getClass().getName() + (vertical ? " up and down" : " sideways"));
        target = new WeakReference<>(scrolling);
        lastFrameAt = SystemClock.uptimeMillis();
        speedPx = 0f;
        schedule(scrolling);
    }

    private void end(MotionEvent event) {
        if (tracker == null) {
            touching = false;
            return;
        }
        tracker.addMovement(event);
        if (axis > UNDECIDED) {
            // The fling goes on at the speed the screen gets from the same samples.
            speedPx = event.getEventTime() - lastMoveAt > STILL_FINGER_MS ? 0f : fingerSpeed();
            // A lift that is too slow to start a fling leaves nothing to fly on.
            Activity screen = activity.get();
            if (screen != null && speedPx < ViewConfiguration.get(screen).getScaledMinimumFlingVelocity()) speedPx = 0f;
        }
        touching = false;
        release();
        if (axis <= UNDECIDED) axis = IGNORED;
    }

    /** Ends the gesture at once and takes the blur off. */
    private void abandon() {
        View removed = target.get();
        target = new WeakReference<>(null);
        touching = false;
        axis = IGNORED;
        speedPx = 0f;
        release();
        if (removed != null) {
            removed.removeCallbacks(frame);
            clear(removed);
        }
        // Whatever view the next gesture takes starts with no blur on it, even if the old one is gone.
        appliedAlong = 0f;
        appliedAcross = 0f;
        looping = false;
    }

    private void release() {
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    private float fingerSpeed() {
        if (tracker == null) return 0f;
        tracker.computeCurrentVelocity(1000);
        return Math.abs(axis == VERTICAL ? tracker.getYVelocity() : tracker.getXVelocity());
    }

    private void schedule(View view) {
        if (looping) return;
        looping = true;
        view.postOnAnimation(frame);
    }

    private void onFrame() {
        looping = false;
        View view = target.get();
        if (view == null || axis <= UNDECIDED) {
            appliedAlong = 0f;
            appliedAcross = 0f;
            return;
        }
        long now = SystemClock.uptimeMillis();
        long dt = Math.max(1, Math.min(100, now - lastFrameAt));
        lastFrameAt = now;
        if (touching) {
            float finger = now - lastMoveAt > STILL_FINGER_MS ? 0f : fingerSpeed();
            speedPx = MotionBlurMath.follow(speedPx, finger, dt, FINGER_HALF_LIFE_MS);
        } else {
            speedPx = MotionBlurMath.decayed(speedPx, dt, FLING_HALF_LIFE_MS);
        }
        float intensity = MotionBlurMath.intensity(speedPx / density);
        apply(view, intensity);
        if (touching || intensity > 0f) {
            schedule(view);
        } else {
            // The fling is over: nothing left to follow.
            target = new WeakReference<>(null);
            axis = IGNORED;
            clear(view);
        }
    }

    private void apply(View view, float intensity) {
        float along = MotionBlurMath.quantize(MotionBlurMath.alongDp(settings.amount, settings.stretch, intensity) * density);
        float across = MotionBlurMath.quantize(MotionBlurMath.acrossDp(settings.amount, intensity) * density);
        if (along == appliedAlong && across == appliedAcross) return;
        appliedAlong = along;
        appliedAcross = across;
        boolean vertical = axis == VERTICAL;
        Api31.set(view, vertical ? across : along, vertical ? along : across);
    }

    private void clear(View view) {
        if (appliedAlong != 0f || appliedAcross != 0f) {
            appliedAlong = 0f;
            appliedAcross = 0f;
            Api31.set(view, 0f, 0f);
        }
    }

    // region Finding the view that scrolls

    /**
     * The view under the finger's first touch that scrolls the way the finger went, or null when nothing does. Every
     * view that holds the point is looked at, not only the top one: a screen may have a see-through layer over it all
     * (the wallpaper, an effect), and the list is under that.
     */
    private View findScrolling(Activity screen, boolean vertical) {
        View root = screen.findViewById(android.R.id.content);
        if (root == null) return null;
        View[] compose = new View[1];
        View found = findAt(root, Math.round(downX), Math.round(downY), vertical, compose, new int[2], 0);
        if (found != null) return found;
        // The Compose version of SoundCloud may not tell whether its lists scroll (the home screen is one such view): a
        // vertical drag on it counts as a scroll. Not a sideways one: the carousels in it would blur the whole page.
        return vertical ? compose[0] : null;
    }

    /** The innermost, topmost view holding the point that scrolls that way; {@code compose} gets the first Compose view met. */
    private static View findAt(View view, int x, int y, boolean vertical, View[] compose, int[] location, int depth) {
        if (depth > 40) return null;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                child.getLocationOnScreen(location);
                if (x < location[0] || x >= location[0] + child.getWidth()
                        || y < location[1] || y >= location[1] + child.getHeight()) continue;
                View found = findAt(child, x, y, vertical, compose, location, depth + 1);
                if (found != null) return found;
            }
        }
        boolean scrolls = vertical
                ? view.canScrollVertically(1) || view.canScrollVertically(-1)
                : view.canScrollHorizontally(1) || view.canScrollHorizontally(-1);
        if (scrolls && depth > 0) return view;
        if (compose[0] == null && view.getClass().getName().endsWith("AndroidComposeView")) compose[0] = view;
        return null;
    }


    // endregion

    /** The render effect needs Android 12; this class is only loaded there, so older versions never touch it. */
    @RequiresApi(Build.VERSION_CODES.S)
    private static final class Api31 {
        static void set(View view, float radiusX, float radiusY) {
            view.setRenderEffect(radiusX <= 0f && radiusY <= 0f ? null
                    : RenderEffect.createBlurEffect(radiusX, radiusY, Shader.TileMode.CLAMP));
        }
    }
}
