package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.theme.effect.Keyframes.progress;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;

/**
 * «Гроза»: slanted rain, rings on the water at the bottom of the screen, and now and then a forked bolt that lights up
 * the whole sky twice, as lightning does. The rain and the rings come from the time alone; a bolt is built once, from
 * its number, so each one has a shape of its own and keeps it while it flickers.
 */
final class StormScene implements EffectScene {
    /** The sideways slip of the rain, as a share of its fall. */
    private static final float SLANT = .16f;
    /** The seconds a strike lasts on screen. */
    private static final float STRIKE_SECONDS = .55f;
    /** The seconds between strikes on average, and how far one may come early or late. */
    private static final float STRIKE_EVERY = 11f, STRIKE_SWAY = 4f;
    /** The bolt ends at this share of the height: it stays in the sky, above the player's buttons. */
    private static final float BOLT_REACH = .62f;
    private static final int BOLT_STEPS = 9;

    private static final class Drop {
        float x, phase, speed, length, opacity;
    }

    private static final class Ring {
        float x, y, size, duration, delay;
    }

    private final EffectBrush brush;
    private final Drop[] drops;
    private final Ring[] rings;
    private final RectF oval = new RectF();
    private final Path bolt = new Path();
    private final Path fork = new Path();
    private long boltNumber = -1;
    private float boltWidth, boltHeight;

    StormScene(EffectBrush brush, float density) {
        this.brush = brush;
        ParticleRandom random = new ParticleRandom();
        drops = new Drop[Particles.count(70, density)];
        for (int i = 0; i < drops.length; i++) {
            Drop p = drops[i] = new Drop();
            p.x = random.next();
            p.phase = random.next();
            // The far rain is thin, faint and slow; the near one long and quick.
            float near = random.next();
            p.speed = .55f + near * .6f;
            p.length = 10 + near * 18;
            p.opacity = .10f + near * .22f;
        }
        rings = new Ring[Particles.count(9, density)];
        for (int i = 0; i < rings.length; i++) {
            Ring p = rings[i] = new Ring();
            p.x = random.next();
            p.y = .80f + random.next() * .18f;
            p.size = 22 + random.next() * 26;
            p.duration = 2.2f + random.next() * 2.4f;
            p.delay = -random.next() * 4;
        }
    }

    /** The time of strike {@code number} in seconds; two strikes are always some seconds apart. */
    static float strikeTime(long number) {
        return number * STRIKE_EVERY + STRIKE_SWAY * noise(number * 12.9898f);
    }

    /** The number of the last strike by this time, -1 before the first one. */
    static long lastStrike(float time) {
        long number = (long) (time / STRIKE_EVERY) + 1;
        while (number >= 0 && strikeTime(number) > time) number--;
        return number;
    }

    /** How bright the sky is this long after a strike, 0..1: a flash, a short dark, a second weaker flash. */
    static float flash(float since) {
        if (since < 0 || since >= STRIKE_SECONDS) return 0;
        if (since < .07f) return 1 - since / .07f * .4f;
        if (since < .16f) return .6f * (1 - (since - .07f) / .09f);
        if (since < .24f) return (since - .16f) / .08f * .55f;
        return .55f * (1 - (since - .24f) / (STRIKE_SECONDS - .24f));
    }

    /** A number in 0..1 that looks random and is the same for the same input. */
    private static float noise(float input) {
        float value = (float) Math.sin(input) * 43758.547f;
        return value - (float) Math.floor(value);
    }

    @Override
    public void layout(Rect bounds) {
        boltNumber = -1;
    }

    @Override
    public void draw(Canvas canvas, float time, float w, float h) {
        long strike = lastStrike(time);
        float light = strike < 0 ? 0 : flash(time - strikeTime(strike));
        if (light > 0) drawStrike(canvas, strike, light, w, h);
        drawRain(canvas, time, w, h, light);
        drawRings(canvas, time, w, h);
    }

    private int faded(int color, float opacity) {
        return EffectBrush.withAlpha(color, opacity * brush.getAlpha() / 255f);
    }

    private void drawStrike(Canvas canvas, long strike, float light, float w, float h) {
        Paint paint = brush.paint;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(faded(brush.tint(0xFFC7CCFF), light * .16f));
        canvas.drawRect(0, 0, w, h, paint);

        if (strike != boltNumber || w != boltWidth || h != boltHeight) buildBolt(strike, w, h);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(7 * brush.dp);
        paint.setColor(faded(brush.tint(0xFF8B7CFF), light * .30f));
        canvas.drawPath(bolt, paint);
        paint.setStrokeWidth(1.8f * brush.dp);
        paint.setColor(faded(brush.tint(0xFFF2F0FF), light));
        canvas.drawPath(bolt, paint);
        paint.setStrokeWidth(brush.dp);
        paint.setColor(faded(brush.tint(0xFFD9D4FF), light * .7f));
        canvas.drawPath(fork, paint);
    }

    /** A bolt down the sky from a place of its own, and two forks that leave it on the way. */
    private void buildBolt(long strike, float w, float h) {
        boltNumber = strike;
        boltWidth = w;
        boltHeight = h;
        bolt.rewind();
        fork.rewind();
        float x = w * (.18f + noise(strike * 3.1f) * .64f), y = 0;
        float step = h * BOLT_REACH / BOLT_STEPS;
        bolt.moveTo(x, y);
        for (int i = 1; i <= BOLT_STEPS; i++) {
            x += (noise(strike * 7.3f + i) - .5f) * w * .14f;
            y += step;
            bolt.lineTo(x, y);
            if (i != 3 && i != 6) continue;
            float side = noise(strike + i * 5.7f) < .5f ? -1 : 1;
            float forkX = x, forkY = y;
            fork.moveTo(forkX, forkY);
            for (int j = 1; j <= 4; j++) {
                forkX += side * (.02f + noise(strike * 2.3f + i * j) * .05f) * w;
                forkY += step * .55f;
                fork.lineTo(forkX, forkY);
            }
        }
    }

    private void drawRain(Canvas canvas, float time, float w, float h, float light) {
        Paint paint = brush.paint;
        float dp = brush.dp;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp);
        int color = brush.tint(0xFFAEB8E8);
        float fall = h * 1.2f;
        for (Drop p : drops) {
            float t = p.phase + time * p.speed;
            t -= (float) Math.floor(t);
            float y = t * fall - h * .1f;
            float x = p.x * w * 1.2f - y * SLANT;
            float length = p.length * dp;
            // The lightning shows the rain up.
            paint.setColor(faded(color, Math.min(1, p.opacity + light * .5f)));
            canvas.drawLine(x, y, x - length * SLANT, y + length, paint);
        }
    }

    private void drawRings(Canvas canvas, float time, float w, float h) {
        Paint paint = brush.paint;
        float dp = brush.dp;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp);
        int color = brush.tint(0xFFAEB8E8);
        for (Ring p : rings) {
            float t = progress(time, p.duration, p.delay);
            if (t < 0) continue;
            float radius = p.size * dp * t;
            float x = p.x * w, y = p.y * h;
            // Water seen from the side: the rings lie flat.
            oval.set(x - radius, y - radius * .32f, x + radius, y + radius * .32f);
            paint.setColor(faded(color, (1 - t) * .38f));
            canvas.drawOval(oval, paint);
        }
    }
}
