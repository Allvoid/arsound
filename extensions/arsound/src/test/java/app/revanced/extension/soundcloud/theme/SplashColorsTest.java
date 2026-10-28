package app.revanced.extension.soundcloud.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SplashColorsTest {
    private static final int BLACK = 0xFF000000;
    private static final int WHITE = 0xFFFFFFFF;
    private static final int ORANGE = 0xFFFF5500;
    private static final int BLUE = 0xFF2196F3;
    private static final int GREEN = 0xFF4CAF50;

    @Test
    public void whiteAndBlackAreFarApart() {
        double[] white = SplashColors.oklab(WHITE);
        double[] black = SplashColors.oklab(BLACK);
        assertEquals(1.0, white[0], 0.001);
        assertEquals(0.0, black[0], 0.001);
        assertEquals(0.0, white[1], 0.001);
        assertEquals(0.0, white[2], 0.001);
    }

    @Test
    public void distanceIsZeroForTheSameColourAndIgnoresAlpha() {
        assertEquals(0.0, SplashColors.distance(ORANGE, ORANGE), 0.0);
        assertEquals(0.0, SplashColors.distance(ORANGE, 0x80FF5500), 0.0);
    }

    @Test
    public void distanceIsSymmetric() {
        assertEquals(SplashColors.distance(BLUE, GREEN), SplashColors.distance(GREEN, BLUE), 1e-12);
    }

    @Test
    public void exactMatchWinsWhereverItIs() {
        int[] palette = {BLACK, ORANGE, BLUE, GREEN};
        assertEquals(0, SplashColors.nearest(palette, BLACK));
        assertEquals(1, SplashColors.nearest(palette, ORANGE));
        assertEquals(3, SplashColors.nearest(palette, GREEN));
    }

    @Test
    public void ofEqualColoursTheFirstWins() {
        assertEquals(0, SplashColors.nearest(new int[]{BLUE, BLUE}, BLUE));
    }

    @Test
    public void aLighterBlueIsNearerToBlueThanToGreenOrOrange() {
        int lightBlue = 0xFF4FC3F7;
        assertEquals(2, SplashColors.nearest(new int[]{ORANGE, GREEN, BLUE}, lightBlue));
    }

    @Test
    public void aDarkBluishBackgroundIsNearerToTheBluishThanToTheNeutralOne() {
        int neutral = 0xFF121212;
        int bluish = 0xFF0B1A33;
        int brownish = 0xFF33200B;
        assertEquals(1, SplashColors.nearest(new int[]{neutral, bluish, brownish}, 0xFF0A1426));
    }

    @Test
    public void nearerMeansSmallerDistance() {
        assertTrue(SplashColors.distance(0xFF121212, 0xFF0D0D0D) < SplashColors.distance(0xFF121212, 0xFF242424));
    }

    @Test(expected = IllegalArgumentException.class)
    public void anEmptyPaletteHasNoNearest() {
        SplashColors.nearest(new int[0], BLACK);
    }
}
