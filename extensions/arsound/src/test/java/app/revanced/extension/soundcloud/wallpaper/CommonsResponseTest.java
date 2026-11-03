package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads answers of the Commons search the way the phone does. The first picture is an entry recorded from the live API on
 * 03.11 (the extra fields it carries are kept, because the reader must not trip over them; its categories are what the
 * same search with {@code prop=categories} gave); the others are written to the same shape to cover what the filters, the
 * check of the words and the host check have to turn away.
 */
public class CommonsResponseTest {
    private static final WallhavenRules.Shape TALL = WallhavenRules.Shape.TALL;
    private static final WallpaperSource SOURCE = CommonsSource.INSTANCE;

    /** JSON written with single quotes, which is easier to read inside Java; an escaped quote is written as backslash and '. */
    private static String q(String text) {
        return text.replace('\'', '"');
    }

    private static final String THUMB_URL = "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Seattle_Space_needle.jpg/"
            + "500px-Seattle_Space_needle.jpg?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail";

    /** Recorded: "space" in the quality images, rank 2. */
    private static final String SEATTLE = q("{'pageid':3840619,'ns':6,'title':'File:Seattle Space needle.jpg','index':2,"
            + "'imagerepository':'local','categories':[{'ns':14,'title':'Category:Space Needle'}],"
            + "'imageinfo':[{'size':1722428,'width':2304,'height':3456,"
            + "'thumburl':'" + THUMB_URL + "','thumbwidth':500,'thumbheight':750,"
            + "'responsiveUrls':{'2':'https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Seattle_Space_needle.jpg/"
            + "1280px-Seattle_Space_needle.jpg?utm_source=commons.wikimedia.org&utm_campaign=imageinfo'},"
            + "'thumbattribs':{'src':'" + THUMB_URL + "','decoding':'async','loading':'lazy','width':500,'height':750},"
            + "'url':'https://upload.wikimedia.org/wikipedia/commons/b/b9/Seattle_Space_needle.jpg"
            + "?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=original',"
            + "'descriptionurl':'https://commons.wikimedia.org/wiki/File:Seattle_Space_needle.jpg',"
            + "'descriptionshorturl':'https://commons.wikimedia.org/w/index.php?curid=3840619',"
            + "'extmetadata':{'ImageDescription':{'value':'Space needle in Seattle, WA','source':'commons-desc-page'},"
            + "'Artist':{'value':'<a href=\\'//commons.wikimedia.org/wiki/User:Ikiwaner\\' title=\\'User:Ikiwaner\\'>Ikiwaner</a>',"
            + "'source':'commons-desc-page'},"
            + "'LicenseShortName':{'value':'CC BY-SA 3.0','source':'commons-desc-page','hidden':''},"
            + "'AttributionRequired':{'value':'true','source':'commons-desc-page','hidden':''}},'mime':'image/jpeg'}]}");

    /** A file of the answer in the same shape, with the host of its pictures to choose. */
    private static String file(long pageId, int index, int width, int height, String mime, long size, String name, String host,
                               String artist, String licence, String description) {
        String thumb = "https://" + host + "/wikipedia/commons/thumb/a/ab/" + name + "/500px-" + name;
        String original = "https://" + host + "/wikipedia/commons/a/ab/" + name;
        return q("{'pageid':" + pageId + ",'ns':6,'title':'File:" + name + "','index':" + index + ",'imageinfo':[{'size':" + size
                + ",'width':" + width + ",'height':" + height + ",'thumburl':'" + thumb + "','url':'" + original
                + "','descriptionurl':'https://commons.wikimedia.org/wiki/File:" + name + "','mime':'" + mime
                + "','extmetadata':{'Artist':{'value':'" + artist + "'},'LicenseShortName':{'value':'" + licence
                + "'},'ImageDescription':{'value':'" + description + "'}}}]}");
    }

    private static String jpeg(long pageId, int index, int width, int height, String name) {
        return file(pageId, index, width, height, "image/jpeg", 2_000_000, name, "upload.wikimedia.org", "Jane Doe", "CC BY 2.0",
                "A picture");
    }

