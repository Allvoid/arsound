package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class LineTimerTest {
    @Test
    public void linesAreSpreadOverTheSpanInOrder() {
        long[] times = LineTimer.spread(Arrays.asList("aaaa aaaa", "bbbb bbbb", "cccc cccc"), 10_000, 40_000, 0);
        assertEquals(10_000, times[0]);
        assertTrue(times[1] > times[0] && times[2] > times[1]);
        assertTrue(times[2] < 40_000);
    }

    @Test
    public void aLongLineTakesMoreTime() {
        long[] times = LineTimer.spread(Arrays.asList("a very very long line of the song", "short", "x"), 0, 30_000, 0);
        assertTrue(times[1] - times[0] > times[2] - times[1]);
    }

    @Test
    public void nothingStartsBeforeTheLineBefore() {
        long[] times = LineTimer.spread(Arrays.asList("one", "two"), 1_000, 5_000, 20_000);
        assertEquals(20_000, times[0]);
        assertTrue(times[1] > times[0]);
    }

    @Test
    public void aTinySpanStillSeparatesTheLines() {
        long[] times = LineTimer.spread(Arrays.asList("one", "two", "three"), 5_000, 5_100, 0);
        assertTrue(times[1] - times[0] >= 100);
        assertTrue(times[2] > times[1]);
    }

    @Test
    public void oneLineStartsWhereTheSoundDoes() {
        assertEquals(7_000, LineTimer.spread(Collections.singletonList("only"), 7_000, 9_000, 0)[0]);
    }
}
