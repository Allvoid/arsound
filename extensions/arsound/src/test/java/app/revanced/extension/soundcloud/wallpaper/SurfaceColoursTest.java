package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SurfaceColoursTest {
    private final SurfaceColours colours = new SurfaceColours(0xFF1B1B22, 0xFF121212, 0xFFFFFFFF);

    @Test
    public void opaqueSurfaceColoursAreBaseLayers() {
        assertTrue(colours.covers(0xFF1B1B22));
        assertTrue(colours.covers(0xFF121212));
        assertTrue(colours.covers(0xFFFFFFFF));
    }

    @Test
    public void otherColoursAreKept() {
        assertFalse(colours.covers(0xFF2A2A33));
        assertFalse(colours.covers(0xFF000000));
    }

    @Test
    public void translucentSurfaceIsLeftAlone() {
        assertFalse(colours.covers(0xBF1B1B22));
        assertFalse(colours.covers(0x001B1B22));
    }

    @Test
    public void alphaOfTheGivenColoursIsIgnored() {
        SurfaceColours given = new SurfaceColours(0x801B1B22, 0x00121212);
        assertTrue(given.covers(0xFF1B1B22));
        assertTrue(given.covers(0xFF121212));
    }
}
