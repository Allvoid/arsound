package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The wallpaper as a background: the picture scaled to cover, the theme's surface colour laid over it by the dim
 * setting so the text stays readable, and under it the surface colour itself, which is all that shows until the
 * picture is read (then it fades in).
 * <p>
 * With a height it is a band at the top instead of the whole area: the picture fades into the surface at the bottom
 * of the band, which is how the home screen shows it. The surface colour comes from the chosen theme, so it fits every
 * theme, the custom one and Monet included.
 */
public final class WallpaperDrawable extends Drawable {
    private static final long FADE_IN_MS = 260;
    /** Where in the band the fade into the surface starts, of its height. */
    private static final float FADE_FROM = .45f;

    private final int surface;
    private int bandHeight;
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint colour = new Paint();
    private final Paint fade = new Paint();
    private int alpha = 255;
    /** When the picture was first drawn after being waited for, or 0 when it was there from the start. */
    private long fadeStart;
    private boolean waited;
    private float shaderFrom = -1, shaderTo = -1;

    /** @param bandHeight Height of the band in pixels, or 0 for the whole area. */
    WallpaperDrawable(Context context, int bandHeight) {
        this.surface = ArsoundTheme.palette(context, "surface") | 0xFF000000;
        this.bandHeight = bandHeight;
        WallpaperStore.watch(this);
    }

    /** Changes the band's height (the home screen knows it only after its layout). */
    public void setBandHeight(int height) {
        if (height == bandHeight) return;
        bandHeight = height;
        invalidateSelf();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        colour.setColor(surface);
        colour.setAlpha(alpha);
        canvas.drawRect(bounds, colour);

        Bitmap bitmap = WallpaperStore.bitmap();
        if (bitmap == null) {
            waited = true;
            return;
        }
        if (waited && fadeStart == 0) fadeStart = SystemClock.uptimeMillis();
        float visible = 1f;
        if (fadeStart != 0) {
            visible = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_IN_MS);
            if (visible < 1f) invalidateSelf();
        }

        int bottom = bandHeight > 0 ? Math.min(bounds.bottom, bounds.top + bandHeight) : bounds.bottom;
        int shown = Math.round(alpha * visible);
        picture.setAlpha(shown);
        canvas.save();
        canvas.clipRect(bounds.left, bounds.top, bounds.right, bottom);
        float width = bounds.width(), height = bottom - bounds.top;
        float scale = Math.max(width / bitmap.getWidth(), height / bitmap.getHeight());
        // A band shows the upper part of the picture, where the subject usually is; the whole area shows the middle.
        float focus = bandHeight > 0 ? .3f : .5f;
        canvas.translate(bounds.left + (width - bitmap.getWidth() * scale) / 2f,
                bounds.top + (height - bitmap.getHeight() * scale) * focus);
        canvas.scale(scale, scale);
        canvas.drawBitmap(bitmap, 0, 0, picture);
        canvas.restore();

        // The dim: the surface colour over the picture.
        colour.setColor(surface);
        colour.setAlpha(Math.round(shown * WallpaperStore.dim() / 100f));
        canvas.drawRect(bounds.left, bounds.top, bounds.right, bottom, colour);

        if (bandHeight > 0) {
            float from = bounds.top + (bottom - bounds.top) * FADE_FROM;
            if (from != shaderFrom || bottom != shaderTo) {
                fade.setShader(new LinearGradient(0, from, 0, bottom, surface & 0x00FFFFFF, surface, Shader.TileMode.CLAMP));
                shaderFrom = from;
                shaderTo = bottom;
            }
            fade.setAlpha(shown);
            canvas.drawRect(bounds.left, from, bounds.right, bottom, fade);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        picture.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }
}