    private static final String LUTTER = file(79367716, 1, 2145, 2854, "image/jpeg", 5538401, "Lutter_10_Pfg_1920.jpg",
            "upload.wikimedia.org",
            "Designed by <a href=\\'https://de.wikipedia.org/wiki/Franz\\' class=\\'extiw\\'>Franz J\u00fcttner</a>, issued by the town"
                    + " of <a href=\\'https://en.wikipedia.org/wiki/Lutter\\'>Lutter am Barenberge</a>, reproduced by Palauenc05",
            "Public domain", "10 Pfennig banknote");
    private static final String LYON = file(180410157, 3, 2229, 3877, "image/jpeg", 9869547, "Lyon_Statue.jpg",
            "upload.wikimedia.org", "Romainbehar", "CC0", "Statue de saint Fran&#231;ois &amp; sa colombe");
    private static final String WIDE = jpeg(111, 4, 3000, 2000, "Wide_lake.jpg");
    private static final String GIF = file(112, 5, 1200, 2400, "image/gif", 100_000, "Animation.gif", "upload.wikimedia.org",
            "Jane Doe", "CC BY 2.0", "Moving");
    private static final String NARROW = jpeg(113, 6, 1000, 2000, "Narrow.jpg");
    private static final String EVIL = file(114, 7, 2000, 3000, "image/jpeg", 1000, "Evil.jpg", "evil.example.com", "Mallory",
            "CC0", "Not from Wikimedia");
    private static final String SQUARE = jpeg(115, 8, 2075, 2346, "Square.jpg");
    private static final String BIG_TALL = jpeg(116, 9, 3000, 4500, "Big_tall.jpg");

    /** The file with the categories the answer lists for it, as the service writes them: a namespace and a full title. */
    private static String inCategories(String file, String... titles) {
        StringBuilder list = new StringBuilder();
        for (String title : titles) list.append(list.length() == 0 ? "" : ",").append(q("{'ns':14,'title':'" + title + "'}"));
        return file.replace("\"ns\":6,", "\"ns\":6,\"categories\":[" + list + "],");
    }

    private static String answer(boolean more, String... files) {
        return q("{'batchcomplete':true,") + (more ? q("'continue':{'gsroffset':30,'continue':'gsroffset||'},") : "")
                + q("'query':{'pages':[") + String.join(",", files) + "]}}";
    }

    private static List<String> ids(WallpaperSource.Page page) {
        List<String> ids = new ArrayList<>();
        for (Wallhaven.Item item : page.items) ids.add(item.id);
        return ids;
    }

    // region A recorded entry

    @Test
    public void aRecordedEntryBecomesAPictureWithItsCredit() throws Exception {
        WallpaperSource.Page page = CommonsResponse.parse(answer(true, SEATTLE), TALL, 1, SOURCE);
        assertEquals(1, page.items.size());
        Wallhaven.Item item = page.items.get(0);
        assertSame(SOURCE, item.source);
        assertEquals("commons3840619", item.id);
        assertEquals("2304x3456", item.resolution);
        assertEquals("sfw", item.purity);
        assertTrue(item.isSafe());
        assertFalse(item.isWide());
        assertEquals("Ikiwaner", item.author);
        assertEquals("CC BY-SA 3.0", item.licence);
        assertEquals("Space needle in Seattle, WA", item.description);
        assertEquals("Space needle in Seattle, WA", item.label());
        assertEquals("https://commons.wikimedia.org/wiki/File:Seattle_Space_needle.jpg", item.pageUrl);
        assertEquals(0, item.views);
        assertEquals(0, item.favorites);
    }

    @Test
    public void theAddressesLoseTheirTrackingWordsAndTheFullOneIsA1920Copy() throws Exception {
        Wallhaven.Item item = CommonsResponse.parse(answer(false, SEATTLE), TALL, 1, SOURCE).items.get(0);
        assertEquals("https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Seattle_Space_needle.jpg/"
                + "500px-Seattle_Space_needle.jpg", item.thumbnail);
        assertEquals("https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Seattle_Space_needle.jpg/"
                + "1920px-Seattle_Space_needle.jpg", item.fullUrl);
        assertTrue(SOURCE.ownsHost(item.thumbnail));
        assertTrue(SOURCE.ownsHost(item.fullUrl));
    }

