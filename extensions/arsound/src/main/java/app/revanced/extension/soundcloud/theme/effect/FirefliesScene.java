package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.theme.effect.Keyframes.ease;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.progress;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.pulse;

import android.graphics.Canvas;
import android.graphics.Rect;

/** «Мята»: fireflies that drift on a slow loop and glow on a faster one. */
final class FirefliesScene implements EffectScene {
    private static final class Firefly {
        float x, y, duration, delay, glowDuration, glowDelay;
        /** The offsets in dp the drift visits: (a, b), then (c, d), then (b, a), then back to the start. */
        float a, b, c, d;
    }

    private final EffectBrush brush;
    private final Firefly[] fireflies;

    FirefliesScene(EffectBrush brush, float density) {
        this.brush = brush;
        ParticleRandom random = new ParticleRandom();
        fireflies = new Firefly[Particles.count(12, density)];
        for (int i = 0; i < fireflies.length; i++) {
            Firefly p = fireflies[i] = new Firefly();
            p.x = .05f + random.next() * .9f;
            p.y = .15f + random.next() * .8f;
            p.a = random.next() * 60 - 30;
            p.b = random.next() * 60 - 30;
            p.c = random.next() * 60 - 30;
            p.d = random.next() * 60 - 30;
            p.duration = 8 + random.next() * 6;
            p.delay = -random.next() * 10;
            p.glowDuration = 2.5f + random.next() * 3;
            p.glowDelay = -random.next() * 4;
        }
    }

    @Override
    public void layout(Rect bounds) {
        // Nothing to make: the positions are fractions of the size, taken at every frame.
    }

    @Override
    public void draw(Canvas canvas, float time, float w, float h) {
        float dp = brush.dp;
        for (Firefly p : fireflies) {
            float t = progress(time, p.duration, p.delay);
            float g = progress(time, p.glowDuration, p.glowDelay);
            if (t < 0 || g < 0) continue;
            // Keyframes (0,0) → (a,b) → (c,d) → (b,a) → (0,0), eased on each quarter.
            float[] xs = {0, p.a, p.c, p.b, 0}, ys = {0, p.b, p.d, p.a, 0};
            int segment = Math.min(3, (int) (t * 4));
            float local = ease(t * 4 - segment);
            float dx = xs[segment] + (xs[segment + 1] - xs[segment]) * local;
            float dy = ys[segment] + (ys[segment + 1] - ys[segment]) * local;
            float opacity = .1f + .85f * pulse(g);
            float x = p.x * w + (dx + 2) * dp, y = p.y * h + (dy + 2) * dp;
            brush.halo(canvas, x, y, 9 * dp, brush.tint(0x8C3EE0A0), opacity);
            brush.circle(canvas, x, y, 2 * dp, brush.tint(0xFF7AF5C4), opacity);
        }
    }
}
