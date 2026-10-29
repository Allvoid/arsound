package app.revanced.extension.soundcloud.lyrics;

import java.util.ArrayList;
import java.util.List;

/**
 * How well a record of LRCLIB is the track that plays, and whether its timings can be followed. Pure arithmetic over
 * the record and the {@link TrackQuery}, so it runs in the JVM tests.
 */
final class RecordScorer {
    /** The timings of a record are followed only if its length is this close to the track's. */
    static final long TIMINGS_FIT_MS = 6_000;
    /** The same for a track whose title names another version (slowed, live): its own timings drift sooner. */
    static final long TIMINGS_FIT_VERSION_MS = 3_000;
    /** Synced text that goes on longer than the track by more than this has stamps for another song or another cut. */
    private static final long LAST_STAMP_SLACK_MS = 5_000;

    private static final double MIN_SCORE = 0.62;
    private static final double MIN_TITLE = 0.6;
    /** Below this the record's artist is not the one named, whatever the title says. */
    private static final double MIN_ARTIST = 0.3;
    /** An artist that only partly matches (or is spelled in another alphabet) is taken if the length is this close. */
    private static final double UNCONFIRMED_LENGTH_S = 2;
    /** ... and the title is this good a match. */
    private static final double UNCONFIRMED_TITLE = 0.9;

    private RecordScorer() {
    }

    /** One record with the marks it earned. */
    static final class Score {
        final LyricsRecord record;
        final double total;
        final double title;
        final double lengthDifferenceSeconds;
        /** Whether the record's artist is, or can be taken for, the track's. */
        final boolean artistFits;

        Score(LyricsRecord record, double total, double title, double lengthDifferenceSeconds,
              boolean artistFits) {
            this.record = record;
            this.total = total;
            this.title = title;
            this.lengthDifferenceSeconds = lengthDifferenceSeconds;
            this.artistFits = artistFits;
        }

        /** Whether the record is over the bar to be taken for the track at all. */
        boolean passes() {
            return artistFits && title >= MIN_TITLE && total >= MIN_SCORE;
        }

        boolean isSynced() {
            return !record.synced.trim().isEmpty();
        }
    }

    /** Whether the record is about as long as the track, so its timings can be followed. */
    static boolean timingsFit(LyricsRecord record, TrackQuery query) {
        if (Double.isNaN(record.durationSeconds) || query.durationMs <= 0) return false;
        double difference = Math.abs(record.durationSeconds * 1000 - query.durationMs);
        return difference <= (query.versionTagged ? TIMINGS_FIT_VERSION_MS : TIMINGS_FIT_MS);
    }

    /** Whether the stamps stop about when the track does: a text that goes on much longer is not for this track. */
    static boolean endsInTime(Lyrics synced, TrackQuery query) {
        return synced.lastTime() <= query.durationMs + LAST_STAMP_SLACK_MS;
    }

    /** How well the record is the track: 0.4 title, 0.25 artist, 0.25 length, 0.1 synced; nothing if it has no text. */
    static Score score(LyricsRecord record, TrackQuery query) {
        return score(record, query, record.hasText() || record.instrumental);
    }

    /**
     * How well a song whose text is not fetched yet can be the track, by its title, artist and length: whether its
     * text is worth a request (NetEase gives the text of a song apart from the search).
     */
    static Score scoreBeforeText(LyricsRecord record, TrackQuery query) {
        return score(record, query, true);
    }

    private static Score score(LyricsRecord record, TrackQuery query, boolean content) {
        double length = lengthScore(record.durationSeconds, query.durationMs);
        double difference = Double.isNaN(record.durationSeconds) || query.durationMs <= 0
                ? Double.MAX_VALUE : Math.abs(record.durationSeconds - query.durationMs / 1000.0);
        double bestTotal = -1;
        double bestTitle = 0;
        boolean bestFits = false;
        // Judged under every guess: the one that is right makes the record fit.
        for (TrackQuery.Variant variant : query.variants) {
            double artist = 0;
            boolean foreign = true;
            List<String> names = query.artistNames(variant);
            for (String name : names) {
                artist = Math.max(artist, TextMatch.similarity(name, record.artistName));
                if (!TextMatch.differentAlphabets(name, record.artistName)) foreign = false;
            }
            // A title that only contains the other counts as it when the rest is an artist or a tag, not more words.
            List<String> artistWords = new ArrayList<>(names);
            artistWords.add(record.artistName);
            double title = Math.max(TextMatch.titleSimilarity(variant.track, record.trackName, artistWords),
                    TextMatch.titleSimilarity(variant.track, TitleCleaner.withoutTags(record.trackName), artistWords));
            double synced = record.synced.trim().isEmpty() ? 0 : 1;
            double total = content ? 0.40 * title + 0.25 * artist + 0.25 * length + 0.10 * synced : 0;
            // Two songs of one name are told apart by the artist. Where the names do not settle it (they share a word,
            // or are spelled in different alphabets) only a record of the same length under an almost identical title
            // is taken; names with nothing in common mean another artist's song.
            boolean fits = artist >= MIN_ARTIST;
            if (!fits && !query.versionTagged && difference <= UNCONFIRMED_LENGTH_S && title >= UNCONFIRMED_TITLE) {
                fits = artist > 0 || foreign;
            }
            // A guess the artist agrees with outranks any other; among equals, the better score.
            if ((fits && !bestFits) || (fits == bestFits && total > bestTotal)) {
                bestTotal = total;
                bestTitle = title;
                bestFits = fits;
            }
        }
        return new Score(record, Math.max(0, bestTotal), bestTitle, difference, bestFits);
    }

    private static double lengthScore(double recordSeconds, long trackMs) {
        if (Double.isNaN(recordSeconds) || trackMs <= 0) return 0.3;
        double difference = Math.abs(recordSeconds - trackMs / 1000.0);
        if (difference <= 2) return 1.0;
        if (difference <= 5) return 0.75;
        if (difference <= 10) return 0.45;
        if (difference <= 20) return 0.15;
        return 0;
    }
}
