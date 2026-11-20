package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.local.LocalAdditions;

/**
 * The real overlap of the smooth transition (bot ticket #63): while the end of a track fades out in SoundCloud's
 * player, the beginning of the next one already fades in, in a second player of Arsound's own, so there is no silence
 * between them. When SoundCloud gets to the next track it starts it where the second player has got to
 * ({@link #startPosition}), at full volume, and the second player stops.
 * <p>
 * Only when the next track is on the phone (downloaded, imported or loaded ahead by {@link NextTrackPreload}): the
 * second player opens the file, and SoundCloud starts that same file where the second player has got to. A track that
 * is streamed by SoundCloud cannot be handed over like that (SoundCloud starts a stream from 0, so its first seconds
 * would play twice), so it keeps the fade one after the other of {@link Crossfade}. A skip, a pause or another track
 * than the expected one stops the second player at once.
 */
public final class CrossfadeOverlap {
    private static final long TICK_MS = 40;
    /**
     * Gapless: the second player starts this much before the end of the track, as a started player takes a moment to
     * make its first sound. It plays at full volume at once (there is no fade), so this is the most it overlaps the tail.
     */
    private static final long GAPLESS_LEAD_MS = 80;
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
    /**
     * The fade-out's length on the wall clock: {@link Crossfade} fades the first track by its POSITION, so at 85 % speed a 6 s
     * fade takes about 7 s of real time, and the fade-in of the second player must take as long (review r15).
     */
    private static long fadeWallMs;
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
    /**
     * Gapless: the reports come about twice a second, so one older than this means a pause shortly before the end. The
     * second player does not start under a pause.
     */
    private static final long GAPLESS_REPORT_GAP_MS = 800;
    private static long handedOffAt;
    /**
     * Counts the attempts to start the second player and every change that cancels them (a seek back, a pause, the next
     * track). A start that wakes up after the count moved on is stale and must not play.
     */
    private static long attempt;
    private static final Runnable TICK = CrossfadeOverlap::tick;

    private CrossfadeOverlap() {
    }

