package app.revanced.extension.soundcloud.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * A rounded bar cut into coloured parts in proportion to their sizes: how the space is shared between the downloads and
 * the caches. Used by {@link StorageUsage}; the sizes arrive when they are counted.
 */
final class UsageBar extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final int trackColor;
    private long[] sizes = new long[0];
    private int[] colors = new int[0];

    UsageBar(Context context, int trackColor) {
        super(context);
        this.trackColor = trackColor;
    }

    /** Sets the parts; a part of size 0 is not drawn. */
    void setParts(long[] sizes, int[] colors) {
        this.sizes = sizes;
        this.colors = colors;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float width = getWidth();
        float height = getHeight();
        float radius = height / 2f;
        clip.reset();
        rect.set(0, 0, width, height);
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        paint.setColor(trackColor);
        canvas.drawRect(rect, paint);

        long total = 0;
        for (long size : sizes) total += size;
        float x = 0;
        for (int i = 0; total > 0 && i < sizes.length; i++) {
            if (sizes[i] <= 0) continue;
            // A part that exists is at least a few pixels wide, so it can be seen.
            float part = Math.max(height * 0.6f, width * sizes[i] / total);
            paint.setColor(colors[i]);
            canvas.drawRect(x, 0, Math.min(width, x + part), height, paint);
            x += part;
        }
        canvas.restore();
    }
}
