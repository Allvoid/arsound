package app.revanced.extension.soundcloud.debug;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
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
    /** A slide that never reached a final state (the screen went away) is closed after this. */
    private static final long WATCHDOG_MS = 8_000;

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

    /** Called by {@link PlayerSheet} for every state of the sheet, on the main thread. */
    public static void onSheetState(int state, Object sheet) {
        if (!PlaybackLog.enabled) return;
        try {
            if (state == PlayerSheet.DRAGGING || state == PlayerSheet.SETTLING) {
                if (current != null && current.ending) finish(current);
                if (current == null && sheet instanceof View) start((View) sheet);
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

    private static void start(View sheet) {
        Activity activity = activityOf(sheet.getContext());
        if (activity == null) return;
        Window window = activity.getWindow();
        float rate = 60f;
        try {
            //noinspection deprecation
            rate = activity.getWindowManager().getDefaultDisplay().getRefreshRate();
        } catch (Exception ignored) {
            // The usual 60 Hz.
        }
        Slide slide = new Slide(window, rest, PlayerSheet.isSmoothEnabled(), rate);
        if (worker == null) {
            thread = new HandlerThread("arsound-slide-jank", android.os.Process.THREAD_PRIORITY_BACKGROUND);
            thread.setDaemon(true);
            thread.start();
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
        final float refreshRate;
        final long startedAt = android.os.SystemClock.uptimeMillis();
        final Runnable finish = () -> SlideJank.finish(this);

        /** Main thread. */
        boolean ending;
        int to;

        private final float[] total = new float[MAX_FRAMES];
        private final float[][] parts = new float[NAMES.length][MAX_FRAMES];
        private final long[] vsync = new long[MAX_FRAMES];
        private int frames;
        private boolean done;

        Slide(Window window, int from, boolean smooth, float refreshRate) {
            this.window = window;
            this.from = from;
            this.smooth = smooth;
            this.refreshRate = refreshRate;
        }

        @Override
        public synchronized void onFrameMetricsAvailable(Window ignored, FrameMetrics metrics, int dropCount) {
            if (done || frames >= MAX_FRAMES) return;
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
            float budget = 1000f / refreshRate;

            float[] sorted = Arrays.copyOf(total, frames);
            Arrays.sort(sorted);
            int slow = 0;
            for (int i = 0; i < frames; i++) if (total[i] > budget) slow++;

            // Frames the screen did not get at all: the gaps between the frames the system aimed for.
            long interval = Math.round(1_000_000_000d / refreshRate);
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

            return String.format(Locale.US,
                    "slide %s -> %s, smooth %s, %.0f Hz, %d ms: %d frames, %d over %.1f ms, missed %d, about %.0f fps;"
                            + " total median %.1f, p90 %.1f, max %.1f ms; average/max ms:%s; worst: %s",
                    stateName(from), stateName(to), smooth ? "on" : "off", refreshRate,
                    android.os.SystemClock.uptimeMillis() - startedAt, frames, slow, budget, missed, fps,
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
