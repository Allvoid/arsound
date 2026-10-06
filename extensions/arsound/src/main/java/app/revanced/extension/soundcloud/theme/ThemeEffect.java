package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * The animated background of a theme, after the design "Анимации тем": sparks rising for «Алый», a night sky
 * with meteors for «Кобальт», fireflies for «Мята», falling petals for «Сакура», a scanner line with rising
 * squares for «Лайм».
 * <p>
 * A drawable, so it can lie under a screen (Arsound settings), over one (the main screen, where SoundCloud's
 * backgrounds are opaque) or in a small preview tile. Every particle is computed from the time alone, like a CSS
 * keyframe animation, so nothing piles up between frames. It runs only while visible, and stands still in battery
 * saver mode or when Android's animations are turned off.
 */
public final class ThemeEffect extends Drawable implements Runnable {
    private static final long FRAME_MS = 16;
    /** The design draws its effects on a card this tall, in dp; travel distances are scaled to the real height. */
    private static final float DESIGN_HEIGHT = 600f;

    private static final String SCARLET = "scarlet", COBALT = "cobalt", MINT = "mint", SAKURA = "sakura", LIME = "lime";

    private final String kind;
    /** Pixels per design dp, with the size scale of a preview tile. */
    private final float dp;
    private final boolean night;
    private final int accent;
    private final boolean still;
    private final Particle[] particles;
    private final long start = SystemClock.uptimeMillis();

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shaded = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path petal = new Path();
    private final RectF rect = new RectF();
    private Shader glow, meteor, petalShader, scan;
    private int alpha = 255;

    private static final class Particle {
        float x, y, size, duration, delay, duration2, delay2, opacity, dx, a, b, c, d;
        int color;
        boolean filled;
    }

    /**
     * @param kind      Effect id, the theme id by default ({@link ArsoundTheme#effect}).
     * @param sizeScale 1 on a whole screen, less in a preview tile.
     * @param density   How many particles, 1 as in the design.
     * @param night     The dark look: the design's colours. The light look draws everything in the accent.
     * @return Null for a theme without an effect.
     */
    public static ThemeEffect create(Context context, String kind, float sizeScale, float density, boolean night, int accent) {
        if (kind == null) return null;
        switch (kind) {
            case SCARLET:
            case COBALT:
            case MINT:
            case SAKURA:
            case LIME:
                return new ThemeEffect(context, kind, sizeScale, density, night, accent);
            default:
                return null;
        }
    }

    /** Whether the theme has an effect, for the settings. */
    public static boolean exists(String kind) {
        return SCARLET.equals(kind) || COBALT.equals(kind) || MINT.equals(kind) || SAKURA.equals(kind) || LIME.equals(kind);
    }

    private ThemeEffect(Context context, String kind, float sizeScale, float density, boolean night, int accent) {
        this.kind = kind;
        this.dp = context.getResources().getDisplayMetrics().density * sizeScale;
        this.night = night;
        this.accent = accent;
        this.still = isStill(context);
        this.particles = build(kind, density);
    }

    private static boolean isStill(Context context) {
        try {
            PowerManager power = context.getSystemService(PowerManager.class);
            if (power != null && power.isPowerSaveMode()) return true;
            return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        } catch (Exception ex) {
            return false;
        }
    }

    // region Particles, with the design's random sequence so the picture matches it

    private long seed = 7;

    private float random() {
        seed = (seed * 16807) % 2147483647;
        return (seed - 1) / 2147483646f;
    }

    private static int count(int design, float density) {
        return Math.max(1, Math.round(design * density));
    }

