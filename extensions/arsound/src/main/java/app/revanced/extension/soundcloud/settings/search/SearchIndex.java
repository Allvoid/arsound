package app.revanced.extension.soundcloud.settings.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import app.revanced.extension.soundcloud.settings.search.SearchEntry.Field;

/**
 * Every row of the settings, and the search over them. A query word finds a row by its letters, by the stem of the word
 * ("уведомления" finds "уведомлении"), by words that mean the same or nearly the same (see {@link Synonyms}), and, when
 * nothing else works, as typed on the wrong keyboard layout or with a typing mistake. All words of a query must be found
 * in a row. Used by SettingsSearch; has no Android parts, so it is tested on the JVM.
 *
 * <p>Score of a row: for every query word the best of "part of the row" times "how the word was found", summed over the
 * words. Equal scores keep the order the rows were added in, which is the order of the screens.
 */
public final class SearchIndex {
    /** A find that is worth less than this is noise. */
    private static final double MIN_WORD_SCORE = 8;
    private static final int ALL_WORDS_IN_TITLE_BONUS = 15;
    private static final int TITLE_IS_THE_QUERY_BONUS = 40;
    private static final int SECTION_ROW_BONUS = 5;
    private static final int MAX_CORRECTIONS = 5;
    /** From this length a word may have two mistakes; a shorter one has at most one, or everything would match everything. */
    private static final int LONG_WORD = 8;

    /** A stem shorter than this is corrected only into a word of the same length. */
    private static final int SHORT_STEM = 4;
    /** From this stem length a half-typed word with a mistake is matched against the beginning of longer words. */
    private static final int PREFIX_STEM = 5;

    private final Synonyms synonyms;
    private final List<SearchEntry> entries = new ArrayList<>();
    /** Stem to a word of that stem, for the correction of typing mistakes; built when first needed. */
    private Map<String, String> vocabulary;

    public SearchIndex() {
        this(Synonyms.standard());
    }

    SearchIndex(Synonyms synonyms) {
        this.synonyms = synonyms;
    }

    public void add(SearchEntry entry) {
        entry.attachPhrases(synonyms);
        entries.add(entry);
        vocabulary = null;
    }

    /** The best find of one query term in one row. */
    private static final class Found {
        final double score;
        final MatchKind kind;
        final Token token;
        final Field field;

        Found(double score, MatchKind kind, Token token, Field field) {
            this.score = score;
            this.kind = kind;
            this.token = token;
            this.field = field;
        }
    }

    /** A term with what it found in every row (null where nothing). */
    private static final class Column {
        final QueryTerm term;
        final Found[] finds;
        boolean alive;

        Column(QueryTerm term, int rows) {
            this.term = term;
            this.finds = new Found[rows];
        }
    }

    /** Rows that contain every word of the query, the best matches first. */
    public List<Hit> search(String query, int limit) {
        List<Hit> found = run(query, limit, true, true);
        // Russian typed on the English layout uses punctuation keys for б ж х э ю ъ, which the word splitter drops.
        String converted = KeyboardLayout.punctuationKeysAsLetters(query);
        if (converted == null) return found;
        // The punctuation key between letters is not a word on its own for the person, so if the other reading finds
        // rows it wins; otherwise the query stays as it was.
        List<Hit> viaLayout = run(converted, limit, true, true);
        if (viaLayout.isEmpty()) return found;
        List<Hit> relabelled = new ArrayList<>(viaLayout.size());
        for (Hit hit : viaLayout) {
            relabelled.add(new Hit(hit.entry, hit.score, hit.titleRanges, Reason.LAYOUT, query.trim(), converted));
        }
        return relabelled;
    }

    /** As {@link #search}, but a query that finds nothing as typed is not retried with another layout or a mistake fixed. */
    public List<Hit> searchAsTyped(String query, int limit) {
        return run(query, limit, true, false);
    }

    /** Rows that contain at least one word of the query, for when {@link #search} found nothing. */
    public List<Hit> searchAny(String query, int limit) {
        return run(query, limit, false, false);
    }

