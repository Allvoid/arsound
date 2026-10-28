package app.revanced.extension.soundcloud.settings.search;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What to offer under the search when the query found nothing, or little: "did you mean" (the wrong keyboard layout,
 * a typing mistake) and neighbouring words from the {@link Synonyms} groups. Only words that find a row are offered.
 */
public final class Suggestions {
    private Suggestions() {
    }

    /** Other spellings of the whole query that find something, the likeliest first. */
    public static List<String> didYouMean(SearchIndex index, String query, int max) {
        List<String> result = new ArrayList<>();
        String trimmed = query.trim();
        String normalized = Text.normalize(trimmed);
        String swapped = null;
        if (KeyboardLayout.isLatinTyped(normalized.replace(" ", ""))) swapped = KeyboardLayout.toCyrillic(normalized);
        else if (KeyboardLayout.isCyrillic(normalized.replace(" ", ""))) swapped = KeyboardLayout.toLatin(normalized);
        if (swapped != null && !index.searchAsTyped(swapped, 1).isEmpty()) result.add(swapped);

        // Fix one word at a time, the first word that finds nothing.
        List<Token> tokens = Text.tokens(trimmed);
        for (Token token : tokens) {
            if (result.size() >= max) break;
            if (Text.isStopWord(token.norm) || index.knows(token.norm)) continue;
            for (String fix : index.correctionsFor(token.norm)) {
                String fixed = normalized.substring(0, token.start) + fix + normalized.substring(token.end);
                if (!result.contains(fixed) && !index.searchAsTyped(fixed, 1).isEmpty()) result.add(fixed);
                if (result.size() >= max) break;
            }
        }
        return result;
    }

    /** Words near in meaning to the query that find rows, excluding the words of the query itself. */
    public static List<String> related(SearchIndex index, String query, int max) {
        List<String> result = new ArrayList<>();
        Set<String> seenStems = new HashSet<>();
        List<Token> tokens = Text.tokens(query);
        for (Token token : tokens) seenStems.add(token.stem);
        for (Token token : tokens) {
            if (Text.isStopWord(token.norm)) continue;
            for (String neighbour : index.synonyms().neighbours(token.stem)) {
                if (result.size() >= max) return result;
                if (!seenStems.add(Stemmer.stem(neighbour))) continue;
                if (!index.searchAsTyped(neighbour, 1).isEmpty()) result.add(neighbour);
            }
        }
        return result;
    }
}
