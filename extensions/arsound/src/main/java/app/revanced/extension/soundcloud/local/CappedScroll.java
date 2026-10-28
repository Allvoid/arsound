package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.ScrollView;

/** A list that scrolls inside the dialog's own scrolling body, and keeps the touches that are meant for it. */
final class CappedScroll extends ScrollView {
    int limit = Integer.MAX_VALUE;
    /** Runs once the list was scrolled or laid out again, to ask for the covers that came into view. */
    Runnable onViewportChanged;
    private boolean viewportPending;

    CappedScroll(Context context) {
        super(context);
    }

    private void viewportChanged() {
        // Posted: the covers are set outside of the layout pass.
        if (onViewportChanged == null || viewportPending) return;
        viewportPending = true;
        post(() -> {
            viewportPending = false;
            onViewportChanged.run();
        });
    }

    @Override
    protected void onScrollChanged(int left, int top, int oldLeft, int oldTop) {
        super.onScrollChanged(left, top, oldLeft, oldTop);
        viewportChanged();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        viewportChanged();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        // The body of the dialog scrolls too, when the window is very low: this list goes first.
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return super.dispatchTouchEvent(event);
    }
}
