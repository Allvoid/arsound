package app.revanced.extension.soundcloud.scrobble;

/**
 * How long a track has really been heard, and when that is enough for a listen: half of its length or four minutes,
 * whichever is less, and the track must be at least 30 seconds long (the rule of ListenBrainz and Last.fm, as on the
 * desktop). A pause is not heard time; skipping forward adds none either, because only the time the player spent
 * playing is counted. Pure: the times come in as arguments. Not thread safe: the caller holds its own lock.
 */
final class HeardTime {
    static final long SHORTEST_TRACK_MS = 30_000;
    static final long LONGEST_WAIT_MS = 240_000;

    private long durationMs;
    private long heardMs;
    /** Whether a stretch of playing is open, and when it began (any clock value, 0 included). */
    private boolean playing;
    private long playingSince;

    /** A new track: the count starts over. */
    void begin(long durationMs) {
        this.durationMs = Math.max(0, durationMs);
        heardMs = 0;
        playing = false;
        playingSince = 0;
    }

    /** A listen taken up again after a restart: the count starts with what was heard before it. */
    void begin(long durationMs, long alreadyHeardMs) {
        begin(durationMs);
        heardMs = Math.max(0, alreadyHeardMs);
    }

    long durationMs() {
        return durationMs;
    }

    void onPlaying(boolean nowPlaying, long now) {
        if (nowPlaying && !playing) {
            playing = true;
            playingSince = now;
        } else if (!nowPlaying && playing) {
            heardMs += Math.max(0, now - playingSince);
            playing = false;
        }
    }

    boolean isPlaying() {
        return playing;
    }

    long heard(long now) {
        return heardMs + (playing ? Math.max(0, now - playingSince) : 0);
    }

    /**
     * What must be heard for this length to count; -1 when the track is too short. An unknown length (0) is taken for a
     * long track: four minutes heard count whatever the length was.
     */
    static long neededMs(long durationMs) {
        if (durationMs <= 0) return LONGEST_WAIT_MS;
        if (durationMs < SHORTEST_TRACK_MS) return -1;
        return Math.min(durationMs / 2, LONGEST_WAIT_MS);
    }

    /** How much more must be heard; 0 when it is enough already; -1 when this track can never count. */
    long remainingMs(long now) {
        long needed = neededMs(durationMs);
        if (needed < 0) return -1;
        return Math.max(0, needed - heard(now));
    }
}
