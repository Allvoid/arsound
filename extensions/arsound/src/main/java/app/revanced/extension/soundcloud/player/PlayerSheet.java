package app.revanced.extension.soundcloud.player;

import android.os.Handler;
import android.os.Looper;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.debug.SlideJank;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.theme.ThemeBackdrop;

/**
 * Follows SoundCloud's player sheet: the bottom sheet that is the collapsed player bar and, pulled up, the full
 * player. While it is dragged or settles, the whole screen is laid out and drawn on every frame, and anything else
 * that wants to redraw in those milliseconds makes the slide stutter. So Arsound's own per-frame work asks
 * {@link #isBusy()} and gives way.
 * <p>
 * Also the one place that knows whether the "Smooth player collapse" setting is on.
 */
public final class PlayerSheet {
    /** The states of the sheet, those of the material BottomSheetBehavior. */
    public static final int DRAGGING = 1, SETTLING = 2, EXPANDED = 3, COLLAPSED = 4;

    /** The sheet is never left "moving" for longer than this, whatever happened to the state callbacks. */
    private static final long WATCHDOG_MS = 15_000;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable release = () -> setMoving(false);

    /** Main thread only. */
    private static boolean moving;

    private PlayerSheet() {
    }

    /** Whether Arsound keeps the player from needless work while it slides. On by default. */
    public static boolean isSmoothEnabled() {
        return Settings.isSmoothPlayerCollapseEnabled();
    }

    /**
     * True while the sheet is dragged or settles and the smooth collapse is on: the time when Arsound's
     * own work that is not needed this frame waits.
     */
    public static boolean isBusy() {
        return moving && isSmoothEnabled();
    }

    /** Injection point. Called when the player sheet changes its state, before SoundCloud handles it. */
    public static void onSheetState(int state, Object sheet) {
        try {
            SlideJank.onSheetState(state, sheet);
            boolean slides = state == DRAGGING || state == SETTLING;
            if (slides) {
                // Every state of a slide proves that the sheet is still moving.
                handler.removeCallbacks(release);
                handler.postDelayed(release, WATCHDOG_MS);
            }
            setMoving(slides);
        } catch (Exception ex) {
            Logger.printException(() -> "Player sheet: state " + state + " not handled", ex);
        }
    }

    private static void setMoving(boolean now) {
        if (!now) handler.removeCallbacks(release);
        if (moving == now) return;
        moving = now;
        // The theme animation draws over the player; it waits for the slide and goes on at rest.
        ThemeBackdrop.setSheetBusy(isBusy());
    }
}
