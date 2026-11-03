package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Locale;

public class CommonsRulesTest {
    private static final String THUMB = "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Seattle_Space_needle.jpg/"
            + "330px-Seattle_Space_needle.jpg?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=thumbnail";
    private static final String ORIGINAL = "https://upload.wikimedia.org/wikipedia/commons/b/b9/Seattle_Space_needle.jpg"
            + "?utm_source=commons.wikimedia.org&utm_campaign=imageinfo&utm_content=original";

    // region Shape and order

    @Test
    public void aPictureIsTallFromOneAndAThirdTimesItsWidth() {
        assertTrue(CommonsRules.isTall(1000, 1300));
        assertTrue(CommonsRules.isTall(1080, 2400));
        assertFalse(CommonsRules.isTall(1000, 1299));
        assertFalse(CommonsRules.isTall(2000, 2000));
        assertFalse(CommonsRules.isTall(3000, 2000));
        assertFalse(CommonsRules.isTall(0, 2000));
    }

    @Test
    public void theTallStageKeepsOnlyTallPicturesOfAPhoneSize() {
        WallhavenRules.Shape tall = WallhavenRules.Shape.TALL;
        assertTrue(CommonsRules.keeps(tall, 1080, 1920));
        assertTrue(CommonsRules.keeps(tall, 2304, 3456));
        // Too narrow, too low, or not tall enough.
        assertFalse(CommonsRules.keeps(tall, 1000, 2000));
        assertFalse(CommonsRules.keeps(tall, 1080, 1700));
        assertFalse(CommonsRules.keeps(tall, 2075, 2346));
        assertFalse(CommonsRules.keeps(tall, 3000, 2000));
    }

    @Test
    public void theBigStageKeepsBigPicturesOfAnyShape() {
        WallhavenRules.Shape big = WallhavenRules.Shape.WIDE_FOR_PHONE;
        assertTrue(CommonsRules.keeps(big, 3000, 2000));
        assertTrue(CommonsRules.keeps(big, 2560, 1440));
        assertTrue(CommonsRules.keeps(big, 3000, 4500));
        assertFalse(CommonsRules.keeps(big, 2559, 1440));
        assertFalse(CommonsRules.keeps(big, 2560, 1439));
    }

    @Test
    public void theWideStageKeepsOnlyWidePictures() {
        WallhavenRules.Shape wide = WallhavenRules.Shape.WIDE;
        assertTrue(CommonsRules.keeps(wide, 1920, 1080));
        assertTrue(CommonsRules.keeps(wide, 4000, 3000));
        assertFalse(CommonsRules.keeps(wide, 1919, 1080));
        assertFalse(CommonsRules.keeps(wide, 3000, 3000));
        assertFalse(CommonsRules.keeps(wide, 2000, 3000));
    }

    @Test
    public void tallPicturesComeFirstAndKeepTheirOrder() {
        int[] widths = {3000, 1200, 2000, 1500, 3000};
        int[] heights = {2000, 2400, 2000, 3000, 4500};
        assertArrayEquals(new int[]{1, 3, 4, 0, 2}, CommonsRules.tallFirstOrder(widths, heights));
    }

    @Test
    public void anOrderOfNothingIsNothing() {
        assertEquals(0, CommonsRules.tallFirstOrder(new int[0], new int[0]).length);
    }

    @Test
    public void onlyJpegAndPngAreUsed() {
        assertTrue(CommonsRules.isUsableMime("image/jpeg"));
        assertTrue(CommonsRules.isUsableMime("image/png"));
        assertFalse(CommonsRules.isUsableMime("image/gif"));
        assertFalse(CommonsRules.isUsableMime("image/tiff"));
        assertFalse(CommonsRules.isUsableMime("image/svg+xml"));
        assertFalse(CommonsRules.isUsableMime(""));
    }

    // endregion

    // region Sets

    @Test
    public void anUnknownSetIsTheDefaultOne() {
        assertEquals(CommonsRules.FEATURED, CommonsRules.validSet("featured"));
        assertEquals(CommonsRules.QUALITY, CommonsRules.validSet("quality"));
        assertEquals(CommonsRules.DEFAULT_SET, CommonsRules.validSet("all"));
        assertEquals(CommonsRules.DEFAULT_SET, CommonsRules.validSet(null));
        assertEquals("Featured pictures on Wikimedia Commons", CommonsRules.category(CommonsRules.FEATURED));
        assertEquals("Quality images", CommonsRules.category(CommonsRules.QUALITY));
    }

