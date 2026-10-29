package app.revanced.extension.soundcloud.lyrics;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds the song of NetEase Cloud Music that is the track that plays: one search, then the texts of at most
 * {@value #MAX_TEXTS} songs whose title, artist and length can be the track (each text costs a request). The songs
 * are judged like LRCLIB's records ({@link RecordScorer}, {@link RecordPick}): a song of another artist is never
 * taken. The words found are credited to NetEase.
 */
final class NeteaseFinder {
    /** Songs whose text is fetched at most. */
    private static final int MAX_TEXTS = 2;

    private final NeteaseClient client;
    private final LookupCancel cancel;

    NeteaseFinder(NeteaseClient client, LookupCancel cancel) {
        this.client = client;
        this.cancel = cancel;
    }

    /** @return Found, instrumental or missing. A failed request throws: it says nothing about the track. */
    LyricsResult find(TrackQuery query) throws IOException {
        if (query.durationMs > LyricsFinder.MAX_TRACK_MS || query.variants.isEmpty()) return LyricsResult.missing();
        TrackQuery.Variant first = query.variants.get(0);
        stopIfCancelled();
        List<LyricsRecord> songs = client.search((first.artist + " " + first.track).trim());

        // The names and lengths first: only a song that can be the track is worth a request for its text.
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
        return result.lyrics == null ? result : LyricsResult.found(result.lyrics.withSource(Lyrics.Source.NETEASE));
    }

    private void stopIfCancelled() throws IOException {
        if (cancel != null && cancel.isCancelled()) throw new IOException("Lookup cancelled");
    }
}
