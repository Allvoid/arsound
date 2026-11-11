package app.revanced.extension.soundcloud.player;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.recommendations.MyWave;
import app.revanced.extension.soundcloud.recommendations.WaveMemory;

/**
 * Injection points of SoundCloud's player that more than one feature follows: an item starts, the player reports its
 * position. They are handed on to the smooth transition ({@link Crossfade}) and the loading of the next track
 * ({@link NextTrackPreload}).
 */
@SuppressWarnings("unused")
public final class PlaybackEvents {
    /** The urn of the item that started last; a resume of it is not a new track. */
    private static volatile String playingUrn;
    /** A listener that keeps failing is logged this often, not twice a second. */
    private static final long REPORT_EVERY_MS = 30_000;
    private static volatile long lastReportAt;

    private PlaybackEvents() {
    }

    /** The urn of the item that started last, or null before any. */
    public static String playingUrn() {
        return playingUrn;
    }

    /** Injection point: start of {@code BaseExoPlayer.f(PlaybackItem)}, an item starts or resumes. Player thread. */
    public static void onPlay(Object exoPlayer, Object item) {
        String urn;
        try {
            urn = String.valueOf(item.getClass().getMethod("h").invoke(item));
        } catch (Exception ex) {
            Logger.printException(() -> "Playback events: the item has no urn", ex);
            return;
        }
        boolean newTrack = !urn.equals(playingUrn);
        playingUrn = urn;
        // Each listener on its own: one that fails must not stop the others, and nothing may reach the player,
        // where an exception closes the app.
        try {
            Crossfade.onPlay(exoPlayer, newTrack);
        } catch (Throwable ex) {
            failed("crossfade, start", ex);
        }
        if (newTrack) {
            try {
                NextTrackPreload.onTrackChanged();
            } catch (Throwable ex) {
                failed("preload, track change", ex);
            }
            try {
                MyWave.onTrackChanged(urn);
            } catch (Throwable ex) {
                failed("My Wave, track change", ex);
            }
        }
        try {
            SlowedPlayback.onPlay(exoPlayer, urn);
        } catch (Throwable ex) {
            failed("speed, start", ex);
        }
    }

    /** Injection point: start of {@code LocalPlayback.i(ProgressChangeEvent)}, about twice a second while playing. */
    public static void onProgress(long position, long duration) {
        try {
            Crossfade.onProgress(position, duration);
        } catch (Throwable ex) {
            failed("crossfade, progress", ex);
        }
        try {
            SlowedPlayback.reassert();
        } catch (Throwable ex) {
            failed("speed, progress", ex);
        }
        try {
            NextTrackPreload.onProgress(position, duration);
        } catch (Throwable ex) {
            failed("preload, progress", ex);
        }
        try {
            WaveMemory.onProgress(duration);
        } catch (Throwable ex) {
            failed("My Wave, progress", ex);
        }
    }

    /** Logs a listener's failure, not more often than {@link #REPORT_EVERY_MS}: the progress comes twice a second. */
    private static void failed(String listener, Throwable ex) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastReportAt < REPORT_EVERY_MS && lastReportAt != 0) return;
        lastReportAt = now;
        Logger.printException(() -> "Playback events: " + listener + " failed", ex);
    }
}
