package app.revanced.extension.soundcloud.network;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A line globe: the outline, a meridian and the equator, crossed out by a slash with a gap around it. */
final class GlobeDrawable extends Drawable {
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gap = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float crossed;
    private ValueAnimator slash;

    GlobeDrawable(float stroke) {
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(stroke);
        line.setStrokeCap(Paint.Cap.ROUND);
        gap.setStyle(Paint.Style.STROKE);
        gap.setStrokeWidth(stroke * 3);
        gap.setStrokeCap(Paint.Cap.ROUND);
        gap.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    }

    /** The slash draws itself in or out. */
    void set(boolean crossedOut, int color) {
        line.setColor(color);
        float target = crossedOut ? 1 : 0;
        if (slash != null) slash.cancel();
        if (crossed == target) {
            invalidateSelf();
            return;
        }
        slash = ValueAnimator.ofFloat(crossed, target);
        slash.setDuration(300);
        slash.addUpdateListener(a -> {
            crossed = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        slash.start();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float inset = line.getStrokeWidth();
        float cx = b.exactCenterX(), cy = b.exactCenterY();
        float r = Math.min(b.width(), b.height()) / 2f - inset;
        // A separate layer, so the gap around the slash cuts the globe and not what is under the icon.
        int save = canvas.saveLayer(b.left, b.top, b.right, b.bottom, null);
        canvas.drawCircle(cx, cy, r, line);
        canvas.drawOval(cx - r * .42f, cy - r, cx + r * .42f, cy + r, line);
        canvas.drawLine(cx - r, cy, cx + r, cy, line);
        if (crossed > 0) {
            float x0 = cx - r * 1.05f, y0 = cy - r * 1.05f;
            float x1 = x0 + (r * 2.1f) * crossed, y1 = y0 + (r * 2.1f) * crossed;
            canvas.drawLine(x0, y0, x1, y1, gap);
            canvas.drawLine(x0, y0, x1, y1, line);
        }
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        line.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        line.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
