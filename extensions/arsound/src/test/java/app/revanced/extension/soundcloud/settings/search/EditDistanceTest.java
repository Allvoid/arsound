package app.revanced.extension.soundcloud.settings.search;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class EditDistanceTest {
    @Test
    public void equalWordsAreZeroAway() {
        assertTrue(EditDistance.within("плеер", "плеер", 0));
    }

    @Test
    public void aSwapOfNeighbourLettersIsOneMistake() {
        assertTrue(EditDistance.within("плеер", "пелер", 1));
        assertFalse(EditDistance.within("плеер", "пелер", 0));
    }

    @Test
    public void insertDeleteAndReplaceAreOneMistakeEach() {
        assertTrue(EditDistance.within("эквалайзер", "эквалайзар", 1));
        assertTrue(EditDistance.within("плеер", "прлеер", 1));
        assertTrue(EditDistance.within("батарея", "батрея", 1));
        assertFalse(EditDistance.within("батарея", "батр", 2));
    }

    @Test
    public void theBoundIsRespected() {
        assertFalse(EditDistance.within("abcdef", "abcxyz", 2));
        assertTrue(EditDistance.within("abcdef", "abcxyz", 3));
        // Lengths that differ by more than the bound are rejected at once.
        assertFalse(EditDistance.within("a", "abcd", 2));
    }

    @Test
    public void totallyDifferentWordsGiveUpEarly() {
        assertFalse(EditDistance.within("память", "equalizer", 2));
        assertFalse(EditDistance.within("", "abc", 2));
        assertTrue(EditDistance.within("", "ab", 2));
    }
}