    @Test
    public void onlyTheShapeCountsAsAFilterOfCommons() {
        assertEquals(0, CommonsRules.activeFilters(true));
        assertEquals(1, CommonsRules.activeFilters(false));
    }

    // endregion

    // region Search text

    @Test
    public void theSearchTextIsLettersDigitsAndSpacesOnly() {
        assertEquals("space needle", CommonsRules.cleanText("  Space   Needle!! "));
        assertEquals("кот", CommonsRules.cleanText("Кот"));
        assertEquals("a b", CommonsRules.cleanText("a:b"));
        assertEquals("", CommonsRules.cleanText(null));
        assertEquals("", CommonsRules.cleanText("  \"\" ::: "));
    }

    @Test
    public void hyphensAndApostrophesStayInsideWordsOnly() {
        assertEquals("x-ray", CommonsRules.cleanText("x-ray"));
        assertEquals("rock'n'roll", CommonsRules.cleanText("rock'n'roll"));
        assertEquals("rock'n'roll", CommonsRules.cleanText("rock’n’roll"));
        // A minus at the start of a word would exclude it from the search.
        assertEquals("foo bar", CommonsRules.cleanText("foo -bar"));
        assertEquals("foo", CommonsRules.cleanText("-foo"));
    }

    @Test
    public void searchOperatorsCannotWidenTheSearchBeyondItsFilters() {
        // The words are lower case: the operators are the upper case OR, AND and NOT. Quotes and colons are gone.
        assertEquals("a or b incategory foo x", CommonsRules.cleanText("a OR b\" incategory:\"Foo -x"));
        assertEquals("not intitle cat", CommonsRules.cleanText("NOT intitle:cat"));
    }