    private Particle[] build(String kind, float density) {
        Particle[] list;
        switch (kind) {
            case SCARLET:
                list = new Particle[count(18, density)];
                for (int i = 0; i < list.length; i++) {
                    Particle p = list[i] = new Particle();
                    p.size = 2 + random() * 2.5f;
                    p.duration = 6 + random() * 5;
                    p.x = random();
                    p.color = random() > .5f ? 0xFFFF5A3C : 0xFFE5242E;
                    p.dx = random() * 60 - 30;
                    p.delay = -random() * p.duration;
                }
                return list;
            case COBALT:
                list = new Particle[count(28, density)];
                for (int i = 0; i < list.length; i++) {
                    Particle p = list[i] = new Particle();
                    p.size = 1 + random() * 1.8f;
                    p.x = random();
                    p.y = random() * .75f;
                    p.duration = 2 + random() * 4;
                    p.delay = -random() * 6;
                }
                return list;
            case MINT:
                list = new Particle[count(12, density)];
                for (int i = 0; i < list.length; i++) {
                    Particle p = list[i] = new Particle();
                    p.x = .05f + random() * .9f;
                    p.y = .15f + random() * .8f;
                    p.a = random() * 60 - 30;
                    p.b = random() * 60 - 30;
                    p.c = random() * 60 - 30;
                    p.d = random() * 60 - 30;
                    p.duration = 8 + random() * 6;
                    p.delay = -random() * 10;
                    p.duration2 = 2.5f + random() * 3;
                    p.delay2 = -random() * 4;
                }
                return list;
            case SAKURA:
                list = new Particle[count(16, density)];
                for (int i = 0; i < list.length; i++) {
                    Particle p = list[i] = new Particle();
                    p.size = 8 + random() * 6;
                    p.duration = 9 + random() * 6;
                    p.x = -.05f + random() * 1.05f;
                    p.delay = -random() * p.duration;
                    p.opacity = .55f + random() * .4f;
                    p.duration2 = 3 + random() * 2;
                    p.delay2 = -random() * 3;
                }
                return list;
            default:
                list = new Particle[count(10, density)];
                for (int i = 0; i < list.length; i++) {
                    Particle p = list[i] = new Particle();
                    p.size = 6 + random() * 7;
                    p.duration = 10 + random() * 6;
                    p.filled = random() > .7f;
                    p.x = random() * .95f;
                    p.delay = -random() * p.duration;
                }
                return list;
        }
    }

    // endregion

    // region Timing

    /** Progress 0..1 of a looping animation with a CSS-style delay (negative: already running). -1 before it starts. */
    private static float progress(float time, float duration, float delay) {
        float local = time - delay;
        if (local < 0) return -1;
        return (local % duration) / duration;
    }

    /** CSS ease-in-out, close enough for slow loops. */
    private static float ease(float value) {
        return (float) (0.5 - 0.5 * Math.cos(Math.PI * value));
    }