    @Test
    public void theGridIsNotOfferedAFileToSharpenFromButThePreviewHasAnEstimate() throws Exception {
        Wallhaven.Item item = CommonsResponse.parse(answer(false, SEATTLE), TALL, 1, SOURCE).items.get(0);
        // The sharp stage of the grid only runs for a picture that names a size: a 500 pixel thumbnail already fills a cell.
        assertEquals(0L, item.fileSize);
        double scale = 1920.0 / 2304;
        assertEquals(Math.round(1722428 * scale * scale), item.previewBytes);
        assertTrue(item.previewBytes < 1722428L);
    }

    // endregion

    // region What is kept, and in which order

    @Test
    public void theTallStageKeepsOnlyTallJpegsAndPngsFromWikimedia() throws Exception {
        String json = answer(true, SEATTLE, LUTTER, LYON, WIDE, GIF, NARROW, EVIL, SQUARE, BIG_TALL);
        WallpaperSource.Page page = CommonsResponse.parse(json, TALL, 1, SOURCE);
        assertEquals(Arrays.asList("commons79367716", "commons3840619", "commons180410157", "commons116"), ids(page));
        assertEquals(4, page.total);
    }

    @Test
    public void aPngIsKeptToo() throws Exception {
        String png = file(120, 1, 1500, 2600, "image/png", 3_000_000, "Poster.png", "upload.wikimedia.org", "Jane", "CC0", "Poster");
        assertEquals(Arrays.asList("commons120"), ids(CommonsResponse.parse(answer(false, png), TALL, 1, SOURCE)));
    }

    @Test
    public void thePicturesAreInTheOrderOfTheirRankingWhateverOrderTheAnswerListsThem() throws Exception {
        WallpaperSource.Page page = CommonsResponse.parse(answer(false, LYON, SEATTLE, LUTTER), TALL, 1, SOURCE);
        assertEquals(Arrays.asList("commons79367716", "commons3840619", "commons180410157"), ids(page));
    }

    @Test
    public void theWideStageKeepsOnlyWidePictures() throws Exception {
        String json = answer(false, SEATTLE, LUTTER, WIDE, GIF, EVIL, BIG_TALL);
        assertEquals(Arrays.asList("commons111"), ids(CommonsResponse.parse(json, WallhavenRules.Shape.WIDE, 1, SOURCE)));
    }

    @Test
    public void theBigStageListsTheTallOnesFirstThenTheWideOnes() throws Exception {
        // By rank the wide picture comes before the tall one; the tall one goes in front of it.
        String json = answer(false, SEATTLE, WIDE, BIG_TALL, LUTTER);
        WallpaperSource.Page page = CommonsResponse.parse(json, WallhavenRules.Shape.WIDE_FOR_PHONE, 1, SOURCE);
        assertEquals(Arrays.asList("commons116", "commons111"), ids(page));
        assertTrue(page.items.get(1).isWide());
        assertFalse(page.items.get(0).isWide());
    }

    @Test
    public void aWideBigPictureIsDownloadedAt3840() throws Exception {
        String huge = file(117, 1, 4200, 2800, "image/jpeg", 9_000_000, "Huge_panorama.jpg", "upload.wikimedia.org", "Jane",
                "CC BY 2.0", "Panorama");
        Wallhaven.Item item = CommonsResponse.parse(answer(false, huge), WallhavenRules.Shape.WIDE_FOR_PHONE, 1, SOURCE).items.get(0);
        assertTrue(item.fullUrl, item.fullUrl.endsWith("/3840px-Huge_panorama.jpg"));
        assertTrue(item.thumbnail, item.thumbnail.endsWith("/500px-Huge_panorama.jpg"));
        assertTrue(item.previewBytes > 0 && item.previewBytes < 9_000_000L);
    }

