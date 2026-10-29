package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class HeardWordTest {
    /** GigaAM writes a letter per token and a blank between words, each with its moment in seconds. */
    @Test
    public void lettersMakeWordsWithTheirMoments() {
        String[] tokens = {"е", "с", "л", "и", " ", "я", " ", "н", "е"};
        float[] seconds = {0.32f, 0.40f, 0.52f, 0.56f, 0.68f, 0.76f, 0.84f, 1.00f, 1.04f};
        List<HeardWord> words = HeardWord.fromTokens(tokens, seconds);
        assertEquals(3, words.size());
        assertEquals("если", words.get(0).text);
        assertEquals(320, words.get(0).startMs);
        assertEquals(560, words.get(0).endMs);
        assertEquals("я", words.get(1).text);
        assertEquals(1000, words.get(2).startMs);
    }

    @Test
    public void piecesWithTheWordMarkStartNewWords() {
        List<HeardWord> words = HeardWord.fromTokens(new String[]{"▁при", "вет", "▁мир"}, new float[]{0.1f, 0.2f, 0.5f});
        assertEquals(2, words.size());
        assertEquals("привет", words.get(0).text);
        assertEquals(200, words.get(0).endMs);
        assertEquals("мир", words.get(1).text);
    }

    @Test
    public void noMomentsGiveNoWords() {
        assertTrue(HeardWord.fromTokens(new String[]{"a"}, new float[0]).isEmpty());
        assertTrue(HeardWord.fromTokens(null, null).isEmpty());
    }

    @Test
    public void theCleanedTextKeepsItsWordsInOrder() {
        List<HeardWord> heard = HeardWord.fromTokens(
                new String[]{"а", " ", "б", " ", "а", " ", "в"}, new float[]{0, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f});
        List<HeardWord> kept = HeardWord.keptIn(heard, "а в");
        assertEquals(2, kept.size());
        assertEquals(0, kept.get(0).startMs);
        assertEquals(600, kept.get(1).startMs);
    }

    @Test
    public void aTextNotMadeOfTheWordsIsNotMatched() {
        List<HeardWord> heard = HeardWord.fromTokens(new String[]{"а", " ", "б"}, new float[]{0, 0.1f, 0.2f});
        assertNull(HeardWord.keptIn(heard, "а г"));
        assertNull(HeardWord.keptIn(heard, ""));
    }
}
