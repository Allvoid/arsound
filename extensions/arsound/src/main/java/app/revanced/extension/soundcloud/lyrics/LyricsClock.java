package app.revanced.extension.soundcloud.lyrics;

import android.os.Bundle;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * Where the playing track is, for following the lyrics.
 * <p>
 * SoundCloud hands every playback state to its media session about twice a second while playing (state, position,
 * speed, and in the extras the track's URN), and the patch passes each one here. Between two states the position is
 * counted forward from the clock, so the lines can change on every frame without asking the player. A seek goes
 * through the media session's own transport controls, as a headset button or the notification would.
 */
@SuppressWarnings("unused")
public final class LyricsClock {
    private static final String STATE = "android.support.v4.media.session.PlaybackStateCompat";
    private static final String SESSION = "android.support.v4.media.session.MediaSessionCompat";
    private static final String CONTROLLER = "android.support.v4.media.session.MediaControllerCompat";
    private static final String TRANSPORT = "android.support.v4.media.session.MediaControllerCompat$TransportControls";
    // States of PlaybackStateCompat.
    private static final int NONE = 0;
    private static final int STOPPED = 1;
    private static final int PAUSED = 2;
    private static final int PLAYING = 3;
    private static final int BUFFERING = 6;
    /** A state older than this is not counted forward: the player stopped telling and the position is a guess. */
    private static final long MAX_EXTRAPOLATION_MS = 5_000;
    /** After a seek the new position is used until the player's own state arrives. */
    private static final long OVERRIDE_MS = 1_500;
    /** The state the session already holds is looked for no more often than this, while none has come through. */
    private static final long ADOPT_RETRY_MS = 1_000;
    /** A position further than this from where the last state was heading is a seek, not the clock's drift. */
    private static final long SEEK_JUMP_MS = 1_200;

    private static final class Snapshot {
        final String urn;
        final int state;
        final long position;
        final long updatedAt;
        final float speed;

        Snapshot(String urn, int state, long position, long updatedAt, float speed) {
            this.urn = urn;
            this.state = state;
            this.position = position;
            this.updatedAt = updatedAt;
            this.speed = speed;
        }

        long positionNow() {
            if (state != PLAYING) return position;
            long elapsed = Math.max(0, Math.min(SystemClock.elapsedRealtime() - updatedAt, MAX_EXTRAPOLATION_MS));
            return position + (long) (elapsed * speed);
        }
    }

    private static volatile Snapshot snapshot;
    /** Where the user has just jumped to, until the player confirms it. */
    private static volatile Snapshot jump;
    private static volatile WeakReference<Object> service = new WeakReference<>(null);

    private static Method getState, getPosition, getUpdateTime, getSpeed, getExtras;
    private static Field sessionField;
    private static Method sessionController, controllerTransport, transportSeek, controllerState;
    private static long lastAdopt;

    private LyricsClock() {
    }

    /**
     * Injection point. The start of the media service's method that hands a playback state to the session; it runs
     * for every update, so it reads little and never throws.
     *
     * @param mediaService SoundCloud's media service, which keeps the session.
     * @param state        The {@code PlaybackStateCompat}.
     */
    public static void onState(Object mediaService, Object state) {
        try {
            if (state == null) return;
            if (service.get() != mediaService) service = new WeakReference<>(mediaService);
            Snapshot previous = snapshot;
            Snapshot next = read(state, previous);
            if (next == null) return;
            snapshot = next;
            jump = null;
            boolean changed = previous == null || previous.state != next.state
                    || !equal(previous.urn, next.urn)
                    // A position that is not where the last state was heading: a seek, from anywhere.
                    || Math.abs(previous.positionNow() - next.position) > SEEK_JUMP_MS;
            if (changed) LyricsManager.onClockChanged();
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not read the playback state", ex);
        }
    }

    /**
     * Injection point. The start of the media service's method that puts new metadata on the session. Right after the
     * service starts, the queue the app closed with is restored through it, and the state of that queue goes straight
     * to the session without passing {@link #onState}: so the service is noted here, and the state it holds is taken
     * from the session when it is first needed.
     *
     * @param mediaService SoundCloud's media service.
     */
    public static void onService(Object mediaService) {
        try {
            if (mediaService != null && service.get() != mediaService) service = new WeakReference<>(mediaService);
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not note the media service", ex);
        }
    }

    private static Snapshot read(Object state, Snapshot previous) throws Exception {
        if (getState == null) {
            Class<?> type = Class.forName(STATE);
            getState = type.getMethod("getState");
            getPosition = type.getMethod("getPosition");
            getUpdateTime = type.getMethod("getLastPositionUpdateTime");
            getSpeed = type.getMethod("getPlaybackSpeed");
            getExtras = type.getMethod("getExtras");
        }
        int code = (int) getState.invoke(state);
        long position = (long) getPosition.invoke(state);
        long updated = (long) getUpdateTime.invoke(state);
        float speed = (float) getSpeed.invoke(state);
        Bundle extras = (Bundle) getExtras.invoke(state);
        // A state without a track (an ad, a stop) says nothing about the lyrics' track.
        String urn = extras == null ? null : extras.getString("urnKey");
        if (urn == null && previous != null && code != STOPPED && code != NONE) urn = previous.urn;
        long stamp = updated > 0 ? updated : SystemClock.elapsedRealtime();
        return new Snapshot(urn, code, Math.max(0, position), stamp, speed <= 0 ? 1f : speed);
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * The last state, or, when none has come through the hook yet, the one the session was restored with at the start
     * (a paused track from last time): without it nothing would be lit and a tap on a line would do nothing until the
     * first press of play.
     */
    private static Snapshot currentSnapshot() {
        Snapshot current = snapshot;
        if (current == null) {
            adoptSessionState();
            current = snapshot;
        }
        return current;
    }

    private static void adoptSessionState() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastAdopt < ADOPT_RETRY_MS) return;
        lastAdopt = now;
        try {
            Object mediaService = service.get();
            Object session = mediaService == null ? null : sessionOf(mediaService);
            if (session == null) return;
            Object state = controllerState.invoke(sessionController.invoke(session));
            if (state != null) onState(mediaService, state);
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not read the state the session holds", ex);
        }
    }

    /** The media session the service keeps; null if it has none yet or cannot be reached. */
    private static Object sessionOf(Object mediaService) throws Exception {
        if (sessionField == null) {
            Class<?> sessionType = Class.forName(SESSION);
            Field found = Reflect.field(mediaService.getClass(), Reflect.typeIs(sessionType));
            if (found == null) return null;
            sessionController = sessionType.getMethod("getController");
            Class<?> controllerType = Class.forName(CONTROLLER);
            controllerTransport = controllerType.getMethod("getTransportControls");
            controllerState = controllerType.getMethod("getPlaybackState");
            transportSeek = Class.forName(TRANSPORT).getMethod("seekTo", long.class);
            sessionField = found;
        }
        return sessionField.get(mediaService);
    }

    /** The URN of the track the player has, or null if it has none or has not told yet. */
    static String urn() {
        Snapshot current = currentSnapshot();
        return current == null ? null : current.urn;
    }

    /** Whether the track is playing now (a buffering one is about to). */
    static boolean isPlaying() {
        Snapshot current = currentSnapshot();
        return current != null && (current.state == PLAYING || current.state == BUFFERING);
    }

    /**
     * @return Where the track is in milliseconds, or -1 if the player is not on this track (or has not told).
     */
    static long positionFor(String urn) {
        Snapshot current = jump;
        if (current != null && SystemClock.elapsedRealtime() - current.updatedAt < OVERRIDE_MS && urn.equals(current.urn)) {
            return current.positionNow();
        }
        current = currentSnapshot();
        if (current == null || !urn.equals(current.urn)) return -1;
        return current.positionNow();
    }

    /**
     * Moves the track to a moment, without changing whether it plays.
     *
     * @return False if the player could not be reached.
     */
    static boolean seekTo(String urn, long positionMs) {
        try {
            Object mediaService = service.get();
            if (mediaService == null) return false;
            Snapshot current = currentSnapshot();
            // The player is on another track: a jump to a line of this one would move the wrong song.
            if (current != null && current.urn != null && !current.urn.equals(urn)) return false;
            Object session = sessionOf(mediaService);
            if (session == null) return false;
            Object transport = controllerTransport.invoke(sessionController.invoke(session));
            transportSeek.invoke(transport, positionMs);
            boolean playing = current != null && current.state == PLAYING;
            jump = new Snapshot(urn, playing ? PLAYING : PAUSED, positionMs, SystemClock.elapsedRealtime(),
                    current == null ? 1f : current.speed);
            return true;
        } catch (Throwable ex) {
            Logger.printException(() -> "Lyrics: could not seek", ex);
            return false;
        }
    }
}
