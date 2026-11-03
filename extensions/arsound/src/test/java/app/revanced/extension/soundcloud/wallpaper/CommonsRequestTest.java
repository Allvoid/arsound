package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.net.URLDecoder;

/**
 * What the Commons source really asks the service for. The filters decide which pictures can be found at all, and the
 * category of a set and the category that is left out are what keeps an unchecked picture out (Commons has no safe
 * search), so all are pinned here.
 */
public class CommonsRequestTest {
    private static final WallhavenRules.Shape TALL = WallhavenRules.Shape.TALL;

    /** The value of a parameter of the address, decoded; null when it is not there. */
    private static String value(String params, String name) throws Exception {
        for (String pair : params.split("&")) {
            if (pair.startsWith(name + "=")) return URLDecoder.decode(pair.substring(name.length() + 1), "UTF-8");
        }
        return null;
    }

    @Test
    public void aTextSearchOfTheQualitySetAsksForTallPictures() throws Exception {
        assertEquals("action=query&format=json&formatversion=2&generator=search&gsrnamespace=6&gsrlimit=30"
                        + "&gsrsearch=space+filetype%3Abitmap+fileh%3A%3E1919+filew%3A1080%2C2400+incategory%3A%22Quality+images%22"
                        + "+-deepcategory%3A%22Nudity%22"
                        + "&prop=imageinfo%7Ccategories&iiprop=url%7Csize%7Cmime%7Cextmetadata"
                        + "&iiextmetadatafilter=Artist%7CLicenseShortName%7CAttributionRequired%7CImageDescription&iiurlwidth=330"
                        + "&cllimit=max&clshow=%21hidden",
                CommonsRequest.params("space", CommonsRules.QUALITY, TALL, 1));
    }

    @Test
    public void theThreeStagesAskForTheirSizes() {
        assertEquals("fileh:>1919 filew:1080,2400", CommonsRequest.sizeFilters(WallhavenRules.Shape.TALL));
        assertEquals("fileh:>1439 filew:>2559", CommonsRequest.sizeFilters(WallhavenRules.Shape.WIDE_FOR_PHONE));
        assertEquals("fileh:>1079 filew:>1919", CommonsRequest.sizeFilters(WallhavenRules.Shape.WIDE));
    }

    @Test
    public void everySearchHasTheCategoryOfASetAndOnlyBitmaps() {
        assertEquals("space filetype:bitmap fileh:>1919 filew:1080,2400 incategory:\"Quality images\" -deepcategory:\"Nudity\"",
                CommonsRequest.searchText("space", CommonsRules.QUALITY, TALL));
        assertEquals("filetype:bitmap fileh:>1439 filew:>2559 incategory:\"Featured pictures on Wikimedia Commons\""
                        + " -deepcategory:\"Nudity\"",
                CommonsRequest.searchText("", CommonsRules.FEATURED, WallhavenRules.Shape.WIDE_FOR_PHONE));
        // An unknown set is the default one, never no category at all.
        assertTrue(CommonsRequest.searchText("", "everything", TALL).contains(" incategory:\"Quality images\" "));
    }

    @Test
    public void everySearchLeavesOutTheNudityCategoryWhateverTheSetStageOrWords() throws Exception {
        assertEquals("-deepcategory:\"Nudity\"", CommonsRequest.EXCLUDED);
        for (String set : CommonsRules.SETS) {
            for (WallhavenRules.Shape shape : WallhavenRules.Shape.values()) {
                for (String words : new String[]{"", "space", "nude", "cat\" OR -deepcategory:\"Nudity\" x"}) {
                    assertTrue(CommonsRequest.searchText(words, set, shape), CommonsRequest.searchText(words, set, shape)
                            .endsWith(" -deepcategory:\"Nudity\""));
                    assertTrue(value(CommonsRequest.params(words, set, shape, 1), "gsrsearch").endsWith(" -deepcategory:\"Nudity\""));
                }
            }
        }
    }

