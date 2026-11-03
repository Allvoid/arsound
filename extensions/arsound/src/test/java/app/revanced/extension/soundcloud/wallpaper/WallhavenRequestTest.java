package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * What the Wallhaven browser really asks the service for. The sizes and the sorting decide how many pictures a search
 * can find, so they are pinned here (measured on the guest API: the old asks found 292 tall pictures in a browse and none
 * for "space").
 */
public class WallhavenRequestTest {
    private static final int ALL = Wallhaven.GENERAL | Wallhaven.ANIME | Wallhaven.PEOPLE;

    private static String params(String text, String sorting, boolean chosen, WallhavenRules.Shape shape) throws Exception {
        return WallhavenRequest.params(text, WallhavenRules.sorting(text, sorting, chosen), "1y", ALL, Wallhaven.SFW, "", shape, 1);
    }

    @Test
    public void aBrowseAsksForTheMostSavedTallPictures() throws Exception {
        assertEquals("categories=111&purity=100&sorting=favorites&order=desc&page=1&ratios=portrait&atleast=1080x1440",
                params("", Wallhaven.FAVORITES, false, WallhavenRules.Shape.TALL));
    }

    @Test
    public void aTextSearchIsNeverLimitedToTheTopListOfAPeriod() throws Exception {
        // Even when the sorting saved from an older build is the top list.
        String asked = params("space", Wallhaven.TOPLIST, false, WallhavenRules.Shape.TALL);
        assertTrue(asked, asked.contains("&sorting=relevance"));
        assertFalse(asked, asked.contains("topRange"));
        assertTrue(asked, asked.contains("&q=space"));
    }

    @Test
    public void aChosenTopListKeepsItsPeriod() throws Exception {
        String asked = params("space", Wallhaven.TOPLIST, true, WallhavenRules.Shape.TALL);
        assertTrue(asked, asked.contains("&sorting=toplist"));
        assertTrue(asked, asked.contains("&topRange=1y"));
    }

    @Test
    public void thePeriodIsSentOnlyForTheTopList() throws Exception {
        assertFalse(params("", Wallhaven.VIEWS, true, WallhavenRules.Shape.TALL).contains("topRange"));
        assertFalse(params("", Wallhaven.DATE_ADDED, true, WallhavenRules.Shape.TALL).contains("topRange"));
    }

    @Test
    public void theTextIsTrimmedAndEncoded() throws Exception {
        assertTrue(WallhavenRequest.params("  dark forest ", Wallhaven.RELEVANCE, "1y", ALL, Wallhaven.SFW, "",
                WallhavenRules.Shape.TALL, 1).contains("&q=dark+forest&"));
        assertTrue(WallhavenRequest.params("кот", Wallhaven.RELEVANCE, "1y", ALL, Wallhaven.SFW, "",
                WallhavenRules.Shape.TALL, 1).contains("&q=%D0%BA%D0%BE%D1%82&"));
        assertTrue(WallhavenRequest.params("a&b", Wallhaven.RELEVANCE, "1y", ALL, Wallhaven.SFW, "",
                WallhavenRules.Shape.TALL, 1).contains("&q=a%26b&"));
    }

    @Test
    public void aBlankTextAsksForNoText() throws Exception {
        assertFalse(params("   ", Wallhaven.FAVORITES, false, WallhavenRules.Shape.TALL).contains("&q="));
        assertFalse(WallhavenRequest.params(null, Wallhaven.FAVORITES, "1y", ALL, Wallhaven.SFW, "",
                WallhavenRules.Shape.TALL, 1).contains("&q="));
    }

    @Test
    public void theSeedIsSentOnlyForARandomSortingAndOnlyWhenThereIsOne() throws Exception {
        assertTrue(WallhavenRequest.params("", Wallhaven.RANDOM, "1y", ALL, Wallhaven.SFW, "abc123",
                WallhavenRules.Shape.TALL, 2).contains("&seed=abc123"));
        assertFalse(WallhavenRequest.params("", Wallhaven.RANDOM, "1y", ALL, Wallhaven.SFW, "",
                WallhavenRules.Shape.TALL, 1).contains("seed"));
        assertFalse(WallhavenRequest.params("", Wallhaven.FAVORITES, "1y", ALL, Wallhaven.SFW, "abc123",
                WallhavenRules.Shape.TALL, 1).contains("seed"));
    }

    @Test
    public void thePageIsNeverBelowOne() throws Exception {
        assertTrue(WallhavenRequest.params("", Wallhaven.FAVORITES, "1y", ALL, Wallhaven.SFW, "",
                WallhavenRules.Shape.TALL, 0).contains("&page=1&"));
        assertTrue(WallhavenRequest.params("", Wallhaven.FAVORITES, "1y", ALL, Wallhaven.SFW, "",
                WallhavenRules.Shape.TALL, 7).contains("&page=7&"));
    }

    @Test
    public void wideOnesThatFollowTheTallOnesAreWideAndBig() {
        assertEquals("&ratios=landscape&atleast=2560x1440", WallhavenRequest.shapeParams(WallhavenRules.Shape.WIDE_FOR_PHONE));
    }

    @Test
    public void wideByChoiceKeepsItsOldAsk() {
        assertEquals("&atleast=1920x1080", WallhavenRequest.shapeParams(WallhavenRules.Shape.WIDE));
    }

    @Test
    public void tallOnesAskForTallRatiosOnly() {
        assertEquals("&ratios=portrait&atleast=1080x1440", WallhavenRequest.shapeParams(WallhavenRules.Shape.TALL));
    }

    @Test
    public void theMasksAreThreeDigits() {
        assertEquals("111", WallhavenRequest.mask(ALL));
        assertEquals("100", WallhavenRequest.mask(Wallhaven.SFW));
        assertEquals("101", WallhavenRequest.mask(Wallhaven.SFW | Wallhaven.NSFW));
        assertEquals("010", WallhavenRequest.mask(Wallhaven.ANIME));
    }
}
