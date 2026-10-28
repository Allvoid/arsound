package app.revanced.extension.soundcloud.theme;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class SplashGridTest {
    /** A small grid shaped like the baked one: the exact default look comes first. */
    private static final SplashGrid GRID = SplashGrid.of(
            "custom", "Arsound.Splash.custom_%02d_%02d",
            Arrays.asList("#121212", "#000000", "#0B1A33", "#33200B"),
            Arrays.asList("#FF5500", "#FFFFFF", "#2196F3", "#4CAF50"));

    @Test
    public void theDefaultLookGetsTheFirstCell() {
        assertEquals("Arsound.Splash.custom_00_00", GRID.styleFor(0xFF121212, 0xFFFF5500));
    }

    @Test
    public void aBlueThemeGetsTheBlueLetterOnTheBluishBackground() {
        assertEquals("Arsound.Splash.custom_02_02", GRID.styleFor(0xFF0A1426, 0xFF4FC3F7));
    }

    @Test
    public void theBackgroundAndTheLetterAreChosenApart() {
        assertEquals("Arsound.Splash.custom_01_03", GRID.styleFor(0xFF000000, 0xFF55AA55));
    }

    @Test
    public void knowsItsTheme() {
        assertEquals("custom", GRID.themeId);
    }
}
