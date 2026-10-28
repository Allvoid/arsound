package app.revanced.extension.soundcloud.wallpaper;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/**
 * The skeleton of a picture that is not there yet: a flat colour with a soft light band that runs across it again and
 * again. A {@link WallhavenCell} shows it until its thumbnail arrives. Call {@link #start} when it is shown and
 * {@link #stop} when it is not, so no animator runs for a cell that is off screen.
 */
final class ShimmerDrawable extends Drawable {
    private static final long SWEEP_MS = 1300;

    private final Paint base = new Paint();
    private final Paint band = new Paint();
    private final Matrix matrix = new Matrix();
    private ValueAnimator animator;
    private float phase;
    private float bandWidth;

    /** @param color The colour of the skeleton; the band is a light veil over it. */
    ShimmerDrawable(int color) {
        base.setColor(color);
    }

    void start() {
        if (animator != null) return;
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(SWEEP_MS);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> {
            // Taken off its view (the picture arrived): nobody draws it, so it stops by itself.
            if (getCallback() == null) {
                stop();
                return;
            }
            phase = (float) a.getAnimatedValue();
            invalidateSelf();
        });
        animator.start();
    }

    void stop() {
        if (animator == null) return;
        animator.cancel();
        animator = null;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        bandWidth = Math.max(1f, bounds.width() * .8f);
        band.setShader(new LinearGradient(0, 0, bandWidth, 0, new int[]{0x00FFFFFF, 0x24FFFFFF, 0x00FFFFFF}, null,
                Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        canvas.drawRect(bounds, base);
        if (band.getShader() == null) return;
        // The band starts left of the cell and ends right of it.
        float x = bounds.left - bandWidth + phase * (bounds.width() + bandWidth);
        matrix.setTranslate(x, 0);
        band.getShader().setLocalMatrix(matrix);
        canvas.save();
        canvas.clipRect(bounds);
        canvas.drawRect(bounds, band);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        base.setAlpha(alpha);
        band.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        base.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
