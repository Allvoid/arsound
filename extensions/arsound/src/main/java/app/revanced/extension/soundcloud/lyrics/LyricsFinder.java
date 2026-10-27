package app.revanced.extension.soundcloud.lyrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the record of LRCLIB that is the track that plays.
 * <p>
 * Several records of the same song exist (other cuts, other lengths, a deluxe version glued together, plain copies of
 * synced ones, junk), and SoundCloud names are loose, so the first hit is not taken: every record is scored by how
 * well the title, the artist and the length fit, and the best one over the bar wins. A synced record is preferred.
 * At most {@value #MAX_CALLS} requests per track.
 */
final class LyricsFinder {
    /** Stops a lookup between requests, for a track that is not the one wanted any more. */
    interface Cancel {
        boolean isCancelled();
    }

    /** Longer than this is a mix or a set: no lyrics, no request. */
    static final long MAX_TRACK_MS = 15 * 60 * 1000L;
    /** The timings of a record are followed only if its length is this close to the track's. */
    static final long TIMINGS_FIT_MS = 6_000;
    /** The same for a track whose title names another version (slowed, live): its own timings drift sooner. */
    static final long TIMINGS_FIT_VERSION_MS = 3_000;

    private static final int MAX_CALLS = 5;
    /** A record this good, found by an exact request or a search, ends the lookup. */
    private static final double ACCEPT = 0.8;
    private static final double MIN_SCORE = 0.62;
    private static final double MIN_TITLE = 0.6;

    private static final class Scored {
        final LrclibClient.Record record;
        final double total;
        final double title;
        final double lengthDifferenceSeconds;

        Scored(LrclibClient.Record record, double total, double title, double lengthDifferenceSeconds) {
            this.record = record;
            this.total = total;
            this.title = title;
            this.lengthDifferenceSeconds = lengthDifferenceSeconds;
        }

        boolean passes() {
            return title >= MIN_TITLE && total >= MIN_SCORE;
        }

        boolean isSynced() {
            return !record.synced.trim().isEmpty();
        }
    }

    private final LrclibClient client;
    private final Cancel cancel;

    LyricsFinder(LrclibClient client, Cancel cancel) {
        this.client = client;
        this.cancel = cancel;
    }

    /** @return Found, instrumental or missing. A failed request throws: it says nothing about the track. */
    LyricsResult find(TrackQuery query) throws IOException {
        if (query.durationMs > MAX_TRACK_MS || query.variants.isEmpty()) return LyricsResult.missing();

        List<Scored> pool = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int calls = 0;
        for (int i = 0; i < query.variants.size() && calls < MAX_CALLS; i++) {
            TrackQuery.Variant variant = query.variants.get(i);
            if (!variant.artist.isEmpty()) {
                stopIfCancelled();
                LrclibClient.Record exact = client.get(variant.artist, variant.track, query.durationMs);
                calls++;
                if (exact != null && seen.add(exact.id)) {
                    Scored scored = score(exact, query);
                    pool.add(scored);
                    // A plain copy found by name may hide a synced one of the same song: only synced ends it here.
                    if (scored.passes() && scored.total >= ACCEPT && scored.isSynced()) {
                        LyricsResult result = result(scored, query);
                        if (result != null) return result;
                    }
                }
            }
            if (calls >= MAX_CALLS) break;
            stopIfCancelled();
            collect(client.search(variant.track, variant.artist, null), pool, seen, query);
            calls++;
            LyricsResult result = acceptable(pool, query);
            if (result != null) return result;
        }
        if (calls < MAX_CALLS) {
            // The records are listed under words that are not ours: a free query finds songs the others miss.
            TrackQuery.Variant first = query.variants.get(0);
            stopIfCancelled();
            collect(client.search(null, null, (first.artist + " " + first.track).trim()), pool, seen, query);
            LyricsResult result = acceptable(pool, query);
            if (result != null) return result;
        }
        return best(pool, query, 0);
    }

    private void stopIfCancelled() throws IOException {
        if (cancel != null && cancel.isCancelled()) throw new IOException("Lookup cancelled");
    }

    private static void collect(List<LrclibClient.Record> records, List<Scored> pool, Set<Long> seen, TrackQuery query) {
        for (LrclibClient.Record record : records) {
            if (seen.add(record.id)) pool.add(score(record, query));
        }
    }

    /** The best record, if it is good enough to stop looking. */
    private static LyricsResult acceptable(List<Scored> pool, TrackQuery query) {
        List<Scored> ranked = rank(pool);
        if (ranked.isEmpty() || !ranked.get(0).passes() || ranked.get(0).total < ACCEPT) return null;
        return best(pool, query, ACCEPT);
    }

    /** The first of the ranked records over the bar that has a text to show. */
    private static LyricsResult best(List<Scored> pool, TrackQuery query, double bar) {
        for (Scored scored : rank(pool)) {
            if (!scored.passes() || scored.total < bar) continue;
            LyricsResult result = result(scored, query);
            if (result != null) return result;
        }
        return bar > 0 ? null : LyricsResult.missing();
    }

    /** Highest score first; of equal ones the closer length, then the synced, then the older record. */
    private static List<Scored> rank(List<Scored> pool) {
        List<Scored> ranked = new ArrayList<>(pool);
        Collections.sort(ranked, new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) {
                int byScore = Double.compare(b.total, a.total);
                if (Math.abs(a.total - b.total) > 1e-9) return byScore;
                int byLength = Double.compare(a.lengthDifferenceSeconds, b.lengthDifferenceSeconds);
                if (byLength != 0) return byLength;
                if (a.isSynced() != b.isSynced()) return a.isSynced() ? -1 : 1;
                return a.record.id < b.record.id ? -1 : a.record.id == b.record.id ? 0 : 1;
            }
        });
        return ranked;
    }

    /** The result for a chosen record: the text in the best form it can be followed, or "instrumental". */
    private static LyricsResult result(Scored scored, TrackQuery query) {
        LrclibClient.Record record = scored.record;
        Lyrics synced = LrcParser.parseSynced(record.synced);
        Lyrics plain = LrcParser.parsePlain(record.plain);
        Lyrics lyrics = null;
        if (synced != null && timingsFit(record, query)) lyrics = synced;
        else if (plain != null) lyrics = plain;
        // Timings that do not fit the track would highlight the wrong line: the words alone are better.
        else if (synced != null) lyrics = synced.withoutTimes();
        if (lyrics != null) return LyricsResult.found(lyrics);
        return record.instrumental ? LyricsResult.instrumental() : null;
    }

    /** Whether the record is about as long as the track, so its timings can be followed. */
    static boolean timingsFit(LrclibClient.Record record, TrackQuery query) {
        if (Double.isNaN(record.durationSeconds) || query.durationMs <= 0) return false;
        double difference = Math.abs(record.durationSeconds * 1000 - query.durationMs);
        return difference <= (query.versionTagged ? TIMINGS_FIT_VERSION_MS : TIMINGS_FIT_MS);
    }

    /** How well the record is the track: 0.4 title, 0.25 artist, 0.25 length, 0.1 synced; nothing if it has no text. */
    private static Scored score(LrclibClient.Record record, TrackQuery query) {
        boolean content = record.hasText() || record.instrumental;
        double length = lengthScore(record.durationSeconds, query.durationMs);
        double difference = Double.isNaN(record.durationSeconds) || query.durationMs <= 0
                ? Double.MAX_VALUE : Math.abs(record.durationSeconds - query.durationMs / 1000.0);
        double bestTotal = -1;
        double bestTitle = 0;
        // Judged under every guess: the one that is right makes the record fit.
        for (TrackQuery.Variant variant : query.variants) {
            double title = Math.max(TextMatch.similarity(variant.track, record.trackName),
                    TextMatch.similarity(variant.track, TrackQuery.withoutTags(record.trackName)));
            double artist = 0;
            for (String name : query.artistNames(variant)) {
                artist = Math.max(artist, TextMatch.similarity(name, record.artistName));
            }
            double synced = record.synced.trim().isEmpty() ? 0 : 1;
            double total = content ? 0.40 * title + 0.25 * artist + 0.25 * length + 0.10 * synced : 0;
            if (total > bestTotal) {
                bestTotal = total;
                bestTitle = title;
            }
        }
        return new Scored(record, Math.max(0, bestTotal), bestTitle, difference);
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
