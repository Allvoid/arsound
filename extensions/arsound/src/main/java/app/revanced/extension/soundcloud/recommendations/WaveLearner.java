package app.revanced.extension.soundcloud.recommendations;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;

/**
 * What {@link WaveMemory} does with a listen before it reaches the disk: remembers how long the tracks are (the player
 * knows it when the track starts, the listen ends much later), judges the listen ({@link ListenVerdict}) and keeps the
 * verdict waiting until the {@link WaveLedger} is loaded and someone off the player's thread puts it in. The player's
 * hooks only call the cheap methods here; nothing waits for a file. No Android here, so it can be tested on the JVM.
 * <p>
 * Thread safe: the hooks call it on the player's thread, the ledger is filled on a background thread.
 */
final class WaveLearner {
    /** How many track lengths are kept: the playing track, the one that just ended, and a few more. */
    static final int LENGTHS_KEPT = 8;

    /** A listen that says something about the taste, waiting for the ledger. */
    private static final class Listen {
        final String urn;
        final ListenVerdict verdict;
        final long time;

        Listen(String urn, ListenVerdict verdict, long time) {
            this.urn = urn;
            this.verdict = verdict;
            this.time = time;
        }
    }

    /** The newest last. */
    private final Map<String, Long> lengths = new LinkedHashMap<>();
    private final Queue<Listen> waiting = new ArrayDeque<>();

    /**
     * Notes the length of a track. A later note of the same track replaces the earlier: the player's own report, made
     * while the track plays, is more exact than the one SoundCloud's data gave at the start.
     *
     * @param durationMs Milliseconds; a length that is not known ({@link ListenVerdict#isKnownLength}) is ignored.
     */
    synchronized void noteLength(String urn, long durationMs) {
        if (urn == null || !ListenVerdict.isKnownLength(durationMs)) return;
        String key = TrackUrn.normalize(urn);
        lengths.remove(key);
        lengths.put(key, durationMs);
        for (Iterator<String> it = lengths.keySet().iterator(); lengths.size() > LENGTHS_KEPT && it.hasNext(); ) {
            it.next();
            it.remove();
        }
    }

    /** @return The length noted for the track, 0 if none. */
    synchronized long lengthOf(String urn) {
        Long length = urn == null ? null : lengths.get(TrackUrn.normalize(urn));
        return length == null ? 0 : length;
    }

    /**
     * A listen ended. Only SoundCloud tracks count; one in between (neither skipped nor heard) says nothing.
     *
     * @param now When it ended, epoch milliseconds.
     * @return True if the listen is now waiting for {@link #applyTo}.
     */
    synchronized boolean listenEnded(String urn, long playedMs, long now) {
        if (!TrackUrn.isTrack(urn)) return false;
        String key = TrackUrn.normalize(urn);
        ListenVerdict verdict = ListenVerdict.of(playedMs, lengthOf(key));
        if (verdict == ListenVerdict.BETWEEN) return false;
        waiting.add(new Listen(key, verdict, now));
        return true;
    }

    /**
     * Puts the waiting listens into the ledger in the order they ended; they are no longer waiting then.
     *
     * @return True if the ledger changed, so it is worth saving.
     */
    synchronized boolean applyTo(WaveLedger ledger) {
        boolean changed = false;
        for (Listen listen = waiting.poll(); listen != null; listen = waiting.poll()) {
            ledger.record(listen.urn, listen.verdict, listen.time);
            changed = true;
        }
        return changed;
    }

    /** Forgets the listens that wait: the person asked to forget what the wave learned. */
    synchronized void dropWaiting() {
        waiting.clear();
    }
}