    /** A loop 0 → 1 → 0 with ease-in-out on both halves, as keyframes 0%, 50%, 100%. */
    private static float pulse(float progress) {
        return (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * progress));
    }

    private static int withAlpha(int color, float alpha) {
        int a = Math.round(Color.alpha(color) * Math.max(0, Math.min(1, alpha)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    /** The design colour in the dark look, the accent in the light one (pale sparks would vanish on white). */
    private int tint(int designColor) {
        return night ? designColor : (accent & 0x00FFFFFF) | (designColor & 0xFF000000);
    }

    // endregion

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        float w = bounds.width(), h = bounds.height();
        if (w <= 0 || h <= 0) return;
        // «Алый»: radial glow at the bottom, as the design's ellipse box 160% x 55% sunk by a quarter.
        glow = new RadialGradient(0, 0, 1, new int[]{tint(0x66E5242E), tint(0x00E5242E)}, new float[]{0, .65f}, Shader.TileMode.CLAMP);
        Matrix matrix = new Matrix();
        matrix.setScale(.8f * w * 1.414f, .275f * h * 1.414f);
        matrix.postTranslate(bounds.left + w / 2, bounds.top + h * .975f);
        glow.setLocalMatrix(matrix);
        meteor = new LinearGradient(-45 * dp, 0, 45 * dp, 0, tint(0xFFCFE0FF), tint(0x004C8DFF), Shader.TileMode.CLAMP);
        int petalLight = night ? 0xFFFFD0E6 : blend(accent, 0xFFFFFFFF, .55f);
        petalShader = new LinearGradient(0, 0, 1, .75f, petalLight, tint(0xFFF28AC0), Shader.TileMode.CLAMP);
        scan = new LinearGradient(bounds.left, 0, bounds.right, 0,
                new int[]{0, tint(0x73A6FF3B), 0}, null, Shader.TileMode.CLAMP);
        // The petal of the design: a box with the top-left and bottom-right corners rounded by 80%, 1 wide.
        petal.reset();
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
    }

    private static int blend(int from, int to, float amount) {
        return Color.rgb(
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty() || glow == null) return;
        float time = (SystemClock.uptimeMillis() - start) / 1000f;
        canvas.save();
        canvas.clipRect(bounds);
        canvas.translate(bounds.left, bounds.top);
        float w = bounds.width(), h = bounds.height();
        switch (kind) {
            case SCARLET:
                drawScarlet(canvas, time, w, h);
                break;
            case COBALT:
                drawCobalt(canvas, time, w, h);
                break;
            case MINT:
                drawMint(canvas, time, w, h);
                break;
            case SAKURA:
                drawSakura(canvas, time, w, h);
                break;
            default:
                drawLime(canvas, time, w, h);
        }
        canvas.restore();
        if (!still && isVisible()) {
            unscheduleSelf(this);
            scheduleSelf(this, SystemClock.uptimeMillis() + FRAME_MS);
        }
    }

    @Override
    public void run() {
        invalidateSelf();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) unscheduleSelf(this);
        else if (changed) invalidateSelf();
        return changed;
    }

    private void circle(Canvas canvas, float x, float y, float radius, int color, float opacity) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(color, opacity * alpha / 255f));
        canvas.drawCircle(x, y, radius, paint);
    }

    /** A soft halo like the design's box-shadow: two faint rings under the dot. */
    private void halo(Canvas canvas, float x, float y, float radius, int color, float opacity) {
        circle(canvas, x, y, radius, color, opacity * .18f);
        circle(canvas, x, y, radius * .62f, color, opacity * .3f);
    }

    private void drawScarlet(Canvas canvas, float time, float w, float h) {
        float breathe = .35f + .35f * pulse(progress(time, 5, 0));
        shaded.setShader(glow);
        shaded.setAlpha(Math.round(255 * breathe * alpha / 255f));
        canvas.drawRect(-.3f * w, .7f * h, 1.3f * w, 1.25f * h, shaded);
        float rise = 680 / DESIGN_HEIGHT * h;
        for (Particle p : particles) {
            float t = progress(time, p.duration, p.delay);
            if (t < 0) continue;
            float opacity = t < .1f ? t / .1f : 1 - (t - .1f) / .9f;
            float scale = 1 - .7f * t;
            float x = p.x * w + p.dx * dp * t + p.size * dp / 2;
            float y = h + 10 * dp - p.size * dp / 2 - rise * t;
            float radius = p.size * dp / 2 * scale;
            halo(canvas, x, y, radius + 4 * dp * scale, tint(0xCCE5242E), opacity);
            circle(canvas, x, y, radius, tint(p.color), opacity);
        }
    }

    private void drawCobalt(Canvas canvas, float time, float w, float h) {
        for (Particle p : particles) {
            float t = progress(time, p.duration, p.delay);
            if (t < 0) continue;
            float f = pulse(t);
            float opacity = .15f + .85f * f, scale = .6f + .4f * f;
            float x = p.x * w + p.size * dp / 2, y = p.y * h + p.size * dp / 2;
            float radius = p.size * dp / 2 * scale;
            halo(canvas, x, y, radius + 3 * dp * scale, tint(0xB378AAFF), opacity);
            circle(canvas, x, y, radius, tint(0xFFCFE0FF), opacity);
        }
        shaded.setShader(meteor);
        for (int i = 0; i < 2; i++) {
            float t = progress(time, 8 + i * 3, 1 + i * 4);
            if (t < 0 || t > .12f) continue;
            float opacity = t < .02f ? t / .02f : 1 - (t - .02f) / .1f;
            float move = t / .12f;
            float x = (.70f + i * .22f) * w + 45 * dp - 260 * dp * move;
            float y = (.06f + i * .14f) * h + .75f * dp + 180 * dp * move;
            shaded.setAlpha(Math.round(255 * opacity * alpha / 255f));
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate(-35);
            rect.set(-45 * dp, -.75f * dp, 45 * dp, .75f * dp);
            canvas.drawRoundRect(rect, dp, dp, shaded);
            canvas.restore();
        }
    }

    private void drawMint(Canvas canvas, float time, float w, float h) {
        for (Particle p : particles) {
            float t = progress(time, p.duration, p.delay);
            float g = progress(time, p.duration2, p.delay2);
            if (t < 0 || g < 0) continue;
            // Keyframes (0,0) → (a,b) → (c,d) → (b,a) → (0,0), eased on each quarter.
            float[] xs = {0, p.a, p.c, p.b, 0}, ys = {0, p.b, p.d, p.a, 0};
            int segment = Math.min(3, (int) (t * 4));
            float local = ease(t * 4 - segment);
            float dx = xs[segment] + (xs[segment + 1] - xs[segment]) * local;
            float dy = ys[segment] + (ys[segment + 1] - ys[segment]) * local;
            float opacity = .1f + .85f * pulse(g);
            float x = p.x * w + (dx + 2) * dp, y = p.y * h + (dy + 2) * dp;
            halo(canvas, x, y, 9 * dp, tint(0x8C3EE0A0), opacity);
            circle(canvas, x, y, 2 * dp, tint(0xFF7AF5C4), opacity);
        }
    }

    private void drawSakura(Canvas canvas, float time, float w, float h) {
        float fall = 680 / DESIGN_HEIGHT * h + 40 * dp;
        shaded.setShader(petalShader);
        for (Particle p : particles) {
            float t = progress(time, p.duration, p.delay);
            float s = progress(time, p.duration2, p.delay2);
            if (t < 0 || s < 0) continue;
            float f = pulse(s);
            float size = p.size * dp;
            float x = p.x * w + (-18 + 36 * f) * dp;
            float y = -40 * dp + fall * t;
            shaded.setAlpha(Math.round(255 * p.opacity * alpha / 255f));
            canvas.save();
            canvas.translate(x + size / 2, y + size * .375f);
            canvas.rotate(-30 + 80 * f);
            canvas.scale(size, size);
            canvas.translate(-.5f, -.375f);
            canvas.drawPath(petal, shaded);
            canvas.restore();
        }
    }

    private void drawLime(Canvas canvas, float time, float w, float h) {
        float scanY = -10 * dp + (620 / DESIGN_HEIGHT * h + 10 * dp) * progress(time, 5, 0);
        shaded.setShader(scan);
        shaded.setAlpha(alpha);
        canvas.drawRect(0, scanY, w, scanY + Math.max(1, dp), shaded);
        float rise = 700 / DESIGN_HEIGHT * h;
        paint.setShader(null);
        for (Particle p : particles) {
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
                paint.setColor(withAlpha(tint(0x80A6FF3B), opacity * alpha / 255f));
                canvas.drawRect(-half, -half, half, half, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.5f * dp);
            paint.setColor(withAlpha(tint(0xFFA6FF3B), opacity * alpha / 255f));
            canvas.drawRect(-half, -half, half, half, paint);
            canvas.restore();
        }
        paint.setStyle(Paint.Style.FILL);
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return alpha;
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
