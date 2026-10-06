package app.revanced.extension.soundcloud.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.util.Locale;

/**
 * The equalizer as a curve: a smooth line through the band levels with a soft fill under it, a handle on every
 * band. A handle is dragged up and down to change its band; a new preset morphs the curve into its shape.
 */
final class EqualizerGraph extends View {
    interface Listener {
        /** A band was moved by the user; level in millibels. */
        void onLevel(int band, short level);
    }

    private final float density;
    private final String[] labels;
    private final short min, max;
    private final float[] shown;
    private final int accent, text, faint, card;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path curve = new Path();
    private final Path area = new Path();
    private final RectF rect = new RectF();
    private ValueAnimator morph;
    private int dragging = -1;
    private float dragGrow;
    private Listener listener;

    EqualizerGraph(Context context, int[] centerFrequenciesHz, short min, short max, int accent, int text, int card) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        this.min = min;
        this.max = max;
        this.accent = accent;
        this.text = text;
        this.card = card;
        faint = (text & 0x00FFFFFF) | 0x26000000;
        shown = new float[centerFrequenciesHz.length];
        labels = new String[centerFrequenciesHz.length];
        boolean russian = "ru".equals(Locale.getDefault().getLanguage());
        for (int i = 0; i < labels.length; i++) {
            int hz = centerFrequenciesHz[i];
            labels[i] = hz >= 1000
                    ? (hz % 1000 == 0 || hz >= 10000 ? String.valueOf(Math.round(hz / 1000f)) : String.format(Locale.US, "%.1f", hz / 1000f))
                    + (russian ? " кГц" : " kHz")
                    : hz + (russian ? " Гц" : " Hz");
        }
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2.5f * density);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(accent);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(Math.max(1, density));
        label.setTextAlign(Paint.Align.CENTER);
        background.setColor(card);
        setContentDescription(russian ? "Кривая эквалайзера" : "Equalizer curve");
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Shows levels in millibels, sliding from the old curve unless told otherwise. */
    void setLevels(short[] levels, boolean animate) {
        if (morph != null) morph.cancel();
        float[] from = shown.clone();
        if (!animate) {
            for (int i = 0; i < shown.length && i < levels.length; i++) shown[i] = levels[i];
            invalidate();
            return;
        }
        morph = ValueAnimator.ofFloat(0, 1);
        morph.setDuration(380);
        morph.setInterpolator(new DecelerateInterpolator(1.6f));
        morph.addUpdateListener(a -> {
            float t = (float) a.getAnimatedValue();
            for (int i = 0; i < shown.length && i < levels.length; i++) shown[i] = from[i] + (levels[i] - from[i]) * t;
            invalidate();
        });
        morph.start();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(236 * density));
    }

    private float top() {
        return 34 * density;
    }

    private float bottom() {
        return getHeight() - 34 * density;
    }

    private float xOf(int band) {
        float side = 30 * density;
        float span = getWidth() - 2 * side;
        return shown.length == 1 ? getWidth() / 2f : side + span * band / (shown.length - 1);
    }

    private float yOf(float level) {
        float t = (level - min) / (float) (max - min);
        return bottom() - (bottom() - top()) * t;
    }

    private short levelAt(float y) {
        float t = (bottom() - y) / (bottom() - top());
        float level = min + (max - min) * Math.max(0, Math.min(1, t));
        // Steps of half a decibel are easy to hit with a finger.
        return (short) (Math.round(level / 50f) * 50);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight();
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, 20 * density, 20 * density, background);

        // The 0 dB line dashed, the limits plain and faint.
        grid.setColor(faint);
        grid.setPathEffect(null);
        canvas.drawLine(16 * density, yOf(max), w - 16 * density, yOf(max), grid);
        canvas.drawLine(16 * density, yOf(min), w - 16 * density, yOf(min), grid);
        grid.setPathEffect(new DashPathEffect(new float[]{4 * density, 4 * density}, 0));
        grid.setColor((text & 0x00FFFFFF) | 0x40000000);
        canvas.drawLine(16 * density, yOf(0), w - 16 * density, yOf(0), grid);
        for (int i = 0; i < shown.length; i++) {
            grid.setColor(faint);
            grid.setPathEffect(null);
            canvas.drawLine(xOf(i), yOf(max), xOf(i), yOf(min), grid);
        }

        // A smooth curve through the handles (Catmull-Rom as cubic Béziers), flat out to the edges.
        int n = shown.length;
        curve.reset();
        curve.moveTo(16 * density, yOf(shown[0]));
        curve.lineTo(xOf(0), yOf(shown[0]));
        for (int i = 0; i < n - 1; i++) {
            float x0 = xOf(Math.max(0, i - 1)), y0 = yOf(shown[Math.max(0, i - 1)]);
            float x1 = xOf(i), y1 = yOf(shown[i]);
            float x2 = xOf(i + 1), y2 = yOf(shown[i + 1]);
            float x3 = xOf(Math.min(n - 1, i + 2)), y3 = yOf(shown[Math.min(n - 1, i + 2)]);
            // Control points stay between the two handles, so the line never bulges past a band's level.
            float low = Math.min(y1, y2), high = Math.max(y1, y2);
            float c1 = Math.max(low, Math.min(high, y1 + (y2 - y0) / 6));
            float c2 = Math.max(low, Math.min(high, y2 - (y3 - y1) / 6));
            curve.cubicTo(x1 + (x2 - x0) / 6, c1, x2 - (x3 - x1) / 6, c2, x2, y2);
        }
        curve.lineTo(w - 16 * density, yOf(shown[n - 1]));
        area.set(curve);
        area.lineTo(w - 16 * density, yOf(min));
        area.lineTo(16 * density, yOf(min));
        area.close();
        fill.setShader(new LinearGradient(0, top(), 0, bottom(),
                (accent & 0x00FFFFFF) | 0x59000000, accent & 0x00FFFFFF, Shader.TileMode.CLAMP));
        canvas.drawPath(area, fill);
        canvas.drawPath(curve, line);

        label.setTextSize(11 * density);
        for (int i = 0; i < n; i++) {
            float x = xOf(i), y = yOf(shown[i]);
            boolean active = i == dragging;
            float radius = (7 + (active ? 3 * dragGrow : 0)) * density;
            if (active) {
                dot.setColor((accent & 0x00FFFFFF) | 0x33000000);
                canvas.drawCircle(x, y, radius + 9 * density * dragGrow, dot);
            }
            dot.setColor(accent);
            canvas.drawCircle(x, y, radius, dot);
            dot.setColor(card);
            canvas.drawCircle(x, y, radius * .42f, dot);

            label.setColor(active ? accent : (text & 0x00FFFFFF) | 0xB3000000);
            label.setFakeBoldText(active);
            String value = String.format(Locale.US, "%+.1f", shown[i] / 100f);
            canvas.drawText(value, x, Math.max(top() - 12 * density, y - radius - 8 * density), label);
            label.setFakeBoldText(false);
            label.setColor((text & 0x00FFFFFF) | 0x99000000);
            canvas.drawText(labels[i], x, h - 12 * density, label);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                int nearest = 0;
                for (int i = 1; i < shown.length; i++) {
                    if (Math.abs(xOf(i) - event.getX()) < Math.abs(xOf(nearest) - event.getX())) nearest = i;
                }
                if (Math.abs(xOf(nearest) - event.getX()) > 40 * density) return false;
                if (morph != null) morph.cancel();
                dragging = nearest;
                getParent().requestDisallowInterceptTouchEvent(true);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                ValueAnimator grow = ValueAnimator.ofFloat(dragGrow, 1);
                grow.setDuration(150);
                grow.addUpdateListener(a -> {
                    dragGrow = (float) a.getAnimatedValue();
                    invalidate();
                });
                grow.start();
                move(event.getY());
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging < 0) return false;
                move(event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging < 0) return false;
                ValueAnimator shrink = ValueAnimator.ofFloat(dragGrow, 0);
                shrink.setDuration(200);
                shrink.addUpdateListener(a -> {
                    dragGrow = (float) a.getAnimatedValue();
                    if (dragGrow == 0) dragging = -1;
                    invalidate();
                });
                shrink.start();
                return true;
            default:
                return false;
        }
    }

    private void move(float y) {
        short level = levelAt(y);
        if (shown[dragging] == level) return;
        if (level == 0) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        shown[dragging] = level;
        invalidate();
        if (listener != null) listener.onLevel(dragging, level);
    }
}
