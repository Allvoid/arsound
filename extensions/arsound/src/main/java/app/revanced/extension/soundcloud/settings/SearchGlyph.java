package app.revanced.extension.soundcloud.settings;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** The magnifier and the cross of the settings search, drawn as lines in one colour so they follow the theme. */
final class SearchGlyph extends Drawable {
    static final int MAGNIFIER = 0;
    static final int CROSS = 1;

    private final int kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    SearchGlyph(int kind, int color) {
        this.kind = kind;
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        float left = bounds.left + (bounds.width() - size) / 2f;
        float top = bounds.top + (bounds.height() - size) / 2f;
        paint.setStrokeWidth(size * 0.085f);
        if (kind == MAGNIFIER) {
            float radius = size * 0.26f;
            float cx = left + size * 0.45f;
            float cy = top + size * 0.45f;
            canvas.drawCircle(cx, cy, radius, paint);
            float handle = radius * 0.7071f;
            canvas.drawLine(cx + handle + size * 0.02f, cy + handle + size * 0.02f,
                    left + size * 0.78f, top + size * 0.78f, paint);
        } else {
            float from = size * 0.3f;
            float to = size * 0.7f;
            canvas.drawLine(left + from, top + from, left + to, top + to, paint);
            canvas.drawLine(left + to, top + from, left + from, top + to, paint);
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
