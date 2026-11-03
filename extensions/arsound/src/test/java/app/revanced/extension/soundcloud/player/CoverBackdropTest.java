package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public class CoverBackdropTest {
    private static final int SIDE = CoverBackdrop.SIDE;

    @Test
    public void anEvenColourOnlyGetsDarker() {
        int[] pixels = new int[SIDE * SIDE];
        Arrays.fill(pixels, 0xFFC86432);
        CoverBackdrop.process(pixels, SIDE);
        int expected = CoverBackdrop.darken(0xFFC86432);
        for (int pixel : pixels) assertEquals(expected, pixel);
        assertEquals(0xFF6E371C, expected);
    }

    @Test
    public void aSharpEdgeBecomesAGradient() {
        int[] pixels = new int[SIDE * SIDE];
        for (int y = 0; y < SIDE; y++) {
            for (int x = 0; x < SIDE; x++) pixels[y * SIDE + x] = x < SIDE / 2 ? 0xFF000000 : 0xFFFFFFFF;
        }
        CoverBackdrop.process(pixels, SIDE);
        int row = 5 * SIDE;
        int previous = -1;
        for (int x = 0; x < SIDE; x++) {
            int blue = pixels[row + x] & 0xFF;
            assertTrue("brightness never drops along the row", blue >= previous);
            previous = blue;
        }
        int left = pixels[row + SIDE / 2 - 1] & 0xFF, right = pixels[row + SIDE / 2] & 0xFF;
        assertTrue("the edge is soft", left > 0 && right < Math.round(255 * CoverBackdrop.KEEP));
        assertEquals("far from the edge it stays black", 0, pixels[row] & 0xFF);
    }

    @Test
    public void aTransparentPixelBecomesOpaque() {
        int[] pixels = new int[SIDE * SIDE];
        CoverBackdrop.process(pixels, SIDE);
        for (int pixel : pixels) assertEquals(0xFF000000, pixel);
    }

    @Test(expected = IllegalArgumentException.class)
    public void onlyASquareIsTaken() {
        CoverBackdrop.process(new int[10], SIDE);
    }
}
