package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.shared.Colors.blend;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.progress;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.pulse;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/** «Сакура»: petals falling and swaying. */
final class PetalsScene implements EffectScene {
    private static final class Petal {
        float x, size, duration, delay, opacity, swayDuration, swayDelay;
    }

    private final EffectBrush brush;
    private final Petal[] petals;
    private final Path shape = petalShape();
    private Shader gradient;

    PetalsScene(EffectBrush brush, float density) {
        this.brush = brush;
        ParticleRandom random = new ParticleRandom();
        petals = new Petal[Particles.count(16, density)];
        for (int i = 0; i < petals.length; i++) {
            Petal p = petals[i] = new Petal();
            p.size = 8 + random.next() * 6;
            p.duration = 9 + random.next() * 6;
            p.x = -.05f + random.next() * 1.05f;
            p.delay = -random.next() * p.duration;
            p.opacity = .55f + random.next() * .4f;
            p.swayDuration = 3 + random.next() * 2;
            p.swayDelay = -random.next() * 3;
        }
    }

    /** The petal of the design: a box with the top-left and bottom-right corners rounded by 80%, 1 wide. */
    private static Path petalShape() {
        Path petal = new Path();
        RectF rect = new RectF();
        float pw = 1, ph = .75f;
        petal.moveTo(.8f * pw, 0);
        petal.lineTo(pw, 0);
        petal.lineTo(pw, .2f * ph);
        rect.set(pw - 1.6f * pw, ph - 1.6f * ph, pw, ph);
        petal.arcTo(rect, 0, 90, false);
        petal.lineTo(0, ph);
        petal.lineTo(0, .8f * ph);
        rect.set(0, 0, 1.6f * pw, 1.6f * ph);
        petal.arcTo(rect, 180, 90, false);
        petal.close();
        return petal;
    }

    @Override
    public void layout(Rect bounds) {
        int petalLight = brush.isNight() ? 0xFFFFD0E6 : blend(brush.accent(), 0xFFFFFFFF, .55f);
        gradient = new LinearGradient(0, 0, 1, .75f, petalLight, brush.tint(0xFFF28AC0), Shader.TileMode.CLAMP);
    }

    @Override
    public void draw(Canvas canvas, float time, float w, float h) {
        float dp = brush.dp;
        float fall = EffectBrush.travel(680, h) + 40 * dp;
        brush.shaded.setShader(gradient);
        for (Petal p : petals) {
            float t = progress(time, p.duration, p.delay);
            float s = progress(time, p.swayDuration, p.swayDelay);
            if (t < 0 || s < 0) continue;
            float f = pulse(s);
            float size = p.size * dp;
            float x = p.x * w + (-18 + 36 * f) * dp;
            float y = -40 * dp + fall * t;
            brush.shaded.setAlpha(brush.paintAlpha(p.opacity));
            canvas.save();
            canvas.translate(x + size / 2, y + size * .375f);
            canvas.rotate(-30 + 80 * f);
            canvas.scale(size, size);
            canvas.translate(-.5f, -.375f);
            canvas.drawPath(shape, brush.shaded);
            canvas.restore();
        }
    }
}
