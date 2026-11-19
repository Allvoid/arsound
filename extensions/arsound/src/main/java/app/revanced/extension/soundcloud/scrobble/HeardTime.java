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
    /** When the current stretch of playing began, or 0 while not playing. */
    private long playingSince;

    /** A new track: the count starts over. */
    void begin(long durationMs) {
        this.durationMs = Math.max(0, durationMs);
        heardMs = 0;
        playingSince = 0;
    }

    /** The length became known later (it was 0 at the start). */
    void setDuration(long durationMs) {
        if (durationMs > 0) this.durationMs = durationMs;
    }

    long durationMs() {
        return durationMs;
    }

    void onPlaying(boolean playing, long now) {
        if (playing && playingSince == 0) {
            playingSince = now;
        } else if (!playing && playingSince != 0) {
            heardMs += Math.max(0, now - playingSince);
            playingSince = 0;
        }
    }

    boolean isPlaying() {
        return playingSince != 0;
    }

    long heard(long now) {
        return heardMs + (playingSince != 0 ? Math.max(0, now - playingSince) : 0);
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