    @Test
    public void aLongTextIsCut() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 300; i++) text.append('a');
        assertEquals(CommonsRules.MAX_TEXT, CommonsRules.cleanText(text.toString()).length());
    }

    // endregion

    // region Unwanted words

    @Test
    public void everyUnwantedWordIsCaughtInAnyCase() {
        String[] words = {"nude", "nudes", "nudity", "naked", "topless", "erotic", "erotica", "porn", "pornography",
                "pornographic", "fetish", "sexual", "lingerie", "bdsm", "genital", "genitals", "penis", "vagina", "breast",
                "breasts", "nipple", "nipples", "buttocks"};
        for (String word : words) {
            String capital = Character.toUpperCase(word.charAt(0)) + word.substring(1);
            assertTrue(word, CommonsRules.hasUnwantedWord(word));
            assertTrue(word, CommonsRules.hasUnwantedWord(word.toUpperCase(Locale.ROOT)));
            assertTrue(word, CommonsRules.hasUnwantedWord("Category:" + capital + " art"));
        }
    }

    @Test
    public void aWordEndsAtAnythingThatIsNotALetterOrADigit() {
        assertTrue(CommonsRules.hasUnwantedWord("File:Nude_beach.jpg"));
        assertTrue(CommonsRules.hasUnwantedWord("File:Nude-study (1912).jpg"));
        assertTrue(CommonsRules.hasUnwantedWord("Category:Nude women in art"));
        assertTrue(CommonsRules.hasUnwantedWord("Category:Sexual dimorphism in birds"));
        assertTrue(CommonsRules.hasUnwantedWord("the nude's pose"));
    }

    @Test
    public void wordsThatOnlyContainAnUnwantedOneAreKept() {
        String[] fine = {"Sussex", "Essex", "File:Middlesex coast.jpg", "Penistone", "Nudibranch", "Breastplate", "Pornic",
                "Naturist", "File:Seattle Space needle.jpg", "Category:Quality images", "nude2", "", null};
        for (String text : fine) assertFalse(String.valueOf(text), CommonsRules.hasUnwantedWord(text));
    }

    // endregion

    // region Addresses

    @Test
    public void picturesComeOnlyFromTheWikimediaServersOverHttps() {
        assertTrue(CommonsRules.isImageHost("https://upload.wikimedia.org/wikipedia/commons/b/b9/X.jpg"));
        assertTrue(CommonsRules.isImageHost("https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/X.jpg/500px-X.jpg"));
        assertTrue(CommonsRules.isImageHost("HTTPS://UPLOAD.WIKIMEDIA.ORG/x.jpg"));
        assertTrue(CommonsRules.isImageHost("https://upload.wikimedia.org:443/x.jpg"));
    }

    @Test
    public void anyOtherHostOrSchemeIsRefused() {
        String[] refused = {
                "http://upload.wikimedia.org/x.jpg",
                "https://evil.example.com/x.jpg",
                "https://upload.wikimedia.org.evil.example.com/x.jpg",
                "https://evil.example.com/upload.wikimedia.org/x.jpg",
                "https://evil.example.com/?u=https://upload.wikimedia.org/x.jpg",
                "https://upload.wikimedia.org@evil.example.com/x.jpg",
                "https://user@upload.wikimedia.org/x.jpg",
                "https://upload.wikimedia.org:8443/x.jpg",
                "https://upload.wikimedia.org./x.jpg",
                "https://commons.wikimedia.org/wiki/File:X.jpg",
                "https://wikimedia.org/x.jpg",
                "ftp://upload.wikimedia.org/x.jpg",
                "file:///sdcard/x.jpg",
                "//upload.wikimedia.org/x.jpg",
                "upload.wikimedia.org/x.jpg",
                "not an address",
                "",
                null};
        for (String address : refused) assertFalse(String.valueOf(address), CommonsRules.isImageHost(address));
    }

    @Test
    public void theDescriptionPageIsOnCommonsOverHttps() {
        assertTrue(CommonsRules.isPageUrl("https://commons.wikimedia.org/wiki/File:Seattle_Space_needle.jpg"));
        assertFalse(CommonsRules.isPageUrl("http://commons.wikimedia.org/wiki/File:X.jpg"));
        assertFalse(CommonsRules.isPageUrl("https://evil.example.com/wiki/File:X.jpg"));
        assertFalse(CommonsRules.isPageUrl("https://upload.wikimedia.org/x.jpg"));
        assertFalse(CommonsRules.isPageUrl(null));
    }

    @Test
    public void theTrackingWordsOfAnAddressAreDropped() {
        assertEquals("https://upload.wikimedia.org/a.jpg",
                CommonsRules.stripQuery("https://upload.wikimedia.org/a.jpg?utm_source=x&y=1"));
        assertEquals("https://upload.wikimedia.org/a.jpg", CommonsRules.stripQuery("https://upload.wikimedia.org/a.jpg#top"));
        assertEquals("https://upload.wikimedia.org/a.jpg", CommonsRules.stripQuery("https://upload.wikimedia.org/a.jpg"));
        assertEquals("", CommonsRules.stripQuery(null));
    }

    // endregion

    // region Which copy is downloaded

    @Test
    public void aTallPictureIsDownloadedAt1920() {
        assertEquals(1920, CommonsRules.fullWidth(2304, 3456));
        assertEquals(1920, CommonsRules.fullWidth(5000, 8000));
        assertEquals(1920, CommonsRules.fullWidth(1921, 2600));
    }

    @Test
    public void aTallPictureNoWiderThan1920IsFetchedAsItIs() {
        // A copy at 1920 would only be a blur of it.
        assertEquals(0, CommonsRules.fullWidth(1920, 2600));
        assertEquals(0, CommonsRules.fullWidth(1500, 2200));
        assertEquals(0, CommonsRules.fullWidth(1080, 1920));
    }

    @Test
    public void aWideOrSquarePictureKeepsItsPixelsUpTo3840() {
        // Only the middle of it shows on a phone, so a copy at 1920 would leave too little of it.
        assertEquals(3840, CommonsRules.fullWidth(4000, 3000));
        assertEquals(3840, CommonsRules.fullWidth(4200, 2800));
        assertEquals(3840, CommonsRules.fullWidth(5000, 5000));
        assertEquals(3840, CommonsRules.fullWidth(3841, 2000));
        // Not wider than 3840: the original, whatever its width (3000 was a copy at 1920 before).
        assertEquals(0, CommonsRules.fullWidth(3840, 2160));
        assertEquals(0, CommonsRules.fullWidth(3000, 2000));
        assertEquals(0, CommonsRules.fullWidth(2560, 1440));
        // Not tall enough to count as tall: 2346 / 2075 is under 1.3.
        assertEquals(0, CommonsRules.fullWidth(2075, 2346));
    }

    @Test
    public void theFullAddressIsTheThumbnailAddressAtTheChosenWidth() {
        assertEquals("https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Seattle_Space_needle.jpg/"
                + "1920px-Seattle_Space_needle.jpg", CommonsRules.fullUrl(THUMB, ORIGINAL, 2304, 3456));
        assertEquals("https://thumb.wikimedia.org/wikipedia/commons/thumb/b/b9/Seattle_Space_needle.jpg/"
                + "3840px-Seattle_Space_needle.jpg", CommonsRules.fullUrl(THUMB, ORIGINAL, 4000, 3000));
    }

    @Test
    public void aNarrowPictureIsFetchedFromItsOriginalAddress() {
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/b/b9/Seattle_Space_needle.jpg",
                CommonsRules.fullUrl(THUMB, ORIGINAL, 1500, 2200));
    }

    @Test
    public void escapedNamesSurviveTheChange() {
        String folder = "https://thumb.wikimedia.org/wikipedia/commons/thumb/b/ba/Lyon_5e_-_Cath%C3%A9drale_%28Bonnassieux%29.jpg/";
        String thumb = folder + "500px-Lyon_5e_-_Cath%C3%A9drale_%28Bonnassieux%29.jpg";
        assertEquals(folder + "1920px-Lyon_5e_-_Cath%C3%A9drale_%28Bonnassieux%29.jpg",
                CommonsRules.fullUrl(thumb, ORIGINAL, 2229, 3877));
    }

    @Test
    public void aThumbnailAddressOfAnUnknownFormFallsBackToTheOriginal() {
        String original = "https://upload.wikimedia.org/wikipedia/commons/b/b9/Seattle_Space_needle.jpg";
        assertEquals(original, CommonsRules.fullUrl("https://thumb.wikimedia.org/foo.jpg", ORIGINAL, 2304, 3456));
        assertEquals(original, CommonsRules.fullUrl("https://thumb.wikimedia.org/a/abpx-foo.jpg", ORIGINAL, 2304, 3456));
        assertEquals(original, CommonsRules.fullUrl("", ORIGINAL, 2304, 3456));
        assertEquals(original, CommonsRules.fullUrl(null, ORIGINAL, 2304, 3456));
    }

    @Test
    public void theSizeOfACopyIsGuessedFromItsPixels() {
        assertEquals(1_000_000L, CommonsRules.estimateBytes(1_000_000L, 1920, 1920));
        assertEquals(250_000L, CommonsRules.estimateBytes(1_000_000L, 3840, 1920));
        assertEquals(1_000_000L, CommonsRules.estimateBytes(1_000_000L, 1500, 0));
        assertEquals(0L, CommonsRules.estimateBytes(0, 3840, 1920));
        assertEquals(0L, CommonsRules.estimateBytes(-5, 3840, 1920));
    }

    // endregion

    // region Credit

    @Test
    public void htmlIsReducedToItsText() {
        assertEquals("Ikiwaner", CommonsRules.stripHtml(
                "<a href=\"//commons.wikimedia.org/wiki/User:Ikiwaner\" title=\"User:Ikiwaner\">Ikiwaner</a>", 60));
        assertEquals("Bold and plain", CommonsRules.stripHtml("<b>Bold</b> and <i>plain</i>", 60));
        assertEquals("one two", CommonsRules.stripHtml("one<br>two", 60));
        assertEquals("one two", CommonsRules.stripHtml("<p>one</p><p>two</p>", 60));
        assertEquals("", CommonsRules.stripHtml(null, 60));
        assertEquals("", CommonsRules.stripHtml("", 60));
    }

    @Test
    public void entitiesAreDecodedOnceAndAfterTheTags() {
        assertEquals("Cats & dogs", CommonsRules.stripHtml("Cats &amp; dogs", 60));
        assertEquals("<b> is a tag", CommonsRules.stripHtml("&lt;b&gt; is a tag", 60));
        assertEquals("d'Assise \"quoted\"", CommonsRules.stripHtml("d&#039;Assise &quot;quoted&quot;", 60));
        assertEquals("François", CommonsRules.stripHtml("Fran&#231;ois", 60));
        assertEquals("François", CommonsRules.stripHtml("Fran&#xE7;ois", 60));
        assertEquals("a b", CommonsRules.stripHtml("a&nbsp;&nbsp;b", 60));
        // An entity nobody knows, and an ampersand that starts none, stay as they are.
        assertEquals("&unknown; and AT&T", CommonsRules.stripHtml("&unknown; and AT&T", 60));
    }

    @Test
    public void scriptsAndStylesDoNotReachTheText() {
        assertEquals("before after", CommonsRules.stripHtml("before <script>alert(1)</script> after", 60));
        assertEquals("text", CommonsRules.stripHtml("<style>p {color: red}</style>text", 60));
    }

    @Test
    public void anUnfinishedTagIsDropped() {
        assertEquals("text", CommonsRules.stripHtml("text <a href=\"x", 60));
    }

    @Test
    public void anElementThatHidesItselfIsDroppedWithItsContent() {
        assertEquals("Unknown", CommonsRules.stripHtml("Unknown<span style=\"display: none;\">author</span>", 60));
        assertEquals("Unknown", CommonsRules.stripHtml("Unknown<span style=\"display:none\">author</span>", 60));
        assertEquals("Unknown", CommonsRules.stripHtml("Unknown<span style='display:none'>author</span>", 60));
        assertEquals("Unknown",
                CommonsRules.stripHtml("Unknown<span style=\"color: red; DISPLAY : NONE !important\">author</span>", 60));
        assertEquals("Unknown", CommonsRules.stripHtml("Unknown<span class=\"hidden\">author</span>", 60));
        assertEquals("Unknown", CommonsRules.stripHtml("Unknown<span class=\"note Hidden\">author</span>", 60));
        // An element of the same name inside it does not end it early.
        assertEquals("a c", CommonsRules.stripHtml("a <div style=\"display:none\">b<div>x</div>y</div> c", 60));
        // A tag with no end takes nothing with it; one that is never closed hides the rest, as a browser does.
        assertEquals("one two", CommonsRules.stripHtml("one<br style=\"display:none\"> two", 60));
        assertEquals("Unknown", CommonsRules.stripHtml("Unknown<span style=\"display:none\">author", 60));
    }

    @Test
    public void anElementThatOnlyLooksLikeItHidesIsKept() {
        assertEquals("Unknown author", CommonsRules.stripHtml("Unknown <span style=\"display: inline\">author</span>", 60));
        assertEquals("x y", CommonsRules.stripHtml("x <span data-style=\"display:none\">y</span>", 60));
        assertEquals("x y", CommonsRules.stripHtml("x <span class=\"hiddenness\">y</span>", 60));
        assertEquals("x y", CommonsRules.stripHtml("x <span title=\"display:none\">y</span>", 60));
    }

    @Test
    public void aScriptWithALessThanSignInItIsDroppedWhole() {
        assertEquals("a b", CommonsRules.stripHtml("a <script>if (x<y) go()</script> b", 60));
    }

    @Test
    public void theAuthorIsTheTextOfTheFirstLinkWhenThereIsOne() {
        assertEquals("Ikiwaner", CommonsRules.author(
                "<a href=\"//commons.wikimedia.org/wiki/User:Ikiwaner\" title=\"User:Ikiwaner\">Ikiwaner</a>"));
        assertEquals("Franz J\u00fcttner", CommonsRules.author("Designed by <a href=\"https://de.wikipedia.org/wiki/Franz\" "
                + "class=\"extiw\">Franz J\u00fcttner</a>, issued by the town of <a href=\"x\">Lutter am Barenberge</a>"));
        assertEquals("Jane Doe", CommonsRules.author("<span><a href=\"x\">Jane <b>Doe</b></a> (talk)</span>"));
        assertEquals("Jane & Joe", CommonsRules.author("by <a href=\"x\">Jane &amp; Joe</a>"));
    }

    @Test
    public void withNoLinkTheAuthorIsTheWholeTextWithoutWhatIsHidden() {
        assertEquals("Unknown author", CommonsRules.author("Unknown author"));
        assertEquals("Unknown", CommonsRules.author("Unknown<span style=\"display: none;\">author</span>"));
        assertEquals("Jane", CommonsRules.author("Jane<a href=\"x\"></a>"));
        // A link nobody sees is not the author.
        assertEquals("Real",
                CommonsRules.author("<span style=\"display:none\"><a href=\"x\">Junk</a></span><a href=\"y\">Real</a>"));
        assertEquals("", CommonsRules.author(null));
        assertEquals("", CommonsRules.author(""));
    }

    @Test
    public void aLongAuthorIsCutAtAWordAtTheLongerLimit() {
        assertEquals(120, CommonsRules.MAX_AUTHOR);
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 40; i++) name.append("Name").append(i).append(' ');
        String cut = CommonsRules.author(name.toString());
        assertTrue(cut, cut.length() <= CommonsRules.MAX_AUTHOR);
        assertTrue(cut, cut.endsWith("\u2026"));
        // What stands before the ellipsis is whole words of the text.
        assertTrue(cut, name.toString().startsWith(cut.substring(0, cut.length() - 1) + " "));
        // A name of 95 characters fits, which the old limit of 60 would have cut.
        String longName = "Alexandria Octavia Maximilienne de la Tour d'Auvergne et de la Maison Royale de Savoie Carignan";
        assertEquals(longName, CommonsRules.author(longName));
    }

    @Test
    public void aLongTextIsCutAtAWordWithAnEllipsis() {
        String cut = CommonsRules.stripHtml("Designed by Franz Jüttner, issued by the town of Lutter am Barenberge", 30);
        assertTrue(cut, cut.length() <= 30);
        // The comma left at the end of the kept words is dropped.
        assertEquals("Designed by Franz Jüttner…", cut);
        assertEquals("short", CommonsRules.stripHtml("short", 30));
    }

    @Test
    public void aCutIsAtASpaceInTheSecondHalfOnlyAndElseWhereTheRoomEnds() {
        assertEquals("one two…", CommonsRules.shorten("one two three four", 12));
        assertEquals("one, two…", CommonsRules.shorten("one, two, three four", 11));
        // One long word has no place to cut at but its end.
        assertEquals("abcdefghi…", CommonsRules.shorten("abcdefghijklmnopqrstuvwxyz abc", 10));
        // A space too early would leave too little.
        assertEquals("ab cdefghij…", CommonsRules.shorten("ab cdefghijklmnopqrstuvwxyz", 12));
        assertEquals("fits", CommonsRules.shorten("fits", 4));
    }

    @Test
    public void aCutDoesNotSplitACharacterMadeOfTwoParts() {
        // Nine letters, then one character written as two UTF-16 units (an emoji), then four more letters.
        String text = "aaaaaaaaa\uD83D\uDE00bbbb";
        String cut = CommonsRules.shorten(text, 11);
        assertFalse(cut, Character.isHighSurrogate(cut.charAt(cut.length() - 2)));
    }

    @Test
    public void thePublicDomainAndCc0AreSaidInWords() {
        String[] free = {"Public domain", "public domain", "CC0", "CC0 1.0", "PD-old", "PD-USGov", "PD", "Public Domain Mark"};
        for (String licence : free) {
            assertEquals(licence, "Общественное достояние", CommonsRules.licenceLabel(licence, true));
            assertEquals(licence, "Public domain", CommonsRules.licenceLabel(licence, false));
        }
    }

    @Test
    public void otherLicencesKeepTheirNames() {
        assertEquals("CC BY-SA 4.0", CommonsRules.licenceLabel("CC BY-SA 4.0", true));
        assertEquals("CC BY 2.0", CommonsRules.licenceLabel("CC BY 2.0", false));
        // Not mistaken for the public domain by its first letters.
        assertEquals("PDF Free Licence", CommonsRules.licenceLabel("PDF Free Licence", false));
        assertEquals("", CommonsRules.licenceLabel("", true));
        assertEquals("", CommonsRules.licenceLabel(null, true));
    }

    @Test
    public void theCreditLineSaysWhoUnderWhichLicenceAndWhere() {
        assertEquals("Ikiwaner · CC BY-SA 3.0 · Wikimedia Commons",
                CommonsRules.creditLine("Ikiwaner", "CC BY-SA 3.0", true));
        assertEquals("Romainbehar · Общественное достояние · Wikimedia Commons",
                CommonsRules.creditLine("Romainbehar", "CC0", true));
        assertEquals("Romainbehar · Public domain · Wikimedia Commons",
                CommonsRules.creditLine("Romainbehar", "CC0", false));
    }

    @Test
    public void aMissingAuthorOrLicenceIsSaidPlainly() {
        assertEquals("Автор не указан · CC BY 2.0 · Wikimedia Commons", CommonsRules.creditLine("", "CC BY 2.0", true));
        assertEquals("Author not given · CC BY 2.0 · Wikimedia Commons", CommonsRules.creditLine(null, "CC BY 2.0", false));
        assertEquals("Jane · Wikimedia Commons", CommonsRules.creditLine("Jane", "", false));
    }

    // endregion
}