    @Test
    public void theCategoriesOfTheFilesAreAskedForToBeChecked() throws Exception {
        String params = CommonsRequest.params("space", CommonsRules.QUALITY, TALL, 1);
        assertEquals("imageinfo|categories", value(params, "prop"));
        // As many as the service gives for a page, and only the visible ones: the hidden ones are the wiki's bookkeeping.
        assertEquals("max", value(params, "cllimit"));
        assertEquals("!hidden", value(params, "clshow"));
    }

    @Test
    public void aBrowseWithNoWordsAsksForARandomOrderOfTheSet() throws Exception {
        String params = CommonsRequest.params("", CommonsRules.FEATURED, TALL, 1);
        assertEquals("random", value(params, "gsrsort"));
        assertEquals("filetype:bitmap fileh:>1919 filew:1080,2400 incategory:\"Featured pictures on Wikimedia Commons\""
                + " -deepcategory:\"Nudity\"", value(params, "gsrsearch"));
        assertEquals("random", value(CommonsRequest.params("   ", CommonsRules.QUALITY, TALL, 1), "gsrsort"));
    }

    @Test
    public void aSearchWithWordsIsByRelevance() throws Exception {
        assertEquals(null, value(CommonsRequest.params("cat", CommonsRules.QUALITY, TALL, 1), "gsrsort"));
    }

    @Test
    public void thePagesAreWindowsOfThirtyResults() throws Exception {
        assertEquals(0, CommonsRequest.offset(1));
        assertEquals(30, CommonsRequest.offset(2));
        assertEquals(60, CommonsRequest.offset(3));
        assertEquals(null, value(CommonsRequest.params("cat", CommonsRules.QUALITY, TALL, 1), "gsroffset"));
        assertEquals("60", value(CommonsRequest.params("cat", CommonsRules.QUALITY, TALL, 3), "gsroffset"));
    }

    @Test
    public void thePageIsNeverBelowOneOrBeyondTheServicesLimit() {
        assertEquals(0, CommonsRequest.offset(0));
        assertEquals(0, CommonsRequest.offset(-4));
        assertEquals((CommonsRules.MAX_PAGES - 1) * CommonsRules.PAGE_SIZE, CommonsRequest.offset(1000));
        assertTrue((CommonsRules.MAX_PAGES - 1) * CommonsRules.PAGE_SIZE + CommonsRules.PAGE_SIZE <= 10_000);
    }

    @Test
    public void typedOperatorsCannotTakeTheCategoryAway() throws Exception {
        String search = value(CommonsRequest.params("cat\" OR incategory:\"Nudes -x", CommonsRules.QUALITY, TALL, 1), "gsrsearch");
        assertEquals("cat or incategory nudes x filetype:bitmap fileh:>1919 filew:1080,2400 incategory:\"Quality images\""
                + " -deepcategory:\"Nudity\"", search);
        // The only quotes are the two around the category of the set and the two around the one that is left out.
        assertEquals(4, search.length() - search.replace("\"", "").length());
        assertFalse(search.contains("OR"));
    }

    @Test
    public void russianWordsAreEncodedAsUtf8() throws Exception {
        String params = CommonsRequest.params("космос", CommonsRules.QUALITY, TALL, 1);
        assertTrue(params, params.contains("gsrsearch=%D0%BA%D0%BE%D1%81%D0%BC%D0%BE%D1%81+filetype"));
    }

    @Test
    public void theThumbnailIsAskedForAtTheGridWidth() throws Exception {
        // A cell of three columns on a phone of 1080 pixels is about 320 wide; 330 is a width the servers keep ready.
        assertEquals("330", value(CommonsRequest.params("", CommonsRules.QUALITY, TALL, 1), "iiurlwidth"));
        assertEquals(330, CommonsRules.THUMB_WIDTH);
    }

    @Test
    public void theAddressIsTheCommonsApiOverHttps() {
        assertEquals("https://commons.wikimedia.org/w/api.php", CommonsRequest.API);
        assertTrue(CommonsRules.isPageUrl(CommonsRequest.API));
    }
}
