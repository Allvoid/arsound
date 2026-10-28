package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ColorRangeTest {
    private static final float EPS = 1e-5f;

    @Test
    public void theFullRangeIsTheWholeSquare() {
        assertEquals(0f, ColorRange.FULL.saturationAt(0f), EPS);
        assertEquals(1f, ColorRange.FULL.saturationAt(1f), EPS);
        assertEquals(1f, ColorRange.FULL.valueAt(0f), EPS);
        assertEquals(0f, ColorRange.FULL.valueAt(1f), EPS);
    }

    @Test
    public void theDarkRangeSpreadsTheDarkShadesOverTheWholeHeight() {
        // A background of brightness 0.09 sits about two thirds of the way down the dark square.
        assertEquals(.68f, ColorRange.DARK.downOf(.09f), .01f);
        assertEquals(ColorRange.DARK.valueMax, ColorRange.DARK.valueAt(0f), EPS);
        assertEquals(0f, ColorRange.DARK.valueAt(1f), EPS);
    }

    @Test
    public void theLightRangeKeepsToPaleColours() {
        assertEquals(.3f, ColorRange.LIGHT.saturationAt(1f), EPS);
        assertEquals(.82f, ColorRange.LIGHT.valueAt(1f), EPS);
    }

    @Test
    public void positionAndColourFindEachOther() {
        ColorRange range = new ColorRange(.5f, .2f, .8f);
        assertEquals(.3f, range.saturationAt(range.acrossOf(.3f)), EPS);
        assertEquals(.5f, range.valueAt(range.downOf(.5f)), EPS);
    }

    @Test
    public void aColourOutsideTheRangeSitsAtTheEdge() {
        assertEquals(1f, ColorRange.DARK.downOf(-.5f), EPS);
        assertEquals(0f, ColorRange.DARK.downOf(1f), EPS);
        assertEquals(1f, ColorRange.LIGHT.acrossOf(.9f), EPS);
    }

    @Test
    public void aTouchOutsideTheSquareIsHeldAtItsEdge() {
        assertEquals(0f, ColorRange.FULL.saturationAt(-3f), EPS);
        assertEquals(0f, ColorRange.FULL.valueAt(7f), EPS);
    }
}
