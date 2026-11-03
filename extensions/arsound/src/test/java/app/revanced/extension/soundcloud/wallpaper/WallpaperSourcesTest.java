package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

import app.revanced.extension.soundcloud.update.UpdateChecker;

/** The two sources side by side: who owns which address, what each one shows, and the name the services see. */
public class WallpaperSourcesTest {
    private static WallpaperItem commonsItem(String author, String licence, String pageUrl) {
        return new WallpaperItem(CommonsSource.INSTANCE, "commons1", "sfw", "2000x3000",
                "https://thumb.wikimedia.org/a/500px-a.jpg", "https://thumb.wikimedia.org/a/1920px-a.jpg", 0, 1_000_000, 0, 0,
                pageUrl, author, licence, "");
    }

    // region Registry

    @Test
    public void wallhavenIsFirstAndCommonsSecond() {
        WallpaperSource[] all = WallpaperSources.all();
        assertEquals(2, all.length);
        assertEquals("wallhaven", all[0].id());
        assertEquals("commons", all[1].id());
        assertSame(CommonsSource.INSTANCE, all[1]);
    }

    @Test
    public void aSavedIdFindsItsSourceAndAnUnknownOneFindsWallhaven() {
        assertSame(CommonsSource.INSTANCE, WallpaperSources.byId("commons"));
        assertEquals("wallhaven", WallpaperSources.byId("wallhaven").id());
        assertEquals("wallhaven", WallpaperSources.byId("flickr").id());
        assertEquals("wallhaven", WallpaperSources.byId(null).id());
        assertEquals("wallhaven", WallpaperSources.byId("").id());
    }

    @Test
    public void everyAddressBelongsToTheSourceOfItsHostOrToNone() {
        assertSame(CommonsSource.INSTANCE, WallpaperSources.owning("https://upload.wikimedia.org/wikipedia/commons/a/ab/X.jpg"));
        assertSame(CommonsSource.INSTANCE, WallpaperSources.owning("https://thumb.wikimedia.org/wikipedia/commons/thumb/a/X.jpg"));
        assertEquals("wallhaven", WallpaperSources.owning("https://th.wallhaven.cc/orig/dp/dpm5ll.jpg").id());
        assertEquals("wallhaven", WallpaperSources.owning("https://w.wallhaven.cc/full/dp/wallhaven-dpm5ll.jpg").id());
        assertNull(WallpaperSources.owning("https://evil.example.com/x.jpg"));
        assertNull(WallpaperSources.owning("http://upload.wikimedia.org/x.jpg"));
        assertNull(WallpaperSources.owning("https://wallhaven.cc.evil.example.com/x.jpg"));
        assertNull(WallpaperSources.owning(null));
    }

    @Test
    public void noSourceOwnsTheHostOfAnother() {
        assertFalse(Wallhaven.SOURCE.ownsHost("https://upload.wikimedia.org/x.jpg"));
        assertFalse(CommonsSource.INSTANCE.ownsHost("https://w.wallhaven.cc/full/dp/wallhaven-dpm5ll.jpg"));
        assertFalse(Wallhaven.SOURCE.ownsHost("http://w.wallhaven.cc/full/x.jpg"));
    }

    // endregion

    // region What each one shows

    @Test
    public void commonsOffersOnlyChosenSetsAndWallhavenTheRest() {
        assertTrue(CommonsSource.INSTANCE.curatedOnly());
        assertFalse(Wallhaven.SOURCE.curatedOnly());
        assertEquals("Wikimedia Commons", CommonsSource.INSTANCE.name());
        assertEquals("Wallhaven", Wallhaven.SOURCE.name());
    }

    @Test
    public void commonsReadsTwoPicturesAtATimeAndWallhavenHasNoGate() {
        Semaphore gate = CommonsSource.INSTANCE.imageGate();
        assertNotNull(gate);
        assertEquals(2, gate.availablePermits());
        assertNull(Wallhaven.SOURCE.imageGate());
    }

    @Test
    public void commonsHasOnlyTheShapeToCountAsAFilter() {
        Wallhaven.Query query = new Wallhaven.Query();
        query.portrait = true;
        query.categories = Wallhaven.ANIME;
        assertEquals(0, CommonsSource.INSTANCE.activeFilters(query));
        query.portrait = false;
        assertEquals(1, CommonsSource.INSTANCE.activeFilters(query));
    }

    @Test
    public void aNewSearchStartsOnTheQualityPicturesWithoutAWallhavenKeyRefusal() {
        Wallhaven.Query query = new Wallhaven.Query();
        assertEquals(CommonsRules.QUALITY, query.commonsSet);
        assertFalse(query.withoutKey);
        query.commonsSet = CommonsRules.FEATURED;
        query.withoutKey = true;
        Wallhaven.Query copy = query.copy();
        assertEquals(CommonsRules.FEATURED, copy.commonsSet);
        assertTrue(copy.withoutKey);
    }

    // endregion

    // region The lines of the preview

    @Test
    public void commonsShowsTheCreditInRussianAndOpensTheDescriptionPage() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("ru"));
            List<WallpaperSource.Line> lines = CommonsSource.INSTANCE.lines(
                    commonsItem("Ikiwaner", "CC0", "https://commons.wikimedia.org/wiki/File:X.jpg"));
            assertEquals(1, lines.size());
            assertEquals("Ikiwaner · Общественное достояние · Wikimedia Commons", lines.get(0).text);
            assertEquals("https://commons.wikimedia.org/wiki/File:X.jpg", lines.get(0).link);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    public void commonsShowsTheCreditInEnglishAndWithoutALinkWhenThereIsNoPage() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.ENGLISH);
            List<WallpaperSource.Line> lines = CommonsSource.INSTANCE.lines(commonsItem("Ikiwaner", "CC BY-SA 4.0", ""));
            assertEquals("Ikiwaner · CC BY-SA 4.0 · Wikimedia Commons", lines.get(0).text);
            assertNull(lines.get(0).link);
        } finally {
            Locale.setDefault(before);
        }
    }

    // endregion

    // region The name the services see

    @Test
    public void theServicesAreToldWhoCallsAndWhereToReadAboutIt() {
        String agent = WallpaperHttp.userAgent();
        assertEquals("Arsound/" + UpdateChecker.VERSION + " (https://github.com/Allvoid/arsound)", agent);
        assertTrue(agent, agent.startsWith("Arsound/"));
        assertTrue(agent, agent.contains("https://github.com/Allvoid/arsound"));
    }

    @Test
    public void aRedirectMayStayOnTheHostOfTheCallOnly() {
        String api = "https://commons.wikimedia.org/w/api.php";
        assertTrue(WallpaperHttp.sameHost(api, "https://commons.wikimedia.org/w/api.php?x=1"));
        assertTrue(WallpaperHttp.sameHost(api, "https://COMMONS.wikimedia.org/other"));
        assertFalse(WallpaperHttp.sameHost(api, "https://evil.example.com/w/api.php"));
        assertFalse(WallpaperHttp.sameHost(api, "http://commons.wikimedia.org/w/api.php"));
        assertFalse(WallpaperHttp.sameHost(api, "not an address"));
    }

    // endregion
}
