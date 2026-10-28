package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WallhavenRulesTest {
    @Test
    public void aPhoneOfTheOwnersWidthGetsThreeColumns() {
        // 720 px at density 1.875 is 384 dp.
        assertEquals(3, WallhavenRules.columns(384, true));
        assertEquals(3, WallhavenRules.columns(360, true));
    }

    @Test
    public void aNarrowPhoneKeepsTwoColumns() {
        assertEquals(2, WallhavenRules.columns(320, true));
        assertEquals(2, WallhavenRules.columns(200, true));
    }

    @Test
    public void aWideScreenGetsMoreColumns() {
        assertEquals(5, WallhavenRules.columns(600, true));
    }

    @Test
    public void widePicturesAlwaysGetTwoColumns() {
        assertEquals(2, WallhavenRules.columns(800, false));
    }

    @Test
    public void tallCellsAreTallerThanWide() {
        assertEquals(true, WallhavenRules.cellRatio(true) > 1.5f);
        assertEquals(true, WallhavenRules.cellRatio(false) < 1f);
    }

    @Test
    public void theUsualFiltersCountAsNone() {
        int all = Wallhaven.GENERAL | Wallhaven.ANIME | Wallhaven.PEOPLE;
        assertEquals(0, WallhavenRules.activeFilters(all, true, false));
    }

    @Test
    public void everyChangeFromTheUsualCountsOnce() {
        int all = Wallhaven.GENERAL | Wallhaven.ANIME | Wallhaven.PEOPLE;
        assertEquals(1, WallhavenRules.activeFilters(Wallhaven.ANIME, true, false));
        assertEquals(1, WallhavenRules.activeFilters(all, false, false));
        assertEquals(1, WallhavenRules.activeFilters(all, true, true));
        assertEquals(3, WallhavenRules.activeFilters(Wallhaven.GENERAL, false, true));
    }
}
