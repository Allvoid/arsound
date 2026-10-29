package app.revanced.extension.soundcloud.lyrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The choice among the records a lyrics service gave for one track: the best scored record over the bar, and its
 * text in the best form it can be followed (synced when the timings fit the track, stretched for a slowed or sped up
 * copy, else plain). Shared by the services' finders ({@link LyricsFinder} for LRCLIB, {@link NeteaseFinder}).
 */
final class RecordPick {
    private RecordPick() {
    }

    /** The best record, if it is good enough (at least {@code accept}) to stop looking. */
    static LyricsResult acceptable(List<RecordScorer.Score> pool, TrackQuery query, double accept) {
        List<RecordScorer.Score> ranked = rank(pool);
        if (ranked.isEmpty() || !ranked.get(0).passes() || ranked.get(0).total < accept) return null;
        return best(pool, query, accept);
    }

    /** The first of the ranked records over the bar that has a text to show. */
    static LyricsResult best(List<RecordScorer.Score> pool, TrackQuery query, double bar) {
        for (RecordScorer.Score scored : rank(pool)) {
            if (!scored.passes() || scored.total < bar) continue;
            LyricsResult result = result(scored, query, pool);
            if (result != null) return result;
        }
        return bar > 0 ? null : LyricsResult.missing();
    }

    /** Highest score first; of equal ones the closer length, then the synced, then the older record. */
    static List<RecordScorer.Score> rank(List<RecordScorer.Score> pool) {
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
    static LyricsResult result(RecordScorer.Score scored, TrackQuery query, List<RecordScorer.Score> pool) {
        LyricsRecord record = scored.record;
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
    private static List<Double> twinLengths(LyricsRecord record, List<RecordScorer.Score> pool) {
        String text = record.synced.trim();
        List<Double> lengths = new ArrayList<>();
        for (RecordScorer.Score other : pool) {
            if (other.record.synced.trim().equals(text)) lengths.add(other.record.durationSeconds);
        }
        return lengths;
    }
}
