package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PhraseLinesTest {
    /** Words sung one after another: each takes 80 ms a letter, then the given pause before the next. */
    private static List<HeardWord> sung(long startMs, Object... wordsAndPauses) {
        List<HeardWord> words = new ArrayList<>();
        long at = startMs;
        for (int i = 0; i < wordsAndPauses.length; i++) {
            String word = (String) wordsAndPauses[i];
            long end = at + 80L * (word.length() - 1);
            words.add(new HeardWord(word, at, end));
            long pause = i + 1 < wordsAndPauses.length && wordsAndPauses[i + 1] instanceof Long
                    ? (Long) wordsAndPauses[++i] : 150;
            at = end + pause;
        }
        return words;
    }

    private static List<String> texts(List<PhraseLines.Line> lines) {
        List<String> texts = new ArrayList<>();
        for (PhraseLines.Line line : lines) texts.add(line.text());
        return texts;
    }

    /** The pauses of the chorus of "Вся моя жизнь говно" as GigaAM heard them on the separated voice. */
    @Test
    public void aPauseEndsTheLine() {
        List<PhraseLines.Line> lines = PhraseLines.cut(sung(12_320,
                "вся", "моя", "жизнь", "говно", 760L, "но", "мне", "хорошо", 800L, "купаться", "в", "тюрьме"));
        assertEquals(Arrays.asList("вся моя жизнь говно", "но мне хорошо", "купаться в тюрьме"), texts(lines));
        assertEquals(12_320, lines.get(0).startMs());
        assertTrue(lines.get(1).startMs() > lines.get(0).endMs() + PhraseLines.PAUSE_MS);
    }

    @Test
    public void aLongPhraseIsCutAtItsLongestPause() {
        List<PhraseLines.Line> lines = PhraseLines.cut(sung(0,
                "обзывать", "не", "перестану", "я", "устану", "повторять", 300L, "все", "мои", "друзья", "ублюдки",
                "а", "моя", "женщина", "тварь"));
        assertEquals("обзывать не перестану я устану повторять", lines.get(0).text());
        for (PhraseLines.Line line : lines) assertTrue(line.text(), line.text().length() <= PhraseLines.MAX_LINE);
    }

    @Test
    public void aLoneShortWordJoinsTheNearerLine() {
        List<PhraseLines.Line> lines = PhraseLines.cut(sung(0, "но", "мне", "хорошо", 600L, "да", 2000L, "вся", "моя", "жизнь"));
        assertEquals(Arrays.asList("но мне хорошо да", "вся моя жизнь"), texts(lines));
    }

    @Test
    public void aShortWordFarFromEverythingStaysAlone() {
        List<PhraseLines.Line> lines = PhraseLines.cut(sung(0, "но", "мне", "хорошо", 1500L, "да", 1500L, "вся", "моя"));
        assertEquals(Arrays.asList("но мне хорошо", "да", "вся моя"), texts(lines));
    }

    @Test
    public void noWordsNoLines() {
        assertTrue(PhraseLines.cut(new ArrayList<>()).isEmpty());
    }
}
