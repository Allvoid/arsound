package app.revanced.extension.soundcloud.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

import app.arsound.shaded.newpipe.extractor.Image;

/**
 * The pictures of the Arsound search: the small one for a row, the big one for the top of an album or an artist page,
 * and the key the two are kept under so they never share a bitmap.
 */
public class SearchPicturesTest {
    private static final String COVER = "https://lh3.googleusercontent.com/abc";
    private static final String AVATAR = "https://yt3.ggpht.com/xyz";

    private static Image image(String url, int side) {
        return new Image(url, side, side, Image.ResolutionLevel.UNKNOWN);
    }

    private static List<Image> googleCovers() {
        return Arrays.asList(image(COVER + "=w60-h60-l90-rj", 60), image(COVER + "=w120-h120-l90-rj", 120));
    }

    @Test
    public void theBigCoverIsAskedFor544AndTheRowCoverFor226() {
        assertEquals(COVER + "=w544-h544", OtherSource.coverUrl(googleCovers()));
        assertEquals(COVER + "=w226-h226", OtherSource.thumbUrl(googleCovers()));
    }

    @Test
    public void theBigAddressDiffersFromTheRowAddress() {
        List<Image> images = googleCovers();
        assertNotEquals(OtherSource.thumbUrl(images), OtherSource.coverUrl(images));
    }

    @Test
    public void anAvatarOfAChannelIsAskedForInTheBigSizeToo() {
        List<Image> avatars = Arrays.asList(image(AVATAR + "=s88-c-k-c0x00ffffff-no-rj", 88),
                image(AVATAR + "=s176-c-k-c0x00ffffff-no-rj", 176));
        assertEquals(AVATAR + "=s544-c-k-c0x00ffffff-no-rj", OtherSource.coverUrl(avatars));
        assertEquals(AVATAR + "=s226-c-k-c0x00ffffff-no-rj", OtherSource.thumbUrl(avatars));
    }

    @Test
    public void aPictureThatCannotBeResizedStaysAsItIs() {
        String frame = "https://i.ytimg.com/vi/id/hqdefault.jpg";
        List<Image> frames = Arrays.asList(image(frame, 360));
        assertEquals(frame, OtherSource.coverUrl(frames));
        assertEquals(frame, OtherSource.thumbUrl(frames));
    }

    @Test
    public void noImagesGiveNoAddress() {
        assertNull(OtherSource.coverUrl(new ArrayList<Image>()));
        assertNull(OtherSource.thumbUrl(new ArrayList<Image>()));
    }

    @Test
    public void theTopOfThePageTakesTheBigCoverAndFallsBackToTheSmallOne() {
        OtherSource.Album both = new OtherSource.Album("u", "title", "artist", 3, "big", "small");
        assertEquals("big", both.heroUrl());
        assertEquals("small", both.thumbUrl);
        assertEquals("small", new OtherSource.Album("u", "title", "artist", 3, null, "small").heroUrl());
        assertEquals("big", new OtherSource.Artist("u", "name", "big", "small").heroUrl());
        assertNull(new OtherSource.Artist("u", "name", null, null).heroUrl());
    }

    @Test
    public void oneAddressAtTwoSidesIsTwoPictures() {
        SearchCovers.Picture row = new SearchCovers.Picture("https://example.com/a.jpg", 192);
        SearchCovers.Picture hero = new SearchCovers.Picture("https://example.com/a.jpg", 504);
        assertNotEquals(row, hero);
        assertNotEquals(row.hashCode(), hero.hashCode());
    }

    @Test
    public void theSameAddressAtTheSameSideIsOnePicture() {
        SearchCovers.Picture first = new SearchCovers.Picture("https://example.com/a.jpg", 192);
        SearchCovers.Picture second = new SearchCovers.Picture(new String("https://example.com/a.jpg"), 192);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotEquals(first, new SearchCovers.Picture("https://example.com/b.jpg", 192));
    }
}
