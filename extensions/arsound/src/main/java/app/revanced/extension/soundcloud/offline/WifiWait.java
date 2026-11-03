package app.revanced.extension.soundcloud.offline;

/**
 * Tells when the requests of SoundCloud's own offline download only wait for Wi-Fi, so they are not progress the user
 * is waiting for any more: the counter at the bottom stops counting them, and the polling of the download state stops.
 * <p>
 * SoundCloud downloads protected tracks only over an unmetered network unless its own settings say otherwise. A request
 * that stays "requested" on mobile data therefore does nothing until Wi-Fi comes. But the same state also shows a
 * request that waits for its turn behind another track, and that track may be downloading over mobile data on purpose.
 * So the decision is not taken from one look: it takes a downloader that did nothing for {@link #IDLE_MS}, which
 * covers the short gap between two tracks of a queue.
 * <p>
 * Pure logic, no Android, so it is tested on the JVM.
 */
final class WifiWait {
    /** How long the downloader must have been idle on a metered network before its requests count as waiting. */
    static final long IDLE_MS = 10_000;

    private WifiWait() {
    }

    /**
     * @param metered           The phone is on a metered network (mobile data, a hotspot).
     * @param downloaderWorking A track of SoundCloud's library is downloading right now.
     * @param now               The current time.
     * @param workedAt          The last time the downloader was seen at work, or 0.
     * @param requestedAt       The last time a track was handed to SoundCloud's offline library, or 0.
     * @return Whether the requests only wait for Wi-Fi.
     */
    static boolean waits(boolean metered, boolean downloaderWorking, long now, long workedAt, long requestedAt) {
        if (!metered || downloaderWorking) return false;
        return now - Math.max(workedAt, requestedAt) >= IDLE_MS;
    }
}
