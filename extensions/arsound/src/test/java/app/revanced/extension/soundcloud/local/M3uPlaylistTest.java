package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class M3uPlaylistTest {
    @Test
    public void filesByNameAndSoundCloudTracksByAddress() {
        String text = M3uPlaylist.text("Вечер", Arrays.asList(
                new M3uPlaylist.Track(201, M3uPlaylist.name("Artist", "Song"), "Song.flac"),
                new M3uPlaylist.Track(-1, "Cloud — user", M3uPlaylist.SOUNDCLOUD_TRACK_URL + "123")));
        assertEquals("#EXTM3U\n#PLAYLIST:Вечер\n"
                + "#EXTINF:201,Artist - Song\nSong.flac\n"
                + "#EXTINF:-1,Cloud — user\nhttps://api.soundcloud.com/tracks/123\n", text);
    }

    @Test
    public void lineBreaksInTagsStayOnOneLine() {
        String text = M3uPlaylist.text(null, Collections.singletonList(
                new M3uPlaylist.Track(5, "two\nlines", "a\r\nb.mp3")));
        assertEquals("#EXTM3U\n#EXTINF:5,two lines\na  b.mp3\n", text);
    }

    @Test
    public void aTrackWithNoPlaceIsLeftOut() {
        assertEquals("#EXTM3U\n", M3uPlaylist.text("", Collections.singletonList(new M3uPlaylist.Track(5, "x", " "))));
    }

    @Test
    public void namesOfArtistAndTitle() {
        assertEquals("Song", M3uPlaylist.name(null, "Song"));
        assertEquals("Artist", M3uPlaylist.name("Artist", ""));
        assertEquals("A - B", M3uPlaylist.name(" A ", "B"));
    }

    @Test
    public void fileNamesAreSafe() {
        assertEquals("a_b_c.m3u8", M3uPlaylist.fileName("a/b:c"));
        assertEquals("playlist.m3u8", M3uPlaylist.fileName("  "));
        assertEquals(80 + ".m3u8".length(), M3uPlaylist.fileName(new String(new char[200]).replace('\0', 'x')).length());
    }
}
