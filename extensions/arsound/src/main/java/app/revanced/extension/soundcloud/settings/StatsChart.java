package app.revanced.extension.soundcloud.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.View;
import android.view.ViewConfiguration;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.LongFunction;

import app.revanced.extension.soundcloud.player.ListenBuckets;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.theme.ThemeEffect;

/**
 * The bar chart of the statistics: the time listened by day (7 and 30 days), by month (a year) or by year (all time),
 * as on the desktop. The line above the bars names the bar under the finger, the last one (now) until it is touched.
 * The bars rise once when the numbers come, one after another; in battery saving they are simply there.
 */
final class StatsChart extends View {
    private static final float HEIGHT_DP = 150, CAPTION_DP = 26, LABELS_DP = 20;
    private static final long RISE_MS = 560;

    private final float density;
    private final int accent, text, secondary;
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint caption = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionValue = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path shape = new Path();
    /** Top left, top right, bottom right, bottom left, each x and y. */
    private final float[] corners = new float[8];
    private final int touchSlop;
    private float downX, downY;
    /** The bar chosen before the finger came down: a scroll of the page that starts on the chart gives it back. */
    private int beforeTouch = -1;
    private boolean dragging;
    private final LongFunction<String> duration;
    private final Locale locale;

    private ListenBuckets.Unit unit = ListenBuckets.Unit.DAY;
    private long[] starts = new long[0];
    private long[] values = new long[0];
    private long most = 1;
    private String[] labels = new String[0];
    private int selected = -1;
    private float rise = 1f;
    private ValueAnimator riser;

