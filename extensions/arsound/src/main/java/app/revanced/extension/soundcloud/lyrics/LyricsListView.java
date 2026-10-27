package app.revanced.extension.soundcloud.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.BidiFormatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The lyrics as a column of lines you can scroll.
 * <p>
 * With timings the line that sings now is bright and a little larger, the ones around it dimmer, the rest faint, and
 * the column keeps that line near the upper middle of the view with a smooth scroll. Touching the column hands the
 * scrolling to the finger; a few seconds after the last touch it glides back. A tap on a line asks the host to jump
 * there; a tap anywhere else is the host's to pass on. Words without timings are only text to read: the column stays
 * where the reader leaves it.
 * <p>
 * It is a plain ScrollView on purpose: the extension has no RecyclerView, and a song has some sixty lines.
 */
final class LyricsListView extends ScrollView {
    /** What the list cannot do itself. */
    interface Host {
        /** A tap on a line of synced lyrics. */
        void onLineTapped(int index);

        /** A tap that is on no line, or on a line of plain text. */
        void onBlankTap();

        /** Two quick taps on lines, on the same one or on two. */
        void onDoubleTap();
    }

    /** Where the singing line sits, as a share of the height. */
    private static final float FOCUS = 0.40f;
    /** How long the column stays where the user left it. */
    private static final long HOLD_MS = 3_000;
    /** Two taps on lines this close are one double tap: the second pauses or plays instead of jumping again. */
    private static final long DOUBLE_TAP_MS = 280;
    private static final float TEXT_SP = 22f;
    /** The empty space between two verses of plain text. */
    private static final float VERSE_GAP_DP = 18f;
    private static final long STYLE_MS = 260;
    private static final Interpolator GLIDE = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private final Host host;
    private final int slop;
    private final LinearLayout column;
    private final View topSpacer;
    private final View bottomSpacer;
    private final TextView credit;
    private final DecelerateInterpolator ease = new DecelerateInterpolator(1.5f);

    private LineView[] lines = new LineView[0];
    private Lyrics lyrics;
    private int active = -2;
    private boolean pendingScroll;
    private ValueAnimator glide;
    private boolean programmatic;

    private boolean touching;
    private long lastUserAt;
    private long lastScrollAt;
    private float downX;
    private float downY;
    private long downTime;
    private boolean tapCandidate;
    /** The finger went sideways or down from the top: the gesture is the parent's now. */
    private boolean released;
    private boolean startedAtTop;
    /** When the last tap on a line was; 0 once it has been used up by a double tap or a tap elsewhere. */
    private long lastTapTime;

    private final Runnable resume = new Runnable() {
        @Override
        public void run() {
            long left = lastUserAt + HOLD_MS - SystemClock.uptimeMillis();
            // Plain words stay where the reader left them: there is no singing line to go back to.
            if (touching || !isTimed()) return;
            if (left > 0) postDelayed(this, left + 30);
            else glideToActive(true);
        }
    };

