package app.revanced.extension.soundcloud.lyrics;

import java.io.IOException;
import java.util.ArrayList;
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
 * The choice among the records is {@link RecordPick}'s, shared with {@link NeteaseFinder}.
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
                LyricsRecord exact = client.get(variant.artist, variant.track, query.durationMs);
                calls++;
                if (exact != null && seen.add(exact.id)) {
                    RecordScorer.Score scored = RecordScorer.score(exact, query);
                    pool.add(scored);
                    // A plain copy found by name may hide a synced one of the same song: only synced ends it here.
                    // A slowed or sped up copy waits for the search: the other records tell the original's length.
                    if (!query.retimed && scored.passes() && scored.total >= ACCEPT && scored.isSynced()) {
                        LyricsResult result = RecordPick.result(scored, query, pool);
                        if (result != null) return result;
                    }
                }
            }
            if (i > 0 || calls >= MAX_CALLS - 1) continue;
            stopIfCancelled();
            collect(client.search(variant.track, variant.artist, null), pool, seen, query);
            calls++;
            LyricsResult result = RecordPick.acceptable(pool, query, ACCEPT);
            if (result != null) return result;
        }
        if (calls < MAX_CALLS) {
            // The records are listed under words that are not ours: a free query finds songs the others miss.
            TrackQuery.Variant first = query.variants.get(0);
            stopIfCancelled();
            collect(client.search(null, null, (first.artist + " " + first.track).trim()), pool, seen, query);
            LyricsResult result = RecordPick.acceptable(pool, query, ACCEPT);
            if (result != null) return result;
        }
        return RecordPick.best(pool, query, 0);
    }

    private void stopIfCancelled() throws IOException {
        if (cancel != null && cancel.isCancelled()) throw new IOException("Lookup cancelled");
    }

    private static void collect(List<LyricsRecord> records, List<RecordScorer.Score> pool, Set<Long> seen, TrackQuery query) {
        for (LyricsRecord record : records) {
            if (seen.add(record.id)) pool.add(RecordScorer.score(record, query));
        }
    }
}
