package app.revanced.extension.soundcloud.player;

/**
 * The listen in progress: which track, when it started and how long it has really played. Not thread safe on its own:
 * {@link ListeningStats} calls it only while it holds its own monitor.
 */
final class ListenSession {
    private String urn;
    private long start;
    private long playedMs;
    /** When the current stretch of playing began, or 0 while the track is not playing. */
    private long playingSince;

    boolean isActive() {
        return urn != null;
    }

    boolean isOf(String otherUrn) {
        return otherUrn.equals(urn);
    }

    /** A track starts being listened to. */
    void begin(String trackUrn, long now) {
        urn = trackUrn;
        start = now;
    }

    /** The listen goes on from a mark saved at a pause (the app was closed meanwhile). */
    void resume(ListenRecord saved) {
        urn = saved.urn;
        start = saved.start;
        playedMs = saved.playedMs;
        playingSince = 0;
    }

    /**
     * The player started or stopped playing.
     *
     * @return True if a stretch of playing has just ended, so the time played so far is worth saving.
     */
    boolean onPlayingChanged(boolean playing, long now) {
        if (playing && playingSince == 0) {
            playingSince = now;
        } else if (!playing && playingSince != 0) {
            playedMs += now - playingSince;
            playingSince = 0;
            return true;
        }
        return false;
    }

    /** What has played so far, counting a stretch that is still going on; null if there is no listen. */
    ListenRecord progress(long now) {
        if (urn == null) return null;
        return new ListenRecord(start, urn, playedMs + (playingSince != 0 ? now - playingSince : 0));
    }

    /** The mark to save at a pause: what played before the current stretch. */
    ListenRecord pendingMark() {
        return new ListenRecord(start, urn, playedMs);
    }

    /** Ends the listen and starts over. @return The listen as it ended. */
    ListenRecord end(long now) {
        if (playingSince != 0) playedMs += now - playingSince;
        ListenRecord ended = new ListenRecord(start, urn, playedMs);
        urn = null;
        playedMs = 0;
        playingSince = 0;
        return ended;
    }
}
