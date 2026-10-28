package app.revanced.extension.soundcloud.theme.effect;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * What the scenes of one effect draw with: the two paints, the scale of the screen, the colours of the look, and the
 * fade the drawable was given. One brush per effect, so a scene may leave a paint in any state for its own next frame.
 */
public final class EffectBrush {
    /** The design draws its effects on a card this tall, in dp; travel distances are scaled to the real height. */
    private static final float DESIGN_HEIGHT = 600f;

    /** Pixels per design dp, with the size scale of a preview tile. */
    final float dp;
    private final boolean night;
    private final int accent;
    /** Solid shapes, lines and bitmaps. */
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Shapes filled with a gradient. */
    final Paint shaded = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int alpha = 255;

    /**
     * @param dp     Pixels per design dp.
     * @param night  The dark look: the design's colours. The light look draws everything in the accent.
     * @param accent The theme's accent in the look drawn.
     */
    public EffectBrush(float dp, boolean night, int accent) {
        this.dp = dp;
        this.night = night;
        this.accent = accent;
    }

    /** The fade of the whole effect, 0..255 as {@code Drawable.setAlpha}. */
    public void setAlpha(int alpha) {
        this.alpha = alpha;
    }

    public int getAlpha() {
        return alpha;
    }

    /** The accent the effect is drawn in. */
    int accent() {
        return accent;
    }

    boolean isNight() {
        return night;
    }

    /** The design colour in the dark look, the accent in the light one (pale sparks would vanish on white). */
    int tint(int designColor) {
        return night ? designColor : (accent & 0x00FFFFFF) | (designColor & 0xFF000000);
    }

    /** The colour with its alpha scaled by {@code alpha}, 0..1. */
    static int withAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * Math.max(0, Math.min(1, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** A distance of the design's 600 dp card as it is on this height. */
    static float travel(float designDistance, float height) {
        return designDistance / DESIGN_HEIGHT * height;
    }

    /** The paint alpha (0..255) of something drawn at this opacity under the fade of the whole effect. */
    int paintAlpha(float opacity) {
        return Math.round(255 * opacity * alpha / 255f);
    }

    void circle(Canvas canvas, float x, float y, float radius, int color, float opacity) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, opacity * alpha / 255f));
        canvas.drawCircle(x, y, radius, paint);
    }

    /** A soft halo like the design's box-shadow: two faint rings under the dot. */
    void halo(Canvas canvas, float x, float y, float radius, int color, float opacity) {
        circle(canvas, x, y, radius, color, opacity * .18f);
        circle(canvas, x, y, radius * .62f, color, opacity * .3f);
    }
}
