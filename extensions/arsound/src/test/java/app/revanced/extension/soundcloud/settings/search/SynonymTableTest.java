package app.revanced.extension.soundcloud.settings.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class SynonymTableTest {
    private static final Synonyms SYNONYMS = Synonyms.standard();

    @Test
    public void everyGroupHasAtLeastTwoCoreWordsWithoutRepeats() {
        for (int g = 0; g < SYNONYMS.groupCount(); g++) {
            String id = SYNONYMS.groupId(g);
            List<String> core = SYNONYMS.coreWords(g);
            assertTrue(id + " has too few core words", core.size() >= 2);
            assertEquals(id + " repeats a core word", core.size(), new HashSet<>(core).size());
            List<String> related = SYNONYMS.relatedWords(g);
            assertEquals(id + " repeats a related word", related.size(), new HashSet<>(related).size());
            for (String word : related) assertFalse(id + ": " + word + " is both core and related", core.contains(word));
        }
    }

    @Test
    public void everyWordHasAStem() {
        for (int g = 0; g < SYNONYMS.groupCount(); g++) {
            for (String word : SYNONYMS.coreWords(g)) assertFalse(word, Stemmer.stem(word).isEmpty());
            for (String word : SYNONYMS.relatedWords(g)) assertFalse(word, Stemmer.stem(word).isEmpty());
        }
    }

    @Test
    public void noStemIsCoreInMoreThanFiveGroups() {
        Set<String> stems = new HashSet<>();
        for (int g = 0; g < SYNONYMS.groupCount(); g++) for (String word : SYNONYMS.coreWords(g)) stems.add(Stemmer.stem(word));
        for (String stem : stems) assertTrue(stem + " is core in " + SYNONYMS.coreGroupsOf(stem) + " groups", SYNONYMS.coreGroupsOf(stem) <= 5);
    }

    @Test
    public void coreWordsFindEachOtherAndTheRelatedOnes() {
        Map<String, MatchKind> fromMemory = SYNONYMS.expand(Stemmer.stem("память"));
        assertEquals(MatchKind.SYNONYM, fromMemory.get(Stemmer.stem("хранилище")));
        assertEquals(MatchKind.RELATED, fromMemory.get(Stemmer.stem("кэш")));
        // And back: the related word finds the core one, but only as related.
        Map<String, MatchKind> fromCache = SYNONYMS.expand(Stemmer.stem("папка"));
        assertEquals(MatchKind.RELATED, fromCache.get(Stemmer.stem("память")));
    }

    @Test
    public void twoRelatedWordsNeverFindEachOther() {
        // "sd" and "gb" are related to the storage group only.
        Map<String, MatchKind> fromSd = SYNONYMS.expand("sd");
        assertNull(fromSd.get(Stemmer.stem("мб")));
        assertNull(fromSd.get(Stemmer.stem("кэш")));
        assertEquals(MatchKind.RELATED, fromSd.get(Stemmer.stem("память")));
    }

    @Test
    public void aWordBeingTypedReachesTheSynonyms() {
        Map<String, MatchKind> typing = SYNONYMS.expand("хран");
        assertEquals(MatchKind.SYNONYM, typing.get(Stemmer.stem("память")));
    }

    @Test
    public void phrasesAreOnlyQueries() {
        assertNotNull(SYNONYMS.expandPhrase(Stemmer.stem("рабочий"), Stemmer.stem("стол")));
        assertNull(SYNONYMS.expandPhrase("рабочий", "кабачок"));
        // A phrase is never a target: the underscore word is not in the lookups.
        assertTrue(SYNONYMS.expand(Stemmer.stem("виджет")).get("рабочий_стол") == null);
    }
}
