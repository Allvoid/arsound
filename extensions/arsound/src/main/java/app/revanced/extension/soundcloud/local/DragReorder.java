package app.revanced.extension.soundcloud.local;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import app.revanced.extension.shared.Logger;

/**
 * Drag to rearrange rows of a SoundCloud list: a long press starts the rearrange mode, the rows wiggle
 * and the pressed one follows the finger. Another long press drags another row, a tap ends the mode.
 * <p>
 * The drag itself reuses SoundCloud's own play queue drag helper. Obfuscated names of this app version:
 * {@code RecyclerView.N(View)} getChildViewHolder, {@code RecyclerView.c0} item touch listeners,
 * {@code OnItemTouchListener.a(MotionEvent)} onInterceptTouchEvent, {@code ItemTouchHelper.h} attach,
 * {@code ItemTouchHelper.r} startDrag, {@code Adapter.m(II)} notifyItemMoved (as called by {@code AdapterListUpdateCallback.e}, onMoved), {@code UniflowAdapter.h} items.
 */
public final class DragReorder {
    private final ViewGroup recycler;
    private final Predicate<Object> movable;
    private final Consumer<List<Object>> saver;
    private final ClassLoader loader;
    private Object touchHelper;
    private boolean active;
    private final List<ObjectAnimator> wiggles = new ArrayList<>();
    private final GestureDetector gestures;

