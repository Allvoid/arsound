package app.revanced.extension.soundcloud.settings;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/**
 * The main mark of Arsound (A9: the katakana ア as two pairs of parallel lines) drawn in the colours it is given, so
 * the logo of the settings can follow the tile a theme puts behind it ({@link LogoColors}). The geometry is that of
 * {@code glyph-white.svg} of the logo project (letter-a-icon, export/svg): four lines on a 512 grid, 20 thick in the
 * file's own scale of 1.57901, round at the ends. The letter lines are the white ones of the picture, the accent lines
 * the azure ones. Pairs of numbers, x then y, in the file's own coordinates.
 */
final class LogoMark extends Drawable {
    private static final float[] LETTER_TOP = {
            118f, 137f, 371.18f, 137.01f, 373.18f, 137.08f, 375.23f, 137.23f, 377.32f, 137.46f, 378.37f, 137.61f,
            380.52f, 137.98f, 381.61f, 138.21f, 383.82f, 138.77f, 384.94f, 139.09f, 387.2f, 139.87f,
            388.35f, 140.32f, 389.5f, 140.81f, 390.65f, 141.36f, 391.8f, 141.96f, 392.96f, 142.61f,
            394.11f, 143.32f, 395.25f, 144.1f, 396.38f, 144.94f, 397.49f, 145.84f, 398.58f, 146.81f,
            399.63f, 147.85f, 400.65f, 148.95f, 401.62f, 150.12f, 402.54f, 151.35f, 403.39f, 152.63f,
            404.19f, 153.96f, 404.91f, 155.34f, 405.55f, 156.75f, 406.12f, 158.19f, 406.61f, 159.64f,
            407.02f, 161.11f, 407.34f, 162.58f, 407.6f, 164.04f, 407.78f, 165.49f, 407.88f, 166.92f,
            407.93f, 168.33f, 407.91f, 169.72f, 407.84f, 171.08f, 407.71f, 172.41f, 407.54f, 173.71f,
            407.32f, 174.98f, 407.07f, 176.22f, 406.45f, 178.6f, 406.1f, 179.75f, 405.31f, 181.96f,
            404.88f, 183.02f, 403.95f, 185.07f, 403.47f, 186.06f, 402.44f, 187.96f, 401.91f, 188.88f,
            400.81f, 190.66f, 399.07f, 193.18f, 397.25f, 195.55f, 395.31f, 197.86f, 316.99f, 282.21f
    };

    private static final float[] ACCENT_TOP = {
            118f, 167f, 370.06f, 167f, 372.07f, 167.07f, 373f, 167.15f, 374.28f, 167.33f, 375.42f, 167.57f,
            376.66f, 167.95f, 377.52f, 168.34f, 378.08f, 168.71f, 377.92f, 168.4f, 377.93f, 168.72f,
            377.88f, 169.21f, 377.73f, 169.91f, 377.57f, 170.46f, 377.07f, 171.75f, 376.54f, 172.82f,
            375.88f, 173.96f, 375.1f, 175.13f, 374.21f, 176.33f, 372.94f, 177.87f, 295.01f, 261.79f
    };

    private static final float[] LETTER_STEM = {
            259f, 213.68f, 259.07f, 218.35f, 259.09f, 223.03f, 259.04f, 227.73f, 258.94f, 232.43f,
            258.76f, 237.14f, 258.51f, 241.86f, 258.19f, 246.58f, 257.87f, 250.51f, 257.4f, 255.24f,
            256.95f, 259.18f, 256.44f, 263.12f, 255.87f, 267.06f, 255.11f, 271.78f, 254.4f, 275.7f,
            253.62f, 279.63f, 252.78f, 283.54f, 251.86f, 287.44f, 250.88f, 291.34f, 249.82f, 295.22f,
            248.69f, 299.08f, 247.49f, 302.93f, 246.21f, 306.75f, 244.86f, 310.55f, 243.73f, 313.58f,
            242.24f, 317.34f, 240.68f, 321.07f, 239.04f, 324.77f, 237.68f, 327.7f, 235.92f, 331.34f,
            234.08f, 334.94f, 232.17f, 338.51f, 230.19f, 342.03f, 228.56f, 344.82f, 226.45f, 348.27f,
            224.29f, 351.67f, 222.06f, 355.03f, 219.77f, 358.34f, 217.43f, 361.61f, 215.02f, 364.83f,
            212.56f, 368f, 210.05f, 371.13f, 207.49f, 374.2f, 204.89f, 377.23f, 201.7f, 380.8f, 198.99f, 383.72f,
            196.24f, 386.6f, 193.45f, 389.43f, 190.06f, 392.76f, 187.2f, 395.49f, 183.71f, 398.71f,
            180.77f, 401.34f, 177.21f, 404.44f, 173.61f, 407.49f, 169.96f, 410.47f, 166.29f, 413.4f,
            162.57f, 416.28f, 158.83f, 419.1f, 155.06f, 421.87f, 151.26f, 424.59f, 146.79f, 427.7f,
            142.94f, 430.32f, 138.42f, 433.32f, 134.51f, 435.84f, 129.95f, 438.72f
    };

