package app.revanced.extension.soundcloud.theme.effect;

import static app.revanced.extension.soundcloud.theme.effect.Keyframes.ease;

import android.graphics.Canvas;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;

/**
 * «Monet»: large soft blobs of colour drifting about, as on the desktop. Six of them (at the usual amount), 280 to 520
 * dp wide, each a round gradient that fades to nothing at its edge, in the hue of the theme's accent (the desktop takes
 * the hue of the cover) turned a different way for each blob. A blob drifts there and back, 18 to 34 seconds one way,
 * with its size breathing a little.
 */
final class BlobsScene implements EffectScene {
    /** How far each blob's colour is turned from the accent's hue, in degrees, in the order of the blobs. */
    private static final double[] HUE_OFFSETS = {0, 40, -40, 80, -70, 160};
    /** The design's lightness and chroma of a blob in the dark look and in the light one. */
    private static final double DARK_LIGHTNESS = 0.62, LIGHT_LIGHTNESS = 0.78, CHROMA = 0.15;
    /** How strong the middle of a blob is (of 255) in the dark look and in the light one. */
    private static final int DARK_STRENGTH = 0x57, LIGHT_STRENGTH = 0x6B;
    /** The keyframes of a drift: the offset in dp and the scale at the far end, and at the other end of the way back. */
    private static final float MID_DX = 120, MID_DY = -70, MID_SCALE = 1.18f;
    private static final float END_DX = -80, END_DY = 60, END_SCALE = 0.92f;

    private static final class Blob {
        /** The middle as fractions of the layer; the size in dp; the seconds of one way and the delay. */
        float x, y, size, duration, delay;
        int colour;
        Shader shader;
    }

    private final EffectBrush brush;
    private final Blob[] blobs;
    /** The offset and scale of the blob being drawn, kept: a frame makes no new objects. */
    private final float[] scratch = new float[3];

    BlobsScene(EffectBrush brush, float density) {
        this.brush = brush;
        ParticleRandom random = new ParticleRandom();
        blobs = new Blob[Particles.count(6, density)];
        boolean night = brush.isNight();
        double hue = BlobColours.hueOf(brush.accent());
        for (int i = 0; i < blobs.length; i++) {
            Blob p = blobs[i] = new Blob();
            p.x = random.next();
            p.y = .1f + random.next() * .8f;
            p.size = 280 + random.next() * 240;
            p.duration = 18 + random.next() * 16;
            p.delay = -random.next() * p.duration;
            p.colour = BlobColours.argb(night ? DARK_LIGHTNESS : LIGHT_LIGHTNESS, CHROMA,
                    hue + HUE_OFFSETS[i % HUE_OFFSETS.length], night ? DARK_STRENGTH : LIGHT_STRENGTH);
        }
    }

    @Override
    public void layout(Rect bounds) {
        // The gradients are in the blob's own space, round, middle at (0, 0): the canvas moves and scales them.
        float dp = brush.dp;
        for (Blob p : blobs) {
            float radius = p.size * dp / 2;
            p.shader = new RadialGradient(0, 0, radius, p.colour, p.colour & 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
    }

    @Override
    public void draw(Canvas canvas, float time, float w, float h) {
        float dp = brush.dp;
        float[] move = scratch;
        for (Blob p : blobs) {
            float u = position(time, p.duration, p.delay);
            if (u < 0) continue;
            keyframe(u, move);
            float radius = p.size * dp / 2;
            canvas.save();
            canvas.translate(p.x * w + move[0] * dp, p.y * h + move[1] * dp);
            canvas.scale(move[2], move[2]);
            brush.shaded.setShader(p.shader);
            brush.shaded.setAlpha(brush.paintAlpha(1f));
            canvas.drawCircle(0, 0, radius, brush.shaded);
            canvas.restore();
        }
    }

    /**
     * Where the blob is on its way, 0..1 (0 at one end, 1 at the other), for an animation that goes there and back
     * ("infinite alternate"): a whole round trip takes twice the duration. -1 before it starts.
     */
    static float position(float time, float duration, float delay) {
        float local = time - delay;
        if (local < 0) return -1;
        float cycle = (local % (2 * duration)) / duration;
        return cycle <= 1 ? cycle : 2 - cycle;
    }

    /**
     * The drift at a position on the way, written to {@code out}: the offset in dp (x, y) and the scale. Three keyframes (rest, the far point,
     * the end), each leg eased in and out like the desktop's sine timing.
     */
    static void keyframe(float u, float[] out) {
        float x, y, scale;
        if (u < .5f) {
            float e = ease(u * 2);
            x = MID_DX * e;
            y = MID_DY * e;
            scale = 1 + (MID_SCALE - 1) * e;
        } else {
            float e = ease(u * 2 - 1);
            x = MID_DX + (END_DX - MID_DX) * e;
            y = MID_DY + (END_DY - MID_DY) * e;
            scale = MID_SCALE + (END_SCALE - MID_SCALE) * e;
        }
        out[0] = x;
        out[1] = y;
        out[2] = scale;
    }
}
