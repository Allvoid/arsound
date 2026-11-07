package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.theme.effect.Keyframes.progress;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

import java.util.function.BooleanSupplier;

/**
 * «Винил»: a big record in the corner of the screen, turning only while the music plays. On pause the platter runs down
 * for a second and a half and the tone arm lifts off, as on a real turntable; it comes up to speed the same way. The light
 * on the grooves stays where it is, as it does on a record, and dust drifts through it.
 * <p>
 * Unlike the other scenes this one keeps a state, the angle: the record has to stop where it is and go on from there,
 * which the time alone cannot tell.
 */
final class VinylScene implements EffectScene {
    /** Degrees a second at full speed: half of 33⅓ revolutions a minute, a full one flickers behind the content. */
    private static final float FULL_SPEED = 100;
    /** How long the platter takes to run down or come up to speed. */
    private static final float SPIN_SECONDS = 1.5f;
    /** A pause in the frames (the effect stood still) longer than this is not a spin of the record. */
    private static final float LONGEST_FRAME = .25f;
    private static final int GROOVES = 9;
    /** The arm's angle off the record and on its grooves, in degrees from straight down. */
    private static final float ARM_REST = 50, ARM_PLAYING = 25;

    private static final class Mote {
        float x, y, size, duration, delay;
    }

    private final EffectBrush brush;
    private final BooleanSupplier playing;
    private final Mote[] dust;
    private final RectF rect = new RectF();
    private Shader sheen;
    private float centerX, centerY, radius;

    private float lastTime = -1;
    private float speed;
    private float angle;
    private float arm = ARM_REST;

    VinylScene(EffectBrush brush, float density, BooleanSupplier playing) {
        this.brush = brush;
        this.playing = playing;
        ParticleRandom random = new ParticleRandom();
        dust = new Mote[Particles.count(16, density)];
        for (int i = 0; i < dust.length; i++) {
            Mote p = dust[i] = new Mote();
            p.x = random.next();
            p.y = random.next();
            p.size = 1 + random.next() * 1.6f;
            p.duration = 9 + random.next() * 12;
            p.delay = -random.next() * 20;
        }
    }

    /** The speed one step of {@code seconds} later: it moves evenly to the target, full speed from a standstill in {@link #SPIN_SECONDS}. */
    static float nextSpeed(float speed, boolean turning, float seconds) {
        float step = FULL_SPEED / SPIN_SECONDS * seconds;
        return turning ? Math.min(FULL_SPEED, speed + step) : Math.max(0, speed - step);
    }

    @Override
    public void layout(Rect bounds) {
        int w = bounds.width(), h = bounds.height();
        radius = Math.max(w * .72f, Math.min(h * .42f, w * 1.05f));
        centerX = w * .92f;
        centerY = h * .84f;
        // Two opposite glints on the grooves, as a lamp above the player leaves them.
        int none = brush.tint(0x00FFD9A0), glint = brush.tint(0x38FFD9A0);
        sheen = new SweepGradient(centerX, centerY,
                new int[]{none, glint, none, none, glint, none, none},
                new float[]{.02f, .09f, .17f, .52f, .59f, .67f, 1f});
    }

    @Override
    public void draw(Canvas canvas, float time, float w, float h) {
        advance(time);
        drawRecord(canvas);
        drawLabel(canvas);
        drawArm(canvas);
        drawDust(canvas, time, w, h);
    }

    private void advance(float time) {
        float seconds = lastTime < 0 ? 0 : Math.min(LONGEST_FRAME, Math.max(0, time - lastTime));
        lastTime = time;
        boolean turning = playing.getAsBoolean();
        speed = nextSpeed(speed, turning, seconds);
        angle = (angle + speed * seconds) % 360;
        float target = turning ? ARM_PLAYING : ARM_REST;
        float armStep = Math.abs(ARM_REST - ARM_PLAYING) / SPIN_SECONDS * seconds;
        arm = arm < target ? Math.min(target, arm + armStep) : Math.max(target, arm - armStep);
    }

    private int faded(int color, float opacity) {
        return EffectBrush.withAlpha(color, opacity * brush.getAlpha() / 255f);
    }

    private void drawRecord(Canvas canvas) {
        Paint paint = brush.paint;
        float dp = brush.dp;
        // No disc under the grooves: on the main screen the effect lies over the covers, and a dark plate would dim them.
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp);
        paint.setColor(faded(brush.tint(0xFFFFD9A0), .08f));
        for (int i = 0; i < GROOVES; i++) {
            canvas.drawCircle(centerX, centerY, radius * (.40f + .58f * i / (GROOVES - 1)), paint);
        }
        paint.setColor(faded(brush.tint(0xFFFFD9A0), .20f));
        canvas.drawCircle(centerX, centerY, radius, paint);

        brush.shaded.setShader(sheen);
        brush.shaded.setStyle(Paint.Style.FILL);
        brush.shaded.setAlpha(brush.paintAlpha(1));
        canvas.drawCircle(centerX, centerY, radius, brush.shaded);
    }

    /** The label: it alone shows the turning, by the two marks on it. */
    private void drawLabel(Canvas canvas) {
        float label = radius * .33f;
        brush.circle(canvas, centerX, centerY, label, brush.accent(), .12f);
        Paint paint = brush.paint;
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * brush.dp);
        paint.setColor(faded(brush.accent(), .55f));
        canvas.save();
        canvas.rotate(angle, centerX, centerY);
        rect.set(centerX - label * .72f, centerY - label * .72f, centerX + label * .72f, centerY + label * .72f);
        canvas.drawArc(rect, 20, 120, false, paint);
        canvas.drawArc(rect, 200, 120, false, paint);
        canvas.restore();
        brush.circle(canvas, centerX, centerY, 4 * brush.dp, brush.accent(), .6f);
    }

    /** The tone arm, from a pivot over the rim of the record to the grooves; off them on pause. */
    private void drawArm(Canvas canvas) {
        Paint paint = brush.paint;
        float dp = brush.dp;
        float pivotX = centerX - radius * .22f, pivotY = centerY - radius * 1.04f;
        float length = radius * .98f;
        canvas.save();
        canvas.rotate(arm, pivotX, pivotY);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3 * dp);
        paint.setColor(faded(brush.tint(0xFFE8D3B0), .22f));
        canvas.drawLine(pivotX, pivotY, pivotX, pivotY + length, paint);
        paint.setStrokeWidth(7 * dp);
        canvas.drawLine(pivotX, pivotY + length - 16 * dp, pivotX, pivotY + length, paint);
        canvas.restore();
        brush.circle(canvas, pivotX, pivotY, 9 * dp, brush.tint(0xFFE8D3B0), .22f);
    }

    /** Dust through the light: it rises slowly, and shows as it passes. */
    private void drawDust(Canvas canvas, float time, float w, float h) {
        float dp = brush.dp;
        for (Mote p : dust) {
            float t = progress(time, p.duration, p.delay);
            if (t < 0) continue;
            float opacity = (t < .5f ? t * 2 : (1 - t) * 2) * .5f;
            float x = p.x * w + (float) Math.sin(t * 6.28f) * 14 * dp;
            float y = p.y * h - EffectBrush.travel(90, h) * t;
            brush.circle(canvas, x, y, p.size * dp, brush.tint(0xFFFFD9A0), opacity);
        }
    }
}