    private static synchronized Handler handler() {
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
     * @param fade The length of the fade-out of this track; 0 for the gapless change: no fade, the second player starts
     *             at the end of the track at full volume and SoundCloud's player takes over under it.
     */
    static void onProgress(long position, long duration, long fade) {
        if (duration <= 0 || position <= 0) return;
        long left = duration - position;
        boolean seekedAway;
        synchronized (lock) {
            reportedAt = SystemClock.uptimeMillis();
            // A seek back out of the fade: the overlap is not wanted any more. That holds also while the second player is
            // still being prepared, so its pending start is cancelled too.
            seekedAway = (player != null || starting) && !handedOff && left > startWindow(fade) + 500;
            if (seekedAway) {
                starting = false;
                attempt++;
            } else {
                endAt = endAtFor(SystemClock.uptimeMillis(), left, currentFactor);
                // A change of speed mid-track moves the end, and the fade with it (the fade of a handed-off track is over).
                if ((starting || player != null) && !handedOff) {
                    fadeWallMs = wallLeft(fadeMs, currentFactor);
                    fadeStartAt = fadeStartFor(endAt, fadeMs, currentFactor);
                }
            }
        }
        if (seekedAway) {
            handler().post(CrossfadeOverlap::stop);
            return;
        }
        synchronized (lock) {
            if (player != null || starting) return;
            // Too late to be heard properly, or a track too short for a fade of its own.
            if (left > startWindow(fade) || left < fade / 2 || duration < 3 * fade) return;
            starting = true;
            long now = SystemClock.uptimeMillis();
            endAt = endAtFor(now, left, currentFactor);
            fadeMs = fade;
            fadeWallMs = wallLeft(fade, currentFactor);
            fadeStartAt = fadeStartFor(endAt, fade, currentFactor);
            long mine = ++attempt;
            handler().post(() -> start(mine));
        }
    }

    /** @param mine The attempt this start belongs to; a start of an older attempt does nothing. */
    private static void start(long mine) {
        MediaPlayer created = null;
        try {
            // A skip or a pause may have come while this start waited on the thread: nothing is asked for then.
            if (!isCurrent(mine)) return;
            String next = NextTrackPreload.nextTrackId();
            // The effects of the track cannot be heard on the second player: the fade stays the one after the other.
            float speed = next == null ? 1f : overlapSpeed(TRACK_URN + next);
            if (speed <= 0f) {
                synchronized (lock) {
                    if (mine == attempt) starting = true;
                }
                return;
            }
            // Only a track SoundCloud also starts from a file: only such a start takes the position of the second player.
            String source = next == null ? null : DownloadTrackPatch.playableSource(next);
            Context context = Utils.getContext();
            if (!canHandOver(source) || context == null) {
                synchronized (lock) {
                    // Not again for this track: the fade stays the one after the other.
                    if (mine == attempt) starting = true;
                }
                return;
            }
            // The requests took their time: a skip meanwhile makes the second player pointless, so it is not even opened.
            if (!isCurrent(mine)) return;
            created = new MediaPlayer();
            created.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            if (source.startsWith("/")) created.setDataSource(source);
            else created.setDataSource(context, Uri.parse(source));
            created.setVolume(0f, 0f);
            created.prepare();
            if (!isCurrent(mine)) {
                created.release();
                created = null;
                return;
            }
            long wait;
            synchronized (lock) {
                wait = startAt(fadeStartAt, fadeMs) - SystemClock.uptimeMillis();
            }
            // Started a little before the fade-out, so it is heard from the fade's start: prepare took its time already.
            // Waited for without holding the thread, so a pause or a skip can still stop the overlap meanwhile.
            MediaPlayer ready = created;
            created = null;
            handler().postDelayed(() -> begin(mine, ready, next, speed), Math.max(0, wait));
        } catch (Throwable ex) {
            // Whatever failed (the stream, the storage, the lookups): the same fallback, and the thread lives on.
            if (created != null) created.release();
            synchronized (lock) {
                if (mine == attempt) starting = true;
            }
            Logger.printInfo(() -> "Crossfade: the next track cannot be played ahead (" + ex + "), one after the other");
        }
    }

    /**
     * Whether the overlap may play this track: only one SoundCloud starts from a file too. A start from a file takes the
     * position of the second player ({@code InstantFilePlayback.playbackItem}); SoundCloud starts a streamed track from 0
     * whatever the second player has played, so its first seconds would sound twice.
     *
     * @param playableSource {@link DownloadTrackPatch#playableSource} of the next track.
     */
    static boolean canHandOver(String playableSource) {
        return playableSource != null && !playableSource.isEmpty();
    }

    private static final String TRACK_URN = "soundcloud:tracks:";

    /** Whether the start of this attempt is still wanted: no skip, pause or seek moved on since it was posted. */
    private static boolean isCurrent(long mine) {
        synchronized (lock) {
            return starting && mine == attempt;
        }
    }

    /**
     * The speed the next track plays at in SoundCloud's player, as a factor (the pitch follows it), so the second
     * player plays it the same way.
     *
     * @return The factor, 1 for the normal speed; 0 when the track has a reverb or a bass boost, which only SoundCloud's
     * player applies (no overlap then).
     */
    private static float overlapSpeed(String urn) {
        try {
            if (TrackSpeeds.reverbOf(urn).isOn() || TrackSpeeds.bassOf(urn).isOn()) return 0f;
            // The second player is outside the player's sound chain (equalizer, preamp, limiter): with any of them on it would
            // come in at another level and without the curve, then jump at the handoff.
            if (outsideChain(AudioEqualizer.isEnabled(), AudioEqualizer.preamp())) return 0f;
            return trackFactor(urn);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Crossfade: the speed of the next track is not known (" + ex + ")");
            return 0f;
        }
    }

    /** The speed factor SoundCloud's player gives the track (1 for the normal speed). */
    private static float trackFactor(String urn) {
        VersionSpeed own = TrackSpeeds.of(urn);
        VersionSpeed common = TrackVersions.speed();
        boolean retimed = false;
        // The same reading as the player's own (SlowedPlayback): the stored title of a track, the tag or the file name of an
        // imported file, so "x (slowed).mp3" is left at its own speed here as there.
        if (own == null && common != VersionSpeed.NORMAL) retimed = SlowedPlayback.titleSaysRetimed(urn);
        return playsSlowed(own, common, retimed) ? TrackVersions.factorOf(own != null ? own : common) : 1f;
    }

    /**
     * Whether the sound of SoundCloud's player is shaped after the track by something the second player lacks: the equalizer
     * is on, or the preamp is not 0. Then the tracks fade one after the other.
     */
    static boolean outsideChain(boolean equalizerOn, int preampMillibels) {
        return equalizerOn || preampMillibels != 0;
    }

    /**
     * How long, on the wall clock, the rest of the track takes: a position report gives media time, and at 140 % speed it
     * passes 1.4 times as fast. Without this the fade-in of the second player lags and the handoff dips and then jumps.
     */
    static long wallLeft(long mediaLeft, float factor) {
        return factor <= 0f ? mediaLeft : Math.round(mediaLeft / (double) factor);
    }

    /** The uptime the track ends at: now and the time left, in wall time at the speed it plays at. */
    static long endAtFor(long now, long mediaLeft, float factor) {
        return now + wallLeft(mediaLeft, factor);
    }

    /**
     * The uptime the fade-out begins at, for an end and a fade length in media time: the first track fades by its position, so
     * the fade takes {@code fadeMs / factor} on the wall clock.
     */
    static long fadeStartFor(long endAt, long fadeMs, float factor) {
        return endAt - wallLeft(fadeMs, factor);
    }

    /**
     * The speed the track that plays now has was changed (the versions popup, the settings, a speed of its own): the factor is
     * worked out again on the overlap thread, so the next report moves the end and the fade to the right time. Without it the
     * fade came about two seconds early after "Slowed 85 %" was chosen in the middle of a track, and cut the tail the other way.
     */
    public static void refreshFactor() {
        handler().post(() -> currentFactor = factorOfPlaying(PlaybackEvents.playingUrn()));
    }

    /**
     * Whether the item has a speed of its own at all: a SoundCloud track, or an imported file; any other item (an ad) plays
     * at the normal speed, as {@code SlowedPlayback.apply} treats it.
     */
    static boolean hasSpeed(String urn, boolean importedFile) {
        return urn != null && (urn.contains(":tracks:") || importedFile);
    }

    private static float factorOfPlaying(String urn) {
        if (urn == null) return 1f;
        try {
            if (!hasSpeed(urn, LocalAdditions.importedFileOf(urn) != null)) return 1f;
            return trackFactor(urn);
        } catch (Exception ex) {
            // Not known: the normal speed.
            return 1f;
        }
    }

    /** The speed factor of the track that plays now; 1 until it is known (worked out on the overlap thread). */
    private static volatile float currentFactor = 1f;

    /** {@link #currentFactor} for {@link Crossfade}, which counts the position between reports with it. */
    static float playingFactor() {
        return currentFactor;
    }

    /**
     * Whether SoundCloud's player plays the track at another speed: the speed of the track itself holds whatever its
     * title says, the common choice leaves a track alone whose title says it is retimed already ({@link SlowedPlayback}).
     */
    static boolean playsSlowed(VersionSpeed own, VersionSpeed common, boolean titleRetimed) {
        VersionSpeed wanted = own != null ? own : common;
        return wanted != VersionSpeed.NORMAL && (own != null || !titleRetimed);
    }

    /** Plays the prepared second player, unless a seek, a pause or the next track moved on while it waited. */
    private static void begin(long mine, MediaPlayer created, String next, float requestedSpeed) {
        try {
            // The speed may have been changed while the player waited (up to six seconds): looked at again, outside the lock
            // (it reads the title store). With a reverb or a bass boost chosen meanwhile there is no overlap any more.
            float speed = next == null ? requestedSpeed : overlapSpeed(TRACK_URN + next);
            if (speed <= 0f) {
                synchronized (lock) {
                    if (mine == attempt) starting = true;
                }
                created.release();
                return;
            }
            final float useSpeed = speed;
            synchronized (lock) {
                // Stale when a seek, a pause or the next track moved on meanwhile, or when another player took the slot.
                if (!starting || mine != attempt || player != null) {
                    created.release();
                    return;
                }
                if (fadeMs == 0) {
                    long now = SystemClock.uptimeMillis();
                    // The track stopped reporting before its end: it was paused, not played out. Nothing starts under a pause;
                    // the next report starts the overlap again once the track plays on.
                    if (now - reportedAt > GAPLESS_REPORT_GAP_MS) {
                        starting = false;
                        attempt++;
                        created.release();
                        return;
                    }
                    // The end moved (a stall, a seek inside the last seconds): the player waits for the real end.
                    long again = rewait(startAt(endAt, 0), now);
                    if (again > 0) {
                        handler().postDelayed(() -> begin(mine, created, next, useSpeed), again);
                        return;
                    }
                } else {
                    // The same with a fade: a change of speed moves the end and the fade, and the second player must not start
                    // at the old time (a silent intro, or the next track skipping its first seconds at the handoff).
                    long again = rewait(startAt(fadeStartAt, fadeMs), SystemClock.uptimeMillis(), FADE_REWAIT_SLACK_MS);
                    if (again > 0) {
                        handler().postDelayed(() -> begin(mine, created, next, useSpeed), again);
                        return;
                    }
                }
                // The speed SoundCloud's player gives this track. Set on a prepared player it starts it, as start() does.
                if (useSpeed != 1f) created.setPlaybackParams(new PlaybackParams().setSpeed(useSpeed).setPitch(useSpeed));
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

    /**
     * How much longer the second player waits before it starts.
     *
     * @return The wait in milliseconds, or 0 when it is time (within 40 ms of it).
     */
    static long rewait(long startAtUptime, long now) {
        return rewait(startAtUptime, now, 40);
    }

    /**
     * The same with a slack of its own. With a fade the start is recomputed on every position report, and their jitter (tens of
     * ms) must not push the start again and again: a start up to {@link #FADE_REWAIT_SLACK_MS} early is under the silent lead
     * anyway (review r15). The gapless start keeps 40 ms: it is heard at full volume.
     */
    static long rewait(long startAtUptime, long now, long slack) {
        long again = startAtUptime - now;
        return again > slack ? again : 0;
    }

    /** See {@link #rewait(long, long, long)}; well under {@code LEAD_MS}. */
    static final long FADE_REWAIT_SLACK_MS = 150;

    /** How long the second player may need to open a file (a slow card); the overlap is prepared this much earlier. */
    private static final long STREAM_PREPARE_MS = 6_000;

    /** How far before the end of a track the second player is prepared: its lead, a second of slack, a file's opening. */
    static long startWindow(long fade) {
        return fade + LEAD_MS + 1_000 + STREAM_PREPARE_MS;
    }

    /** The uptime the second player starts at: a little before the fade-out, so it is heard from the fade's start. */
    static long startAt(long fadeStartAt) {
        return fadeStartAt - LEAD_MS;
    }

    /** The same for a fade of the given length; with none (gapless) the lead is the short one: the sound is not faded in. */
    static long startAt(long fadeStartAt, long fade) {
        return fade == 0 ? fadeStartAt - GAPLESS_LEAD_MS : startAt(fadeStartAt);
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

    /**
     * Whether the reports of the first player stopped because it was paused. With a fade any silence is a pause; without
     * one the reports also stop when the track played out, so only a silence that began with more than {@link #SILENT_MS}
     * left is a pause.
     */
    static boolean isPaused(long fadeMs, long now, long reportedAt, long endAt) {
        if (now - reportedAt <= SILENT_MS) return false;
        return fadeMs != 0 || endAt - reportedAt > SILENT_MS;
    }

    /** Raises the second player while the first fades out; after the handoff, lowers it under SoundCloud's player. */
    private static void tick() {
        MediaPlayer current;
        float volume;
        boolean again = true;
        // Stopped because of a pause or a wait that came to nothing: the track may start the overlap again.
        boolean rearm = false;
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
                // Gapless: the reports stop when the track has played out, and SoundCloud needs a moment to go on; they
                // stop under a pause too, which is told by the time that was left at the last report.
                if (isPaused(fadeMs, now, reportedAt, endAt)) {
                    Logger.printInfo(() -> "Crossfade: the track was paused, the overlap stops");
                    again = false;
                    rearm = true;
                    volume = 0f;
                } else if (now - endAt > HANDOFF_WAIT_MS) {
                    Logger.printInfo(() -> "Crossfade: SoundCloud did not go on to the next track, the overlap stops");
                    again = false;
                    rearm = true;
                    volume = 0f;
                } else {
                    volume = fadeInLevel(now, fadeStartAt, fadeWallMs) * sleep;
                }
            }
        }
        try {
            current.setVolume(volume, volume);
        } catch (Exception ignored) {
            // Released meanwhile.
        }
        if (again) handler().postDelayed(TICK, TICK_MS);
        else stop(rearm);
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
            // The speed this track plays at, for the time left: looked up on the overlap thread (it reads the title store).
            // The same item as refreshFactor looks at: the urn of the item that started (set just before this call).
            String urn = PlaybackEvents.playingUrn();
            handler().post(() -> currentFactor = factorOfPlaying(urn));
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
        stop(false);
    }

    /**
     * @param rearm Whether the track may start the overlap again: after a pause or a wait that came to nothing the track
     *              still plays on (or plays on once resumed), and without this the pending start of the old attempt
     *              would keep every later report from starting the overlap for it.
     */
    static void stop(boolean rearm) {
        MediaPlayer old = detach(rearm);
        if (old == null) return;
        try {
            old.stop();
        } catch (Exception ignored) {
            // Not started or already stopped.
        }
        old.release();
    }

    /** Forgets the second player under the lock and gives it back to be stopped; null if there is none. */
    static MediaPlayer detach(boolean rearm) {
        synchronized (lock) {
            MediaPlayer old = player;
            player = null;
            trackId = null;
            handedOff = false;
            if (old != null && rearm) {
                starting = false;
                attempt++;
            }
            return old;
        }
    }
}
