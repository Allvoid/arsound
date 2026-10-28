package app.revanced.extension.soundcloud.theme;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;

import app.revanced.extension.soundcloud.theme.effect.EffectBrush;
import app.revanced.extension.soundcloud.theme.effect.EffectKind;
import app.revanced.extension.soundcloud.theme.effect.EffectScene;

/**
 * The animated background of a theme, after the design "Анимации тем": sparks rising for «Алый», a night sky
 * with meteors for «Кобальт», fireflies for «Мята», falling petals for «Сакура», a scanner line with rising
 * squares for «Лайм», snowfall under a frozen top edge for «Зима». What each of them draws is an
 * {@link EffectScene} of the {@link EffectKind}.
 * <p>
 * A drawable, so it can lie under a screen (Arsound settings), over one (the main screen, where SoundCloud's
 * backgrounds are opaque) or in a small preview tile. Every particle is computed from the time alone, like a CSS
 * keyframe animation, so nothing piles up between frames. It runs only while visible, and stands still in battery
 * saver mode or when Android's animations are turned off.
 */
public final class ThemeEffect extends Drawable implements Runnable {
    /**
     * Time between frames: one refresh of the screen at its highest rate, so on a 120 Hz screen the particles move
     * as smoothly as the rest of the app. A fixed 16 ms held them at 60 frames a second there.
     */
    private final long frameMs;

    /** Kept to look again at battery saver and Android's animations whenever the effect comes back on screen. */
    private final Context appContext;
    private final EffectBrush brush;
    private final EffectScene scene;
    private boolean still;
    /** True once the scene was told a size it can draw in. */
    private boolean laidOut;
    private final long start = SystemClock.uptimeMillis();

    /**
     * @param kind      Effect id, the theme id by default ({@link ArsoundTheme#effect}).
     * @param sizeScale 1 on a whole screen, less in a preview tile.
     * @param density   How many particles, 1 as in the design.
     * @param night     The dark look: the design's colours. The light look draws everything in the accent.
     * @return Null for a theme without an effect.
     */
    public static ThemeEffect create(Context context, String kind, float sizeScale, float density, boolean night, int accent) {
        EffectKind effect = EffectKind.fromId(kind);
        return effect == null ? null : new ThemeEffect(context, effect, sizeScale, density, night, accent);
    }

    /** Whether the theme has an effect, for the settings. */
    public static boolean exists(String kind) {
        return EffectKind.fromId(kind) != null;
    }

    private ThemeEffect(Context context, EffectKind kind, float sizeScale, float density, boolean night, int accent) {
        Context application = context.getApplicationContext();
        this.appContext = application != null ? application : context;
        this.brush = new EffectBrush(context.getResources().getDisplayMetrics().density * sizeScale, night, accent);
        this.still = isStill(context);
        this.frameMs = frameMs(context);
        this.scene = kind.scene(brush, density);
    }

    /**
     * Stops or restarts the animation after battery saver or Android's animations were switched while it was on
     * screen. Without this it stayed as it was made until the app restarted.
     */
    public void refreshStill(Context context) {
        boolean now = isStill(context);
        if (now == still) return;
        still = now;
        invalidateSelf();
    }

    /** One refresh at the screen's highest supported rate, in whole milliseconds, at least 4 (250 Hz). */
    private static long frameMs(Context context) {
        float rate = 60f;
        try {
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            Display display = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
            if (display != null) {
                for (Display.Mode mode : display.getSupportedModes()) {
                    rate = Math.max(rate, mode.getRefreshRate());
                }
            }
        } catch (Exception ignored) {
            // The usual 60 Hz.
        }
        // A little under one refresh, so the next frame is asked for before its refresh, not one refresh late.
        return Math.max(4, (long) Math.floor(1000f / rate) - 1);
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

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        scene.layout(bounds);
        laidOut = true;
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.isEmpty() || !laidOut) return;
        float time = (SystemClock.uptimeMillis() - start) / 1000f;
        canvas.save();
        canvas.clipRect(bounds);
        canvas.translate(bounds.left, bounds.top);
        scene.draw(canvas, time, bounds.width(), bounds.height());
        canvas.restore();
        if (!still && isVisible()) {
            unscheduleSelf(this);
            scheduleSelf(this, SystemClock.uptimeMillis() + frameMs);
        }
    }

    @Override
    public void run() {
        invalidateSelf();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (!visible) {
            unscheduleSelf(this);
        } else {
            // Battery saver or Android's animations may have been switched while the effect was off screen.
            refreshStill(appContext);
            if (changed) invalidateSelf();
        }
        return changed;
    }

    @Override
    public void setAlpha(int alpha) {
        brush.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public int getAlpha() {
        return brush.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
