package app.revanced.extension.soundcloud.shared;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.os.SystemClock;
import android.view.View;
import android.view.animation.PathInterpolator;

/**
 * The loading view of Arsound's own screens: the А9 mark drawing itself as at the start of the app (the logo's
 * "meet": the letter line from the start of each stroke, the accent line from its end), a short hold, a fade, and
 * again, for as long as the view is shown. In place of Android's spinning circle, which looked like another app.
 * <p>
 * The letter line takes the given colour (the text colour of the screen); the accent line is the logo's azure, the
 * deep one when the letter is dark (a light screen).
 */
public final class A9Loader extends View {
    private static final long HOLD_MS = 450;
    private static final long FADE_MS = 300;
    private static final long REST_MS = 150;
    /** cubic-bezier(.65,0,.25,1): slow start, long gentle finish, as in the logo. */
    private static final PathInterpolator EASE = new PathInterpolator(0.65f, 0f, 0.25f, 1f);

    private final Path[] lines = new Path[A9Mark.LINES.length];
    private final PathMeasure[] measures = new PathMeasure[A9Mark.LINES.length];
    private final float[] lengths = new float[A9Mark.LINES.length];
    private final Path segment = new Path();
    private final Paint letterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long drawMs;
    private final long cycleMs;
    private long startedAt;

    public A9Loader(Context context, int letterColour) {
        super(context);
        long end = 0;
        for (int i = 0; i < lines.length; i++) {
            float[] points = A9Mark.LINES[i];
            Path line = new Path();
            boolean reverse = A9Mark.ACCENT[i];
            int count = points.length / 2;
            for (int p = 0; p < count; p++) {
                int index = reverse ? count - 1 - p : p;
                float x = points[index * 2], y = points[index * 2 + 1];
                if (p == 0) line.moveTo(x, y);
                else line.lineTo(x, y);
            }
            lines[i] = line;
            measures[i] = new PathMeasure(line, false);
            lengths[i] = measures[i].getLength();
            end = Math.max(end, A9Mark.TIMING[i][0] + A9Mark.TIMING[i][1]);
        }
        drawMs = end;
        cycleMs = end + HOLD_MS + FADE_MS + REST_MS;
        for (Paint paint : new Paint[]{letterPaint, accentPaint}) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(A9Mark.WIDTH);
        }
        setColour(letterColour);
        setContentDescription(Lang.text("Загрузка", "Loading"));
    }

    /** The colour of the letter line; the accent line follows it (azure on a dark screen, deep azure on a light one). */
    public void setColour(int letterColour) {
        letterPaint.setColor(letterColour);
        accentPaint.setColor(isLight(letterColour) ? 0xFF4C9BFF : 0xFF1A66D9);
        invalidate();
    }

    private static boolean isLight(int colour) {
        return 0.2126 * ((colour >> 16) & 0xFF) + 0.7152 * ((colour >> 8) & 0xFF) + 0.0722 * (colour & 0xFF) > 128;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startedAt = SystemClock.uptimeMillis();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        // Shown again: the drawing starts from the beginning, not in the middle of a fade.
        if (visibility == VISIBLE && isShown()) startedAt = SystemClock.uptimeMillis();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!isShown()) return;
        long time = (SystemClock.uptimeMillis() - startedAt) % cycleMs;
        float boxWidth = A9Mark.BOX[2] - A9Mark.BOX[0], boxHeight = A9Mark.BOX[3] - A9Mark.BOX[1];
        float side = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom());
        if (side <= 0) return;
        float scale = side / Math.max(boxWidth, boxHeight);
        int alpha = 255;
        if (time > drawMs + HOLD_MS) {
            float faded = Math.min(1f, (time - drawMs - HOLD_MS) / (float) FADE_MS);
            alpha = Math.round(255 * (1f - faded));
        }
        canvas.save();
        canvas.translate(getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - boxWidth * scale) / 2f,
                getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - boxHeight * scale) / 2f);
        canvas.scale(scale, scale);
        canvas.translate(-A9Mark.BOX[0], -A9Mark.BOX[1]);
        for (int i = 0; i < lines.length; i++) {
            long delay = A9Mark.TIMING[i][0], duration = A9Mark.TIMING[i][1];
            float progress = time <= delay ? 0f : Math.min(1f, (time - delay) / (float) duration);
            if (progress <= 0f) continue;
            Paint paint = A9Mark.ACCENT[i] ? accentPaint : letterPaint;
            int colourAlpha = paint.getColor() >>> 24;
            paint.setAlpha(colourAlpha * alpha / 255);
            if (progress >= 1f) {
                canvas.drawPath(lines[i], paint);
            } else {
                segment.rewind();
                measures[i].getSegment(0, lengths[i] * EASE.getInterpolation(progress), segment, true);
                canvas.drawPath(segment, paint);
            }
            paint.setAlpha(colourAlpha);
        }
        canvas.restore();
        postInvalidateOnAnimation();
    }
}
