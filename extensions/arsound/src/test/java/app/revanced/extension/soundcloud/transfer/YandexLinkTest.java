package app.revanced.extension.soundcloud.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class YandexLinkTest {
    @Test
    public void playlistOfAPerson() {
        YandexLink link = YandexLink.find("https://music.yandex.ru/users/yamusic-top/playlists/1076?utm_source=share");
        assertEquals(YandexLink.Kind.PLAYLIST, link.kind);
        assertEquals("yamusic-top", link.owner);
        assertEquals("1076", link.id);
    }

    @Test
    public void linkInsideTheSharedText() {
        YandexLink link = YandexLink.find("Слушаю «Хиты» в Яндекс Музыке: music.yandex.com/users/some.one/playlists/3 ");
        assertEquals("some.one", link.owner);
        assertEquals("3", link.id);
    }

    @Test
    public void playlistById() {
        YandexLink link = YandexLink.find("https://music.yandex.ru/playlists/lk.4612b26e-9983-4c16-bb15-72d5e614c842");
        assertEquals(YandexLink.Kind.PLAYLIST, link.kind);
        assertNull(link.owner);
        assertEquals("lk.4612b26e-9983-4c16-bb15-72d5e614c842", link.id);
        assertEquals("ar.12ab34cd-0000-1111-2222-333344445555",
                YandexLink.find("https://music.yandex.by/playlists/ar.12AB34CD-0000-1111-2222-333344445555").id);
    }

    @Test
    public void likesAndAlbums() {
        YandexLink likes = YandexLink.find("https://music.yandex.ru/users/me%40ya.ru/tracks");
        assertEquals(YandexLink.Kind.LIKES, likes.kind);
        assertEquals("me@ya.ru", likes.owner);
        YandexLink album = YandexLink.find("https://music.yandex.ru/album/43177815/track/153919218");
        assertEquals(YandexLink.Kind.ALBUM, album.kind);
        assertEquals("43177815", album.id);
    }

    @Test
    public void otherLinksAreNotTaken() {
        assertNull(YandexLink.find("https://music.yandex.ru/artist/123"));
        assertNull(YandexLink.find("https://soundcloud.com/users/x/playlists/1"));
        assertNull(YandexLink.find("просто текст"));
        assertNull(YandexLink.find(null));
    }

    @Test
    public void keyTellsCollectionsApart() {
        assertEquals("PLAYLIST:yamusic-top:1076",
                YandexLink.find("https://music.yandex.ru/users/yamusic-top/playlists/1076").key());
    }
}
