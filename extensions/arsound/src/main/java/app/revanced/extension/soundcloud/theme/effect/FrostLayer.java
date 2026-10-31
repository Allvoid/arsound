package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.theme.effect.EffectBrush.withAlpha;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.progress;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.pulse;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * «Зима»: the ice along the top edge, as if the screen froze a little at the top: an uneven crust of frost, a thin rim
 * on its lower edge, frost ferns growing from it (longer in the corners, where frost starts on a window) and a
 * few short icicles. All of it faint, so it never competes with the content under it. It is drawn once per width,
 * off the main thread (a bitmap as wide as the screen is a frame's work on a slow phone), and then only copied, with a
 * few points of the ice that now and then glint. Until the ice of a new width is ready the old one stays.
 */
final class FrostLayer {
    /** The ice of one width: the picture and the tips where it glints (x, y, the length of the loop, its delay). */
    private static final class Ice {
        final Bitmap bitmap;
        final int width;
        final List<float[]> tips;

        Ice(Bitmap bitmap, int width, List<float[]> tips) {
            this.bitmap = bitmap;
            this.width = width;
            this.tips = tips;
        }
    }

    private final EffectBrush brush;
    /** The ice on screen; main thread only. */
    private Ice ice;
    /** The width and height of the ice being drawn now, 0 when none is; main thread only. */
    private int buildingWidth, buildingHeight;
    /** Counts the builds, so a slower one that was overtaken is thrown away. Main thread only. */
    private int builds;

    FrostLayer(EffectBrush brush) {
        this.brush = brush;
    }

    /** Draws the ice again if the width changed; a shorter height only clips the bitmap that is there. Main thread. */
    void build(int w, int h) {
        float dp = brush.dp;
        final int height = Math.round(Math.min(h * .26f, 190 * dp));
        // The ice depends on the width only: bounds that got shorter (the keyboard opened) just clip the same
        // bitmap, so it is not drawn again with every change of the height.
        if (ice != null && ice.width == w && ice.bitmap.getHeight() >= height && height >= 4) return;
        if (w <= 0 || height < 4) {
            builds++;
            buildingWidth = buildingHeight = 0;
            ice = null;
            return;
        }
        if (buildingWidth == w && buildingHeight >= height) return;
        final int build = ++builds;
        final int width = w;
        buildingWidth = w;
        buildingHeight = height;
        Utils.runOnBackgroundThread(() -> {
            try {
                final Ice made = make(width, height);
                Utils.runOnMainThread(() -> {
                    if (build != builds) return;
                    buildingWidth = buildingHeight = 0;
                    ice = made;
                    brush.redraw();
                });
            } catch (Throwable ex) {
                Logger.printException(() -> "Theme effect: could not draw the ice", ex);
            }
        });
    }

    /** Draws the ice of one width. Any thread: it touches nothing but its own bitmap and the brush's colours. */
    private Ice make(int w, int height) {
        float dp = brush.dp;
        List<float[]> tips = new ArrayList<>();
        Bitmap bitmap = Bitmap.createBitmap(w, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Random random = new Random(11);
        int ice = brush.tint(0xFFDDF3FF);

        // The crust: a wavy lower edge, deeper towards both corners.
        float base = 12 * dp, phase1 = random.nextFloat() * 6.3f, phase2 = random.nextFloat() * 6.3f;
        int steps = Math.max(8, Math.round(w / (3 * dp)));
        float[] edge = new float[steps + 1];
        Path crust = new Path(), rim = new Path();
        crust.moveTo(0, 0);
        for (int i = 0; i <= steps; i++) {
            float x = w * i / (float) steps;
            float corner = Math.max(0, 1 - Math.min(x, w - x) / (w * .22f));
            float y = base + 5 * dp * (float) Math.sin(x / (41 * dp) + phase1)
                    + 3 * dp * (float) Math.sin(x / (13 * dp) + phase2) + 30 * dp * corner * corner;
            edge[i] = y;
            crust.lineTo(x, y);
            if (i == 0) rim.moveTo(x, y);
            else rim.lineTo(x, y);
        }
        crust.lineTo(w, 0);
        crust.close();
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setShader(new LinearGradient(0, 0, 0, base + 40 * dp,
                withAlpha(ice, .11f), withAlpha(ice, .035f), Shader.TileMode.CLAMP));
        canvas.drawPath(crust, fill);
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeWidth(Math.max(1, .8f * dp));
        line.setColor(withAlpha(ice, .17f));
        canvas.drawPath(rim, line);

        // Ferns from the rim, every 20-40dp, and bigger ones from the corners.
        line.setStrokeWidth(Math.max(1, .7f * dp));
        float x = random.nextFloat() * 20 * dp;
        while (x < w) {
            int i = Math.min(steps, Math.round(x / w * steps));
            float corner = Math.max(0, 1 - Math.min(x, w - x) / (w * .22f));
            float length = (7 + random.nextFloat() * 12) * dp * (1 + 1.1f * corner);
            float angle = 90 + (random.nextFloat() * 2 - 1) * 28;
            fern(canvas, line, ice, x, edge[i] - 2 * dp, angle, length, 3, .2f, random, tips);
            x += (20 + random.nextFloat() * 20) * dp;
        }
        for (int i = 0; i < 3; i++) {
            fern(canvas, line, ice, 0, i * 12 * dp, 32 + i * 18 + random.nextFloat() * 8, (34 + random.nextFloat() * 22) * dp, 3, .17f, random, tips);
            fern(canvas, line, ice, w, i * 12 * dp, 148 - i * 18 - random.nextFloat() * 8, (34 + random.nextFloat() * 22) * dp, 3, .17f, random, tips);
        }

        // A few short icicles hanging from the rim.
        int icicles = 5 + random.nextInt(4);
        for (int n = 0; n < icicles; n++) {
            float at = (.06f + random.nextFloat() * .88f) * w;
            float top = edge[Math.min(steps, Math.round(at / w * steps))] - 1 * dp;
            float length = (5 + random.nextFloat() * 11) * dp, half = (1.2f + random.nextFloat() * 1.6f) * dp;
            Path icicle = new Path();
            icicle.moveTo(at - half, top);
            icicle.lineTo(at + half, top);
            icicle.lineTo(at + half * .15f, top + length);
            icicle.close();
            fill.setShader(new LinearGradient(0, top, 0, top + length, withAlpha(ice, .16f), withAlpha(ice, 0), Shader.TileMode.CLAMP));
            canvas.drawPath(icicle, fill);
        }

        // Glints wait at random tips, each on its own slow beat.
        Collections.shuffle(tips, random);
        while (tips.size() > 6) tips.remove(tips.size() - 1);
        for (float[] tip : tips) {
            tip[2] = 5 + random.nextFloat() * 6;
            tip[3] = -random.nextFloat() * tip[2];
        }
        return new Ice(bitmap, w, tips);
    }

    /** The ice, and the points of it that catch the light at this time. Nothing before the first ice is ready. */
    void draw(Canvas canvas, float time) {
        Ice ice = this.ice;
        if (ice == null) return;
        Bitmap frost = ice.bitmap;
        List<float[]> tips = ice.tips;
        float dp = brush.dp;
        Paint paint = brush.paint;
        paint.setShader(null);
        paint.setAlpha(brush.getAlpha());
        canvas.drawBitmap(frost, 0, 0, paint);
        paint.setAlpha(255);
        // Now and then a point of the ice catches the light: short and faint.
        for (int i = 0; i < tips.size(); i++) {
            float[] tip = tips.get(i);
            float t = progress(time, tip[2], tip[3]);
            if (t < 0 || t > .14f) continue;
            float f = pulse(t / .14f);
            brush.halo(canvas, tip[0], tip[1], 3.2f * dp, brush.tint(0xFFE6F6FF), .45f * f);
            brush.circle(canvas, tip[0], tip[1], .9f * dp, brush.tint(0xFFF4FBFF), .7f * f);
        }
    }

    /** A frost fern: a stroke with side twigs at a third and two thirds, each a smaller fern, and a shorter tip. */
    private void fern(Canvas canvas, Paint line, int color, float x, float y, float angle, float length, int depth,
                      float opacity, Random random, List<float[]> tips) {
        double radians = Math.toRadians(angle);
        float ex = x + (float) Math.cos(radians) * length, ey = y + (float) Math.sin(radians) * length;
        line.setColor(withAlpha(color, opacity));
        canvas.drawLine(x, y, ex, ey, line);
        if (depth == 0) {
            if (random.nextFloat() < .25f) tips.add(new float[]{ex, ey, 0, 0});
            return;
        }
        for (float at : new float[]{.35f, .65f}) {
            float px = x + (ex - x) * at, py = y + (ey - y) * at;
            float side = 50 + random.nextFloat() * 18;
            fern(canvas, line, color, px, py, angle - side, length * .36f, depth - 1, opacity * .75f, random, tips);
            fern(canvas, line, color, px, py, angle + side, length * .36f, depth - 1, opacity * .75f, random, tips);
        }
        fern(canvas, line, color, ex, ey, angle + (random.nextFloat() * 2 - 1) * 12, length * .5f, depth - 1, opacity * .85f, random, tips);
    }
}
