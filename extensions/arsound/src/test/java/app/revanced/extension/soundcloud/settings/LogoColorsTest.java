package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogoColorsTest {
    /** The tiles the themes put behind the logo, dark and light palettes. */
    private static final int[] THEME_TILES = {
            0xE0242F, 0x4C9AFF, 0x1F6FEB, 0x34E0A1, 0x087A52, 0xFF85BE, 0xD9418A, 0xA3FF3D, 0x6D28D9,
            0x9FDDF0, 0x1784A6, 0x8B7CFF, 0xE8A33D, 0xD0BCFF, 0x6750A4, 0xFF5500, 0xD14600};

    @Test
    public void theLetterAlwaysStandsOutFromTheTile() {
        for (int tile : everyKindOfTile()) {
            LogoColors colors = LogoColors.forTile(tile);
            assertTrue(hex(tile) + " letter " + hex(colors.letter),
                    LogoColors.contrast(colors.letter, tile | 0xFF000000) >= LogoColors.LETTER_CONTRAST);
        }
    }

    @Test
    public void theAccentLineStandsOutFromBothTheTileAndTheLetter() {
        for (int tile : everyKindOfTile()) {
            LogoColors colors = LogoColors.forTile(tile);
            int opaque = tile | 0xFF000000;
            assertTrue(hex(tile) + " accent " + hex(colors.accent) + " on the tile",
                    LogoColors.contrast(colors.accent, opaque) >= 1.5);
            assertTrue(hex(tile) + " accent " + hex(colors.accent) + " on the letter",
                    LogoColors.contrast(colors.accent, colors.letter) >= 1.5);
        }
    }

    @Test
    public void aRedTileGetsAWhiteLetterAndARedShadeNotAzure() {
        LogoColors colors = LogoColors.forTile(0xE0242F);
        assertEquals(0xFFFFFFFF, colors.letter);
        double[] accent = LogoColors.toHsl(colors.accent);
        // The hue of red, give or take the rounding of a colour made from a hue.
        assertTrue("hue " + accent[0], accent[0] < 12 || accent[0] > 348);
        // A shade of red, not black: the line keeps the colour of the tile.
        assertTrue("lightness " + accent[2], accent[2] > 0.15 && accent[2] < 0.5);
    }

    @Test
    public void aLightTileGetsADarkLetter() {
        for (int tile : new int[]{0xFF85BE, 0xA3FF3D, 0x9FDDF0, 0xD0BCFF, 0xE8A33D}) {
            LogoColors colors = LogoColors.forTile(tile);
            assertTrue(hex(tile), LogoColors.contrast(colors.letter, 0xFF000000) < 3);
        }
    }

    @Test
    public void aDarkGreyTileKeepsTheAzureOfTheLogo() {
        assertEquals(LogoColors.AZURE, LogoColors.forTile(0x1B1B1D).accent);
        assertEquals(0xFFFFFFFF, LogoColors.forTile(0x1B1B1D).letter);
    }

    @Test
    public void hslRoundTripsToTheSameColour() {
        for (int color : new int[]{0xE0242F, 0x4C9AFF, 0x34E0A1, 0x808080, 0xFFFFFF, 0x000000, 0xA3FF3D}) {
            double[] hsl = LogoColors.toHsl(color | 0xFF000000);
            assertEquals(hex(color), color | 0xFF000000, LogoColors.fromHsl(hsl[0], hsl[1], hsl[2]));
        }
    }

    /** The themes' tiles plus a sweep of hue, saturation and lightness, for the colours a custom theme can make. */
    private static int[] everyKindOfTile() {
        java.util.List<Integer> tiles = new java.util.ArrayList<>();
        for (int tile : THEME_TILES) tiles.add(tile);
        for (int hue = 0; hue < 360; hue += 15) {
            for (double saturation : new double[]{0, 0.1, 0.4, 0.7, 1}) {
                for (double lightness = 0.05; lightness < 0.96; lightness += 0.05) {
                    tiles.add(LogoColors.fromHsl(hue, saturation, lightness) & 0xFFFFFF);
                }
            }
        }
        int[] all = new int[tiles.size()];
        for (int i = 0; i < all.length; i++) all[i] = tiles.get(i);
        return all;
    }

    private static String hex(int color) {
        return String.format("#%06X", color & 0xFFFFFF);
    }
}
