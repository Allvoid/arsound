package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TapSequenceTest {
    @Test
    public void firstTapIsSingle() {
        assertEquals(TapSequence.Kind.SINGLE, new TapSequence(280).next(1_000));
    }

    @Test
    public void secondTapInsideTheWindowIsDouble() {
        TapSequence taps = new TapSequence(280);
        taps.next(1_000);
        assertEquals(TapSequence.Kind.DOUBLE, taps.next(1_280));
    }

    @Test
    public void secondTapAfterTheWindowIsSingleAgain() {
        TapSequence taps = new TapSequence(280);
        taps.next(1_000);
        assertEquals(TapSequence.Kind.SINGLE, taps.next(1_281));
    }

    @Test
    public void aDoubleTapIsUsedUpSoTheThirdTapStartsAPair() {
        TapSequence taps = new TapSequence(280);
        taps.next(1_000);
        taps.next(1_100);
        assertEquals(TapSequence.Kind.SINGLE, taps.next(1_200));
        assertEquals(TapSequence.Kind.DOUBLE, taps.next(1_300));
    }

    @Test
    public void resetForgetsTheLastTap() {
        TapSequence taps = new TapSequence(280);
        taps.next(1_000);
        taps.reset();
        assertEquals(TapSequence.Kind.SINGLE, taps.next(1_100));
    }

    @Test
    public void aTapAtTimeZeroCountsAsAFirstTap() {
        TapSequence taps = new TapSequence(280);
        assertEquals(TapSequence.Kind.SINGLE, taps.next(0));
        assertEquals(TapSequence.Kind.DOUBLE, taps.next(100));
    }

    @Test
    public void aTapBeforeTheLastOneIsNotItsPair() {
        TapSequence taps = new TapSequence(280);
        taps.next(1_000);
        assertEquals(TapSequence.Kind.SINGLE, taps.next(900));
    }
}
