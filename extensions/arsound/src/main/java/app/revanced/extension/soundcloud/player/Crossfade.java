package app.revanced.extension.soundcloud.player;

import android.os.Handler;
import android.os.SystemClock;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.BoolSettings;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Smooth transition between tracks: the end of a track fades out, and the next track, when the first one ended by
 * itself, fades in. One after the other, not over each other: SoundCloud has one player. A track the user picks or
 * skips to starts at full volume.
 * <p>
 * The player reports its position about twice a second ({@link #onProgress}); in between it is counted from the
 * clock, and while a fade runs the volume is set every {@link #TICK_MS} on the player's own thread. The volume is
 * SoundCloud's player volume ({@link PlayerVolume}), shared with the sleep timer's fade.
 */
public final class Crossfade {
    private static final long TICK_MS = 40;
    /** A track shorter than three fades fades for a third of its length. */
    private static final int FADES_PER_TRACK = 3;
    /** How long after its last report the position is still counted on: longer means paused or stuck. */
    private static final long REPORT_GAP_MS = 1500;
    /** How close to its end the last track must have been for the next one to fade in: it ended by itself. */
    private static final long ENDED_SLACK_MS = 1500;
    /** How long a track that fades in is kept silent while it has not started yet. */
    private static final long START_WAIT_MS = 15_000;

    private static final Object lock = new Object();
    private static final Runnable TICK = Crossfade::tick;
    private static Handler handler;
    private static boolean fadingIn;
    /** Whether the playing track has reported a position yet: until then it is not heard, so no time is counted. */
    private static boolean reported;
    private static long position, duration, reportedAt;
    private Crossfade() {
    }

    public static boolean isEnabled() {
        return BoolSettings.CROSSFADE.get();
    }

    /** The length of one fade in milliseconds. */
    static long fadeMs() {
        return Math.max(1, Settings.getLong(Settings.CROSSFADE_SECONDS, 6)) * 1000;
    }

    /**
     * An item starts or resumes ({@link PlaybackEvents#onPlay}). Runs on the player's thread.
     *
     * @param newTrack False when the same track resumes: it keeps its fade.
     */
    static void onPlay(Object exoPlayer, boolean newTrack) {
        try {
            synchronized (lock) {
                PlayerVolume.bind(exoPlayer);
                handler = PlayerVolume.handler();
                if (!newTrack) return;
                boolean ended = duration > 0
                        && SystemClock.uptimeMillis() - reportedAt < REPORT_GAP_MS + ENDED_SLACK_MS
                        && duration - position <= fadeLength(duration) + ENDED_SLACK_MS;
                fadingIn = isEnabled() && ended;
                position = 0;
                duration = 0;
                reported = false;
                reportedAt = SystemClock.uptimeMillis();
            }
            // SoundCloud may set full volume while it starts the item: the volume is set right after it.
            handler.post(TICK);
        } catch (Throwable ex) {
            Logger.printException(() -> "Crossfade: could not follow the new track", ex);
        }
    }

    /** A position report of the player ({@link PlaybackEvents#onProgress}), about twice a second while it plays. */
    static void onProgress(long position, long duration) {
        Handler current;
        synchronized (lock) {
            Crossfade.position = position;
            Crossfade.duration = duration;
            reported = true;
            reportedAt = SystemClock.uptimeMillis();
            current = handler;
        }
        if (current != null) current.post(TICK);
    }

    /** Sets the volume for the counted position and comes back while a fade runs. Runs on the player's thread. */
    private static void tick() {
        float target;
        boolean again;
        synchronized (lock) {
            handler.removeCallbacks(TICK);
            long now = SystemClock.uptimeMillis();
            boolean reporting = now - reportedAt < REPORT_GAP_MS;
            long at = position + (reporting && reported ? now - reportedAt : 0);
            target = isEnabled() ? volumeAt(at, duration, fadingIn, fadeLength(duration)) : 1f;
            if (target >= 1f && at >= fadeLength(duration)) fadingIn = false;
            // A track that fades in may buffer for a while before its first report: its silence is kept until then.
            boolean starting = fadingIn && !reported && now - reportedAt < START_WAIT_MS;
            again = (reporting || starting) && target < 1f;
        }
        PlayerVolume.set(PlayerVolume.Source.CROSSFADE, target);
        if (again) handler.postDelayed(TICK, TICK_MS);
    }

    private static long fadeLength(long duration) {
        long fade = fadeMs();
        return duration > 0 ? Math.min(fade, duration / FADES_PER_TRACK) : fade;
    }

    /**
     * The volume at a place of a track: fading in from its start (when it fades in), fading out towards its end, full
     * in between. The fade follows the square of the way through it, so it sounds even rather than sudden at the
     * quiet end.
     *
     * @param duration 0 while the length is not known yet: then only the fade in applies.
     */
    static float volumeAt(long position, long duration, boolean fadeIn, long fade) {
        float level = 1f;
        if (fadeIn && position < fade) level = Math.min(level, position / (float) fade);
        if (duration > 0 && duration - position < fade) level = Math.min(level, (duration - position) / (float) fade);
        level = Math.max(0f, Math.min(1f, level));
        return level * level;
    }
}
