package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;

/**
 * The real overlap of the smooth transition (bot ticket #63): while the end of a track fades out in SoundCloud's
 * player, the beginning of the next one already fades in, in a second player of Arsound's own, so there is no silence
 * between them. When SoundCloud gets to the next track it starts it where the second player has got to
 * ({@link #startPosition}), at full volume, and the second player stops.
 * <p>
 * Only when the next track is on the phone: downloaded, imported or loaded ahead ({@link NextTrackPreload}); a track
 * from the cloud has no file the second player could open, and then the fade is the one after the other of
 * {@link Crossfade}. A skip, a pause or another track than the expected one stops the second player at once.
 */
public final class CrossfadeOverlap {
    private static final long TICK_MS = 40;
    /** The second player starts this much before the fade of the first, to be heard from the fade's start. */
    private static final long LEAD_MS = 250;
    /** How long the second player plays on after SoundCloud started the track, fading out under it. */
    private static final long HANDOFF_FADE_MS = 160;
    /** If SoundCloud does not report the next track in this time after the first ended, the second player stops. */
    private static final long HANDOFF_WAIT_MS = 6_000;

    private static final Object lock = new Object();
    private static Handler handler;
    private static MediaPlayer player;
    private static String trackId;
    /** Where the fade-out of the current track started (uptime) and how long the fade is. */
    private static long fadeStartAt, fadeMs;
    /** The current track's end is expected at this uptime. */
    private static long endAt;
    private static boolean starting;
    private static boolean handedOff;
    /** The last position report of SoundCloud's player: without them it is paused or stuck. */
    private static long reportedAt;
    /** Reports may stop this long before the end: the player is paused, the overlap stops. */
    private static final long SILENT_MS = 1_500;
    private static long handedOffAt;
    private static final Runnable TICK = CrossfadeOverlap::tick;

    private CrossfadeOverlap() {
    }

    private static Handler handler() {
        if (handler == null) {
            HandlerThread thread = new HandlerThread("arsound-crossfade-overlap");
            thread.start();
            handler = new Handler(thread.getLooper());
        }
        return handler;
    }

    /**
     * A position report of the current track. Starts the second player when the fade-out is about to begin.
     *
     * @param fade The length of the fade-out of this track.
     */
    static void onProgress(long position, long duration, long fade) {
        if (duration <= 0 || position <= 0) return;
        long left = duration - position;
        boolean seekedAway;
        synchronized (lock) {
            reportedAt = SystemClock.uptimeMillis();
            // A seek back out of the fade: the overlap is not wanted any more.
            seekedAway = player != null && !handedOff && left > fade + LEAD_MS + 1_500;
            if (seekedAway) starting = false;
            if (!seekedAway) endAt = SystemClock.uptimeMillis() + left;
        }
        if (seekedAway) {
            handler().post(CrossfadeOverlap::stop);
            return;
        }
        synchronized (lock) {
            if (player != null || starting) return;
            // Too late to be heard properly, or a track too short for a fade of its own.
            if (left > fade + LEAD_MS + 1_000 || left < fade / 2 || duration < 3 * fade) return;
            starting = true;
            long now = SystemClock.uptimeMillis();
            endAt = now + left;
            fadeMs = fade;
            fadeStartAt = endAt - fade;
        }
        handler().post(CrossfadeOverlap::start);
    }