    /** True if the word, as typed, finds some row by its letters, its stem or its meaning (no corrections tried). */
    public boolean knows(String word) {
        List<Token> tokens = Text.tokens(word);
        if (tokens.isEmpty()) return false;
        return column(QueryTerm.word(tokens.get(0).norm, synonyms)).alive;
    }

    /** Words of the rows that look like the word with a typing mistake fixed; the likeliest first. */
    public List<String> correctionsFor(String word) {
        List<String> result = new ArrayList<>();
        Map<String, String> words = vocabulary();
        for (String stem : fuzzyStems(Text.normalize(word))) result.add(words.get(stem));
        return result;
    }

    Synonyms synonyms() {
        return synonyms;
    }

    // region Search

    private List<Hit> run(String query, int limit, boolean all, boolean rescue) {
        QueryPlan plan = new QueryPlan(query, synonyms);
        if (plan.isEmpty() || entries.isEmpty()) return Collections.emptyList();
        Hit[] best = new Hit[entries.size()];
        for (List<QueryTerm> reading : plan.readings) {
            List<Column> columns = new ArrayList<>();
            for (QueryTerm term : reading) {
                Column column = column(term);
                columns.add(rescue ? rescued(column) : column);
            }
            for (int i = 0; i < entries.size(); i++) {
                Hit hit = combine(i, columns, plan, all);
                if (hit != null && (best[i] == null || hit.score > best[i].score)) best[i] = hit;
            }
        }
        List<Hit> found = new ArrayList<>();
        for (Hit hit : best) {
            if (hit == null) continue;
            // Stable insertion by score: equal scores keep the order of the screens.
            int at = found.size();
            while (at > 0 && found.get(at - 1).score < hit.score) at--;
            found.add(at, hit);
        }
        return found.size() > limit ? new ArrayList<>(found.subList(0, limit)) : found;
    }

    private Column column(QueryTerm term) {
        Column column = new Column(term, entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Found found = find(entries.get(i), term);
            column.finds[i] = found;
            if (found != null) column.alive = true;
        }
        return column;
    }

    private static Found find(SearchEntry entry, QueryTerm term) {
        Found best = null;
        for (Field field : Field.values()) {
            for (Token token : entry.tokens(field)) {
                MatchKind kind = term.match(token);
                if (kind == null) continue;
                if (!field.widensByMeaning && (kind == MatchKind.SYNONYM || kind == MatchKind.RELATED)) continue;
                double score = field.base * kind.factor * term.scale;
                if (score >= MIN_WORD_SCORE && (best == null || score > best.score)) best = new Found(score, kind, token, field);
            }
        }
        return best;
    }

    /** A term that found nothing gets a second try: typed on the other layout, then with a mistake fixed. */
    private Column rescued(Column column) {
        QueryTerm term = column.term;
        // A phrase of two words has nothing to be corrected into.
        if (column.alive || term.word.indexOf(' ') >= 0) return column;
        String converted = null;
        if (KeyboardLayout.isLatin(term.word)) converted = KeyboardLayout.toCyrillic(term.word);
        else if (KeyboardLayout.isCyrillic(term.word)) converted = KeyboardLayout.toLatin(term.word);
        if (converted != null) {
            Column other = column(QueryTerm.otherLayout(term, converted, synonyms));
            if (other.alive) return other;
        }
        if (term.word.length() >= 4) {
            List<String> stems = fuzzyStems(term.word);
            if (!stems.isEmpty()) {
                Map<String, MatchKind> expansion = new HashMap<>();
                for (String stem : stems) {
                    expansion.put(stem, MatchKind.STEM);
                    for (Map.Entry<String, MatchKind> related : synonyms.expand(stem).entrySet()) {
                        MatchKind old = expansion.get(related.getKey());
                        if (old == null || related.getValue().factor > old.factor) expansion.put(related.getKey(), related.getValue());
                    }
                }
                Column fixed = column(QueryTerm.corrected(term.typed, vocabulary().get(stems.get(0)), expansion));
                if (fixed.alive) return fixed;
            }
        }
        return column;
    }

