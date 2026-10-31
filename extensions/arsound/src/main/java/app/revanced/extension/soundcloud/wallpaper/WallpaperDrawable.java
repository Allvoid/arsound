package app.revanced.extension.soundcloud.wallpaper;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import app.revanced.extension.soundcloud.theme.ArsoundTheme;

/**
 * The wallpaper as a background: the picture scaled to cover, the theme's surface colour laid over it by the dim
 * setting so the text stays readable, and under it the surface colour itself, which is all that shows until the
 * picture is read (then it fades in). The surface colour comes from the chosen theme, so it fits every theme, the
 * custom one and Monet included.
 * <p>
 * It is the background of whole screens: Arsound's own, and the window of the main screen, which SoundCloud's screens
 * show through (see {@link WallpaperSurfaces}).
 */
public final class WallpaperDrawable extends Drawable {
    private static final long FADE_IN_MS = 260;

    private final int surface;
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint colour = new Paint();
    /** The dim the picture's filter was made for; -1 before the first one. */
    private int filterDim = -1;
    private int alpha = 255;
    /** When the picture was first drawn after being waited for, or 0 when it was there from the start. */
    private long fadeStart;
    private boolean waited;

    WallpaperDrawable(Context context) {
        this.surface = ArsoundTheme.palette(context, "surface") | 0xFF000000;
        WallpaperStore.watch(this);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        Bitmap bitmap = WallpaperStore.bitmap();
        float visible = 1f;
        if (bitmap != null) {
            if (waited && fadeStart == 0) fadeStart = SystemClock.uptimeMillis();
            if (fadeStart != 0) {
                visible = Math.min(1f, (SystemClock.uptimeMillis() - fadeStart) / (float) FADE_IN_MS);
                if (visible < 1f) invalidateSelf();
            }
        }
        int shown = bitmap == null ? 0 : Math.round(alpha * visible);

        // The surface under the picture only while the picture is not read yet, fades in or lets light through: an
        // opaque picture shown in full covers the whole bounds, and the fill under it was painted on every frame.
        if (shown < 255 || bitmap.hasAlpha()) {
            colour.setColor(surface);
            colour.setAlpha(alpha);
            canvas.drawRect(bounds, colour);
        }
        if (bitmap == null) {
            waited = true;
            return;
        }
        picture.setAlpha(shown);
        canvas.save();
        canvas.clipRect(bounds);
        float width = bounds.width(), height = bounds.height();
        float scale = Math.max(width / bitmap.getWidth(), height / bitmap.getHeight());
        canvas.translate(bounds.left + (width - bitmap.getWidth() * scale) / 2f,
                bounds.top + (height - bitmap.getHeight() * scale) / 2f);
        canvas.scale(scale, scale);
        // The dim is mixed into the picture by its filter: a second pass over the whole window with the surface colour
        // was drawn on every frame.
        dimFilter(WallpaperStore.dim());
        canvas.drawBitmap(bitmap, 0, 0, picture);
        canvas.restore();
    }

    /**
     * Sets the picture's filter to the picture mixed with the surface colour by the dim: each channel becomes
     * {@code channel * (1 - dim) + surface * dim}, as the surface colour laid over it with that alpha. Made again
     * only when the dim changes.
     */
    private void dimFilter(int dim) {
        if (dim == filterDim) return;
        filterDim = dim;
        if (dim == 0) {
            picture.setColorFilter(null);
            return;
        }
        float part = dim / 100f;
        float keep = 1f - part;
        android.graphics.ColorMatrix matrix = new android.graphics.ColorMatrix(new float[]{
                keep, 0, 0, 0, ((surface >> 16) & 0xFF) * part,
                0, keep, 0, 0, ((surface >> 8) & 0xFF) * part,
                0, 0, keep, 0, (surface & 0xFF) * part,
                0, 0, 0, 1, 0});
        picture.setColorFilter(new android.graphics.ColorMatrixColorFilter(matrix));
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
