package app.revanced.extension.soundcloud.settings;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * The mark of Arsound (the katakana ア of two thick lines) drawn in the colours it is given, so the logo of the
 * settings can follow the tile a theme puts behind it ({@link LogoColors}). The lines are those of the app icon
 * "A9 minimalism", on its 512 grid: a letter line and a shorter accent line, round at the ends, 32 thick.
 */
final class LogoMark extends Drawable {
    /** The width of the mark on the 512 grid is 256 (x 144 to 368) and its height 216; the lines are 32 thick. */
    private static final float GRID_SIZE = 330f;
    private static final float CENTER = 256f;

    private final Path letterLine = new Path();
    private final Path accentLine = new Path();
    private final Paint letterPaint = linePaint();
    private final Paint accentPaint = linePaint();

    LogoMark(LogoColors colors) {
        letterPaint.setColor(colors.letter);
        accentPaint.setColor(colors.accent);
        letterLine.moveTo(144, 164);
        letterLine.lineTo(368, 164);
        letterLine.lineTo(232, 348);
        accentLine.moveTo(246.6f, 220);
        accentLine.lineTo(152, 348);
    }

    private static Paint linePaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(32);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        return paint;
    }

    @Override
    public void draw(Canvas canvas) {
        float scale = Math.min(getBounds().width(), getBounds().height()) / GRID_SIZE;
        int save = canvas.save();
        canvas.translate(getBounds().exactCenterX(), getBounds().exactCenterY());
        canvas.scale(scale, scale);
        // The lines with their thickness span x 128 to 384 and y 148 to 364: their middle is the middle of the grid.
        canvas.translate(-CENTER, -CENTER);
        canvas.drawPath(letterLine, letterPaint);
        canvas.drawPath(accentLine, accentPaint);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        letterPaint.setAlpha(alpha);
        accentPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        letterPaint.setColorFilter(colorFilter);
        accentPaint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