    private static void start() {
        String next = NextTrackPreload.nextTrackId();
        String source = next == null ? null : DownloadTrackPatch.playableSource(next);
        Context context = Utils.getContext();
        if (source == null || context == null) {
            synchronized (lock) {
                // Not again for this track: the fade stays the one after the other.
                starting = true;
            }
            return;
        }
        MediaPlayer created = new MediaPlayer();
        try {
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            if (source.startsWith("/")) created.setDataSource(source);
            else created.setDataSource(context, Uri.parse(source));
            created.setVolume(0f, 0f);
            created.prepare();
            long wait;
            synchronized (lock) {
                wait = fadeStartAt - SystemClock.uptimeMillis();
            }
            // Started when the fade-out starts: prepare took its time already.
            if (wait > 0) SystemClock.sleep(Math.min(wait, 3_000));
            synchronized (lock) {
                if (!starting) {
                    created.release();
                    return;
                }
                created.start();
                player = created;
                trackId = next;
                handedOff = false;
            }
            Logger.printInfo(() -> "Crossfade: the next track " + next + " fades in over the end of this one");
            handler().post(TICK);
        } catch (Exception ex) {
            created.release();
            Logger.printInfo(() -> "Crossfade: the next track cannot be played ahead (" + ex + "), one after the other");
        }
    }

    /** Raises the second player while the first fades out; after the handoff, lowers it under SoundCloud's player. */
    private static void tick() {
        MediaPlayer current;
        float volume;
        boolean again = true;
        synchronized (lock) {
            current = player;
            if (current == null) return;
            long now = SystemClock.uptimeMillis();
            if (handedOff) {
                float left = 1f - (now - handedOffAt) / (float) HANDOFF_FADE_MS;
                volume = Math.max(0f, left);
                if (left <= 0f) again = false;
            } else {
                if (now - reportedAt > SILENT_MS && endAt - now > SILENT_MS) {
                    Logger.printInfo(() -> "Crossfade: the track was paused, the overlap stops");
                    again = false;
                    volume = 0f;
                } else if (now - endAt > HANDOFF_WAIT_MS) {
                    Logger.printInfo(() -> "Crossfade: SoundCloud did not go on to the next track, the overlap stops");
                    again = false;
                    volume = 0f;
                } else {
                    float through = (now - fadeStartAt) / (float) Math.max(1, fadeMs);
                    float level = Math.max(0f, Math.min(1f, through));
                    volume = level * level;
                }
            }
        }
        try {
            current.setVolume(volume, volume);
        } catch (Exception ignored) {
            // Released meanwhile.
        }
        if (again) handler().postDelayed(TICK, TICK_MS);
        else stop();
    }

    /**
     * SoundCloud is about to start a track: where it should start it. The track the second player plays starts where
     * that one has got to, so it goes on without a break; any other track as asked.
     *
     * @param trackId  The id of the track SoundCloud starts.
     * @param position The position SoundCloud asks for.
     */
    public static long startPosition(String trackId, long position) {
        synchronized (lock) {
            if (player == null || handedOff || trackId == null || !trackId.equals(CrossfadeOverlap.trackId)) return position;
            try {
                long at = player.getCurrentPosition();
                Logger.printInfo(() -> "Crossfade: SoundCloud starts the next track at " + at + " ms, where the overlap is");
                return at;
            } catch (Exception ex) {
                return position;
            }
        }
    }

    /**
     * A track starts in SoundCloud's player. The expected one takes over from the second player, which fades out under
     * it; any other stops it.
     *
     * @return Whether the track goes on from the overlap: then it starts at full volume, it already faded in.
     */
    static boolean onPlay(String trackId, boolean newTrack) {
        synchronized (lock) {
            if (!newTrack) return false;
            boolean expected = player != null && !handedOff && trackId != null && trackId.equals(CrossfadeOverlap.trackId);
            starting = false;
            if (!expected) {
                if (player != null) handler().post(CrossfadeOverlap::stop);
                return false;
            }
            handedOff = true;
            handedOffAt = SystemClock.uptimeMillis();
        }
        return true;
    }

    /** A pause, a seek or anything else that ends the transition. */
    static void cancel() {
        synchronized (lock) {
            starting = false;
            if (player == null) return;
        }
        handler().post(CrossfadeOverlap::stop);
    }

    private static void stop() {
        MediaPlayer old;
        synchronized (lock) {
            old = player;
            player = null;
            trackId = null;
            handedOff = false;
        }
        if (old == null) return;
        try {
            old.stop();
        } catch (Exception ignored) {
            // Not started or already stopped.
        }
        old.release();
    }
}
