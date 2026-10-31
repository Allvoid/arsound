package app.revanced.extension.soundcloud.theme;

import android.animation.ValueAnimator;
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
 * <p>
 * It is drawn at most {@link #SCREEN_FPS} frames a second on a screen and {@link #TILE_FPS} in a preview tile, not at
 * the display's top rate: a layer over the whole window redrawn 120 times a second kept a core of the phone busy at
 * rest, and the particles drift too slowly for the eye to tell the difference.
 */
public final class ThemeEffect extends Drawable implements Runnable {
    /** The most frames a second of an effect over or under a whole screen. */
    public static final int SCREEN_FPS = 60;
    /** The same for a preview tile: small, and several of them at once. */
    public static final int TILE_FPS = 30;

    /** The delay asked of the drawable's callback after a frame, so the next one comes a whole number of refreshes later. */
    private final long frameMs;

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
    public static ThemeEffect create(Context context, String kind, float sizeScale, float density, boolean night, int accent,
                                     int maxFps) {
        EffectKind effect = EffectKind.fromId(kind);
        return effect == null ? null : new ThemeEffect(context, effect, sizeScale, density, night, accent, maxFps);
    }

    /** Whether the theme has an effect, for the settings. */
    public static boolean exists(String kind) {
        return EffectKind.fromId(kind) != null;
    }

    private ThemeEffect(Context context, EffectKind kind, float sizeScale, float density, boolean night, int accent,
                        int maxFps) {
        this.brush = new EffectBrush(context.getResources().getDisplayMetrics().density * sizeScale, night, accent);
        this.still = isStill(context);
        this.frameMs = frameMs(context, maxFps);
        this.scene = kind.scene(brush, density);
        // The ice of «Зима» is drawn off the main thread and arrives later.
        brush.setRedraw(this::invalidateSelf);
    }

    /**
     * Stops or restarts the animation after battery saver or Android's animations were switched while it was on
     * screen. Without this it stayed as it was made until the app restarted. Called by {@link ThemeBackdrop}, which
     * hears of both switches.
     */
    public void refreshStill(Context context) {
        boolean now = isStill(context);
        if (now == still) return;
        still = now;
        invalidateSelf();
    }

    /**
     * The delay to ask for after a frame. The drawable's callback runs on the frame clock, which takes
     * {@link ValueAnimator#getFrameDelay()} (the frame delay of Choreographer) off any delay and then waits for the next refresh. Asking for half a refresh
     * less than a whole number of them puts the next frame right on the refresh it is meant for, whatever the moment
     * of the refresh this frame was drawn in.
     */
    private static long frameMs(Context context, int maxFps) {
        float refreshMs = 1000f / displayRate(context);
        int refreshes = Math.max(1, Math.round((1000f / maxFps) / refreshMs));
        return Math.max(1, Math.round(refreshes * refreshMs - refreshMs / 2 + ValueAnimator.getFrameDelay()));
    }

    /** The screen's highest supported rate, 60 Hz if it cannot be read. */
    private static float displayRate(Context context) {
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
        return rate;
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
        // Battery saver and Android's animations are not read here: this runs at the end of every slide of the player,
        // and ThemeBackdrop is told of both switches as they happen.
        if (!visible) unscheduleSelf(this);
        else if (changed) invalidateSelf();
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
