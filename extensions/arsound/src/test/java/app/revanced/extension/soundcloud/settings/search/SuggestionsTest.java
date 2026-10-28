package app.revanced.extension.soundcloud.settings.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

public class SuggestionsTest {
    @Test
    public void aWrongLayoutIsOfferedFirst() {
        SearchIndex index = SearchFixture.index();
        List<String> offered = Suggestions.didYouMean(index, "gfvznm", 3);
        assertEquals("память", offered.get(0));
    }

    @Test
    public void aWrongLayoutWithPunctuationKeysIsOffered() {
        SearchIndex index = SearchFixture.index();
        assertTrue(Suggestions.didYouMean(index, "[hfybkbot", 3).contains("хранилище"));
    }

    @Test
    public void aTypoIsCorrectedInPlace() {
        SearchIndex index = SearchFixture.index();
        List<String> offered = Suggestions.didYouMean(index, "очистить кшэ", 3);
        assertFalse(offered.isEmpty());
        assertTrue(offered.toString(), offered.get(0).startsWith("очистить "));
    }

    @Test
    public void nothingIsOfferedForNonsense() {
        assertTrue(Suggestions.didYouMean(SearchFixture.index(), "щщщщщщ", 3).isEmpty());
    }

    @Test
    public void neighbourWordsAreTheOnesThatFindRowsAndNotTheQuery() {
        SearchIndex index = SearchFixture.index();
        List<String> related = Suggestions.related(index, "память", 6);
        assertFalse(related.isEmpty());
        assertTrue(related.toString(), related.size() <= 6);
        for (String word : related) {
            assertFalse(word, Stemmer.stem(word).equals(Stemmer.stem("память")));
            assertFalse("«" + word + "» finds nothing", index.search(word, 1).isEmpty());
        }
        assertTrue(related.toString(), related.contains("хранилище"));
    }
}