    LyricsListView(Context context, Host host) {
        super(context);
        this.host = host;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalFadingEdgeEnabled(true);
        setFadingEdgeLength(dp(56));
        setClipToPadding(false);

        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        topSpacer = new View(context);
        bottomSpacer = new View(context);
        credit = new TextView(context);
        credit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        credit.setTextColor(0x66FFFFFF);
        credit.setAlpha(0.7f);
        credit.setPadding(dp(16), dp(18), dp(20), dp(8));
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    /** One line of the song. */
    private static final class LineView extends TextView {
        boolean rtl;

        LineView(Context context) {
            super(context);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // Lines shrink and grow around the start of their text, left for most languages, right for Arabic and Hebrew.
            setPivotX(rtl ? w - getPaddingRight() : getPaddingLeft());
            setPivotY(h / 2f);
        }
    }

    /**
     * Fills the column with a song (or empties it with null).
     *
     * @param face       The font of the player's title, so the words look like the rest of the screen.
     * @param creditText The line of thanks under the words.
     */
    void setLyrics(Lyrics next, Typeface face, String creditText) {
        stopGlide();
        column.removeAllViews();
        lyrics = next;
        lines = new LineView[0];
        active = -2;
        // A new song starts with the column free, whatever the user did to the last one.
        lastUserAt = 0;
        removeCallbacks(resume);
        if (next == null) return;

        column.addView(topSpacer, new LinearLayout.LayoutParams(1, Math.round(getHeight() * FOCUS)));
        lines = new LineView[next.lines.length];
        BidiFormatter bidi = BidiFormatter.getInstance();
        for (int i = 0; i < lines.length; i++) {
            LineView view = new LineView(getContext());
            boolean empty = next.lines[i].isEmpty();
            // With timings an empty line is a pause in the music: three dots, smaller than the words. Without timings
            // it is only the gap between two verses, and dots there would mark a pause that is not in the song.
            boolean pause = empty && next.isTimed();
            String text = pause ? "• • •" : next.lines[i];
            view.rtl = !empty && bidi.isRtl(text);
            view.setText(text);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, pause ? TEXT_SP * 0.6f : TEXT_SP);
            view.setTextColor(0xFFFFFFFF);
            if (face != null) view.setTypeface(face);
            view.setIncludeFontPadding(false);
            view.setLineSpacing(dp(2), 1f);
            view.setShadowLayer(dp(8), 0, dp(1.5f), 0x99000000);
            view.setPadding(dp(16), dp(pause ? 6 : 9), dp(20), dp(pause ? 6 : 9));
            view.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            view.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
            view.setGravity(Gravity.START);
            if (empty && !pause) {
                view.setPadding(0, 0, 0, 0);
                view.setHeight(dp(VERSE_GAP_DP));
            }
            lines[i] = view;
            column.addView(view, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        credit.setText(creditText);
        column.addView(credit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        column.addView(bottomSpacer, new LinearLayout.LayoutParams(1, Math.round(getHeight() * (1 - FOCUS))));

        programmatic = true;
        scrollTo(0, 0);
        programmatic = false;
        applyAll(false);
        // Sized when the first layout has happened.
        pendingScroll = true;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // The spacers are set to the height this pass gives the list before the column is measured, so the first layout
        // already has the room above and below that lets the singing line stand in its place.
        if (lines.length > 0 && MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
            sizeSpacers(MeasureSpec.getSize(heightMeasureSpec));
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void sizeSpacers(int height) {
        int top = Math.round(height * FOCUS);
        int bottom = Math.round(height * (1 - FOCUS));
        ViewGroup.LayoutParams topParams = topSpacer.getLayoutParams();
        ViewGroup.LayoutParams bottomParams = bottomSpacer.getLayoutParams();
        if (topParams.height == top && bottomParams.height == bottom) return;
        topParams.height = top;
        bottomParams.height = bottom;
        // Not requestLayout(): this runs inside a measure pass that goes on to measure the column; it only has to know.
        column.forceLayout();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // The singing line is put in place again once the new size has been laid out.
        if (lines.length > 0) pendingScroll = true;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (pendingScroll && lines.length > 0 && getHeight() > 0) {
            pendingScroll = false;
            jumpToActive();
        }
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (!programmatic) {
            lastUserAt = SystemClock.uptimeMillis();
            lastScrollAt = lastUserAt;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopGlide();
        removeCallbacks(resume);
    }

    // region The line that sings

    boolean isTimed() {
        return lyrics != null && lyrics.isTimed();
    }

    /** The line that is bright now, or -1 before the first; -2 if none was set yet. */
    int activeLine() {
        return active;
    }

    /**
     * Makes a line the singing one: restyles the lines around it and, unless the user has the column, scrolls to it.
     *
     * @param animate Whether the change is seen; false for a jump made while the list is hidden.
     */
    void setActive(int index, boolean animate) {
        if (!isTimed() || index == active) return;
        int previous = active;
        active = index;
        if (previous == -2) {
            applyAll(false);
        } else {
            int from = Math.max(0, Math.min(previous, index) - 3);
            int to = Math.min(lines.length - 1, Math.max(previous, index) + 3);
            for (int i = from; i <= to; i++) {
                // Lines far from both stay as they are; only the two neighbourhoods change.
                if (Math.abs(i - previous) <= 3 || Math.abs(i - index) <= 3) style(i, animate);
            }
        }
        if (!isHeld()) {
            if (animate) glideToActive(true);
            else jumpToActive();
        }
    }

    /** The user took the column, or let go of it a moment ago. */
    boolean isHeld() {
        return touching || SystemClock.uptimeMillis() - lastUserAt < HOLD_MS;
    }

    /** The user is done with the column: the next change of line scrolls again. */
    void clearHold() {
        lastUserAt = 0;
        removeCallbacks(resume);
    }

    /** Glides back to the singing line. */
    void recentre() {
        glideToActive(true);
    }

    private void applyAll(boolean animate) {
        for (int i = 0; i < lines.length; i++) style(i, animate);
    }

    private void style(int i, boolean animate) {
        LineView view = lines[i];
        float alpha;
        float scale;
        if (!lyrics.isTimed()) {
            alpha = 0.92f;
            scale = 1f;
        } else {
            int distance = Math.abs(i - active);
            alpha = distance == 0 ? 1f : distance == 1 ? 0.62f : distance == 2 ? 0.46f : 0.34f;
            scale = distance == 0 ? 1f : 0.88f;
            if (lyrics.lines[i].isEmpty() && distance != 0) alpha *= 0.7f;
        }
        view.animate().cancel();
        if (animate) {
            view.animate().alpha(alpha).scaleX(scale).scaleY(scale).setDuration(STYLE_MS).setInterpolator(ease).start();
        } else {
            view.setAlpha(alpha);
            view.setScaleX(scale);
            view.setScaleY(scale);
        }
    }

    // endregion

    // region Scrolling

    private int maxScroll() {
        return Math.max(0, column.getHeight() - getHeight());
    }

    private int targetFor(int index) {
        if (index < 0 || index >= lines.length) return 0;
        LineView view = lines[index];
        int target = view.getTop() + view.getHeight() / 2 - Math.round(getHeight() * FOCUS);
        return Math.max(0, Math.min(target, maxScroll()));
    }

    private void jumpToActive() {
        // Plain words have no line to follow; the reader's place is not touched.
        if (!isTimed()) return;
        stopGlide();
        programmatic = true;
        scrollTo(0, targetFor(active));
        programmatic = false;
    }

    private void glideToActive(boolean smooth) {
        if (!isTimed()) return;
        if (lines.length == 0 || getHeight() == 0) {
            pendingScroll = true;
            return;
        }
        stopGlide();
        int from = getScrollY();
        int target = targetFor(active);
        if (from == target) return;
        if (!smooth) {
            jumpToActive();
            return;
        }
        long duration = Math.max(320, Math.min(700, 250 + (long) (Math.abs(target - from) * 0.35f)));
        glide = ValueAnimator.ofInt(from, target);
        glide.setDuration(duration);
        glide.setInterpolator(GLIDE);
        glide.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                programmatic = true;
                scrollTo(0, (int) animation.getAnimatedValue());
                programmatic = false;
            }
        });
        glide.start();
    }

    /** Stops the glide, wherever it is. */
    private void stopGlide() {
        if (glide != null) {
            glide.cancel();
            glide = null;
        }
    }

    // endregion

    // region Touch

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                downTime = event.getEventTime();
                // A touch that stops a fling is the stop, not a tap.
                tapCandidate = SystemClock.uptimeMillis() - lastScrollAt > 120;
                released = false;
                touching = true;
                stopGlide();
                removeCallbacks(resume);
                startedAtTop = getScrollY() == 0;
                // The column scrolls by itself: nothing above it may take the drag (the player sheet, the pager).
                if (column.getHeight() > getHeight()) {
                    ViewParent parent = getParent();
                    if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) tapCandidate = false;
                if (!released) {
                    if (Math.abs(dx) > slop && Math.abs(dx) > 2 * Math.abs(dy)) {
                        // A sideways swipe is for the pager: it changes the track.
                        release();
                    } else if (startedAtTop && dy > slop && dy > 2 * Math.abs(dx)) {
                        // A pull down that began at the top of the words is for the player sheet: it closes the player.
                        release();
                    }
                }
                // The column would take the gesture back the moment the finger drifts up or down a little.
                if (released) return true;
                break;
            case MotionEvent.ACTION_UP:
                touching = false;
                lastUserAt = SystemClock.uptimeMillis();
                if (tapCandidate && event.getEventTime() - downTime <= ViewConfiguration.getLongPressTimeout()) {
                    tapped(event.getX(), event.getY(), event.getEventTime());
                }
                postResume();
                break;
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                lastUserAt = SystemClock.uptimeMillis();
                tapCandidate = false;
                postResume();
                break;
            default:
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    /** Gives the gesture back to the parents that were kept from it. */
    private void release() {
        released = true;
        ViewParent parent = getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
    }

    private void postResume() {
        removeCallbacks(resume);
        postDelayed(resume, HOLD_MS + 30);
    }

    private void tapped(float x, float y, long time) {
        int line = lineAt(Math.round(y) + getScrollY());
        boolean seekable = line >= 0 && isTimed() && !lyrics.lines[line].isEmpty();
        if (!seekable) {
            lastTapTime = 0;
            host.onBlankTap();
            return;
        }
        // The first tap of a double tap has jumped already; the second only pauses or plays, whichever line it is on.
        if (lastTapTime > 0 && time - lastTapTime <= DOUBLE_TAP_MS) {
            lastTapTime = 0;
            host.onDoubleTap();
            return;
        }
        lastTapTime = time;
        host.onLineTapped(line);
    }

    private int lineAt(int contentY) {
        for (int i = 0; i < lines.length; i++) {
            if (contentY >= lines[i].getTop() && contentY < lines[i].getBottom()) return i;
        }
        return -1;
    }

    // endregion
}
