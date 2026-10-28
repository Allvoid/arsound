package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.theme.effect.Keyframes.progress;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.pulse;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/** «Кобальт»: a night sky of twinkling stars, with two meteors that cross it now and then. */
final class StarsScene implements EffectScene {
    private static final class Star {
        float x, y, size, duration, delay;
    }

    private final EffectBrush brush;
    private final Star[] stars;
    private final RectF rect = new RectF();
    private Shader meteor;

    StarsScene(EffectBrush brush, float density) {
        this.brush = brush;
        ParticleRandom random = new ParticleRandom();
        stars = new Star[Particles.count(28, density)];
        for (int i = 0; i < stars.length; i++) {
            Star p = stars[i] = new Star();
            p.size = 1 + random.next() * 1.8f;
            p.x = random.next();
            p.y = random.next() * .75f;
            p.duration = 2 + random.next() * 4;
            p.delay = -random.next() * 6;
        }
    }

    @Override
    public void layout(Rect bounds) {
        float dp = brush.dp;
        meteor = new LinearGradient(-45 * dp, 0, 45 * dp, 0, brush.tint(0xFFCFE0FF), brush.tint(0x004C8DFF), Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(Canvas canvas, float time, float w, float h) {
        float dp = brush.dp;
        for (Star p : stars) {
            float t = progress(time, p.duration, p.delay);
            if (t < 0) continue;
            float f = pulse(t);
            float opacity = .15f + .85f * f, scale = .6f + .4f * f;
            float x = p.x * w + p.size * dp / 2, y = p.y * h + p.size * dp / 2;
            float radius = p.size * dp / 2 * scale;
            brush.halo(canvas, x, y, radius + 3 * dp * scale, brush.tint(0xB378AAFF), opacity);
            brush.circle(canvas, x, y, radius, brush.tint(0xFFCFE0FF), opacity);
        }
        brush.shaded.setShader(meteor);
        for (int i = 0; i < 2; i++) {
            float t = progress(time, 8 + i * 3, 1 + i * 4);
            if (t < 0 || t > .12f) continue;
            float opacity = t < .02f ? t / .02f : 1 - (t - .02f) / .1f;
            float move = t / .12f;
            float x = (.70f + i * .22f) * w + 45 * dp - 260 * dp * move;
            float y = (.06f + i * .14f) * h + .75f * dp + 180 * dp * move;
            brush.shaded.setAlpha(brush.paintAlpha(opacity));
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(-35);
            rect.set(-45 * dp, -.75f * dp, 45 * dp, .75f * dp);
            canvas.drawRoundRect(rect, dp, dp, brush.shaded);
            canvas.restore();
        }
    }
}
