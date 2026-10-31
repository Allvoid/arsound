package app.revanced.extension.soundcloud.debug;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;
import android.view.FrameMetrics;
import android.view.View;
import android.view.Window;

import java.util.Arrays;
import java.util.Locale;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.debug.PlaybackLog;
import app.revanced.extension.soundcloud.player.PlayerSheet;

/**
 * Measures how smooth the player sheet is while it is pulled up and down, and writes one line per slide to the
 * playback log, so the log a user saves with "Save logs" tells where a stutter comes from: which part of the
 * frame took the time (the layout, the drawing, handing the work to the graphics chip, the chip itself).
 * <p>
 * Costs nothing at rest: the frame statistics are asked for only between the start of a slide and a moment after
 * its end, and only while the playback log is on.
 */
public final class SlideJank {
    private static final int MAX_FRAMES = 240;
    /** The last frame of a slide is reported a little after the sheet's final state. */
    private static final long FINISH_DELAY_MS = 250;
    /**
     * A slide that never reached a final state (the screen went away) is closed this long after its last state.
     * Long, because a finger that holds the sheet still sends nothing: a shorter time would cut one gesture into
     * two lines.
     */
    private static final long WATCHDOG_MS = 60_000;

    /** The parts of a frame that are told apart, as the log names them. */
    private static final String[] NAMES = {"anim", "layout", "draw", "sync", "issue", "swap", "gpu", "late"};

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static HandlerThread thread;
    private static Handler worker;

    /** The slide being measured, main thread only. */
    private static Slide current;
    /** The state the sheet last rested in, 0 before it is known. */
    private static int rest;

    private SlideJank() {
    }

