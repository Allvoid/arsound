package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.theme.effect.Keyframes.progress;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.pulse;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

/** «Алый»: sparks rising from a red glow that breathes at the bottom. */
final class SparksScene implements EffectScene {
    private static final class Spark {
        float x, size, duration, delay, dx;
        int color;
    }

    private final EffectBrush brush;
    private final Spark[] sparks;
    private Shader glow;

    SparksScene(EffectBrush brush, float density) {
        this.brush = brush;
        ParticleRandom random = new ParticleRandom();
        sparks = new Spark[Particles.count(18, density)];
        for (int i = 0; i < sparks.length; i++) {
            Spark p = sparks[i] = new Spark();
            p.size = 2 + random.next() * 2.5f;
            p.duration = 6 + random.next() * 5;
            p.x = random.next();
            p.color = random.next() > .5f ? 0xFFFF5A3C : 0xFFE5242E;
            p.dx = random.next() * 60 - 30;
            p.delay = -random.next() * p.duration;
        }
    }

    @Override
    public void layout(Rect bounds) {
        float w = bounds.width(), h = bounds.height();
        // Radial glow at the bottom, as the design's ellipse box 160% x 55% sunk by a quarter.
        glow = new RadialGradient(0, 0, 1, new int[]{brush.tint(0x66E5242E), brush.tint(0x00E5242E)}, new float[]{0, .65f}, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        matrix.setScale(.8f * w * 1.414f, .275f * h * 1.414f);
        matrix.postTranslate(bounds.left + w / 2, bounds.top + h * .975f);
        glow.setLocalMatrix(matrix);
    }

    @Override
    public void draw(Canvas canvas, float time, float w, float h) {
        float dp = brush.dp;
        float breathe = .35f + .35f * pulse(progress(time, 5, 0));
        brush.shaded.setShader(glow);
        brush.shaded.setAlpha(brush.paintAlpha(breathe));
        canvas.drawRect(-.3f * w, .7f * h, 1.3f * w, 1.25f * h, brush.shaded);
        float rise = EffectBrush.travel(680, h);
        for (Spark p : sparks) {
            float t = progress(time, p.duration, p.delay);
            if (t < 0) continue;
            float opacity = t < .1f ? t / .1f : 1 - (t - .1f) / .9f;
            float scale = 1 - .7f * t;
            float x = p.x * w + p.dx * dp * t + p.size * dp / 2;
            float y = h + 10 * dp - p.size * dp / 2 - rise * t;
            float radius = p.size * dp / 2 * scale;
            brush.halo(canvas, x, y, radius + 4 * dp * scale, brush.tint(0xCCE5242E), opacity);
            brush.circle(canvas, x, y, radius, brush.tint(p.color), opacity);
        }
    }
}
