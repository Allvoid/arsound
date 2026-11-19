package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** Invented placeholder lines only: no real lyrics. */
public class LyricsAlignerTest {
    private static final String TEXT = "first line of a song\nsecond line goes here\n\nthird line comes after\n"
            + "fourth line ends it all";

    @Test
    public void splitTrimsAndCollapsesEmptyLines() {
        assertEquals(Arrays.asList("a", "", "b"), LyricsAligner.split("\n a \r\n\n\n b \n\n"));
    }

    @Test
    public void withoutDurationOrTranscriptTheTextStaysPlain() {
        LyricsAligner.Result result = LyricsAligner.align(TEXT, null, null, 0);
        assertEquals(LyricsAligner.Mode.PLAIN, result.mode);
        assertNull(result.times);
        assertEquals(5, result.lines.length);
    }

    @Test
    public void spreadingSkipsTheIntroAndKeepsTimesRisingInsideTheTrack() {
        LyricsAligner.Result result = LyricsAligner.align(TEXT, null, null, 200_000);
        assertEquals(LyricsAligner.Mode.APPROXIMATE, result.mode);
        assertEquals(4, result.lines.length);
        assertTrue(result.times[0] >= 4_000);
        for (int i = 1; i < result.times.length; i++) assertTrue(result.times[i] > result.times[i - 1]);
        assertTrue(result.times[result.times.length - 1] < 200_000);
    }

    @Test
    public void longerLinesTakeMoreTime() {
        LyricsAligner.Result result = LyricsAligner.align("la la la\nthis is a much much longer invented line here\nla la", null, null, 120_000);
        long first = result.times[1] - result.times[0];
        long second = result.times[2] - result.times[1];
        assertTrue(second > first);
    }

    @Test
    public void sectionMarksTakeNoTime() {
        LyricsAligner.Result result = LyricsAligner.align("[Chorus]\nla la la one two\n(x2)\nla la la three four", null, null, 100_000);
        assertEquals(2, result.lines.length);
    }

    @Test
    public void heardWordsGiveTheTimes() {
        String[] heard = {"first line of a song", "second line goes here", "third line comes after", "fourth line ends it all"};
        long[] at = {10_000, 20_000, 40_000, 55_000};
        LyricsAligner.Result result = LyricsAligner.align(TEXT, heard, at, 70_000);
        assertEquals(LyricsAligner.Mode.HEARD, result.mode);
        assertEquals(4, result.lines.length);
        assertEquals(10_000, result.times[0]);
        assertEquals(20_000, result.times[1]);
        assertEquals(40_000, result.times[2]);
        assertEquals(55_000, result.times[3]);
    }

    @Test
    public void misheardWordsStillMatch() {
        String[] heard = {"first lyne of a song", "second line goes hear", "third line comes after"};
        long[] at = {5_000, 15_000, 30_000};
        LyricsAligner.Result result = LyricsAligner.align("first line of a song\nsecond line goes here\nthird line comes after",
                heard, at, 60_000);
        assertEquals(LyricsAligner.Mode.HEARD, result.mode);
        assertEquals(15_000, result.times[1]);
    }

    @Test
    public void unrelatedHeardTextFallsBackToSpreading() {
        String[] heard = {"completely different words appear", "nothing matches at all here", "not even one line close"};
        long[] at = {5_000, 15_000, 30_000};
        LyricsAligner.Result result = LyricsAligner.align(TEXT, heard, at, 100_000);
        assertEquals(LyricsAligner.Mode.APPROXIMATE, result.mode);
    }

    @Test
    public void timesNeverGoBack() {
        String[] heard = {"first line of a song", "second line goes here", "third line comes after"};
        long[] at = {1_000, 1_100, 1_200};
        LyricsAligner.Result result = LyricsAligner.align(TEXT, heard, at, 0);
        for (int i = 1; i < result.times.length; i++) assertTrue(result.times[i] > result.times[i - 1]);
    }

    @Test
    public void sameWordAllowsOneLetterOff() {
        assertTrue(LyricsAligner.same("light", "lights"));
        assertTrue(LyricsAligner.same("planet", "planot"));
        assertTrue(!LyricsAligner.same("go", "no"));
        assertTrue(!LyricsAligner.same("planet", "plantain"));
    }

    @Test
    public void parenthesisedSungLinesStayButPartMarksGo() {
        LyricsAligner.Result result = LyricsAligner.align(
                "(Chorus)\n(oh yeah, oh yeah)\nla la la one two\n(2x)\n(Outro)\nla la la three four", null, null, 100_000);
        assertEquals(Arrays.asList("(oh yeah, oh yeah)", "la la la one two", "la la la three four"), Arrays.asList(result.lines));
    }

    @Test
    public void trailingLinesStayInsideTheTrack() {
        String[] heard = {"first line of a song", "second line goes here", "third line comes after", "fourth line ends it all"};
        long[] at = {10_000, 20_000, 40_000, 179_000};
        String text = TEXT + "\nsilver kettle drum\namber window glass\nmorning copper field\n"
                + "velvet harbour light\nwinter paper ship\nhollow cedar bell\nstone meadow ember\n"
                + "quiet ridge thunder\nsalt garden hour\nlast brass lantern";
        LyricsAligner.Result result = LyricsAligner.align(text, heard, at, 180_000);
        assertEquals(14, result.lines.length);
        assertEquals(179_000, result.times[3]);
        for (int i = 1; i < result.times.length; i++) assertTrue(result.times[i] > result.times[i - 1]);
        assertTrue(result.times[result.times.length - 1] < 180_000);
    }
}
