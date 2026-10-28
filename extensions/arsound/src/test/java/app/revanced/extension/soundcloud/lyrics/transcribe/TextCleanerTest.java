package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class TextCleanerTest {
    @Test
    public void madeUpPhrasesAreDropped() {
        assertEquals("", TextCleaner.clean("Субтитры сделал DimaTorzok"));
        assertEquals("", TextCleaner.clean("Продолжение следует..."));
        assertEquals("", TextCleaner.clean("Thanks for watching!"));
        assertEquals("", TextCleaner.clean("Thank you."));
        assertEquals("", TextCleaner.clean("  "));
    }

    @Test
    public void realWordsSurvive() {
        assertEquals("Thank you for the flowers, my love", TextCleaner.clean("Thank you for the flowers, my love"));
    }

    @Test
    public void soundNotesInBracketsAreRemoved() {
        assertEquals("Hello there", TextCleaner.clean("[Music] Hello there (applause) ♪"));
        assertEquals("", TextCleaner.clean("[Музыка]"));
    }

    @Test
    public void aLoopIsKeptTwice() {
        String loop = "ты моя ты моя ты моя ты моя ты моя ты моя ты моя";
        assertEquals("ты моя ты моя", TextCleaner.collapseLoops(loop));
        // Said seven times in a row and nothing else: the window is a loop of the decoder, not a song.
        assertEquals("", TextCleaner.clean(loop));
        assertEquals("la la", TextCleaner.collapseLoops("la la la la la la la la"));
    }

    @Test
    public void aChorusRepeatedAFewTimesIsLeftAlone() {
        String text = "oh my love oh my love oh my love";
        assertEquals(text, TextCleaner.collapseLoops(text));
    }

    @Test
    public void textBeforeAndAfterALoopStays() {
        assertEquals("start na na end", TextCleaner.collapseLoops("start na na na na na na na na end"));
    }

    @Test
    public void linesBreakAtPunctuationAndStayShort() {
        List<String> lines = TextCleaner.splitLines(
                "I walk alone down the street, the rain is falling on my face. Nobody knows my name, nobody cares");
        assertTrue(lines.toString(), lines.size() >= 3);
        for (String line : lines) {
            assertTrue(line, line.length() <= 42);
            assertTrue(line, !line.endsWith(",") && !line.endsWith("."));
        }
    }

    @Test
    public void aLongRunWithoutPunctuationIsWrappedAtSpaces() {
        String text = "this is a very long line of words without any punctuation at all that must be broken somewhere";
        List<String> lines = TextCleaner.splitLines(text);
        assertTrue(lines.size() >= 3);
        assertEquals(text, String.join(" ", lines));
        for (String line : lines) assertTrue(line, line.length() <= 42);
    }

    @Test
    public void shortPiecesAreJoined() {
        assertEquals(Collections.singletonList("Hey, you there"), TextCleaner.splitLines("Hey, you there"));
    }

    @Test
    public void emptyTextHasNoLines() {
        assertTrue(TextCleaner.splitLines("   ").isEmpty());
    }

    @Test
    public void aWindowThatOnlyRepeatsTheLastLineIsDropped() {
        List<String> before = Arrays.asList("first line", "Same Line!");
        assertTrue(TextCleaner.withoutRepeatOfPrevious(Collections.singletonList("same line"), before).isEmpty());
        assertEquals(2, TextCleaner.withoutRepeatOfPrevious(Arrays.asList("same line", "another"), before).size());
    }

    private static final Set<LanguagePolicy.Script> RUSSIAN = EnumSet.of(LanguagePolicy.Script.CYRILLIC);
    private static final Set<LanguagePolicy.Script> COMMON =
            EnumSet.of(LanguagePolicy.Script.CYRILLIC, LanguagePolicy.Script.LATIN);

    private static String repeat(String piece, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) out.append(piece);
        return out.toString();
    }

    // The strings below are from the benchmark of the phone's model (base and small, Russian songs).

    @Test
    public void aChainOfNumbersWithDashesIsALoop() {
        String raw = "Ты наличишь, 17" + repeat("-19", 70);
        assertEquals("17-19-19", TextCleaner.collapseLoops("17" + repeat("-19", 70)));
        TextCleaner.Cleaned cleaned = TextCleaner.inspect(raw, COMMON);
        assertEquals("", cleaned.text);
        assertEquals(TextCleaner.Dropped.LOOP, cleaned.dropped);
    }

    @Test
    public void aJapaneseLoopInARussianSongIsDropped() {
        String raw = "Dinna nishon 今何時何時も 二人はもう一回" + repeat(" もう一回", 29) + " もう一";
        TextCleaner.Cleaned cleaned = TextCleaner.inspect(raw, RUSSIAN);
        assertEquals("", cleaned.text);
        assertEquals(TextCleaner.Dropped.LOOP, cleaned.dropped);
    }

    @Test
    public void aRunOfCharactersWithoutSpacesIsCut() {
        assertEquals("もう一回もう一回", TextCleaner.collapseLoops(repeat("もう一回", 12)));
        assertEquals("ああ", TextCleaner.collapseLoops(repeat("あ", 10)));
        assertEquals("今何時何時も", TextCleaner.collapseLoops("今何時何時も"));
    }

    @Test
    public void aWindowOfOneWordRepeatedIsDropped() {
        String raw = "Папу, папу, папу, Папу, папу, Папу, папу, папу, Папу" + repeat(", папу", 40);
        TextCleaner.Cleaned cleaned = TextCleaner.inspect(raw, RUSSIAN);
        assertEquals("", cleaned.text);
        assertEquals(TextCleaner.Dropped.LOOP, cleaned.dropped);
    }

    @Test
    public void aWindowOfFewWordsInVariationsIsDropped() {
        String raw = "Я хочу плачать, один раз и не плачать Я хочу плачать, один раз и не плачать "
                + "Я хочу плачать, один раз и не плачать Я хочу плачать, плачать, плачать, плачать "
                + "Я хочу плачать, плачать, плачать Я хочу плачать, плачать, плачать "
                + "Я хочу плачать, плачать, плачать Я хочу плачать, плачать, плачать "
                + "Я хочу плачать, плачать Я хочу плачать, плачать Я хочу плачать, плачать Я хочу плачать";
        assertEquals(TextCleaner.Dropped.LOOP, TextCleaner.inspect(raw, RUSSIAN).dropped);
    }

    @Test
    public void aPhraseWithATailOfItselfIsDropped() {
        String raw = "Я за чум, а патит, а пит, а пит" + repeat(" Я за чум, а патит, а пит", 11) + " Я за чум, а патит";
        assertEquals(TextCleaner.Dropped.LOOP, TextCleaner.inspect(raw, RUSSIAN).dropped);
    }

    @Test
    public void aPhraseSaidSixTimesIsALoopButFourTimesIsAChorus() {
        String line = "Мы очень ждем тебя найти";
        assertEquals(TextCleaner.Dropped.LOOP, TextCleaner.inspect(line + repeat(" " + line, 5), RUSSIAN).dropped);
        String chorus = line + repeat(" " + line, 3);
        TextCleaner.Cleaned kept = TextCleaner.inspect(chorus, RUSSIAN);
        assertNull(kept.dropped);
        assertEquals(chorus, kept.text);
    }

    @Test
    public void aChorusLineRepeatedAFewTimesSurvives() {
        String line = "Я хочу, я хочу, я хочу тебя";
        String chorus = line + repeat(" " + line, 3);
        TextCleaner.Cleaned kept = TextCleaner.inspect(chorus, RUSSIAN);
        assertNull(kept.dropped);
        assertEquals(chorus, kept.text);
    }

    @Test
    public void aMisheardButRealWindowSurvives() {
        String raw = "Дина несёт, Симночь изнательный, Узаром мой живой, Прямо на пол стекает моя злой, Ты продолжает, "
                + "Не надощить устарик, По плоте тревожа, По башке камена везучей, Бля, Дина несёт.";
        TextCleaner.Cleaned cleaned = TextCleaner.inspect(raw, RUSSIAN);
        assertNull(cleaned.dropped);
        assertEquals(raw, cleaned.text);
    }

    @Test
    public void textMostlyInOtherLettersIsDropped() {
        TextCleaner.Cleaned english = TextCleaner.inspect("Hello my darling, I love you so much tonight", RUSSIAN);
        assertEquals(TextCleaner.Dropped.FOREIGN, english.dropped);
        assertEquals("", english.text);
        assertEquals(TextCleaner.Dropped.FOREIGN, TextCleaner.inspect("二人はもう一回", COMMON).dropped);
        assertNull(TextCleaner.inspect("Hello my darling, I love you so much tonight", COMMON).dropped);
        // A few English words in a Russian line do not matter.
        assertNull(TextCleaner.inspect("Дина несёт oh baby", RUSSIAN).dropped);
    }

    @Test
    public void asteriskNotesAndWordlessSoundsAreRemoved() {
        assertEquals("", TextCleaner.clean("*Звук пива*"));
        assertEquals("", TextCleaner.clean("(по-по-по-по-по)"));
        assertEquals("", TextCleaner.clean("[MUSIC PLAYING]"));
        assertEquals("", TextCleaner.clean("(音楽)"));
        assertEquals("", TextCleaner.clean("17 19"));
    }
}
