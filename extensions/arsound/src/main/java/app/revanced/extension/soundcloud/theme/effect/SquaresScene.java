package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.theme.effect.Keyframes.progress;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;

/** «Лайм»: a scanner line going down the screen, and outlined squares turning as they rise. */
final class SquaresScene implements EffectScene {
    private static final class Square {
        float x, size, duration, delay;
        boolean filled;
    }

    private final EffectBrush brush;
    private final Square[] squares;
    private Shader scan;

    SquaresScene(EffectBrush brush, float density) {
        this.brush = brush;
        ParticleRandom random = new ParticleRandom();
        squares = new Square[Particles.count(10, density)];
        for (int i = 0; i < squares.length; i++) {
            Square p = squares[i] = new Square();
            p.size = 6 + random.next() * 7;
            p.duration = 10 + random.next() * 6;
            p.filled = random.next() > .7f;
            p.x = random.next() * .95f;
            p.delay = -random.next() * p.duration;
        }
    }

    @Override
    public void layout(Rect bounds) {
        scan = new LinearGradient(bounds.left, 0, bounds.right, 0,
                new int[]{0, brush.tint(0x73A6FF3B), 0}, null, Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(Canvas canvas, float time, float w, float h) {
        float dp = brush.dp;
        Paint paint = brush.paint;
        float scanY = -10 * dp + (EffectBrush.travel(620, h) + 10 * dp) * progress(time, 5, 0);
        brush.shaded.setShader(scan);
        brush.shaded.setAlpha(brush.getAlpha());
        canvas.drawRect(0, scanY, w, scanY + Math.max(1, dp), brush.shaded);
        float rise = EffectBrush.travel(700, h);
        paint.setShader(null);
        for (Square p : squares) {
            float t = progress(time, p.duration, p.delay);
            if (t < 0) continue;
            float opacity = .7f * (t < .15f ? t / .15f : t > .85f ? (1 - t) / .15f : 1);
            float size = p.size * dp;
            float cx = p.x * w + size / 2, cy = h + 20 * dp - size / 2 - rise * t;
            canvas.save();
            canvas.translate(cx, cy);
            canvas.rotate(180 * t);
            float half = size / 2 - .75f * dp;
            if (p.filled) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(EffectBrush.withAlpha(brush.tint(0x80A6FF3B), opacity * brush.getAlpha() / 255f));
                canvas.drawRect(-half, -half, half, half, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * dp);
            paint.setColor(EffectBrush.withAlpha(brush.tint(0xFFA6FF3B), opacity * brush.getAlpha() / 255f));
            canvas.drawRect(-half, -half, half, half, paint);
            canvas.restore();
        }
        paint.setStyle(Paint.Style.FILL);
    }
}
