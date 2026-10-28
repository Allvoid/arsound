package app.revanced.extension.soundcloud.shared;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ColorsTest {
    @Test
    public void brightnessRunsFromBlackToWhite() {
        assertEquals(0.0, Colors.brightness(0xFF000000), 1e-9);
        assertEquals(1.0, Colors.brightness(0xFFFFFFFF), 1e-9);
        assertEquals(0.299, Colors.brightness(0xFFFF0000), 1e-9);
        assertEquals(0.587, Colors.brightness(0xFF00FF00), 1e-9);
        assertEquals(0.114, Colors.brightness(0xFF0000FF), 1e-9);
    }

    @Test
    public void brightnessIgnoresAlpha() {
        assertEquals(Colors.brightness(0xFF336699), Colors.brightness(0x00336699), 0.0);
    }

    @Test
    public void contrastIsNearBlackOnBrightAndWhiteOnDark() {
        assertEquals(0xFF111111, Colors.contrast(0xFFFFFFFF));
        assertEquals(0xFFFFFFFF, Colors.contrast(0xFF000000));
        // SoundCloud's orange is under the 0.6 threshold, so it gets white.
        assertEquals(0xFFFFFFFF, Colors.contrast(0xFFFF5500));
    }

    @Test
    public void thresholdItselfCountsAsDark() {
        // Grey 0x99 = 153 is exactly 0.6 of 255; one step brighter flips.
        assertEquals(0xFFFFFFFF, Colors.contrast(0xFF999999));
        assertEquals(0xFF111111, Colors.contrast(0xFF9A9A9A));
    }

    @Test
    public void inverseSwitchesAtHalf() {
        assertEquals(0xFF121212, Colors.inverse(0xFFFFFFFF));
        assertEquals(0xFFFFFFFF, Colors.inverse(0xFF000000));
        assertEquals(0xFF121212, Colors.inverse(0xFF808080));
        assertEquals(0xFFFFFFFF, Colors.inverse(0xFF7F7F7F));
    }

    @Test
    public void inkTakesTheInksAndThresholdItIsGiven() {
        assertEquals(0xFF000000, Colors.ink(0xFFFFFFFF, 0.6, 0xFF000000, 0xFFFFFFFF));
        assertEquals(0xFFFFFFFF, Colors.ink(0xFF000000, 0.6, 0xFF000000, 0xFFFFFFFF));
        assertEquals(0xFF000000, Colors.ink(0xFF808080, 0.1, 0xFF000000, 0xFFFFFFFF));
    }

    @Test
    public void withAlphaKeepsTheColourAndReplacesTheAlpha() {
        assertEquals(0x14336699, Colors.withAlpha(0xFF336699, 0x14));
        assertEquals(0xFF336699, Colors.withAlpha(0x00336699, 0xFF));
        assertEquals(0x00336699, Colors.withAlpha(0xFF336699, 0));
    }

    @Test
    public void blendEndsAreTheTwoColoursAndAlwaysSolid() {
        assertEquals(0xFF102030, Colors.blend(0x00102030, 0xFFFFFFFF, 0f));
        assertEquals(0xFFFFFFFF, Colors.blend(0xFF102030, 0x00FFFFFF, 1f));
    }

    @Test
    public void blendTakesTheMidpointPerChannel() {
        // 127.5 rounds up, as Math.round does.
        assertEquals(0xFF808080, Colors.blend(0xFF000000, 0xFFFFFFFF, 0.5f));
        assertEquals(0xFFBFBFBF, Colors.blend(0xFFFFFFFF, 0xFF000000, 0.25f));
        // 12 % of the way from 0x1C to 0x3C is 0x1C + 3.84, rounded: 0x20.
        assertEquals(0xFF202020, Colors.blend(0xFF1C1C1C, 0xFF3C3C3C, 0.12f));
    }

    @Test
    public void blendKeepsTheChannelsApart() {
        assertEquals(0xFF80FF00, Colors.blend(0xFF00FF00, 0xFFFFFF00, 0.5f));
    }
}