    private static final float[] ACCENT_STEM = {
            229f, 214.32f, 229.07f, 218.64f, 229.09f, 222.95f, 229.05f, 227.24f, 228.95f, 231.52f, 228.79f, 235.8f,
            228.61f, 239.35f, 228.33f, 243.6f, 228.05f, 247.13f, 227.65f, 251.35f, 227.26f, 254.86f,
            226.82f, 258.35f, 226.32f, 261.83f, 225.66f, 265.99f, 225.05f, 269.44f, 224.38f, 272.87f,
            223.66f, 276.28f, 222.88f, 279.67f, 222.04f, 283.05f, 221.14f, 286.4f, 220.18f, 289.73f,
            219.16f, 293.04f, 218.07f, 296.33f, 216.93f, 299.59f, 215.72f, 302.82f, 214.45f, 306.03f,
            213.12f, 309.21f, 211.73f, 312.36f, 210.27f, 315.49f, 208.75f, 318.58f, 207.17f, 321.64f,
            205.53f, 324.68f, 203.83f, 327.68f, 202.07f, 330.65f, 200.25f, 333.58f, 198.37f, 336.49f,
            196.44f, 339.36f, 194.45f, 342.2f, 192.4f, 345f, 190.3f, 347.77f, 188.15f, 350.5f, 185.95f, 353.21f,
            183.7f, 355.88f, 181.39f, 358.51f, 178.57f, 361.63f, 176.17f, 364.19f, 173.73f, 366.71f,
            171.24f, 369.21f, 168.2f, 372.16f, 165.63f, 374.58f, 162.49f, 377.45f, 159.83f, 379.81f,
            156.6f, 382.59f, 153.87f, 384.88f, 150.55f, 387.58f, 147.19f, 390.25f, 143.79f, 392.87f,
            140.35f, 395.46f, 136.87f, 398f, 133.35f, 400.51f, 129.8f, 402.98f, 126.22f, 405.41f, 122f, 408.2f,
            118.35f, 410.56f, 114.05f, 413.28f
    };

    /** The transform of the group in the SVG: x and y are moved by these before the scale. */
    private static final float SHIFT_X = -156.105f;
    private static final float SHIFT_Y = -198.534f;
    private static final float SCALE = 1.57901f;
    private static final float STROKE = 20f * SCALE;
    private static final float GRID = 512f;

    private final Path letterLines = path(LETTER_TOP, LETTER_STEM);
    private final Path accentLines = path(ACCENT_TOP, ACCENT_STEM);
    private final Paint letterPaint = linePaint();
    private final Paint accentPaint = linePaint();

    LogoMark(LogoColors colors) {
        letterPaint.setColor(colors.letter);
        accentPaint.setColor(colors.accent);
    }

    private static Path path(float[]... lines) {
        Path path = new Path();
        for (float[] line : lines) {
            for (int i = 0; i + 1 < line.length; i += 2) {
                float x = line[i] * SCALE + SHIFT_X;
                float y = line[i + 1] * SCALE + SHIFT_Y;
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
        }
        return path;
    }

    private static Paint linePaint() {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        return paint;
    }

    @Override
    public void draw(Canvas canvas) {
        float scale = Math.min(getBounds().width(), getBounds().height()) / GRID;
        int save = canvas.save();
        canvas.translate(getBounds().exactCenterX() - GRID * scale / 2, getBounds().exactCenterY() - GRID * scale / 2);
        canvas.scale(scale, scale);
        canvas.drawPath(letterLines, letterPaint);
        canvas.drawPath(accentLines, accentPaint);
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
