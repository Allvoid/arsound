package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeaderShadeTest {
    private static long packed(long argb) {
        return (argb & 0xFFFFFFFFL) << 32;
    }

    @Test
    public void theAlphaIsReplacedAndTheColourKept() {
        assertEquals(packed(0x4D1A1B2CL), HeaderShade.withAlpha(packed(0xFF1A1B2CL), 0.3f));
        assertEquals(packed(0x001A1B2CL), HeaderShade.withAlpha(packed(0xFF1A1B2CL), 0f));
        assertEquals(packed(0xFF1A1B2CL), HeaderShade.withAlpha(packed(0x001A1B2CL), 1f));
    }

    @Test
    public void theShadeIsClearAtBothEnds() {
        assertEquals(0f, HeaderShade.ALPHAS[0], 0f);
        assertEquals(0f, HeaderShade.ALPHAS[HeaderShade.ALPHAS.length - 1], 0f);
    }
}
