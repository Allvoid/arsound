package app.revanced.extension.soundcloud.player;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import app.revanced.extension.shared.Logger;

/**
 * The end of the sleep timer: over its last {@link #FADE_MS} the player's volume goes down smoothly to silence, the
 * music pauses, and a moment later the volume is back, so the next play starts at full volume. The volume is the
 * player's ({@link PlayerVolume}), as in the smooth transition, not the phone's.
 * <p>
 * Turned off or moved while it fades, the timer brings the volume back up over {@link #RISE_MS} rather than at once.
 * {@link SleepTimerScheduler} decides when it starts; this class only follows the clock. Main thread.
 */
final class SleepFade {
    /** How long before the stop the volume starts to go down. */
    static final long FADE_MS = 45_000;
    /** How long the volume takes to come back when the fade is called off. */
    static final long RISE_MS = 1_500;
    private static final long TICK_MS = 200;
    /** How long the silence is kept after the pause key, so the pause takes hold before full volume is back. */
    private static final long RESTORE_AFTER_PAUSE_MS = 1_500;

    /** Made on first use, so the curves below can be tested without Android. */
    private static Handler handler;
    private static final Runnable TICK = SleepFade::tick;
    private static final Runnable RESTORE = () -> PlayerVolume.post(PlayerVolume.Source.SLEEP, 1f);

    /** What the volume does now: 0 nothing, going down to {@link #endAt}, or coming back up from {@link #riseFrom}. */
    private static int state;
    private static final int IDLE = 0, FADING = 1, RISING = 2;
    /** Fading: when the music stops, in {@link System#currentTimeMillis()}, and how long the whole fade is. */
    private static long endAt, length;
    /** Rising: the level it starts from and when, in {@link SystemClock#uptimeMillis()}. */
    private static float riseFrom;
    private static long riseStart;

    private SleepFade() {
    }

    private static Handler handler() {
        if (handler == null) handler = new Handler(Looper.getMainLooper());
        return handler;
    }

    /**
     * Starts the fade towards {@code stopAt}. Started late (the timer set for less than the fade, or the app opened
     * in the middle of it), it fades over the time that is left, from full volume.
     */
    static void start(long stopAt) {
        long now = System.currentTimeMillis();
        handler().removeCallbacks(RESTORE);
        endAt = stopAt;
        length = Math.max(1, Math.min(FADE_MS, stopAt - now));
        state = FADING;
        Logger.printInfo(() -> "Sleep timer: the music fades out over " + length / 1000 + " s");
        handler().removeCallbacks(TICK);
        handler().post(TICK);
    }

    static boolean isFading() {
        return state == FADING;
    }

    /** The time is up and the music is being paused: silence until the pause has taken hold, then full volume. */
    static void finish() {
        handler().removeCallbacks(TICK);
        boolean faded = state == FADING;
        state = IDLE;
        if (faded) PlayerVolume.post(PlayerVolume.Source.SLEEP, 0f);
        handler().removeCallbacks(RESTORE);
        handler().postDelayed(RESTORE, RESTORE_AFTER_PAUSE_MS);
    }

    /** The timer was turned off, moved or the fade switched off: the volume comes back up smoothly. */
    static void cancel() {
        handler().removeCallbacks(TICK);
        handler().removeCallbacks(RESTORE);
        float current = PlayerVolume.level(PlayerVolume.Source.SLEEP);
        if (current >= 1f) {
            state = IDLE;
            return;
        }
        riseFrom = current;
        riseStart = SystemClock.uptimeMillis();
        state = RISING;
        Logger.printInfo(() -> "Sleep timer: the fade is called off, the volume comes back from " + current);
        handler().post(TICK);
    }

    private static void tick() {
        float level;
        boolean again;
        if (state == FADING) {
            long left = endAt - System.currentTimeMillis();
            level = fadeLevel(left, length);
            // Kept on at silence until the timer fires: SoundCloud sets full volume when the next track starts.
            again = true;
        } else if (state == RISING) {
            level = riseLevel(riseFrom, SystemClock.uptimeMillis() - riseStart, RISE_MS);
            again = level < 1f;
            if (!again) state = IDLE;
        } else {
            return;
        }
        PlayerVolume.post(PlayerVolume.Source.SLEEP, level);
        if (again) handler().postDelayed(TICK, TICK_MS);
    }

    /**
     * The level with {@code left} milliseconds to the stop in a fade of {@code length}: full before the fade, silence
     * at the stop, and the square of the way in between, as in the smooth transition, so it sounds even rather than
     * sudden at the quiet end.
     */
    static float fadeLevel(long left, long length) {
        if (left <= 0) return 0f;
        if (left >= length) return 1f;
        float share = left / (float) length;
        return share * share;
    }

    /** The level {@code passed} milliseconds into a rise of {@code length} from {@code from}: straight up to full. */
    static float riseLevel(float from, long passed, long length) {
        if (passed >= length) return 1f;
        float share = Math.max(0, passed) / (float) length;
        return Math.min(1f, from + (1f - from) * share);
    }
}
