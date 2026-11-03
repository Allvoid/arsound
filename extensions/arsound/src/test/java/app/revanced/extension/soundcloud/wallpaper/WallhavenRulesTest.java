package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    // region Sorting

    @Test
    public void aTextIsLookedForByRelevanceUnlessASortingWasChosen() {
        assertEquals(Wallhaven.RELEVANCE, WallhavenRules.sorting("space", Wallhaven.TOPLIST, false));
        assertEquals(Wallhaven.RELEVANCE, WallhavenRules.sorting("space", Wallhaven.FAVORITES, false));
        // The top list of a period holds a handful of pictures; a chosen one is still kept, as the user asked for it.
        assertEquals(Wallhaven.TOPLIST, WallhavenRules.sorting("space", Wallhaven.TOPLIST, true));
        assertEquals(Wallhaven.DATE_ADDED, WallhavenRules.sorting("space", Wallhaven.DATE_ADDED, true));
    }

    @Test
    public void aBrowseWithNoTextIsByTheMostSavedPictures() {
        assertEquals(Wallhaven.FAVORITES, WallhavenRules.DEFAULT_SORTING);
        assertEquals(Wallhaven.FAVORITES, WallhavenRules.sorting("", Wallhaven.RELEVANCE, false));
        assertEquals(Wallhaven.FAVORITES, WallhavenRules.sorting("   ", Wallhaven.TOPLIST, false));
        assertEquals(Wallhaven.FAVORITES, WallhavenRules.sorting(null, Wallhaven.FAVORITES, false));
        assertEquals(Wallhaven.RANDOM, WallhavenRules.sorting("", Wallhaven.RANDOM, true));
    }

    @Test
    public void theOldStartsOfASearchCountAsTheAppsOwnChoice() {
        // Older builds saved the sorting always: the month's top list at the start, relevance once a text was typed.
        assertFalse(WallhavenRules.legacySortingWasChosen(Wallhaven.TOPLIST, "1M", false));
        assertFalse(WallhavenRules.legacySortingWasChosen(Wallhaven.RELEVANCE, "1M", true));
        assertFalse(WallhavenRules.legacySortingWasChosen(Wallhaven.RELEVANCE, "3M", true));
    }

    @Test
    public void everythingElseAnOlderBuildSavedWasATapOnAChip() {
        assertTrue(WallhavenRules.legacySortingWasChosen(Wallhaven.TOPLIST, "3M", false));
        assertTrue(WallhavenRules.legacySortingWasChosen(Wallhaven.RELEVANCE, "1M", false));
        assertTrue(WallhavenRules.legacySortingWasChosen(Wallhaven.VIEWS, "1M", false));
        assertTrue(WallhavenRules.legacySortingWasChosen(Wallhaven.RANDOM, "1M", true));
    }

    // endregion

    // region Shape

    @Test
    public void tallPicturesAreAskedForFirstAndWideOnesFollow() {
        assertEquals(WallhavenRules.Shape.TALL, WallhavenRules.shape(true, false));
        assertEquals(WallhavenRules.Shape.WIDE_FOR_PHONE, WallhavenRules.shape(true, true));
    }

    @Test
    public void wideByChoiceStaysWideWhatEverTheSearchDid() {
        assertEquals(WallhavenRules.Shape.WIDE, WallhavenRules.shape(false, false));
        assertEquals(WallhavenRules.Shape.WIDE, WallhavenRules.shape(false, true));
    }

    @Test
    public void wideOnesFollowOnlyAfterTheLastPageOfTheTallOnes() {
        assertTrue(WallhavenRules.tallRanOut(true, false, 20, 20));
        assertFalse(WallhavenRules.tallRanOut(true, false, 19, 20));
        // A search with no tall picture at all answers page 1 of 1.
        assertTrue(WallhavenRules.tallRanOut(true, false, 1, 1));
    }

    @Test
    public void wideOnesAreNotAskedForTwiceOrWhenTheUserChoseWide() {
        assertFalse(WallhavenRules.tallRanOut(true, true, 20, 20));
        assertFalse(WallhavenRules.tallRanOut(false, false, 20, 20));
    }

    @Test
    public void aWidePictureIsRecognisedByItsResolution() {
        assertTrue(WallhavenRules.isWide("1920x1080"));
        assertTrue(WallhavenRules.isWide("5120x2880"));
        assertFalse(WallhavenRules.isWide("1080x1920"));
        assertFalse(WallhavenRules.isWide("1000x1000"));
    }

    @Test
    public void aResolutionThatCannotBeReadIsNotWide() {
        assertFalse(WallhavenRules.isWide(null));
        assertFalse(WallhavenRules.isWide(""));
        assertFalse(WallhavenRules.isWide("x"));
        assertFalse(WallhavenRules.isWide("1920x"));
        assertFalse(WallhavenRules.isWide("x1080"));
        assertFalse(WallhavenRules.isWide("wide"));
    }

    @Test
    public void theTallFloorIsLowerThanTheOldOneButStillAPhoneWidth() {
        // The old floor was 1080x1920; most phones need about 1080 pixels across.
        assertEquals(1080, WallhavenRules.TALL_MIN_WIDTH);
        assertTrue(WallhavenRules.TALL_MIN_HEIGHT < 1920);
        assertTrue(WallhavenRules.TALL_MIN_HEIGHT > WallhavenRules.TALL_MIN_WIDTH);
    }

    @Test
    public void aSizeIsWrittenWithTheMultiplicationSign() {
        assertEquals("1080×1440", WallhavenRules.size(1080, 1440));
    }

    // endregion
}
