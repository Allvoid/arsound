package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class BoxBlurTest {
    @Test
    public void aFlatPictureStaysAsItIs() {
        int[] pixels = new int[5 * 4];
        Arrays.fill(pixels, 0xFF336699);
        int[] expected = pixels.clone();
        BoxBlur.apply(pixels, 5, 4, 2);
        assertArrayEquals(expected, pixels);
    }

    @Test
    public void aLineIsAveragedOverTheBoxAndRepeatsItsEdges() {
        int[] source = {0xFF000000, 0xFF000000, 0xFF303030, 0xFF000000, 0xFF000000};
        int[] target = new int[5];
        BoxBlur.blurLine(source, target, 5, 1);
        // The 0x30 is spread over three pixels: 16 each (0x10).
        assertEquals(0xFF000000, target[0]);
        assertEquals(0xFF101010, target[1]);
        assertEquals(0xFF101010, target[2]);
        assertEquals(0xFF101010, target[3]);
        assertEquals(0xFF000000, target[4]);
    }

    @Test
    public void blurringDoesNotChangeTheMeanBrightnessMuch() {
        int[] pixels = new int[8 * 8];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (i % 3 == 0) ? 0xFFFFFFFF : 0xFF000000;
        long before = 0;
        for (int pixel : pixels) before += pixel & 0xFF;
        BoxBlur.apply(pixels, 8, 8, 1);
        long after = 0;
        for (int pixel : pixels) after += pixel & 0xFF;
        assertTrue(Math.abs(after - before) < before / 5);
    }
}
