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
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * A colour picker: a square for saturation (across) and brightness (down) of the hue below it, and a hue bar. The
 * thumbs are round, in the colour they pick, with a white ring and a soft shadow, and grow a little under the finger.
 * <p>
 * Holds the colour as hue, saturation and value, so dragging through a grey keeps the hue instead of jumping to red.
 */
public final class ColorPickerView extends View {
    public interface Listener {
        /** @param fromUser False when the colour was set from code, for example from the hex field. */
        void onColorChanged(int color, boolean fromUser);
    }

    private static final int[] HUES = {0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000};
    private static final int NONE = 0, SQUARE = 1, BAR = 2;

    private final float[] hsv = {210f, .6f, .9f};
    /** The colour as it was set or last dragged to: converting to hue, saturation and value and back can shift a channel. */
    private int color = Color.HSVToColor(hsv);
    private final RectF square = new RectF();
    private final RectF bar = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float corner;
    private final float thumbRadius;
    private final float barHeight;
    private final float gap;

    private Shader squareShader;
    private float squareShaderHue = -1;
    private Shader barShader;
    private float thumbScale = 1f;
    private ValueAnimator thumbAnimation;
    private int dragging = NONE;
    /** The thumb that is grown or still shrinking back: the finger has left it by then, so {@link #dragging} cannot tell. */
    private int scaled = NONE;
    private Listener listener;

    public ColorPickerView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        corner = dp(14);
        thumbRadius = dp(13);
        barHeight = dp(26);
        gap = dp(18);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Math.max(1f, dp(1)));
        edge.setColor(0x22808080);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
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
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private void notifyListener(boolean fromUser) {
        if (listener != null) listener.onColorChanged(getColor(), fromUser);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? Math.round(dp(280)) : MeasureSpec.getSize(widthSpec);
        float squareHeight = Math.min(dp(190), (width - 2 * thumbRadius) * .62f);
        int height = Math.round(thumbRadius + squareHeight + gap + barHeight + thumbRadius);
        setMeasuredDimension(width, resolveSize(height, heightSpec));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float squareHeight = Math.min(dp(190), (width - 2 * thumbRadius) * .62f);
        square.set(thumbRadius, thumbRadius, width - thumbRadius, thumbRadius + squareHeight);
        bar.set(thumbRadius, square.bottom + gap, width - thumbRadius, square.bottom + gap + barHeight);
        barShader = new LinearGradient(bar.left, 0, bar.right, 0, HUES, null, Shader.TileMode.CLAMP);
        squareShaderHue = -1;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (square.isEmpty()) return;
        // The square: white to the hue across, multiplied by white to black down.
        if (squareShaderHue != hsv[0]) {
            int hue = Color.HSVToColor(new float[]{hsv[0], 1f, 1f});
            squareShader = new ComposeShader(
                    new LinearGradient(square.left, 0, square.right, 0, Color.WHITE, hue, Shader.TileMode.CLAMP),
                    new LinearGradient(0, square.top, 0, square.bottom, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP),
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

        drawThumb(canvas, square.left + hsv[1] * square.width(), square.top + (1 - hsv[2]) * square.height(), getColor(),
                scaled == SQUARE);
        drawThumb(canvas, bar.left + hsv[0] / 360f * bar.width(), bar.centerY(),
                Color.HSVToColor(new float[]{hsv[0], 1f, 1f}), scaled == BAR);
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

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = event.getX(), y = event.getY();
                float reach = thumbRadius;
                if (x >= square.left - reach && x <= square.right + reach && y >= square.top - reach && y <= square.bottom + reach / 2) {
                    dragging = SQUARE;
                } else if (x >= bar.left - reach && x <= bar.right + reach && y >= bar.top - reach && y <= bar.bottom + reach) {
                    dragging = BAR;
                } else {
                    return false;
                }
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
            hsv[1] = clamp((x - square.left) / square.width());
            hsv[2] = 1f - clamp((y - square.top) / square.height());
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
