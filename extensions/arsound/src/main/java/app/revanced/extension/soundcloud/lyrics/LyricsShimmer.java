package app.revanced.extension.soundcloud.lyrics;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * What stands in for the lyrics while they are looked up: faint rounded bars where the lines will be, with a soft
 * light passing over them. It moves only while it is on screen.
 */
final class LyricsShimmer extends View {
    /** Widths of the bars as a share of the view's, like lines of a song. */
    private static final float[] WIDTHS = {0.72f, 0.88f, 0.56f, 0.8f, 0.64f, 0.84f, 0.5f};

    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private final Path bars = new Path();
    private final RectF rect = new RectF();
    private final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
    private final float barHeight;
    private final float barGap;
    private final float inset;
    private LinearGradient gradient;
    private float phase;

    LyricsShimmer(Context context) {
        super(context);
        barHeight = dp(22);
        barGap = dp(20);
        inset = dp(16);
        bar.setColor(0x24FFFFFF);
        animator.setDuration(1_400);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                phase = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float band = w * 0.7f;
        gradient = new LinearGradient(0, 0, band, 0, new int[]{0x00FFFFFF, 0x3DFFFFFF, 0x00FFFFFF}, null,
                Shader.TileMode.CLAMP);
        light.setShader(gradient);
        bars.reset();
        float top = h * 0.12f;
        for (int i = 0; top + barHeight < h * 0.9f; i++, top += barHeight + barGap) {
            rect.set(inset, top, inset + (w - 2 * inset) * WIDTHS[i % WIDTHS.length], top + barHeight);
            bars.addRoundRect(rect, barHeight / 2, barHeight / 2, Path.Direction.CW);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawPath(bars, bar);
        if (gradient == null) return;
        float band = getWidth() * 0.7f;
        matrix.setTranslate(-band + phase * (getWidth() + band), 0);
        gradient.setLocalMatrix(matrix);
        canvas.save();
        canvas.clipPath(bars);
        canvas.drawRect(0, 0, getWidth(), getHeight(), light);
        canvas.restore();
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        update();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animator.cancel();
    }

    @Override
    public void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        update();
    }

    private void update() {
        // The view calls this while it is still being built, before its fields are set.
        if (animator == null) return;
        boolean run = isAttachedToWindow() && getVisibility() == VISIBLE && getWindowVisibility() == VISIBLE;
        if (run && !animator.isStarted()) animator.start();
        else if (!run && animator.isStarted()) animator.cancel();
    }
}
