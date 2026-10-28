package app.revanced.extension.soundcloud.lyrics;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * How much to stretch the timings of a record for a track that plays the song at another speed (slowed, sped up,
 * nightcore). Such a track is the original played slower or faster, so its lines come later or sooner by the same
 * factor all through: the track's length over the original's.
 * <p>
 * The original's length is the record's own when it differs from the track's. Some records are uploaded under the
 * length of the slowed track but keep the timings of the original; then the length is taken from the other records
 * of the song with the very same timed text. Pure arithmetic, used by {@link LyricsFinder} and tested on the JVM.
 */
final class TempoMatch {
    /** Lengths closer than this are the same cut: nothing to stretch. */
    static final double SAME_LENGTH_S = 3;
    /** Speeds outside this range are not a slowed or sped up copy but another song or cut. */
    static final double MIN_FACTOR = 0.6;
    static final double MAX_FACTOR = 1.6;
    /** Stretched text may end this much after the track does (a last line on the final note). */
    private static final long END_SLACK_MS = 5_000;
    /** Shorter than this is a preview record, longer a set: neither is the length of the song. */
    private static final double MIN_SONG_S = 30;
    private static final double MAX_SONG_S = 900;

    private TempoMatch() {
    }

    /**
     * @param trackMs       Length of the track that plays.
     * @param recordSeconds Length the record claims; NaN if unknown.
     * @param lastStampMs   Where the record's last line starts.
     * @param siblingSeconds Lengths of the other records of the song with the same timed text (the record itself may
     *                      be among them).
     * @return The factor to multiply every timing by; 1 when the timings already fit or nothing can be told.
     */
    static double factor(long trackMs, double recordSeconds, long lastStampMs, List<Double> siblingSeconds) {
        if (trackMs <= 0) return 1;
        double trackSeconds = trackMs / 1000.0;
        double original = Double.NaN;
        if (isSongLength(recordSeconds) && Math.abs(recordSeconds - trackSeconds) > SAME_LENGTH_S) {
            original = recordSeconds;
        } else {
            original = commonOtherLength(trackSeconds, siblingSeconds);
        }
        if (Double.isNaN(original)) return 1;
        double factor = trackSeconds / original;
        if (factor < MIN_FACTOR || factor > MAX_FACTOR) return 1;
        // Stretched, the words must still end with the track; otherwise the length belongs to another cut.
        if (lastStampMs * factor > trackMs + END_SLACK_MS) return 1;
        return factor;
    }

    /** The length most of the other records give, in whole seconds, among those that differ from the track's. */
    private static double commonOtherLength(double trackSeconds, List<Double> siblingSeconds) {
        if (siblingSeconds == null) return Double.NaN;
        Map<Long, Integer> votes = new HashMap<>();
        long best = -1;
        int bestVotes = 0;
        for (Double seconds : siblingSeconds) {
            if (seconds == null || !isSongLength(seconds) || Math.abs(seconds - trackSeconds) <= SAME_LENGTH_S) continue;
            long rounded = Math.round(seconds);
            int count = votes.containsKey(rounded) ? votes.get(rounded) + 1 : 1;
            votes.put(rounded, count);
            // Ties go to the length heard first: records come best first.
            if (count > bestVotes) {
                bestVotes = count;
                best = rounded;
            }
        }
        return best < 0 ? Double.NaN : best;
    }

    private static boolean isSongLength(double seconds) {
        return !Double.isNaN(seconds) && seconds >= MIN_SONG_S && seconds <= MAX_SONG_S;
    }
}
