package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class TempoMatchTest {
    private static final double DELTA = 1e-9;

    @Test
    public void aRecordOfTheOriginalIsStretchedToTheSlowedTrack() {
        // The original is 170 s, the slowed track 189 s: every line comes 189/170 times later.
        assertEquals(189.0 / 170, TempoMatch.factor(189_000, 170, 170_370, Collections.emptyList()), DELTA);
    }

    @Test
    public void aRecordUnderTheSlowedLengthTakesTheLengthOfItsTwins() {
        // She & Him, "I Thought I Saw Your Face Today (slowed)": one record claims 188 s with the original's timings,
        // the others with the same text say 170 and 171 s.
        double factor = TempoMatch.factor(189_000, 188, 170_370, Arrays.asList(188.0, 170.0, 171.0, 170.0, 3286.0, 30.0));
        assertEquals(189.0 / 170, factor, DELTA);
    }

    @Test
    public void timingsThatFitAreLeftAlone() {
        assertEquals(1, TempoMatch.factor(189_000, 188, 185_000, Arrays.asList(188.0, 189.0)), DELTA);
        assertEquals(1, TempoMatch.factor(189_000, Double.NaN, 185_000, null), DELTA);
    }

    @Test
    public void aSpedUpTrackIsSqueezed() {
        assertEquals(150.0 / 200, TempoMatch.factor(150_000, 200, 195_000, Collections.emptyList()), DELTA);
    }

    @Test
    public void anotherCutIsNotTakenForAnotherSpeed() {
        // Twice as long is a different cut, not a slowed copy.
        assertEquals(1, TempoMatch.factor(400_000, 170, 168_000, Collections.emptyList()), DELTA);
        // Stretched words that would run past the end belong to a longer cut.
        assertEquals(1, TempoMatch.factor(189_000, 170, 185_000, Collections.emptyList()), DELTA);
    }

    @Test
    public void previewAndSetLengthsAreIgnored() {
        assertEquals(1, TempoMatch.factor(189_000, 188, 170_000, Arrays.asList(30.0, 3286.0)), DELTA);
    }
}
