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
 * well the title, the artist and the length fit (see {@link RecordScorer}), and the best one over the bar wins.
 * A synced record is preferred. A record by another artist is never taken for the track: words of another song are worse than none.
 * At most {@value #MAX_CALLS} requests per track (a request tried again after a failure counts once; a lookup that
 * fails is over anyway).
 */
final class LyricsFinder {
    /** Longer than this is a mix or a set: no lyrics, no request. */
    static final long MAX_TRACK_MS = 15 * 60 * 1000L;

    private static final int MAX_CALLS = 4;
    /** A record this good, found by an exact request or a search, ends the lookup. */
    private static final double ACCEPT = 0.8;

    private final LrclibClient client;
    private final LookupCancel cancel;

    LyricsFinder(LrclibClient client, LookupCancel cancel) {
        this.client = client;
        this.cancel = cancel;
    }

    /** @return Found, instrumental or missing. A failed request throws: it says nothing about the track. */
    LyricsResult find(TrackQuery query) throws IOException {
        if (query.durationMs > MAX_TRACK_MS || query.variants.isEmpty()) return LyricsResult.missing();

        List<RecordScorer.Score> pool = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        int calls = 0;
        // The last request is kept for a free query. Before it: an exact request and a search for the likeliest guess,
        // and one exact request for each guess after that.
        for (int i = 0; i < query.variants.size() && calls < MAX_CALLS - 1; i++) {
            TrackQuery.Variant variant = query.variants.get(i);
            if (!variant.artist.isEmpty()) {
                stopIfCancelled();
                LrclibRecord exact = client.get(variant.artist, variant.track, query.durationMs);
                calls++;
                if (exact != null && seen.add(exact.id)) {
                    RecordScorer.Score scored = RecordScorer.score(exact, query);
                    pool.add(scored);
                    // A plain copy found by name may hide a synced one of the same song: only synced ends it here.
                    // A slowed or sped up copy waits for the search: the other records tell the original's length.
                    if (!query.retimed && scored.passes() && scored.total >= ACCEPT && scored.isSynced()) {
                        LyricsResult result = result(scored, query, pool);
                        if (result != null) return result;
                    }
                }
            }
            if (i > 0 || calls >= MAX_CALLS - 1) continue;
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

    private static void collect(List<LrclibRecord> records, List<RecordScorer.Score> pool, Set<Long> seen, TrackQuery query) {
        for (LrclibRecord record : records) {
            if (seen.add(record.id)) pool.add(RecordScorer.score(record, query));
        }
    }

    /** The best record, if it is good enough to stop looking. */
    private static LyricsResult acceptable(List<RecordScorer.Score> pool, TrackQuery query) {
        List<RecordScorer.Score> ranked = rank(pool);
        if (ranked.isEmpty() || !ranked.get(0).passes() || ranked.get(0).total < ACCEPT) return null;
        return best(pool, query, ACCEPT);
    }

    /** The first of the ranked records over the bar that has a text to show. */
    private static LyricsResult best(List<RecordScorer.Score> pool, TrackQuery query, double bar) {
        for (RecordScorer.Score scored : rank(pool)) {
            if (!scored.passes() || scored.total < bar) continue;
            LyricsResult result = result(scored, query, pool);
            if (result != null) return result;
        }
        return bar > 0 ? null : LyricsResult.missing();
    }

    /** Highest score first; of equal ones the closer length, then the synced, then the older record. */
    private static List<RecordScorer.Score> rank(List<RecordScorer.Score> pool) {
        List<RecordScorer.Score> ranked = new ArrayList<>(pool);
        Collections.sort(ranked, new Comparator<RecordScorer.Score>() {
            @Override
            public int compare(RecordScorer.Score a, RecordScorer.Score b) {
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
    private static LyricsResult result(RecordScorer.Score scored, TrackQuery query, List<RecordScorer.Score> pool) {
        LrclibRecord record = scored.record;
        Lyrics synced = LrcParser.parseSynced(record.synced);
        Lyrics plain = LrcParser.parsePlain(record.plain);
        if (synced != null && query.retimed) {
            // The words of the original, played slower or faster: every line moves by the same factor.
            double factor = TempoMatch.factor(query.durationMs, record.durationSeconds, synced.lastTime(),
                    twinLengths(record, pool));
            Lyrics stretched = synced.stretched(factor);
            if (factor != 1 && RecordScorer.endsInTime(stretched, query)) return LyricsResult.found(stretched);
        }
        Lyrics lyrics = null;
        if (synced != null && RecordScorer.timingsFit(record, query) && RecordScorer.endsInTime(synced, query)) lyrics = synced;
        else if (plain != null) lyrics = plain;
        // Timings that do not fit the track would highlight the wrong line: the words alone are better.
        else if (synced != null) lyrics = synced.withoutTimes();
        if (lyrics != null) return LyricsResult.found(lyrics);
        return record.instrumental ? LyricsResult.instrumental() : null;
    }

    /** The lengths the records with exactly this timed text claim: copies of one upload under different lengths. */
    private static List<Double> twinLengths(LrclibRecord record, List<RecordScorer.Score> pool) {
        String text = record.synced.trim();
        List<Double> lengths = new ArrayList<>();
        for (RecordScorer.Score other : pool) {
            if (other.record.synced.trim().equals(text)) lengths.add(other.record.durationSeconds);
        }
        return lengths;
    }
}