    @Test
    public void aWidePictureNoWiderThan3840IsDownloadedFromItsOriginalAddress() throws Exception {
        // 3000 wide: a copy at 1920 would leave too little of the middle that shows on a phone.
        Wallhaven.Item item = CommonsResponse.parse(answer(false, WIDE), WallhavenRules.Shape.WIDE_FOR_PHONE, 1, SOURCE).items.get(0);
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/ab/Wide_lake.jpg", item.fullUrl);
        assertEquals(2_000_000L, item.previewBytes);
    }

    @Test
    public void aNarrowPictureIsDownloadedFromItsOriginalAddress() throws Exception {
        String narrow = file(118, 1, 1500, 2200, "image/jpeg", 800_000, "Narrow_original.jpg", "upload.wikimedia.org", "Jane",
                "CC BY 2.0", "Narrow");
        Wallhaven.Item item = CommonsResponse.parse(answer(false, narrow), TALL, 1, SOURCE).items.get(0);
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/a/ab/Narrow_original.jpg", item.fullUrl);
        assertEquals(800_000L, item.previewBytes);
    }

    // endregion

    // region Unwanted pictures

    @Test
    public void aFileWithAnUnwantedWordInItsTitleIsDropped() throws Exception {
        String nude = jpeg(140, 1, 1500, 2600, "Nude_beach.jpg");
        String shouting = jpeg(141, 2, 1500, 2600, "TOPLESS_sunbather.jpg");
        WallpaperSource.Page page = CommonsResponse.parse(answer(false, nude, shouting, SEATTLE), TALL, 1, SOURCE);
        assertEquals(Arrays.asList("commons3840619"), ids(page));
    }

    @Test
    public void aFileInAnUnwantedCategoryIsDropped() throws Exception {
        String statue = inCategories(jpeg(142, 4, 1500, 2600, "Statue.jpg"), "Category:Statues", "Category:Nude sculptures");
        String hill = inCategories(jpeg(143, 1, 1500, 2600, "Hill.jpg"), "Category:Hills", "Category:Spring in Wales");
        String lace = inCategories(jpeg(144, 5, 1500, 2600, "Shop.jpg"), "Category:Lingerie shops");
        assertEquals(Arrays.asList("commons143", "commons3840619"),
                ids(CommonsResponse.parse(answer(false, statue, hill, lace, SEATTLE), TALL, 1, SOURCE)));
    }

    @Test
    public void onlyWholeWordsAreHeldAgainstAFile() throws Exception {
        String sussex = inCategories(jpeg(145, 1, 1500, 2600, "Sussex_coast.jpg"), "Category:Essex", "Category:Middlesex");
        assertEquals(Arrays.asList("commons145"), ids(CommonsResponse.parse(answer(false, sussex), TALL, 1, SOURCE)));
    }

    @Test
    public void aFileWithNoCategoriesAtAllIsKeptWhenTheListWasNotCut() throws Exception {
        assertEquals(Arrays.asList("commons146"),
                ids(CommonsResponse.parse(answer(false, jpeg(146, 1, 1500, 2600, "Plain.jpg")), TALL, 1, SOURCE)));
    }

    @Test
    public void whenTheListOfCategoriesWasCutTheFilesFromTheCutOnAreDropped() throws Exception {
        // The service lists the categories in the order of the page ids and says where it went on: 150 is complete, 151
        // is the one that was cut in the middle, 152 was not reached.
        String complete = inCategories(jpeg(150, 1, 1500, 2600, "A.jpg"), "Category:Hills");
        String cutInTheMiddle = inCategories(jpeg(151, 2, 1500, 2600, "B.jpg"), "Category:Hills");
        String notReached = jpeg(152, 3, 1500, 2600, "C.jpg");
        String json = q("{'continue':{'clcontinue':'151|Some_category','continue':'||'},'query':{'pages':[")
                + String.join(",", complete, cutInTheMiddle, notReached) + "]}}";
        WallpaperSource.Page page = CommonsResponse.parse(json, TALL, 1, SOURCE);
        assertEquals(Arrays.asList("commons150"), ids(page));
        // The answer has a continuation, so there may be a next page.
        assertEquals(2, page.lastPage);
    }

