package app.revanced.extension.soundcloud.shared;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A column of rows that the user rearranges with a finger: touch the handle of a row and drag it. The row
 * rises under the finger with a shadow, the others make room for it, and a tick on the finger tells when it
 * passes a place. Near the top and the bottom of the scrolling parent the list scrolls by itself.
 * <p>
 * Made of plain views, for dialogs: SoundCloud's rearrangeable lists need its own RecyclerView (see
 * {@link app.revanced.extension.soundcloud.local.DragReorder}). The rows may differ in height. The views
 * stay where they are while a row is dragged (only moved by translation); the children change places
 * once the row is dropped.
 */
public final class ReorderColumn extends LinearLayout {
    private static final long LIFT_MS = 120;
    private static final long SHIFT_MS = 140;
    private static final long DROP_MS = 160;
    private static final float LIFT_SCALE = 1.03f;
    /** How near the edge of the scrolling parent the list starts to scroll, and how fast at the very edge, in dp. */
    private static final float EDGE_ZONE_DP = 64;
    private static final float MAX_SCROLL_DP_PER_MS = 0.6f;

    private final Map<View, Object> keys = new IdentityHashMap<>();
    private final int liftColor;
    private final float density;

    // State of a drag; valid while "dragged" is not null.
    private View dragged;
    /** The finger is up and the row settles into its place: a new touch waits for it. */
    private boolean dropping;
    private Drawable draggedBackground;
    private int from, to;
    /** Top and height of every row when the drag began, and the top each one has after the move. */
    private int[] tops, heights, newTops;
    /** The translation each row was last sent to, so a row is not animated again to where it goes already. */
    private float[] targets;
    /** Where the finger took the row, below its top, and where the finger is on the screen. */
    private float grabOffset, rawY;
    private ScrollView scroller;
    private long lastFrame;
    private float scrollCarry;
    private final int[] location = new int[2];

    /** @param liftColor The colour of a row while it is lifted: the row needs a background for the shadow. */
    public ReorderColumn(Context context, int liftColor) {
        super(context);
        setOrientation(VERTICAL);
        this.liftColor = liftColor;
        this.density = context.getResources().getDisplayMetrics().density;
        // The shadow of the lifted row reaches over the others.
        setClipChildren(false);
        setClipToPadding(false);
    }

    /**
     * Adds a row at the end.
     *
     * @param row    A view that is not in a parent now.
     * @param handle A view inside the row that starts the drag when touched.
     * @param key    Tells the row apart in {@link #keys()}.
     */
    public void addRow(View row, View handle, Object key) {
        // A drag holds the places of the rows it began with: a row that comes or goes ends it.
        if (dragged != null) resetDrag();
        keys.put(row, key);
        handle.setOnTouchListener((view, event) -> onHandleTouch(row, event));
        addView(row, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** Takes the row out of the column; the view is left as it is. */
    public void removeRow(Object key) {
        if (dragged != null) resetDrag();
        for (int i = 0; i < getChildCount(); i++) {
            View row = getChildAt(i);
            if (keys.get(row) != key) continue;
            removeViewAt(i);
            keys.remove(row);
            return;
        }
    }

    /** True while a row is dragged or settling into its place after the drop: rows should not come or go now. */
    public boolean isBusy() {
        return dragged != null;
    }

    /** The keys of the rows from top to bottom. */
    public List<Object> keys() {
        List<Object> result = new ArrayList<>();
        for (int i = 0; i < getChildCount(); i++) result.add(keys.get(getChildAt(i)));
        return result;
    }

    /** The grip that shows a row can be dragged: three bars. */
    public static View createHandle(Context context, int color) {
        return new Grip(context, color);
    }

    private static final class Grip extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;

        Grip(Context context, int color) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            paint.setColor(color);
            paint.setStrokeWidth(2 * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float centerX = getWidth() / 2f, centerY = getHeight() / 2f;
            float half = 9 * density, gap = 5 * density;
            for (int bar = -1; bar <= 1; bar++) {
                canvas.drawLine(centerX - half, centerY + bar * gap, centerX + half, centerY + bar * gap, paint);
            }
        }
    }

    // region Drag

    private boolean onHandleTouch(View row, MotionEvent event) {
        if (dropping) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startDrag(row, event.getRawY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragged == row) {
                    rawY = event.getRawY();
                    updateDrag();
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragged == row) endDrag();
                return true;
            default:
                return false;
        }
    }

    private void startDrag(View row, float touchY) {
        if (dragged != null) return;
        int count = getChildCount();
        tops = new int[count];
        heights = new int[count];
        newTops = new int[count];
        targets = new float[count];
        for (int i = 0; i < count; i++) {
            tops[i] = getChildAt(i).getTop();
            heights[i] = getChildAt(i).getHeight();
            newTops[i] = tops[i];
        }
        from = to = indexOfChild(row);
        dragged = row;
        rawY = touchY;
        getLocationOnScreen(location);
        grabOffset = touchY - (location[1] + tops[from]);
        scroller = scrollParent();
        scrollCarry = 0;

        // Every parent leaves the touch to the handle until the finger is lifted.
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);

