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
    /**
     * Reports that stop for this long mean the player was paused, or the track ended and SoundCloud has not started the
     * next one yet: the overlap stops, unless the next track has already taken over.
     */
    private static final long SILENT_MS = 1_500;
    private static long handedOffAt;
    /**
     * Counts the attempts to start the second player and every change that cancels them (a seek back, a pause, the next
     * track). A start that wakes up after the count moved on is stale and must not play.
     */
    private static long attempt;
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
            // A seek back out of the fade: the overlap is not wanted any more. That holds also while the second player is
            // still being prepared, so its pending start is cancelled too.
            seekedAway = (player != null || starting) && !handedOff && left > fade + LEAD_MS + 1_500;
            if (seekedAway) {
                starting = false;
                attempt++;
            } else {
                endAt = SystemClock.uptimeMillis() + left;
            }
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
            long mine = ++attempt;
            handler().post(() -> start(mine));
        }
    }

    /** @param mine The attempt this start belongs to; a start of an older attempt does nothing. */
    private static void start(long mine) {
        String next = NextTrackPreload.nextTrackId();
        String source = next == null ? null : DownloadTrackPatch.playableSource(next);
        Context context = Utils.getContext();
        if (source == null || context == null) {
            synchronized (lock) {
                // Not again for this track: the fade stays the one after the other.
                if (mine == attempt) starting = true;
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
                wait = startAt(fadeStartAt) - SystemClock.uptimeMillis();
            }
            // Started a little before the fade-out, so it is heard from the fade's start: prepare took its time already.
            if (wait > 0) SystemClock.sleep(Math.min(wait, 3_000));
            synchronized (lock) {
                // Stale when a seek, a pause or the next track moved on meanwhile, or when another player took the slot.
                if (!starting || mine != attempt || player != null) {
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

    /** The uptime the second player starts at: a little before the fade-out, so it is heard from the fade's start. */
    static long startAt(long fadeStartAt) {
        return fadeStartAt - LEAD_MS;
    }

    /** The volume of the second player while it fades in: 0 before the fade, 1 at its end, squared as in {@link Crossfade}. */
    static float fadeInLevel(long now, long fadeStartAt, long fadeMs) {
        float through = (now - fadeStartAt) / (float) Math.max(1, fadeMs);
        float level = Math.max(0f, Math.min(1f, through));
        return level * level;
    }

    /** The volume after the handoff: the second player fades out under SoundCloud's player, 0 once it is over. */
    static float handoffLevel(long now, long handedOffAt) {
        return Math.max(0f, 1f - (now - handedOffAt) / (float) HANDOFF_FADE_MS);
    }

    /** Raises the second player while the first fades out; after the handoff, lowers it under SoundCloud's player. */
    private static void tick() {
        MediaPlayer current;
        float volume;
        boolean again = true;
        // The sleep timer's fade lowers SoundCloud's player too: the overlap follows it, so a transition does not get
        // back to full volume under a sleep fade.
        float sleep = PlayerVolume.product(PlayerVolume.level(PlayerVolume.Source.SLEEP));
        synchronized (lock) {
            current = player;
            if (current == null) return;
            long now = SystemClock.uptimeMillis();
            if (handedOff) {
                volume = handoffLevel(now, handedOffAt) * sleep;
                if (now - handedOffAt >= HANDOFF_FADE_MS) again = false;
            } else {
                // The silence is checked whatever the time left: a pause near the end is caught here too.
                if (now - reportedAt > SILENT_MS) {
                    Logger.printInfo(() -> "Crossfade: the track was paused, the overlap stops");
                    again = false;
                    volume = 0f;
                } else if (now - endAt > HANDOFF_WAIT_MS) {
                    Logger.printInfo(() -> "Crossfade: SoundCloud did not go on to the next track, the overlap stops");
                    again = false;
                    volume = 0f;
                } else {
                    volume = fadeInLevel(now, fadeStartAt, fadeMs) * sleep;
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
            attempt++;
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
            attempt++;
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
