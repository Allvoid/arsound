package app.revanced.extension.soundcloud.player;

import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * The sheet is never left "moving" for longer than this after its last state callback, whatever happened to
     * them. A held finger sends no callbacks, and nothing in the sheet's frames tells that it is still held, so the
     * time is long: a drag held for a minute is not a drag people make. A sheet whose screen was rebuilt in the
     * middle of a slide is not waited for that long: {@link #isBusy()} sees that the sheet is gone, and the
     * rebuilt screen's end is told by {@link #reset()}.
     */
    private static final long WATCHDOG_MS = 60_000;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable release = () -> setMoving(false);

    /** Main thread only. */
    private static boolean moving;
    /** The last state the sheet reported; main thread only. A screen that starts with the player open tells it too. */
    private static int state = COLLAPSED;
    /** Work that waits for the slide to end, in the order it came; main thread only. */
    private static final List<Runnable> afterSlide = new ArrayList<>();
    /**
     * The sheet that reported last. A screen that is rebuilt while the sheet slides (a turn, a fold) never gets the
     * final state of that slide from the new sheet, which is made already at rest, so the flag must not outlive the
     * sheet it was set for.
     */
    private static WeakReference<View> sheetRef = new WeakReference<>(null);

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
        if (!moving) return false;
        if (!sheetAlive()) {
            // The screen of the slide is gone and its final state will not come.
            setMoving(false);
            return false;
        }
        return isSmoothEnabled();
    }

    /** Whether the full player is open and at rest: it covers the whole screen then. */
    public static boolean isExpanded() {
        return state == EXPANDED && !isBusy() && sheetAlive();
    }

    /**
     * Runs the work now if the sheet is at rest, or when the slide is over. For work a frame of the slide cannot afford,
     * such as building many views. Main thread only.
     */
    public static void afterSlide(Runnable work) {
        if (!isBusy()) {
            work.run();
            return;
        }
        afterSlide.add(work);
    }

    /**
     * Called when the main screen is destroyed. A slide that was running on it is over: the new screen starts at
     * rest, and the frames of the old window are of no use to anyone.
     */
    public static void reset() {
        setMoving(false);
        SlideJank.abort();
    }

    private static boolean sheetAlive() {
        View sheet = sheetRef.get();
        return sheet != null && sheet.isAttachedToWindow();
    }

    /** Injection point. Called when the player sheet changes its state, before SoundCloud handles it. */
    public static void onSheetState(int state, Object sheet) {
        try {
            if (sheet instanceof View) sheetRef = new WeakReference<>((View) sheet);
            SlideJank.onSheetState(state, sheet);
            boolean slides = state == DRAGGING || state == SETTLING;
            if (slides) {
                // Every state of a slide proves that the sheet is still moving.
                handler.removeCallbacks(release);
                handler.postDelayed(release, WATCHDOG_MS);
            }
            PlayerSheet.state = state;
            setMoving(slides);
            PlayerArtworkLayers.sheetRested(state);
            // Also at rest, where the moving flag does not change: the theme animation waits out the open player too.
            ThemeBackdrop.onSheet(isBusy(), isExpanded());
        } catch (Exception ex) {
            Logger.printException(() -> "Player sheet: state " + state + " not handled", ex);
        }
    }

    private static void setMoving(boolean now) {
        if (!now) handler.removeCallbacks(release);
        if (moving == now) return;
        moving = now;
        // The theme animation draws over the player; it waits for the slide and goes on at rest.
        ThemeBackdrop.onSheet(isBusy(), isExpanded());
        if (!now) runAfterSlide();
    }

    private static void runAfterSlide() {
        if (afterSlide.isEmpty()) return;
        // Not inside the frame where the sheet came to rest: that one already draws the player in its new look.
        final List<Runnable> due = new ArrayList<>(afterSlide);
        afterSlide.clear();
        handler.post(() -> {
            for (Runnable work : due) {
                try {
                    work.run();
                } catch (Exception ex) {
                    Logger.printException(() -> "Player sheet: work after the slide failed", ex);
                }
            }
        });
    }
}
