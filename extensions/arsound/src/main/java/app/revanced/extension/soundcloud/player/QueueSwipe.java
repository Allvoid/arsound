package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.ResourceType;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.local.SavedQueue;
import app.revanced.extension.soundcloud.shared.Colors;
import app.revanced.extension.soundcloud.theme.ArsoundTheme;
import app.revanced.extension.soundcloud.theme.ThemeEffect;

/**
 * A swipe to the left on a row of the play queue, with no button on the screen: let go after a short swipe and the
 * track plays next; after a long one the whole queue is saved as a playlist of this phone ({@link SavedQueue}). A hint
 * comes out from under the row as it slides, and changes (with a tick of the phone) where the action changes. The swipe
 * to the right stays SoundCloud's own: it removes the track.
 */
@SuppressWarnings("unused")
public final class QueueSwipe {
    /** How far, as a share of the row, the row has to go: a short swipe and a long one. */
    static final float NEXT_AT = 0.22f, SAVE_AT = 0.55f;
    private static final float MOST = 0.8f;
    private static final long SETTLE_MS = 220;
    /** The start page of the tracks put next from here, as SoundCloud's own "Play next" has one. */
    private static final String CONTEXT = "arsound:queue";

    private static final Map<View, Gesture> GESTURES = new WeakHashMap<>();

    private QueueSwipe() {
    }

    /** The action a swipe of this share of the row's width ends in. */
    enum Action {
        NONE,
        PLAY_NEXT,
        SAVE_QUEUE;

        static Action at(float share) {
            if (share >= SAVE_AT) return SAVE_QUEUE;
            if (share >= NEXT_AT) return PLAY_NEXT;
            return NONE;
        }
    }

    /**
     * Injection point: the end of {@code TrackPlayQueueItemViewHolder.bindItem}. Main thread.
     *
     * @param holder The row's holder.
     * @param item   The {@code TrackPlayQueueUIItem} it shows now.
     */
    public static void onBind(Object holder, Object item) {
        try {
            View row = (View) holder.getClass().getField("itemView").get(holder);
            if (row == null) return;
            Gesture gesture = GESTURES.get(row);
            if (gesture == null) {
                gesture = new Gesture(row);
                GESTURES.put(row, gesture);
                row.setOnTouchListener(gesture);
            }
            gesture.item = item;
            gesture.reset();
        } catch (Exception ex) {
            Logger.printException(() -> "Queue swipe: could not follow a row", ex);
        }
    }

    /** The touch of one row. Rows are reused for other tracks: the item is set again at every bind. */
    private static final class Gesture implements View.OnTouchListener {
        private final View row;
        private final int slop;
        private Object item;
        private float downX, downY;
        private boolean swiping;
        private Action shown = Action.NONE;
        private Hint hint;
        private ValueAnimator settle;

        Gesture(View row) {
            this.row = row;
            slop = ViewConfiguration.get(row.getContext()).getScaledTouchSlop();
        }