    /**
     * Starts the thread the frames are told on, without waiting for it: the first slide asked for its looper and made
     * the main thread wait for the thread to come up. Called at every state of the sheet (the first ones come when the
     * screen opens, long before a finger drags it).
     */
    private static void prepareWorker() {
        if (thread != null) return;
        thread = new HandlerThread("arsound-slide-jank", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.setDaemon(true);
        thread.start();
    }

    /** Called by {@link PlayerSheet} for every state of the sheet, on the main thread. */
    public static void onSheetState(int state, Object sheet) {
        if (!PlaybackLog.enabled) return;
        try {
            prepareWorker();
            if (state == PlayerSheet.DRAGGING || state == PlayerSheet.SETTLING) {
                if (current != null && current.ending) finish(current);
                // A slide of another window is over: its screen was rebuilt (a turn, a fold) and this sheet is new.
                if (current != null && sheet instanceof View && !isOnWindow(current, (View) sheet)) finish(current);
                if (current == null && sheet instanceof View) start((View) sheet);
                else if (current != null) {
                    // Dragging, then settling: the same slide, still alive.
                    main.removeCallbacks(current.finish);
                    main.postDelayed(current.finish, WATCHDOG_MS);
                }
                return;
            }
            // Expanded, collapsed or hidden: the slide is over, its last frame is still to come.
            Slide slide = current;
            if (slide != null && !slide.ending) {
                slide.ending = true;
                slide.to = state;
                main.postDelayed(slide.finish, FINISH_DELAY_MS);
            }
            if (state == PlayerSheet.EXPANDED || state == PlayerSheet.COLLAPSED) rest = state;
        } catch (Exception ex) {
            Logger.printException(() -> "Slide jank: state " + state + " not handled", ex);
        }
    }

    /**
     * Called when the main screen is destroyed in the middle of a slide: its window and the frames it measured are
     * dropped, not logged, since a rebuilt screen is not a slide to judge. The new sheet starts at rest.
     */
    public static void abort() {
        Slide slide = current;
        if (slide == null) return;
        current = null;
        main.removeCallbacks(slide.finish);
        try {
            slide.window.removeOnFrameMetricsAvailableListener(slide);
        } catch (Exception ignored) {
            // The window may be gone already.
        }
        // Where the new sheet rests is not what the old one rested in before this slide.
        rest = 0;
    }

    private static boolean isOnWindow(Slide slide, View sheet) {
        Activity activity = activityOf(sheet.getContext());
        // Not knowing the window is not a reason to cut a slide.
        return activity == null || activity.getWindow() == slide.window;
    }

    private static void start(View sheet) {
        Activity activity = activityOf(sheet.getContext());
        if (activity == null) return;
        Window window = activity.getWindow();
        // The rate the screen runs at now and the slowest and fastest it can run at. The rate is only a start: a
        // phone that adapts its rate may change it after the slide began, and the frames tell what it was.
        float rate = 60f, lowest = rate, highest = rate;
        try {
            //noinspection deprecation
            Display display = activity.getWindowManager().getDefaultDisplay();
            rate = lowest = highest = display.getRefreshRate();
            for (Display.Mode mode : display.getSupportedModes()) {
                lowest = Math.min(lowest, mode.getRefreshRate());
                highest = Math.max(highest, mode.getRefreshRate());
            }
        } catch (Exception ignored) {
            // The usual 60 Hz, or what was read before the failure.
        }
        Slide slide = new Slide(window, rest, PlayerSheet.isSmoothEnabled(), rate, lowest, highest);
        if (worker == null) {
            prepareWorker();
            // Started at an earlier state of the sheet, so its looper is ready by now.
            worker = new Handler(thread.getLooper());
        }
        window.addOnFrameMetricsAvailableListener(slide, worker);
        current = slide;
        main.postDelayed(slide.finish, WATCHDOG_MS);
    }

    private static void finish(Slide slide) {
        main.removeCallbacks(slide.finish);
        if (current == slide) current = null;
        try {
            slide.window.removeOnFrameMetricsAvailableListener(slide);
        } catch (Exception ignored) {
            // The window may be gone already.
        }
        String line = slide.summary();
        if (line != null) PlaybackLog.append("jank", line);
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** The frames of one slide. The frame statistics come on the worker thread, the rest happens on the main one. */
    private static final class Slide implements Window.OnFrameMetricsAvailableListener {
        final Window window;
        final int from;
        final boolean smooth;
        /** The rate of the screen when the slide began, and the slowest and fastest rate it has. */
        final float displayRate, lowestRate, highestRate;
        final long startedAt = android.os.SystemClock.uptimeMillis();
        final Runnable finish = () -> SlideJank.finish(this);

        /** Main thread. */
        boolean ending;
        int to;

        private final float[] total = new float[MAX_FRAMES];
        private final float[][] parts = new float[NAMES.length][MAX_FRAMES];
        private final long[] vsync = new long[MAX_FRAMES];
        private int frames;
        /** Every frame of the slide, also those past {@link #MAX_FRAMES}. */
        private int seen;
        private boolean done;

        Slide(Window window, int from, boolean smooth, float displayRate, float lowestRate, float highestRate) {
            this.window = window;
            this.from = from;
            this.smooth = smooth;
            this.displayRate = displayRate;
            this.lowestRate = lowestRate;
            this.highestRate = highestRate;
        }

        @Override
        public synchronized void onFrameMetricsAvailable(Window ignored, FrameMetrics metrics, int dropCount) {
            if (done) return;
            seen++;
            // The frames past the room for them are counted, so the line says it describes only the first ones.
            if (frames >= MAX_FRAMES) return;
            int frame = frames++;
            total[frame] = ms(metrics, FrameMetrics.TOTAL_DURATION);
            parts[0][frame] = ms(metrics, FrameMetrics.ANIMATION_DURATION);
            parts[1][frame] = ms(metrics, FrameMetrics.LAYOUT_MEASURE_DURATION);
            parts[2][frame] = ms(metrics, FrameMetrics.DRAW_DURATION);
            parts[3][frame] = ms(metrics, FrameMetrics.SYNC_DURATION);
            parts[4][frame] = ms(metrics, FrameMetrics.COMMAND_ISSUE_DURATION);
            parts[5][frame] = ms(metrics, FrameMetrics.SWAP_BUFFERS_DURATION);
            // The time the chip itself took is reported from Android 12.
            parts[6][frame] = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? ms(metrics, FrameMetrics.GPU_DURATION) : 0f;
            parts[7][frame] = ms(metrics, FrameMetrics.UNKNOWN_DELAY_DURATION);
            vsync[frame] = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP);
        }

        private static float ms(FrameMetrics metrics, int metric) {
            return metrics.getMetric(metric) / 1_000_000f;
        }

        /** The line for the log, or null when no frame was drawn. */
        synchronized String summary() {
            done = true;
            if (frames == 0) return null;

            // The time between two frames the system aimed for is one interval of the screen when the frames
            // followed each other, so the shortest gap is that interval. A slide of nothing but slow frames has
            // no short gap, which is why the result is kept within what the screen can run at.
            long shortest = Long.MAX_VALUE;
            for (int i = 1; i < frames; i++) {
                long gap = vsync[i] - vsync[i - 1];
                if (gap > 0) shortest = Math.min(shortest, gap);
            }
            boolean fromFrames = shortest != Long.MAX_VALUE;
            // The steps of the frames, which may be every second refresh (60 frames a second on a 120 Hz screen)...
            long cadence = Math.round(1_000_000_000d / displayRate);
            if (fromFrames) {
                cadence = Math.max(Math.round(1_000_000_000d / highestRate),
                        Math.min(Math.round(1_000_000_000d / lowestRate), shortest));
            }
            float rate = (float) (1_000_000_000d / cadence);
            // ...and the screen's own interval, which the frames over the budget and the missed ones are counted
            // against: a slide held at 60 frames on a 120 Hz screen misses every other refresh.
            long interval = Math.round(1_000_000_000d / displayRate);
            float budget = interval / 1_000_000f;

            float[] sorted = Arrays.copyOf(total, frames);
            Arrays.sort(sorted);
            int slow = 0;
            for (int i = 0; i < frames; i++) if (total[i] > budget) slow++;

            // Frames the screen did not get at all: the gaps between the frames the system aimed for.
            int missed = 0;
            for (int i = 1; i < frames; i++) {
                missed += Math.max(0, (int) Math.round((vsync[i] - vsync[i - 1]) / (double) interval) - 1);
            }
            float seconds = (vsync[frames - 1] - vsync[0]) / 1e9f;
            float fps = frames > 1 && seconds > 0 ? (frames - 1) / seconds : 0f;

            // The part that took the most on the frames over the budget, or on all of them when none was over.
            int worst = 0;
            float worstAverage = -1;
            StringBuilder detail = new StringBuilder();
            for (int part = 0; part < NAMES.length; part++) {
                float sum = 0, slowSum = 0, max = 0;
                for (int i = 0; i < frames; i++) {
                    sum += parts[part][i];
                    if (total[i] > budget) slowSum += parts[part][i];
                    max = Math.max(max, parts[part][i]);
                }
                float basis = slow > 0 ? slowSum / slow : sum / frames;
                if (basis > worstAverage) {
                    worstAverage = basis;
                    worst = part;
                }
                detail.append(String.format(Locale.US, " %s %.1f/%.1f", NAMES[part], sum / frames, max));
            }

            String counted = seen > frames ? frames + " of " + seen + " frames (the first ones)" : frames + " frames";
            return String.format(Locale.US,
                    "slide %s -> %s, smooth %s, cadence %.0f fps (%s), display %.0f Hz, %d ms: %s, %d over %.1f ms,"
                            + " missed %d, about %.0f fps; total median %.1f, p90 %.1f, max %.1f ms;"
                            + " average/max ms:%s; worst: %s",
                    stateName(from), stateName(to), smooth ? "on" : "off", rate,
                    fromFrames ? "frames" : "display", displayRate,
                    android.os.SystemClock.uptimeMillis() - startedAt, counted, slow, budget, missed, fps,
                    sorted[frames / 2], sorted[Math.min(frames - 1, (int) (frames * 0.9f))], sorted[frames - 1],
                    detail, NAMES[worst]);
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case PlayerSheet.EXPANDED:
                return "expanded";
            case PlayerSheet.COLLAPSED:
                return "collapsed";
            case 0:
                return "unknown";
            default:
                return "state " + state;
        }
    }
}
