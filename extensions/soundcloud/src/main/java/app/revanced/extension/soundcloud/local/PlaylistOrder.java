package app.revanced.extension.soundcloud.local;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Manual order of the playlists in the library.
 * <p>
 * A long press on a playlist starts the rearrange mode: the rows wiggle and the pressed one follows the
 * finger. Another long press drags another playlist, a tap ends the mode. The order is saved on this
 * device and applied on top of SoundCloud's sorting; playlists that are not in the saved order yet
 * (new ones) come first.
 * <p>
 * The drag itself reuses SoundCloud's own play queue drag helper. Obfuscated names of this app version:
 * {@code RecyclerView.N(View)} getChildViewHolder, {@code RecyclerView.c0} item touch listeners,
 * {@code OnItemTouchListener.a(MotionEvent)} onInterceptTouchEvent, {@code ItemTouchHelper.h} attach,
 * {@code ItemTouchHelper.r} startDrag, {@code Adapter.p(II)} notifyItemMoved, {@code UniflowAdapter.h} items.
 */
@SuppressWarnings("unused")
public final class PlaylistOrder {
    private static final String PREFERENCES_NAME = "arsound_local_additions";
    private static final String ORDER = "playlist_order";
    private static final String PLAYLIST_ITEM_CLASS =
            "com.soundcloud.android.features.library.playlists.PlaylistCollectionItem$Playlist";

    private PlaylistOrder() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static List<String> savedOrder() {
        SharedPreferences preferences = preferences();
        String value = preferences == null ? "" : preferences.getString(ORDER, "");
        List<String> order = new ArrayList<>();
        for (String urn : value.split("\n")) if (!urn.isEmpty()) order.add(urn);
        return order;
    }

    public static void reset() {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(ORDER).apply();
    }

    private static String urnOf(Object item) {
        try {
            return String.valueOf(item.getClass().getMethod("getUrn").invoke(item));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Injection point. Called with the sorted library playlists ({@code List<PlaylistItem>}).
     *
     * @return The playlists in the saved order, new playlists first.
     */
    public static List<?> applyOrder(List<?> playlists) {
        if (playlists == null || !Settings.isPlaylistOrderEnabled()) return playlists;
        List<String> order = savedOrder();
        if (order.isEmpty()) return playlists;
        try {
            Map<String, Integer> positions = new HashMap<>();
            for (int i = 0; i < order.size(); i++) positions.put(order.get(i), i);
            List<Object> result = new ArrayList<>(playlists);
            // Stable sort keeps SoundCloud's order among new playlists.
            Collections.sort(result, (first, second) -> Integer.compare(
                    positions.getOrDefault(urnOf(first), -1), positions.getOrDefault(urnOf(second), -1)));
            return result;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not apply the playlist order", ex);
            return playlists;
        }
    }

    /** Injection point. Called when the playlist collection screen has created its views. */
    public static void attach(Object fragment, View root) {
        if (!Settings.isPlaylistOrderEnabled() || root == null
                || !fragment.getClass().getSimpleName().equals("MyPlaylistCollectionFragment")) return;
        root.post(() -> {
            try {
                ViewGroup recycler = findRecyclerView(root);
                if (recycler == null) {
                    Logger.printInfo(() -> "Playlist order: no list found");
                    return;
                }
                new Rearranger(recycler).install();
            } catch (Exception ex) {
                Logger.printException(() -> "Could not set up playlist rearranging", ex);
            }
        });
    }

    private static ViewGroup findRecyclerView(View view) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("androidx.recyclerview.widget.RecyclerView")) return (ViewGroup) view;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            ViewGroup found = findRecyclerView(group.getChildAt(i));
            if (found != null) return found;
        }
        return null;
    }

    private static final class Rearranger {
        private final ViewGroup recycler;
        private final ClassLoader loader;
        private Object touchHelper;
        /** The playlist being dragged. Positions reported during a fast drag with auto scroll can be stale. */
        private Object dragged;
        private boolean active;
        private final List<ObjectAnimator> wiggles = new ArrayList<>();
        private final GestureDetector gestures;

        Rearranger(ViewGroup recycler) {
            this.recycler = recycler;
            this.loader = recycler.getClass().getClassLoader();
            this.gestures = new GestureDetector(recycler.getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public void onLongPress(MotionEvent event) {
                    View child = childUnder(event.getX(), event.getY());
                    if (child == null || !isPlaylist(child)) return;
                    if (!active) start();
                    startDrag(child);
                }
            });
        }

        void install() throws Exception {
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
            Logger.printDebug(() -> "Playlist rearranging ready");
        }

        private static Object defaultValue(Method method) {
            if (method.getName().equals("toString")) return "ArsoundPlaylistOrder";
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

        private boolean isPlaylistAt(int position) {
            try {
                List<Object> items = items();
                return position >= 0 && position < items.size()
                        && items.get(position).getClass().getName().equals(PLAYLIST_ITEM_CLASS);
            } catch (Exception ex) {
                return false;
            }
        }

        private boolean isPlaylist(View child) {
            return isPlaylistAt(position(child));
        }

        private boolean canMove(int from, int to) {
            return isPlaylistAt(to) && (dragged != null || isPlaylistAt(from));
        }

        private void move(int reportedFrom, int to) {
            try {
                List<Object> items = items();
                int index = dragged == null ? -1 : items.indexOf(dragged);
                int from = index >= 0 ? index : reportedFrom;
                if (from == to || !isPlaylistAt(from) || !isPlaylistAt(to)) return;
                if (from < to) {
                    for (int i = from; i < to; i++) Collections.swap(items, i, i + 1);
                } else {
                    for (int i = from; i > to; i--) Collections.swap(items, i, i - 1);
                }
                Object adapter = recycler.getClass().getMethod("getAdapter").invoke(recycler);
                adapter.getClass().getMethod("p", int.class, int.class).invoke(adapter, from, to);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not move playlist", ex);
            }
        }

        private void save() {
            try {
                StringBuilder order = new StringBuilder();
                for (Object item : items()) {
                    if (!item.getClass().getName().equals(PLAYLIST_ITEM_CLASS)) continue;
                    String urn = urnOf(item);
                    if (urn != null) order.append(urn).append('\n');
                }
                SharedPreferences preferences = preferences();
                if (preferences != null) preferences.edit().putString(ORDER, order.toString()).apply();
                Logger.printDebug(() -> "Saved playlist order");
            } catch (Exception ex) {
                Logger.printException(() -> "Could not save playlist order", ex);
            }
        }

        private void startDrag(View child) {
            try {
                Object holder = viewHolder(child);
                int position = position(child);
                List<Object> list = items();
                dragged = position >= 0 && position < list.size() ? list.get(position) : null;
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
                if (!isPlaylist(child)) continue;
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
    }

    private static final int TAG_WIGGLE = 0x7f_ad_50_01;
}
