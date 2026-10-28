package app.revanced.extension.soundcloud.settings;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ComposeShader;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import app.revanced.extension.soundcloud.shared.Dimens;

/**
 * A colour picker: a square for saturation (across) and brightness (down) of the hue below it, and a hue bar. The
 * thumbs are round, in the colour they pick, with a white ring and a soft shadow, and grow a little under the finger.
 * While a finger drags, a bigger bubble in the picked colour floats above it, so the colour can be seen and not hidden
 * by the finger.
 * <p>
 * The square shows a {@link ColorRange}: all of it for an accent, only the dark (or the pale) part for a background,
 * where the shades differ by a few steps of brightness and the full square would give them a few pixels.
 * <p>
 * Holds the colour as hue, saturation and value, so dragging through a grey keeps the hue instead of jumping to red.
 */
public final class HsvColorPicker extends View {
    public interface Listener {
        /** @param fromUser False when the colour was set from code, for example from the hex field. */
        void onColorChanged(int color, boolean fromUser);
    }

    private static final int[] HUES = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
    private static final int NONE = 0, SQUARE = 1, BAR = 2;

    private final float[] hsv = {210f, .6f, .9f};
    /** The colour as it was set or last dragged to: converting to hue, saturation and value and back can shift a channel. */
    private int color = Color.HSVToColor(hsv);
    private ColorRange range = ColorRange.FULL;
    private final RectF square = new RectF();
    private final RectF bar = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float corner;
    private final float thumbRadius;
    private final float bubbleRadius;
    private final float bubbleLift;
    private final float barHeight;
    private final float gap;
    /** How far from a shape a touch still grabs it: a thumb is smaller than a fingertip. */
    private final float reach;

    private Shader squareShader;
    private float squareShaderHue = -1;
    private Shader barShader;
    private float thumbScale = 1f;
    private ValueAnimator thumbAnimation;
    private int dragging = NONE;
    /** The thumb that is grown or still shrinking back: the finger has left it by then, so {@link #dragging} cannot tell. */
    private int scaled = NONE;
    private Listener listener;