    private Hit combine(int row, List<Column> columns, QueryPlan plan, boolean all) {
        SearchEntry entry = entries.get(row);
        double total = 0;
        int matched = 0;
        boolean allInTitle = true;
        Reason reason = Reason.EXACT;
        String queryWord = "";
        String matchedWord = "";
        for (Column column : columns) {
            Found found = column.finds[row];
            if (found == null) {
                if (all) return null;
                continue;
            }
            total += found.score;
            matched++;
            if (found.field != Field.TITLE) allInTitle = false;
            Reason why = reasonOf(column.term, found.kind);
            if (why.ordinal() > reason.ordinal()) {
                reason = why;
                queryWord = column.term.typed;
                matchedWord = why == Reason.SYNONYM || why == Reason.RELATED ? found.token.norm : column.term.word;
            }
        }
        if (matched == 0) return null;
        if (allInTitle && matched == columns.size()) total += ALL_WORDS_IN_TITLE_BONUS;
        if (plan.normalized.equals(entry.titleKey())) total += TITLE_IS_THE_QUERY_BONUS;
        if (entry.sectionRow) total += SECTION_ROW_BONUS;
        return new Hit(entry, (int) Math.round(total), titleRanges(entry, columns), reason, queryWord, matchedWord);
    }

    private static Reason reasonOf(QueryTerm term, MatchKind kind) {
        if (term.origin != Reason.EXACT) return term.origin;
        if (kind == MatchKind.SYNONYM) return Reason.SYNONYM;
        if (kind == MatchKind.RELATED) return Reason.RELATED;
        return Reason.EXACT;
    }

    /** Where each term of the query stands in the title, in the order of the terms. */
    private static List<int[]> titleRanges(SearchEntry entry, List<Column> columns) {
        List<int[]> ranges = new ArrayList<>();
        for (Column column : columns) {
            for (Token token : entry.tokens(Field.TITLE)) {
                MatchKind kind = column.term.match(token);
                if (kind != null) ranges.add(column.term.range(token, kind));
            }
        }
        return ranges;
    }

    // endregion

    // region Typing mistakes

    private Map<String, String> vocabulary() {
        if (vocabulary == null) {
            Map<String, String> words = new TreeMap<>();
            for (Field field : Field.values()) {
                for (SearchEntry entry : entries) {
                    for (Token token : entry.tokens(field)) if (!token.phrase) words.putIfAbsent(token.stem, token.norm);
                }
            }
            for (Map.Entry<String, String> word : synonyms.vocabulary().entrySet()) words.putIfAbsent(word.getKey(), word.getValue());
            vocabulary = words;
        }
        return vocabulary;
    }

    /** Stems of known words that are one mistake away from the word (two for a long one), the nearest first. */
    private List<String> fuzzyStems(String word) {
        String stem = Stemmer.stem(word);
        int tolerance = word.length() >= LONG_WORD ? 2 : 1;
        for (int allowed = 1; allowed <= tolerance; allowed++) {
            List<String> near = new ArrayList<>();
            for (String known : vocabulary().keySet()) {
                if (known.length() < 3 || known.equals(stem)) continue;
                // A very short stem may only be a wrong or swapped letter of a word of the same length; anything looser
                // would make nonsense match rows.
                if (stem.length() < SHORT_STEM && known.length() != stem.length()) continue;
                // The beginning counts too, so a word half typed and with a mistake is still understood, but only for a
                // stem long enough that the beginning means something.
                boolean close = EditDistance.within(stem, known, allowed)
                        || stem.length() >= PREFIX_STEM && known.length() > stem.length()
                        && EditDistance.within(stem, known.substring(0, stem.length()), allowed);
                if (close) near.add(known);
                if (near.size() == MAX_CORRECTIONS) break;
            }
            if (!near.isEmpty()) return near;
        }
        return Collections.emptyList();
    }

    // endregion
}
