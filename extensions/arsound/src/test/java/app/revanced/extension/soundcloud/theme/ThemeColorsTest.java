package app.revanced.extension.soundcloud.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ThemeColorsTest {
    @Test
    public void aSixDigitColourIsSolid() {
        assertEquals(0xFFFF5500, ThemeColors.parse("#ff5500"));
        assertEquals(0xFF121212, ThemeColors.parse("#121212"));
    }

    @Test
    public void anEightDigitColourHasItsAlphaLast() {
        assertEquals(0x80FF5500, ThemeColors.parse("#ff550080"));
    }

    @Test
    public void aMissingColourIsThePinkPlaceholder() {
        assertEquals(ThemeColors.MISSING, ThemeColors.parse(""));
        assertEquals(ThemeColors.MISSING, ThemeColors.parse(null));
        assertEquals(ThemeColors.MISSING, ThemeColors.parse("#"));
    }

    @Test
    public void theOriginalLookHasItsRolesInBothModes() {
        assertEquals(0xFF121212, ThemeColors.original("surface", true));
        assertEquals(0xFFFFFFFF, ThemeColors.original("surface", false));
        assertEquals(0xFFFFFFFF, ThemeColors.original("primary", true));
        assertEquals(0xFFD32F2F, ThemeColors.original("error", false));
        assertEquals(0xFF1E1E1E, ThemeColors.original("dialog", true));
    }

    @Test
    public void anyOtherRoleIsTheOrangeAccent() {
        assertEquals(0xFFFF5500, ThemeColors.original("special", true));
        assertEquals(0xFFFF5500, ThemeColors.original("anything", false));
    }
}
