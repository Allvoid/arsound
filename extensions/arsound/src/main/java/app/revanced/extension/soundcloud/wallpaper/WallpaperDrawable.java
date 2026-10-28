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

        int shown = Math.round(alpha * visible);
        picture.setAlpha(shown);
        canvas.save();
        canvas.clipRect(bounds);
        float width = bounds.width(), height = bounds.height();
        float scale = Math.max(width / bitmap.getWidth(), height / bitmap.getHeight());
        canvas.translate(bounds.left + (width - bitmap.getWidth() * scale) / 2f,
                bounds.top + (height - bitmap.getHeight() * scale) / 2f);
        canvas.scale(scale, scale);
        canvas.drawBitmap(bitmap, 0, 0, picture);
        canvas.restore();

        // The dim: the surface colour over the picture.
        colour.setColor(surface);
        colour.setAlpha(Math.round(shown * WallpaperStore.dim() / 100f));
        canvas.drawRect(bounds, colour);
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
