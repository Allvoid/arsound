package app.revanced.extension.soundcloud.settings.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A query taken apart: its words without the small ones, and, when two neighbouring words are a known phrase
 * ("рабочий стол"), a second reading with the phrase as one term. A row is judged by the better reading. Made by
 * {@link SearchIndex}.
 */
final class QueryPlan {
    static final int MAX_WORDS = 6;

    /** The readings of the query; each is a list of terms that must all be found. The first one has no phrases. */
    final List<List<QueryTerm>> readings = new ArrayList<>();
    /** The query as normalized words joined by a space, to tell a row named exactly like the query. */
    final String normalized;

    QueryPlan(String query, Synonyms synonyms) {
        List<Token> tokens = Text.tokens(query);
        if (tokens.size() > MAX_WORDS) tokens = tokens.subList(0, MAX_WORDS);
        StringBuilder joined = new StringBuilder();
        for (Token token : tokens) joined.append(joined.length() == 0 ? "" : " ").append(token.norm);
        normalized = joined.toString();

        List<QueryTerm> plain = new ArrayList<>();
        for (Token token : withoutSmallWords(tokens)) plain.add(QueryTerm.word(token.norm, synonyms));
        if (!plain.isEmpty()) readings.add(plain);

        List<QueryTerm> withPhrases = new ArrayList<>();
        boolean anyPhrase = false;
        for (int i = 0; i < tokens.size(); i++) {
            Map<String, MatchKind> phrase = i + 1 < tokens.size()
                    ? synonyms.expandPhrase(tokens.get(i).stem, tokens.get(i + 1).stem) : null;
            if (phrase != null) {
                withPhrases.add(QueryTerm.phrase(tokens.get(i).norm, tokens.get(i + 1).norm, phrase));
                anyPhrase = true;
                i++;
            } else if (!Text.isStopWord(tokens.get(i).norm) || allSmall(tokens)) {
                withPhrases.add(QueryTerm.word(tokens.get(i).norm, synonyms));
            }
        }
        if (anyPhrase) readings.add(withPhrases);
    }

    private static boolean allSmall(List<Token> tokens) {
        for (Token token : tokens) if (!Text.isStopWord(token.norm)) return false;
        return true;
    }

    /** The words that say something; if the query is only small words, all of them. */
    private static List<Token> withoutSmallWords(List<Token> tokens) {
        List<Token> kept = new ArrayList<>();
        for (Token token : tokens) if (!Text.isStopWord(token.norm)) kept.add(token);
        return kept.isEmpty() ? tokens : kept;
    }

    boolean isEmpty() {
        return readings.isEmpty();
    }
}
