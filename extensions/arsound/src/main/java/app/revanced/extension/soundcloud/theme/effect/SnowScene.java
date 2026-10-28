package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.shared.Colors.blend;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.progress;
import static app.revanced.extension.soundcloud.theme.effect.Keyframes.pulse;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/** «Зима»: snowfall in three depths under the frozen top edge ({@link FrostLayer}). */
final class SnowScene implements EffectScene {
    /** The warm light of the garland that a flake now and then catches. */
    private static final int GARLAND = 0xFFF2C46D;

    private static final class Flake {
        float x, size, duration, delay, opacity;
        /** Amplitude of the sway to the sides, dp. */
        float sway;
        /** The sway's own loop. */
        float swayDuration, swayDelay;
        /** A crystal (a close flake) instead of a dot. */
        boolean crystal;
        /** Turning speed of a crystal, degrees a second. */
        float spin;
        /** Whether it now and then catches the garland's light, and on which loop. */
        boolean glints;
        float glintDuration, glintDelay;
    }

    private final EffectBrush brush;
    private final Flake[] flakes;
    private final FrostLayer frost;

    SnowScene(EffectBrush brush, float density) {
        this.brush = brush;
        this.frost = new FrostLayer(brush);
        ParticleRandom random = new ParticleRandom();
        // Three depths of snow: many small slow flakes far away, a few big quick ones close by.
        flakes = new Flake[Particles.count(44, density)];
        for (int i = 0; i < flakes.length; i++) {
            Flake p = flakes[i] = new Flake();
            float depth = random.next();
            int layer = depth < .6f ? 0 : depth < .88f ? 1 : 2;
            p.size = layer == 0 ? 1.2f + random.next() * .8f : layer == 1 ? 2 + random.next() : 3.2f + random.next() * 1.4f;
            p.duration = layer == 0 ? 18 + random.next() * 6 : layer == 1 ? 12 + random.next() * 4 : 8 + random.next() * 3;
            p.sway = layer == 0 ? 6 : layer == 1 ? 12 : 20;
            p.opacity = layer == 0 ? .35f + random.next() * .15f : layer == 1 ? .6f + random.next() * .2f : .85f;
            p.x = random.next();
            p.delay = -random.next() * p.duration;
            p.swayDuration = 3 + random.next() * 3;
            p.swayDelay = -random.next() * p.swayDuration;
            // The close flakes are crystals, turning slowly one way or the other.
            p.crystal = layer == 2 && random.next() < .45f;
            p.spin = (random.next() * 2 - 1) * 24;
            // About one flake in four now and then catches the warm light of the garland.
            p.glints = random.next() < .25f;
            p.glintDuration = 7 + random.next() * 6;
            p.glintDelay = -random.next() * p.glintDuration;
        }
    }

    @Override
    public void layout(Rect bounds) {
        frost.build(Math.round(bounds.width()), Math.round(bounds.height()));
    }

    @Override
    public void draw(Canvas canvas, float time, float w, float h) {
        float dp = brush.dp;
        frost.draw(canvas, time);
        float fall = h + 20 * dp;
        for (Flake p : flakes) {
            float t = progress(time, p.duration, p.delay);
            float s = progress(time, p.swayDuration, p.swayDelay);
            if (t < 0 || s < 0) continue;
            float x = p.x * w + (float) Math.sin(2 * Math.PI * s) * p.sway * dp;
            float y = -10 * dp + fall * t;
            float radius = p.size * dp / 2;
            // Snow thins out at the very bottom instead of piling against the edge.
            float opacity = p.opacity * (t > .9f ? (1 - t) / .1f : 1);

            float glint = 0;
            if (p.glints) {
                float g = progress(time, p.glintDuration, p.glintDelay);
                if (g >= 0 && g < .1f) glint = pulse(g / .1f);
            }
            if (glint > 0) brush.halo(canvas, x, y, radius * 4.5f, brush.tint(GARLAND), opacity * glint);
            // While it glints, the flake itself warms to the garland's gold.
            int snow = glint > 0 ? blend(0xFFEAF6FF, GARLAND, .85f * glint) : 0xFFEAF6FF;
            int ice = glint > 0 ? blend(0xFFC7EAF7, GARLAND, .85f * glint) : 0xFFC7EAF7;

            if (p.crystal) {
                crystal(canvas, x, y, radius * 2.4f, p.spin * time, brush.tint(ice), opacity);
            } else {
                if (p.size > 3) brush.halo(canvas, x, y, radius * 1.8f, brush.tint(snow), opacity * .5f);
                brush.circle(canvas, x, y, radius, brush.tint(snow), opacity);
            }
        }
    }

    /** A six-armed snow crystal: three strokes through the middle, each arm with a pair of short twigs. */
    private void crystal(Canvas canvas, float x, float y, float radius, float angle, int color, float opacity) {
        Paint paint = brush.paint;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(Math.max(1, .9f * brush.dp));
        paint.setColor(EffectBrush.withAlpha(color, opacity * brush.getAlpha() / 255f));
        canvas.save();
        canvas.translate(x, y);
        canvas.rotate(angle);
        float twig = radius * .38f;
        for (int arm = 0; arm < 6; arm++) {
            canvas.drawLine(0, 0, 0, -radius, paint);
            float at = -radius * .58f;
            canvas.drawLine(0, at, -twig * .7f, at - twig * .7f, paint);
            canvas.drawLine(0, at, twig * .7f, at - twig * .7f, paint);
            canvas.rotate(60);
        }
        canvas.restore();
        paint.setStyle(Paint.Style.FILL);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }
}