    public HsvColorPicker(Context context) {
        super(context);
        corner = dp(14);
        thumbRadius = dp(14);
        bubbleRadius = dp(22);
        bubbleLift = dp(58);
        barHeight = dp(36);
        gap = dp(22);
        reach = dp(24);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, dp(1)));
        edge.setColor(0x22808080);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Which part of the square is offered; the picked colour stays, its thumb goes to the nearest edge if it is outside. */
    void setRange(ColorRange range) {
        this.range = range;
        squareShaderHue = -1;
        invalidate();
    }

    /** The picked colour, opaque. */
    public int getColor() {
        return color;
    }

    /** Moves the picker to this colour; the listener hears it with {@code fromUser} false. */
    public void setColor(int color) {
        this.color = color | 0xFF000000;
        float hue = hsv[0];
        Color.colorToHSV(this.color, hsv);
        // A grey has no hue of its own: keep the one the user had.
        if (hsv[1] == 0f || hsv[2] == 0f) hsv[0] = hue;
        invalidate();
        notifyListener(false);
    }

    private float dp(float value) {
        return Dimens.dpExact(getContext(), value);
    }

    private void notifyListener(boolean fromUser) {
        if (listener != null) listener.onColorChanged(getColor(), fromUser);
    }

    private float squareHeightFor(int width) {
        return Math.min(dp(190), (width - 2 * thumbRadius) * .62f);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? Math.round(dp(280)) : MeasureSpec.getSize(widthSpec);
        int height = Math.round(thumbRadius + squareHeightFor(width) + gap + barHeight + thumbRadius);
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float squareHeight = squareHeightFor(width);
        square.set(thumbRadius, thumbRadius, width - thumbRadius, thumbRadius + squareHeight);
        bar.set(thumbRadius, square.bottom + gap, width - thumbRadius, square.bottom + gap + barHeight);
        barShader = new LinearGradient(bar.left, 0, bar.right, 0, HUES, null, Shader.TileMode.CLAMP);
        squareShaderHue = -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (square.isEmpty()) return;
        // The square: white to the hue across, multiplied by a grey that falls down. The range cuts the part shown.
        if (squareShaderHue != hsv[0]) {
            int hue = Color.HSVToColor(new float[]{hsv[0], range.saturationMax, 1f});
            int top = grey(range.valueMax), bottom = grey(range.valueMin);
            squareShader = new ComposeShader(
                    new LinearGradient(square.left, 0, square.right, 0, Color.WHITE, hue, Shader.TileMode.CLAMP),
                    new LinearGradient(0, square.top, 0, square.bottom, top, bottom, Shader.TileMode.CLAMP),
                    PorterDuff.Mode.MULTIPLY);
            squareShaderHue = hsv[0];
        }
        fill.setShader(squareShader);
        canvas.drawRoundRect(square, corner, corner, fill);
        canvas.drawRoundRect(square, corner, corner, edge);
        fill.setShader(barShader);
        canvas.drawRoundRect(bar, barHeight / 2, barHeight / 2, fill);
        canvas.drawRoundRect(bar, barHeight / 2, barHeight / 2, edge);
        fill.setShader(null);

        float squareX = square.left + range.acrossOf(hsv[1]) * square.width();
        float squareY = square.top + range.downOf(hsv[2]) * square.height();
        int hueColor = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
        float barX = bar.left + hsv[0] / 360f * bar.width();
        drawThumb(canvas, squareX, squareY, getColor(), scaled == SQUARE);
        drawThumb(canvas, barX, bar.centerY(), hueColor, scaled == BAR);
        if (dragging == SQUARE) drawBubble(canvas, squareX, squareY, getColor());
        else if (dragging == BAR) drawBubble(canvas, barX, bar.centerY(), hueColor);
    }

    private static int grey(float value) {
        int level = Math.round(Math.max(0f, Math.min(1f, value)) * 255f);
        return Color.rgb(level, level, level);
    }

    /** A round thumb in the colour it stands for: a soft shadow below, a white ring and a faint dark edge for pale colours. */
    private void drawThumb(Canvas canvas, float x, float y, int color, boolean grown) {
        float scale = grown ? thumbScale : 1f;
        float radius = thumbRadius * scale;
        // The shadow is a radial fade, not setShadowLayer, which a hardware canvas does not draw before Android 9.
        float shadowRadius = radius + dp(5);
        thumb.setStyle(Paint.Style.FILL);
        thumb.setShader(new RadialGradient(x, y + dp(2), shadowRadius, new int[]{0x66000000, 0x33000000, 0x00000000},
                new float[]{radius / shadowRadius * .8f, radius / shadowRadius, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y + dp(2), shadowRadius, thumb);
        thumb.setShader(null);
        thumb.setColor(0x33000000);
        canvas.drawCircle(x, y, radius, thumb);
        thumb.setColor(Color.WHITE);
        canvas.drawCircle(x, y, radius - dp(.5f), thumb);
        thumb.setColor(color);
        canvas.drawCircle(x, y, radius - dp(3.5f), thumb);
    }

    /**
     * The bubble over the finger: a big disc of the picked colour. It floats above the thumb, or below it when there is
     * no room above (the thumb is near the top of the view).
     */
    private void drawBubble(Canvas canvas, float x, float y, int color) {
        float cx = Math.max(bubbleRadius, Math.min(getWidth() - bubbleRadius, x));
        float cy = y - bubbleLift;
        if (cy < bubbleRadius + dp(2)) cy = y + bubbleLift;
        thumb.setStyle(Paint.Style.FILL);
        thumb.setColor(0x44000000);
        canvas.drawCircle(cx, cy + dp(2), bubbleRadius + dp(1.5f), thumb);
        thumb.setColor(Color.WHITE);
        canvas.drawCircle(cx, cy, bubbleRadius, thumb);
        thumb.setColor(color);
        canvas.drawCircle(cx, cy, bubbleRadius - dp(3), thumb);
    }

    /** How far a point is from a rectangle: 0 inside it. */
    private static float distance(RectF rect, float x, float y) {
        float dx = Math.max(Math.max(rect.left - x, 0f), x - rect.right);
        float dy = Math.max(Math.max(rect.top - y, 0f), y - rect.bottom);
        return (float) Math.hypot(dx, dy);
    }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX(), y = event.getY();
                float toSquare = distance(square, x, y), toBar = distance(bar, x, y);
                if (Math.min(toSquare, toBar) > reach) return false;
                dragging = toSquare <= toBar ? SQUARE : BAR;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                // Another thumb than the one that was still shrinking: that one is at its size at once.
                if (scaled != dragging) thumbScale = 1f;
                scaled = dragging;
                growThumb(true);
                move(x, y);
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging != NONE) move(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging != NONE) {
                    growThumb(false);
                    dragging = NONE;
                    invalidate();
                    performClick();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void move(float x, float y) {
        if (dragging == SQUARE) {
            hsv[1] = range.saturationAt((x - square.left) / square.width());
            hsv[2] = range.valueAt((y - square.top) / square.height());
        } else {
            hsv[0] = clamp((x - bar.left) / bar.width()) * 360f;
        }
        color = Color.HSVToColor(hsv);
        invalidate();
        notifyListener(true);
    }

    /** The thumb under the finger grows a little, so it can be seen around the finger. */
    private void growThumb(boolean grow) {
        if (thumbAnimation != null) thumbAnimation.cancel();
        thumbAnimation = ValueAnimator.ofFloat(thumbScale, grow ? 1.3f : 1f);
        thumbAnimation.setDuration(140);
        thumbAnimation.setInterpolator(new DecelerateInterpolator());
        thumbAnimation.addUpdateListener(a -> {
            thumbScale = (float) a.getAnimatedValue();
            invalidate();
        });
        thumbAnimation.start();
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (thumbAnimation != null) thumbAnimation.cancel();
        super.onDetachedFromWindow();
    }
}
