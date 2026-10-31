package app.revanced.extension.soundcloud.lyrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the text of Kugou that is the track that plays: one search (by name and length), then the texts of at most
 * {@value #MAX_TEXTS} songs whose title, artist and length can be the track. The texts are judged like LRCLIB's
 * records ({@link RecordScorer}, {@link RecordPick}): a song of another artist is never taken. The words found are
 * credited to Kugou.
 */
final class KugouFinder {
    /** Texts fetched at most. */
    private static final int MAX_TEXTS = 2;

    private final KugouClient client;
    private final LookupCancel cancel;

    KugouFinder(KugouClient client, LookupCancel cancel) {
        this.client = client;
        this.cancel = cancel;
    }

    /** @return Found, instrumental or missing. A failed request throws: it says nothing about the track. */
    LyricsResult find(TrackQuery query) throws IOException {
        if (query.durationMs > LyricsFinder.MAX_TRACK_MS || query.variants.isEmpty()) return LyricsResult.missing();
        TrackQuery.Variant first = query.variants.get(0);
        stopIfCancelled();
        String name = first.artist.isEmpty() ? first.track : first.artist + " - " + first.track;
        List<LyricsRecord> songs = client.search(name, query.durationMs);

        List<RecordScorer.Score> named = new ArrayList<>();
        for (LyricsRecord song : songs) {
            RecordScorer.Score score = RecordScorer.scoreBeforeText(song, query);
            if (score.passes()) named.add(score);
        }
        List<RecordScorer.Score> pool = new ArrayList<>();
        int fetched = 0;
        for (RecordScorer.Score candidate : RecordPick.rank(named)) {
            if (fetched++ == MAX_TEXTS) break;
            stopIfCancelled();
            LyricsRecord song = client.withText(candidate.record);
            if (song.hasText() || song.instrumental) pool.add(RecordScorer.score(song, query));
        }
        LyricsResult result = RecordPick.best(pool, query, 0);
        return result.lyrics == null ? result : LyricsResult.found(result.lyrics.withSource(Lyrics.Source.KUGOU));
    }

    private void stopIfCancelled() throws IOException {
        if (cancel != null && cancel.isCancelled()) throw new IOException("Lookup cancelled");
    }
}