    @Test
    public void aCutThatCannotBeReadDropsEveryFile() throws Exception {
        String json = q("{'continue':{'clcontinue':'garbage','continue':'||'},'query':{'pages':[")
                + inCategories(jpeg(150, 1, 1500, 2600, "A.jpg"), "Category:Hills") + "]}}";
        assertTrue(CommonsResponse.parse(json, TALL, 1, SOURCE).items.isEmpty());
    }

    @Test
    public void aContinuationOfTheSearchAloneDoesNotCutTheCategories() throws Exception {
        assertEquals(Arrays.asList("commons142"),
                ids(CommonsResponse.parse(answer(true, inCategories(jpeg(142, 1, 1500, 2600, "A.jpg"), "Category:Hills")), TALL, 1,
                        SOURCE)));
    }

    // endregion

    // region Credit

    @Test
    public void theAuthorIsTheNameOfTheFirstLink() throws Exception {
        Wallhaven.Item item = CommonsResponse.parse(answer(false, LUTTER), TALL, 1, SOURCE).items.get(0);
        assertEquals("Franz J\u00fcttner", item.author);
        assertEquals("Public domain", item.licence);
    }

    @Test
    public void aLongAuthorWithNoLinkIsCutAtAWordAndStripped() throws Exception {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 40; i++) name.append("Name").append(i).append(i % 2 == 0 ? " <b>of</b> " : " ");
        String long40 = file(131, 1, 1500, 2600, "image/jpeg", 900_000, "Long_author.jpg", "upload.wikimedia.org", name.toString(),
                "CC0", "Description");
        Wallhaven.Item item = CommonsResponse.parse(answer(false, long40), TALL, 1, SOURCE).items.get(0);
        assertTrue(item.author, item.author.startsWith("Name0 of Name1 Name2 of Name3"));
        assertTrue(item.author, item.author.endsWith("\u2026"));
        assertTrue(item.author, item.author.length() <= CommonsRules.MAX_AUTHOR);
        assertFalse(item.author, item.author.contains("<"));
        assertTrue(item.author, name.toString().replace("<b>", "").replace("</b>", "").replace("  ", " ")
                .startsWith(item.author.substring(0, item.author.length() - 1) + " "));
    }

    @Test
    public void textHiddenInTheCreditIsLeftOut() throws Exception {
        String hidden = file(132, 1, 1500, 2600, "image/jpeg", 900_000, "Hidden_note.jpg", "upload.wikimedia.org",
                "Unknown<span style=\\'display: none;\\'>author</span>", "CC0",
                "A hill<span class=\\'hidden\\'> (machine note)</span>");
        Wallhaven.Item item = CommonsResponse.parse(answer(false, hidden), TALL, 1, SOURCE).items.get(0);
        assertEquals("Unknown", item.author);
        assertEquals("A hill", item.description);
    }

    @Test
    public void entitiesOfTheDescriptionAreDecoded() throws Exception {
        Wallhaven.Item item = CommonsResponse.parse(answer(false, LYON), TALL, 1, SOURCE).items.get(0);
        assertEquals("Statue de saint François & sa colombe", item.description);
        assertEquals("CC0", item.licence);
    }

    @Test
    public void aDescriptionPageOnAnotherHostIsNotKept() throws Exception {
        String hostile = LYON.replace("https://commons.wikimedia.org/wiki", "https://evil.example.com/wiki");
        Wallhaven.Item item = CommonsResponse.parse(answer(false, hostile), TALL, 1, SOURCE).items.get(0);
        assertEquals("", item.pageUrl);
    }

    @Test
    public void aFileWithNoCreditStillComes() throws Exception {
        String bare = q("{'pageid':130,'ns':6,'title':'File:Bare.jpg','index':1,'imageinfo':[{'size':900000,'width':1500,"
                + "'height':2600,'mime':'image/jpeg','thumburl':'https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Bare.jpg/"
                + "500px-Bare.jpg','url':'https://upload.wikimedia.org/wikipedia/commons/a/ab/Bare.jpg'}]}");
        Wallhaven.Item item = CommonsResponse.parse(answer(false, bare), TALL, 1, SOURCE).items.get(0);
        assertEquals("", item.author);
        assertEquals("", item.licence);
        assertEquals("", item.pageUrl);
        assertEquals("1500x2600", item.label());
    }

    // endregion

    // region Pages

    @Test
    public void theAnswerNamingAContinuationMeansThereIsAnotherPage() throws Exception {
        WallpaperSource.Page first = CommonsResponse.parse(answer(true, SEATTLE), TALL, 1, SOURCE);
        assertEquals(1, first.page);
        assertEquals(2, first.lastPage);
        assertEquals(6, CommonsResponse.parse(answer(true, SEATTLE), TALL, 5, SOURCE).lastPage);
    }

    @Test
    public void theLastPageIsTheOneWithNoContinuation() throws Exception {
        WallpaperSource.Page last = CommonsResponse.parse(answer(false, SEATTLE), TALL, 4, SOURCE);
        assertEquals(4, last.page);
        assertEquals(4, last.lastPage);
    }

    @Test
    public void thePagesStopAtTheLimit() throws Exception {
        assertEquals(CommonsRules.MAX_PAGES,
                CommonsResponse.parse(answer(true, SEATTLE), TALL, CommonsRules.MAX_PAGES, SOURCE).lastPage);
    }

    @Test
    public void aPageOfPicturesTheFiltersTurnedAwayHasNoneButMayHaveMore() throws Exception {
        WallpaperSource.Page page = CommonsResponse.parse(answer(true, WIDE, GIF), TALL, 2, SOURCE);
        assertTrue(page.items.isEmpty());
        assertEquals(3, page.lastPage);
    }

    @Test
    public void anAnswerWithNoResultsIsAnEmptyPage() throws Exception {
        for (String json : new String[]{q("{'batchcomplete':true}"), q("{'batchcomplete':true,'query':{'pages':[]}}"),
                q("{'query':{}}")}) {
            WallpaperSource.Page page = CommonsResponse.parse(json, TALL, 1, SOURCE);
            assertTrue(json, page.items.isEmpty());
            assertEquals(json, 1, page.lastPage);
        }
    }

    // endregion

    // region Trouble

    @Test
    public void aRateLimitOfTheServiceIsAnAnswer429() {
        try {
            CommonsResponse.parse(q("{'error':{'code':'ratelimited','info':'Slow down'}}"), TALL, 1, SOURCE);
            fail();
        } catch (WallpaperSource.Failure failure) {
            assertEquals(429, failure.code);
        } catch (IOException other) {
            fail(other.toString());
        }
    }

    @Test
    public void anyOtherErrorOfTheServiceIsAFailureOfItsOwn() {
        try {
            CommonsResponse.parse(q("{'error':{'code':'internal_api_error_Whatever','info':'x'}}"), TALL, 1, SOURCE);
            fail();
        } catch (WallpaperSource.Failure failure) {
            assertEquals(500, failure.code);
        } catch (IOException other) {
            fail(other.toString());
        }
    }

    @Test
    public void textThatIsNotAnAnswerIsAnIoErrorNotACrash() {
        StringBuilder tooDeep = new StringBuilder();
        for (int i = 0; i < 500; i++) tooDeep.append('[');
        for (String body : new String[]{"<html>Sign in to the Wi-Fi</html>", "", "[1,2]", "42", "{\"query\":", tooDeep.toString()}) {
            try {
                CommonsResponse.parse(body, TALL, 1, SOURCE);
                fail("accepted: " + body);
            } catch (WallpaperSource.Failure failure) {
                fail("a plain IO error was expected for: " + body);
            } catch (IOException expected) {
                // As it should be.
            }
        }
    }

    @Test
    public void entriesThatAreDamagedAreSkippedNotFatal() throws Exception {
        String json = q("{'query':{'pages':[42,'text',null,{},{'pageid':5,'imageinfo':[]},{'pageid':6,'imageinfo':[7]},")
                + SEATTLE + "]}}";
        assertEquals(Arrays.asList("commons3840619"), ids(CommonsResponse.parse(json, TALL, 1, SOURCE)));
    }

    // endregion
}
