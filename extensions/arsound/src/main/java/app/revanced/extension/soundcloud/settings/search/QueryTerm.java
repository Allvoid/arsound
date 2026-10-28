package app.revanced.extension.soundcloud.settings.search;

import java.util.Collections;
import java.util.Map;

/**
 * One word (or two-word phrase) of a query and everything it may stand for: itself, its stem and the words of the
 * {@link Synonyms} groups. It judges single {@link Token}s of the rows. Made by {@link QueryPlan} and
 * {@link SearchIndex}.
 */
final class QueryTerm {
    /** What the person typed, for messages. */
    final String typed;
    /** The word that is matched (the typed one, or the same on the other keyboard layout, or a corrected one). */
    final String word;
    private final String stem;
    /** False for phrases and corrected words: they match only through {@link #expansion}. */
    private final boolean direct;
    private final Map<String, MatchKind> expansion;
    /** How much worse than a typed word a match of this term is. */
    final double scale;
    /** How the term came about. */
    final Reason origin;

    private QueryTerm(String typed, String word, boolean direct, Map<String, MatchKind> expansion, double scale, Reason origin) {
        this.typed = typed;
        this.word = word;
        this.stem = Stemmer.stem(word);
        this.direct = direct;
        this.expansion = expansion;
        this.scale = scale;
        this.origin = origin;
    }

    /** A word as it was typed. */
    static QueryTerm word(String word, Synonyms synonyms) {
        return new QueryTerm(word, word, true, synonyms.expand(Stemmer.stem(word)), 1.0, Reason.EXACT);
    }

    /** The same word read as typed on the other keyboard layout. */
    static QueryTerm otherLayout(QueryTerm typed, String converted, Synonyms synonyms) {
        return new QueryTerm(typed.typed, converted, true, synonyms.expand(Stemmer.stem(converted)), 0.9, Reason.LAYOUT);
    }

    /** Two words that mean one thing together ("рабочий стол"); found only through the synonyms. */
    static QueryTerm phrase(String first, String second, Map<String, MatchKind> expansion) {
        return new QueryTerm(first + " " + second, first + " " + second, false, expansion, 1.0, Reason.EXACT);
    }

    /** A guess for a word with a typing mistake: the stems it may have meant, and what those stand for. */
    static QueryTerm corrected(String typed, String display, Map<String, MatchKind> expansion) {
        return new QueryTerm(typed, display, false, expansion, 0.5, Reason.FUZZY);
    }

    /** The widest way this term meets the token, or null if it does not. */
    MatchKind match(Token token) {
        MatchKind best = null;
        if (direct && !token.phrase) {
            if (token.norm.startsWith(word)) best = MatchKind.DIRECT_START;
            else if (token.stem.equals(stem) || stem.length() >= 4 && token.stem.startsWith(stem)) best = MatchKind.STEM;
            else if (token.norm.contains(word)) best = MatchKind.DIRECT_PART;
        }
        MatchKind related = expansion.get(token.stem);
        if (related != null && (best == null || related.factor > best.factor)) best = related;
        return best;
    }

    /** The range of the token that this term should colour, for the kind of match it had. */
    int[] range(Token token, MatchKind kind) {
        if (kind == MatchKind.DIRECT_START) return new int[]{token.start, token.start + word.length()};
        if (kind == MatchKind.DIRECT_PART) {
            int at = token.norm.indexOf(word);
            return new int[]{token.start + at, token.start + at + word.length()};
        }
        return new int[]{token.start, token.end};
    }

    static final Map<String, MatchKind> NOTHING = Collections.emptyMap();
}
