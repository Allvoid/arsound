package app.revanced.extension.soundcloud.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The links of VK and YouTube Music, and how a link or a key finds its source. */
public class SourceLinksTest {
    @Test
    public void vkPlaylistLinks() {
        SourceLink link = VkLink.find("Слушай https://vk.com/music/playlist/-147845620_2949_9a8b7c6d5e4f");
        assertEquals(VkLink.PLAYLIST, link.kind);
        assertEquals("-147845620", link.owner);
        assertEquals("2949", link.id);
        assertEquals("9a8b7c6d5e4f", link.accessKey);

        link = VkLink.find("https://m.vk.ru/audio?act=audio_playlist123_45&access_hash=ABCDEF0123");
        assertEquals("123", link.owner);
        assertEquals("45", link.id);
        assertEquals("abcdef0123", link.accessKey);

        link = VkLink.find("https://vk.com/audios123?z=audio_playlist-77_8%2Fff00ee");
        assertEquals(VkLink.PLAYLIST, link.kind);
        assertEquals("-77", link.owner);
        assertEquals("ff00ee", link.accessKey);

        link = VkLink.find("vk.ru/music/album/-2000123_4567");
        assertEquals("4567", link.id);
        assertNull(link.accessKey);
    }

    @Test
    public void vkMusicOfAnOwner() {
        SourceLink link = VkLink.find("https://vk.com/audios-555");
        assertEquals(VkLink.AUDIOS, link.kind);
        assertEquals("-555", link.owner);
        assertNull(link.id);
        assertNull(VkLink.find("https://vk.com/id123"));
        assertNull(VkLink.find("https://notvk.com/audios1"));
    }

    @Test
    public void youTubeMusicLinks() {
        SourceLink link = YouTubeMusicLink.find("https://music.youtube.com/playlist?list=PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI&si=x");
        assertEquals(YouTubeMusicLink.PLAYLIST, link.kind);
        assertEquals("PLFgquLnL59alCl_2TQvOiD5Vgm1hCaGSI", link.id);
        assertEquals("OLAK5uy_abc", YouTubeMusicLink.find("https://youtube.com/playlist?list=OLAK5uy_abc").id);
        assertEquals("RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs",
                YouTubeMusicLink.find("https://music.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs").id);
        assertEquals("PLx1", YouTubeMusicLink.find("https://www.youtube.com/watch?v=abc&list=PLx1").id);
        assertEquals(YouTubeMusicLink.LIKES, YouTubeMusicLink.find("https://music.youtube.com/playlist?list=LM").kind);
        // A mix made on the fly for one listener has no list to read; a link without a list is a song.
        assertNull(YouTubeMusicLink.find("https://music.youtube.com/watch?v=abc&list=RDAMVMabc"));
        assertNull(YouTubeMusicLink.find("https://music.youtube.com/watch?v=abc"));
    }

    @Test
    public void anyLinkFindsItsSource() {
        assertSame(YandexSource.INSTANCE, TransferSource.findLink("https://music.yandex.ru/album/123").source());
        assertSame(VkSource.INSTANCE, TransferSource.findLink("https://vk.com/music/playlist/1_2").source());
        assertSame(YouTubeMusicSource.INSTANCE, TransferSource.findLink("music.youtube.com/playlist?list=PL1").source());
        assertNull(TransferSource.findLink("https://open.spotify.com/playlist/1"));
        assertNull(TransferSource.findLink(null));
    }

    @Test
    public void keysGoBackToTheirLinks() {
        // The keys of Yandex Music stay as they were before the other sources came: the history keeps them.
        TransferLink yandex = TransferSource.linkOfKey("PLAYLIST:someone:3");
        assertTrue(yandex instanceof YandexLink);
        assertEquals("PLAYLIST:someone:3", yandex.key());

        String vkKey = VkLink.playlist("-1", "2", "ab").key();
        assertEquals("vk:PLAYLIST:-1:2:ab", vkKey);
        SourceLink vk = (SourceLink) TransferSource.linkOfKey(vkKey);
        assertSame(VkSource.INSTANCE, vk.source());
        assertEquals("-1", vk.owner);
        assertEquals("ab", vk.accessKey);

        String ytKey = YouTubeMusicLink.likes().key();
        assertEquals("ytm:LIKES:::", ytKey);
        assertSame(YouTubeMusicSource.INSTANCE, TransferSource.linkOfKey(ytKey).source());
        assertEquals(YouTubeMusicLink.LIKES, ((SourceLink) TransferSource.linkOfKey(ytKey)).kind);

        assertNull(TransferSource.linkOfKey("nonsense"));
        assertNull(TransferSource.linkOfKey("vk:PLAYLIST:1"));
        // A damaged key without the part that names the list is no link.
        assertNull(TransferSource.linkOfKey("vk:AUDIOS:::"));
        assertNull(TransferSource.linkOfKey("vk:PLAYLIST:-1::"));
        assertNull(TransferSource.linkOfKey("ytm:PLAYLIST:::"));
        assertNull(TransferSource.linkOfKey("ytm:OTHER::x:"));
    }

    @Test
    public void sourcesById() {
        assertSame(VkSource.INSTANCE, TransferSource.byId("vk"));
        assertSame(YouTubeMusicSource.INSTANCE, TransferSource.byId("ytmusic"));
        assertSame(YandexSource.INSTANCE, TransferSource.byId("something else"));
    }
}