    /** @param face The font of the settings' text, or null for the system one. */
    StatsChart(Context context, int accent, int text, int secondary, Typeface face, LongFunction<String> duration) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.accent = accent;
        this.text = text;
        this.secondary = secondary;
        this.duration = duration;
        locale = Lang.russian() ? new Locale("ru") : Locale.ENGLISH;
        caption.setColor(secondary);
        caption.setTextSize(13 * scaled());
        captionValue.setColor(text);
        captionValue.setTextSize(13 * scaled());
        captionValue.setFakeBoldText(true);
        captionValue.setTextAlign(Paint.Align.RIGHT);
        captionValue.setFontFeatureSettings("tnum");
        label.setColor(secondary);
        label.setTextSize(11 * scaled());
        label.setTextAlign(Paint.Align.CENTER);
        if (face != null) {
            caption.setTypeface(face);
            captionValue.setTypeface(Typeface.create(face, Typeface.BOLD));
            label.setTypeface(face);
        }
    }

    private float scaled() {
        return getResources().getDisplayMetrics().scaledDensity;
    }

    /** Shows the bars of a period; they rise from the floor. */
    void setData(ListenBuckets buckets) {
        unit = buckets.unit;
        starts = buckets.starts();
        values = buckets.values(starts);
        most = 1;
        for (long value : values) most = Math.max(most, value);
        labels = new String[starts.length];
        for (int i = 0; i < starts.length; i++) labels[i] = barLabel(starts[i]);
        selected = starts.length - 1;
        updateDescription();
        if (riser != null) riser.cancel();
        if (ThemeEffect.isStill(getContext())) {
            rise = 1f;
            invalidate();
            return;
        }
        riser = ValueAnimator.ofFloat(0f, 1f);
        riser.setDuration(RISE_MS);
        riser.setInterpolator(new DecelerateInterpolator(1.6f));
        riser.addUpdateListener(a -> {
            rise = (float) a.getAnimatedValue();
            invalidate();
        });
        riser.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (riser != null) riser.cancel();
        rise = 1f;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, Math.round(HEIGHT_DP * density));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int count = starts.length;
        if (count == 0) return;
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        float top = CAPTION_DP * density, bottom = getHeight() - LABELS_DP * density;
        float slot = (right - left) / count;
        float gap = Math.min(slot * 0.32f, 6 * density);
        float width = slot - gap;
        float radius = Math.min(width / 2, 4 * density);
        float floor = Math.max(1.5f * density, 2);

        // The caption: the bar chosen and its time.
        int shown = selected >= 0 && selected < count ? selected : count - 1;
        float baseline = 15 * density;
        canvas.drawText(captionText(shown), left, baseline, caption);
        canvas.drawText(duration.apply(values[shown]), right, baseline, captionValue);

        for (int i = 0; i < count; i++) {
            // One after another: the later bars start a little later and catch up.
            float delay = count > 1 ? 0.35f * i / (count - 1) : 0f;
            float progress = Math.max(0f, Math.min(1f, (rise - delay) / (1f - 0.35f)));
            if (rise >= 1f) progress = 1f;
            float share = values[i] / (float) most;
            float height = Math.max(floor, (bottom - top) * share * progress);
            float x = left + slot * i + gap / 2;
            rect.set(x, bottom - height, x + width, bottom);
            boolean chosen = i == shown;
            int alpha = values[i] == 0 ? 0x22 : chosen ? 0xFF : 0x80;
            bar.setColor((accent & 0x00FFFFFF) | (alpha << 24));
            // Round top corners, square bottom ones: the bars stand on the floor. One shape, so a see-through bar
            // is not painted twice where two shapes would overlap.
            float r = Math.min(radius, height / 2);
            corners[0] = corners[1] = corners[2] = corners[3] = r;
            shape.reset();
            shape.addRoundRect(rect, corners, Path.Direction.CW);
            canvas.drawPath(shape, bar);
        }

        // Labels under the bars, as many as fit, the last one always.
        float widest = 0;
        for (String text : labels) widest = Math.max(widest, label.measureText(text));
        int step = Math.max(1, (int) Math.ceil((widest + 6 * density) / slot));
        float labelBaseline = getHeight() - 5 * density;
        for (int i = count - 1; i >= 0; i -= step) {
            label.setColor(i == shown ? text : secondary);
            float cx = left + slot * i + slot / 2;
            String text = labels[i];
            float half = label.measureText(text) / 2;
            cx = Math.max(left + half, Math.min(right - half, cx));
            canvas.drawText(text, cx, labelBaseline, label);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (starts.length == 0) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                beforeTouch = selected;
                dragging = false;
                choose(event.getX());
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = Math.abs(event.getX() - downX), dy = Math.abs(event.getY() - downY);
                if (!dragging && dx > touchSlop && dx > dy) {
                    // A drag along the bars, not a scroll of the page.
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) choose(event.getX());
                return true;
            case MotionEvent.ACTION_UP:
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                // The page took the touch for its scroll: the bar chosen before stays.
                if (!dragging && beforeTouch != selected) {
                    selected = beforeTouch;
                    updateDescription();
                    invalidate();
                }
                return true;
            default:
                return true;
        }
    }

    private void choose(float x) {
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight();
        int index = (int) ((x - left) / ((right - left) / starts.length));
        index = Math.max(0, Math.min(starts.length - 1, index));
        if (index == selected) return;
        selected = index;
        updateDescription();
        invalidate();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void updateDescription() {
        int shown = selected >= 0 && selected < starts.length ? selected : starts.length - 1;
        if (shown < 0) return;
        setContentDescription(captionText(shown) + ": " + duration.apply(values[shown]));
    }

    /** The short name under a bar: a weekday or a day of the month, a month, a year. */
    private String barLabel(long start) {
        Date date = new Date(start);
        switch (unit) {
            case DAY:
                return starts.length <= 7 ? format("EEEEEE", date) : format("d", date);
            case MONTH:
                return trimDot(format("LLL", date));
            default:
                return format("yyyy", date);
        }
    }

    /** The line above the bars: "Сегодня", "пн, 3 нояб.", "ноябрь 2026", "2025". */
    private String captionText(int index) {
        Date date = new Date(starts[index]);
        boolean last = index == starts.length - 1;
        switch (unit) {
            case DAY:
                if (last) return Lang.text("Сегодня", "Today");
                if (index == starts.length - 2) return Lang.text("Вчера", "Yesterday");
                return format("EEE, d MMMM", date);
            case MONTH:
                String month = format("LLLL yyyy", date);
                return month.isEmpty() ? month : Character.toUpperCase(month.charAt(0)) + month.substring(1);
            default:
                return format("yyyy", date);
        }
    }

    private String format(String pattern, Date date) {
        return new SimpleDateFormat(pattern, locale).format(date);
    }

    private static String trimDot(String text) {
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }
}
