package app.revanced.extension.soundcloud.settings;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** A small picture of an equalizer curve for the preset list: a smooth line with a faint fill on a rounded tile. */
final class CurveThumbnail extends Drawable {
    private final short[] levels;
    private final short min, max;
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float unit;
    private final Path path = new Path();
    private final Path clip = new Path();
    private final RectF rect = new RectF();

    CurveThumbnail(short[] levels, short min, short max, int accent, int tileColor, float unit) {
        this.levels = levels.clone();
        this.min = min;
        this.max = max;
        this.unit = unit;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * unit);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        line.setColor(accent);
        fill.setColor((accent & 0x00FFFFFF) | 0x33000000);
        tile.setColor(tileColor);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        rect.set(b);
        canvas.drawRoundRect(rect, 10 * unit, 10 * unit, tile);
        int n = levels.length;
        if (n == 0) return;
        float pad = 6 * unit;
        float left = b.left + pad, right = b.right - pad, top = b.top + pad, bottom = b.bottom - pad;
        float[] x = new float[n], y = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = n == 1 ? (left + right) / 2 : left + (right - left) * i / (n - 1);
            float t = Math.max(0, Math.min(1, (levels[i] - min) / (float) (max - min)));
            y[i] = bottom - (bottom - top) * t;
        }
        path.reset();
        path.moveTo(x[0], y[0]);
        for (int i = 0; i < n - 1; i++) {
            float mid = (x[i] + x[i + 1]) / 2;
            path.cubicTo(mid, y[i], mid, y[i + 1], x[i + 1], y[i + 1]);
        }
        canvas.save();
        clip.reset();
        clip.addRoundRect(rect, 10 * unit, 10 * unit, Path.Direction.CW);
        canvas.clipPath(clip);
        android.graphics.Path area = new android.graphics.Path(path);
        area.lineTo(x[n - 1], b.bottom);
        area.lineTo(x[0], b.bottom);
        area.close();
        canvas.drawPath(area, fill);
        canvas.drawPath(path, line);
        canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        line.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