        GradientDrawable lifted = new GradientDrawable();
        lifted.setColor(liftColor);
        lifted.setCornerRadius(12 * density);
        draggedBackground = row.getBackground();
        row.setBackground(lifted);
        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        row.animate().scaleX(LIFT_SCALE).scaleY(LIFT_SCALE).alpha(0.98f).translationZ(10 * density)
                .setDuration(LIFT_MS).setInterpolator(new DecelerateInterpolator()).start();

        lastFrame = System.nanoTime();
        postOnAnimation(autoScroll);
    }

    /** Puts the dragged row under the finger, and makes room for it where it would be dropped. */
    private void updateDrag() {
        if (dragged == null) return;
        getLocationOnScreen(location);
        int last = tops.length - 1;
        float top = rawY - location[1] - grabOffset;
        // The row stays in the column.
        top = Math.max(tops[0], Math.min(top, tops[last] + heights[last] - heights[from]));
        dragged.setTranslationY(top - tops[from]);

        // The place is decided by how many of the other rows have their middle above the middle of the dragged one.
        float middle = top + heights[from] / 2f;
        int place = 0;
        for (int i = 0; i < tops.length; i++) {
            if (i != from && tops[i] + heights[i] / 2f < middle) place++;
        }
        if (place == to) return;
        to = place;
        dragged.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        layoutAfterMove();
        for (int i = 0; i < tops.length; i++) {
            if (i == from) continue;
            float target = newTops[i] - tops[i];
            if (target == targets[i]) continue;
            targets[i] = target;
            getChildAt(i).animate().translationY(target).setDuration(SHIFT_MS)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    /** The top of every row once the dragged one is at place "to". */
    private void layoutAfterMove() {
        int top = tops[0];
        int others = 0;
        for (int place = 0; place < tops.length; place++) {
            if (place == to) {
                newTops[from] = top;
                top += heights[from];
                continue;
            }
            // The others keep their order; "from" is skipped when counting them.
            int row = others < from ? others : others + 1;
            others++;
            newTops[row] = top;
            top += heights[row];
        }
    }

    private void endDrag() {
        removeCallbacks(autoScroll);
        dropping = true;
        View row = dragged;
        row.animate().translationY(newTops[from] - tops[from]).scaleX(1f).scaleY(1f).alpha(1f).translationZ(0f)
                .setDuration(DROP_MS).setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::finishDrag).start();
    }

    /** The row is in its place: the views are moved for real and the translations given back. */
    private void finishDrag() {
        View row = dragged;
        if (row == null) return;
        int moved = to;
        int origin = from;
        resetDrag();
        if (moved == origin) return;
        removeViewAt(origin);
        addView(row, moved);
    }

    private void resetDrag() {
        removeCallbacks(autoScroll);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.animate().cancel();
            child.setTranslationY(0f);
        }
        View row = dragged;
        dragged = null;
        dropping = false;
        if (row == null) return;
        row.setScaleX(1f);
        row.setScaleY(1f);
        row.setAlpha(1f);
        row.setTranslationZ(0f);
        row.setBackground(draggedBackground);
        draggedBackground = null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        resetDrag();
    }

    // endregion

    // region Scrolling

    private ScrollView scrollParent() {
        for (android.view.ViewParent parent = getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
        }
        return null;
    }

    /** Runs every frame of a drag: scrolls the parent while the finger is near its top or bottom edge. */
    private final Runnable autoScroll = new Runnable() {
        @Override
        public void run() {
            if (dragged == null) return;
            long now = System.nanoTime();
            float elapsed = Math.min(50f, (now - lastFrame) / 1_000_000f);
            lastFrame = now;
            int step = scrollStep(elapsed);
            if (step != 0) {
                int before = scroller.getScrollY();
                scroller.scrollBy(0, step);
                // The column moved under the finger: the row has to follow it.
                if (scroller.getScrollY() != before) updateDrag();
            }
            postOnAnimation(this);
        }
    };

    /** @return Pixels to scroll in this frame: negative up, positive down. */
    private int scrollStep(float elapsedMs) {
        if (scroller == null) return 0;
        scroller.getLocationOnScreen(location);
        float top = location[1] + scroller.getPaddingTop();
        float bottom = location[1] + scroller.getHeight() - scroller.getPaddingBottom();
        float zone = EDGE_ZONE_DP * density;
        float depth;
        if (rawY < top + zone) {
            depth = -Math.min(1f, (top + zone - rawY) / zone);
        } else if (rawY > bottom - zone) {
            depth = Math.min(1f, (rawY - (bottom - zone)) / zone);
        } else {
            scrollCarry = 0;
            return 0;
        }
        // Slow when just inside the zone, fast at the edge.
        scrollCarry += depth * Math.abs(depth) * MAX_SCROLL_DP_PER_MS * density * elapsedMs;
        int step = (int) scrollCarry;
        scrollCarry -= step;
        return step;
    }

    // endregion
}