    /**
     * @param recycler The RecyclerView of a {@code UniflowAdapter} list.
     * @param movable  Which rows can be dragged and dropped onto.
     * @param saver    Saves the rows in their new order. Called at every step of a drag.
     */
    public DragReorder(ViewGroup recycler, Predicate<Object> movable, Consumer<List<Object>> saver) {
        this.recycler = recycler;
        this.movable = movable;
        this.saver = saver;
        this.loader = recycler.getClass().getClassLoader();
        this.gestures = new GestureDetector(recycler.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent event) {
                View child = childUnder(event.getX(), event.getY());
                if (child == null || !isMovable(child)) return;
                if (!active) start();
                startDrag(child);
            }
        });
    }

    public void install() throws Exception {
        Class<?> hostType = Class.forName(
                "com.soundcloud.android.libs.recyclerviewutils.touchhelpers.ItemDragCallback$DragHost", false, loader);
        Object host = Proxy.newProxyInstance(loader, new Class<?>[]{hostType}, (proxy, method, args) -> {
            switch (method.getName()) {
                case "N":
                    return canMove((Integer) args[0], (Integer) args[1]);
                case "k":
                    move((Integer) args[0], (Integer) args[1]);
                    return null;
                case "p":
                    save();
                    return null;
                default:
                    return defaultValue(method);
            }
        });
        Object callback = Class.forName(
                        "com.soundcloud.android.libs.recyclerviewutils.touchhelpers.ItemDragCallback", false, loader)
                .getConstructor(Context.class, hostType)
                .newInstance(recycler.getContext(), host);
        Class<?> callbackType = Class.forName("androidx.recyclerview.widget.ItemTouchHelper$Callback", false, loader);
        Class<?> helperType = Class.forName("androidx.recyclerview.widget.ItemTouchHelper", false, loader);
        Class<?> recyclerType = Class.forName("androidx.recyclerview.widget.RecyclerView", false, loader);
        touchHelper = helperType.getConstructor(callbackType).newInstance(callback);
        helperType.getMethod("h", recyclerType).invoke(touchHelper, recycler);

        Class<?> listenerType = Class.forName("androidx.recyclerview.widget.RecyclerView$OnItemTouchListener", false, loader);
        Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{listenerType}, (proxy, method, args) -> {
            if (method.getName().equals("a") && args != null && args.length == 1 && args[0] instanceof MotionEvent) {
                return intercept((MotionEvent) args[0]);
            }
            if (method.getName().equals("onTouchEvent")) return null;
            return defaultValue(method);
        });
        Field listeners = recyclerType.getDeclaredField("c0");
        listeners.setAccessible(true);
        //noinspection unchecked
        ((List<Object>) listeners.get(recycler)).add(0, listener);

        recycler.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View view) {
            }

            @Override
            public void onViewDetachedFromWindow(View view) {
                stop();
            }
        });
        recycler.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (active) wiggleChildren();
        });
        Logger.printDebug(() -> "Rearranging ready");
    }

    private static Object defaultValue(Method method) {
        if (method.getName().equals("toString")) return "ArsoundDragReorder";
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        return null;
    }

    /** Long presses are detected here; a tap in the rearrange mode ends it without opening a playlist. */
    private boolean intercept(MotionEvent event) {
        gestures.onTouchEvent(event);
        if (active && event.getActionMasked() == MotionEvent.ACTION_UP
                && event.getEventTime() - event.getDownTime() < android.view.ViewConfiguration.getLongPressTimeout()) {
            stop();
            return true;
        }
        return false;
    }

    private View childUnder(float x, float y) {
        for (int i = recycler.getChildCount() - 1; i >= 0; i--) {
            View child = recycler.getChildAt(i);
            if (x >= child.getLeft() && x <= child.getRight() && y >= child.getTop() && y <= child.getBottom()) {
                return child;
            }
        }
        return null;
    }

    private Object viewHolder(View child) throws Exception {
        return recycler.getClass().getMethod("N", View.class).invoke(recycler, child);
    }

    private int position(View child) {
        try {
            Object holder = viewHolder(child);
            return (Integer) holder.getClass().getMethod("getBindingAdapterPosition").invoke(holder);
        } catch (Exception ex) {
            return -1;
        }
    }

    private List<Object> items() throws Exception {
        Object adapter = recycler.getClass().getMethod("getAdapter").invoke(recycler);
        Class<?> type = Class.forName("com.soundcloud.android.uniflow.android.UniflowAdapter", false, loader);
        Field field = type.getDeclaredField("h");
        field.setAccessible(true);
        //noinspection unchecked
        return (List<Object>) field.get(adapter);
    }

    private boolean isMovableAt(int position) {
        try {
            List<Object> items = items();
            return position >= 0 && position < items.size() && movable.test(items.get(position));
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isMovable(View child) {
        return isMovableAt(position(child));
    }

    private boolean canMove(int from, int to) {
        return isMovableAt(from) && isMovableAt(to);
    }

    /**
     * Moves exactly what the list reports: the adapter positions of the dragged and the target rows.
     * The data and the notification must always match, otherwise RecyclerView crashes with
     * "Inconsistency detected" on the next layout.
     */
    private void move(int from, int to) {
        try {
            List<Object> items = items();
            if (from < 0 || to < 0 || from >= items.size() || to >= items.size()) return;
            items.add(to, items.remove(from));
            Object adapter = recycler.getClass().getMethod("getAdapter").invoke(recycler);
            adapter.getClass().getMethod("m", int.class, int.class).invoke(adapter, from, to);
            // SoundCloud may deliver a fresh list at any moment (like counts, sync) and diffs it against
            // the adapter. Saving at every step keeps that list in the order on screen, otherwise the
            // diff moves rows back under the finger and RecyclerView becomes inconsistent.
            save();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not move row", ex);
        }
    }

    private void save() {
        try {
            saver.accept(items());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save the order", ex);
        }
    }

    private void startDrag(View child) {
        try {
            Object holder = viewHolder(child);
            Class<?> holderType = Class.forName("androidx.recyclerview.widget.RecyclerView$ViewHolder", false, loader);
            touchHelper.getClass().getMethod("r", holderType).invoke(touchHelper, holder);
            child.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not start dragging", ex);
        }
    }

    private void start() {
        active = true;
        wiggleChildren();
    }

    private void stop() {
        if (!active) return;
        active = false;
        for (ObjectAnimator animator : wiggles) animator.cancel();
        wiggles.clear();
        for (int i = 0; i < recycler.getChildCount(); i++) recycler.getChildAt(i).setRotation(0);
    }

    private void wiggleChildren() {
        for (int i = 0; i < recycler.getChildCount(); i++) {
            View child = recycler.getChildAt(i);
            Object running = child.getTag(TAG_WIGGLE);
            if (running instanceof ObjectAnimator && ((ObjectAnimator) running).isRunning()) continue;
            if (!isMovable(child)) continue;
            float angle = (i % 2 == 0) ? 0.8f : -0.8f;
            ObjectAnimator animator = ObjectAnimator.ofFloat(child, View.ROTATION, -angle, angle);
            animator.setDuration(120 + (i % 3) * 15L);
            animator.setRepeatMode(ValueAnimator.REVERSE);
            animator.setRepeatCount(ValueAnimator.INFINITE);
            animator.start();
            child.setTag(TAG_WIGGLE, animator);
            wiggles.add(animator);
        }
    }

    private static final int TAG_WIGGLE = 0x7f_ad_50_01;
}