        void reset() {
            if (settle != null) settle.cancel();
            swiping = false;
            row.setTranslationX(0);
            removeHint();
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX();
                    downY = event.getRawY();
                    swiping = false;
                    return false;
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                    if (!swiping) {
                        if (dx > -slop || Math.abs(dx) < Math.abs(dy) * 1.5f || row.getWidth() == 0) return false;
                        start();
                    }
                    float width = row.getWidth();
                    float x = Math.max(dx, -width * MOST);
                    move(x);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!swiping) return false;
                    swiping = false;
                    Action action = event.getActionMasked() == MotionEvent.ACTION_UP
                            ? Action.at(-row.getTranslationX() / row.getWidth()) : Action.NONE;
                    // The row forgets the press: no tap after a swipe.
                    MotionEvent cancel = MotionEvent.obtain(event);
                    cancel.setAction(MotionEvent.ACTION_CANCEL);
                    row.onTouchEvent(cancel);
                    cancel.recycle();
                    settleBack();
                    run(action);
                    return true;
                }
                default:
                    return swiping;
            }
        }

        private void start() {
            swiping = true;
            if (settle != null) settle.cancel();
            if (row.getParent() != null) row.getParent().requestDisallowInterceptTouchEvent(true);
            row.setPressed(false);
            row.cancelLongPress();
            shown = Action.NONE;
            if (hint == null && row.getParent() instanceof ViewGroup) {
                hint = new Hint(row.getContext());
                ((ViewGroup) row.getParent()).getOverlay().add(hint);
            }
        }

        private void move(float x) {
            row.setTranslationX(x);
            float share = -x / row.getWidth();
            Action action = Action.at(share);
            if (action != shown && action != Action.NONE) {
                row.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            shown = action;
            if (hint != null) {
                hint.update(row.getRight() + x, row.getTop(), row.getRight(), row.getBottom(), share, action);
            }
        }

        private void settleBack() {
            float from = row.getTranslationX();
            if (ThemeEffect.isStill(row.getContext()) || from == 0) {
                row.setTranslationX(0);
                removeHint();
                return;
            }
            settle = ValueAnimator.ofFloat(from, 0f);
            settle.setDuration(SETTLE_MS);
            settle.setInterpolator(new DecelerateInterpolator(1.6f));
            settle.addUpdateListener(a -> {
                float x = (float) a.getAnimatedValue();
                row.setTranslationX(x);
                if (hint != null) {
                    hint.update(row.getRight() + x, row.getTop(), row.getRight(), row.getBottom(), -x / row.getWidth(), shown);
                }
            });
            settle.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    removeHint();
                }
            });
            settle.start();
        }

        private void removeHint() {
            if (hint == null) return;
            if (row.getParent() instanceof ViewGroup) ((ViewGroup) row.getParent()).getOverlay().remove(hint);
            hint = null;
        }

        private void run(Action action) {
            if (action == Action.NONE || item == null) return;
            Context context = row.getContext();
            try {
                if (action == Action.PLAY_NEXT) playNext(context, item);
                else saveQueue(context);
            } catch (Exception ex) {
                Logger.printException(() -> "Queue swipe: " + action + " failed", ex);
                Toast.makeText(context, text("Не получилось", "That did not work"), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** The {@code PlayQueueItem} a queue row shows: a field of the row's item, found by its type. */
    private static Object playQueueItemOf(Object uiItem) throws Exception {
        Class<?> itemClass = Class.forName("com.soundcloud.android.foundation.playqueue.PlayQueueItem", false,
                uiItem.getClass().getClassLoader());
        for (Class<?> type = uiItem.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!itemClass.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                Object value = field.get(uiItem);
                if (value != null) return value;
            }
        }
        return null;
    }

    /**
     * The track of the row plays next. A track still ahead moves to the place after the playing one (no copy in the
     * queue); a track already played, or the playing one, is put there again as SoundCloud's "Play next" does.
     */
    private static void playNext(Context context, Object uiItem) throws Exception {
        Object manager = PlayModeButton.queueManager();
        Object playItem = playQueueItemOf(uiItem);
        if (manager == null || playItem == null) return;
        Object queue = PlayQueueApi.queue(manager);
        int playing = PlayQueueApi.position(manager);
        Class<?> itemClass = Class.forName("com.soundcloud.android.foundation.playqueue.PlayQueueItem", false,
                manager.getClass().getClassLoader());
        int from = (int) queue.getClass().getMethod("indexOfPlayQueueItem", itemClass).invoke(queue, playItem);
        String message;
        if (from == playing + 1) {
            message = text("Уже следующий", "Already next");
        } else if (from > playing + 1) {
            queue.getClass().getMethod("moveItem", int.class, int.class).invoke(queue, from, playing + 1);
            publishReordered(manager);
            message = text("Сыграет следующим", "Plays next");
        } else {
            Object urn = playItem.getClass().getMethod("getUrn").invoke(playItem);
            Class<?> trackUrn = Class.forName("com.soundcloud.android.foundation.domain.TrackUrn", false,
                    manager.getClass().getClassLoader());
            if (!trackUrn.isInstance(urn)) return;
            Object array = java.lang.reflect.Array.newInstance(trackUrn, 1);
            java.lang.reflect.Array.set(array, 0, urn);
            manager.getClass().getMethod("p", String.class, array.getClass()).invoke(manager, CONTEXT, array);
            message = text("Сыграет следующим", "Plays next");
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    /** The queue screen and the player read the new order from this event, as after a drag of a row. */
    private static void publishReordered(Object manager) throws Exception {
        ClassLoader loader = manager.getClass().getClassLoader();
        Class<?> event = Class.forName("com.soundcloud.android.foundation.playqueue.PlayQueueEvent", false, loader);
        Object reordered = Class.forName("com.soundcloud.android.foundation.playqueue.PlayQueueEvent$QueueReordered", true, loader)
                .getField("INSTANCE").get(null);
        manager.getClass().getMethod("z", event).invoke(manager, reordered);
    }

    private static void saveQueue(Context context) throws Exception {
        Object manager = PlayModeButton.queueManager();
        if (manager == null) return;
        Object queue = PlayQueueApi.queue(manager);
        List<Object> urns = new ArrayList<>();
        for (Object playItem : (List<?>) queue.getClass().getMethod("items").invoke(queue)) {
            urns.add(playItem.getClass().getMethod("getUrn").invoke(playItem));
        }
        SavedQueue.save(context, urns);
    }

    /** What comes out from under the row: a band in the accent with the icon and the name of the action. */
    private static final class Hint extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final float density;
        private final int accent, ink;
        private final Drawable nextIcon, saveIcon;
        private float share;
        private Action action = Action.NONE;

        Hint(Context context) {
            density = context.getResources().getDisplayMetrics().density;
            accent = ArsoundTheme.palette(context, "special");
            ink = Colors.contrast(accent);
            label.setColor(ink);
            label.setTextSize(13 * context.getResources().getDisplayMetrics().scaledDensity);
            label.setFakeBoldText(true);
            nextIcon = icon(context, "ic_actions_playlist_queue");
            saveIcon = icon(context, "ic_actions_playlist_add_to_playlist");
        }

        private Drawable icon(Context context, String name) {
            int id = Utils.getResourceIdentifier(ResourceType.DRAWABLE, name);
            if (id == 0) return null;
            Drawable drawable = context.getDrawable(id);
            if (drawable == null) return null;
            drawable = drawable.mutate();
            drawable.setColorFilter(new PorterDuffColorFilter(ink, PorterDuff.Mode.SRC_IN));
            return drawable;
        }

        void update(float left, float top, float right, float bottom, float share, Action action) {
            setBounds(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom));
            this.share = share;
            this.action = action;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            if (getBounds().width() <= 0) return;
            // Faint until the swipe means something, full past the first mark; the long swipe a shade darker.
            float strength = action == Action.NONE ? Math.min(1f, share / NEXT_AT) * 0.35f : 1f;
            int colour = action == Action.SAVE_QUEUE ? darker(accent, 0.25f) : accent;
            fill.setColor(Colors.withAlpha(colour, Math.round(0xFF * strength)));
            rect.set(getBounds());
            rect.inset(4 * density, 3 * density);
            float radius = 12 * density;
            canvas.drawRoundRect(rect, radius, radius, fill);

            Drawable icon = action == Action.SAVE_QUEUE ? saveIcon : nextIcon;
            String text = action == Action.SAVE_QUEUE ? text("Сохранить очередь", "Save the queue")
                    : text("Играть следующим", "Play next");
            int size = Math.round(22 * density);
            float iconLeft = rect.left + 14 * density;
            int iconTop = Math.round(rect.centerY() - size / 2f);
            label.setAlpha(Math.round(0xFF * Math.max(0.35f, strength)));
            if (icon != null && rect.width() > size + 28 * density) {
                icon.setBounds(Math.round(iconLeft), iconTop, Math.round(iconLeft) + size, iconTop + size);
                icon.setAlpha(Math.round(0xFF * Math.max(0.35f, strength)));
                icon.draw(canvas);
            }
            float textLeft = iconLeft + size + 10 * density;
            if (rect.right - textLeft > label.measureText(text) + 12 * density) {
                canvas.drawText(text, textLeft, rect.centerY() - (label.descent() + label.ascent()) / 2, label);
            }
        }

        /** The colour moved a share of the way to black. */
        private static int darker(int colour, float share) {
            int r = Math.round(((colour >> 16) & 0xFF) * (1 - share));
            int g = Math.round(((colour >> 8) & 0xFF) * (1 - share));
            int b = Math.round((colour & 0xFF) * (1 - share));
            return (colour & 0xFF000000) | (r << 16) | (g << 8) | b;
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
