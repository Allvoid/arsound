package app.revanced.extension.soundcloud.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class YouTubeMusicParserTest {
    /** Trimmed from a live answer for a public playlist ("browse VL…", anonymous, November 2026). */
    @Test
    public void publicPlaylistFirstPage() throws Exception {
        JSONObject answer = Fixtures.json("ytmusic-playlist.json");
        YouTubeMusicParser.TrackPage page = YouTubeMusicParser.firstPage(answer);
        assertTrue(page.hasList);
        assertEquals("Popular Music Videos", page.title);
        assertEquals(3, page.tracks.size());
        SourceTrack first = page.tracks.get(0);
        assertEquals("yt:fOT0BUpITw8", first.id);
        assertEquals("BELLAKEO", first.title);
        assertEquals(Arrays.asList("Peso Pluma", "Anitta"), first.artists);
        assertEquals(235_000, first.durationMs);
        assertEquals("La Diabla", page.tracks.get(1).title);
        assertEquals(Collections.singletonList("Xavi"), page.tracks.get(1).artists);
        assertTrue(page.continuation.startsWith("4qmFsg"));
        assertEquals(Boolean.FALSE, YouTubeMusicParser.loggedIn(answer));
    }

    /** Trimmed from the live answer to the token of the page above. */
    @Test
    public void publicPlaylistNextPage() throws Exception {
        YouTubeMusicParser.TrackPage page = YouTubeMusicParser.nextPage(Fixtures.json("ytmusic-playlist-continuation.json"));
        assertEquals(2, page.tracks.size());
        SourceTrack first = page.tracks.get(0);
        assertEquals("Holy Night / Cantique de Noël (The Chosen Performance)", first.title);
        assertEquals(Arrays.asList("Virginia Bocelli", "Matteo Bocelli", "Andrea Bocelli"), first.artists);
        assertEquals(266_000, first.durationMs);
        assertNull(page.continuation);
    }

    @Test
    public void likedMusicOfAnAccount() throws Exception {
        JSONObject answer = Fixtures.json("ytmusic-liked.json");
        assertEquals(Boolean.TRUE, YouTubeMusicParser.loggedIn(answer));
        YouTubeMusicParser.TrackPage page = YouTubeMusicParser.firstPage(answer);
        assertEquals("LIKED-PAGE-2", page.continuation);
        List<SourceTrack> tracks = page.tracks;
        assertEquals(3, tracks.size());
        assertEquals("Кукла колдуна", tracks.get(0).title);
        assertEquals(Collections.singletonList("Король и Шут"), tracks.get(0).artists);
        // A listener's upload: the artist is in the title, the channel is not the artist, the note goes.
        assertEquals("yt:bbbbbbbbbb2", tracks.get(1).id);
        assertEquals(Collections.singletonList("Daft Punk"), tracks.get(1).artists);
        assertEquals("One More Time", tracks.get(1).title);
        assertEquals(3_723_000, tracks.get(1).durationMs);
        // No video and no links: the artists from the text, the id from the names.
        assertEquals(Arrays.asList("Artist A", "Artist B"), tracks.get(2).artists);
        assertTrue(tracks.get(2).id.startsWith("yt?:"));
        assertEquals(0, tracks.get(2).durationMs);
    }

    @Test
    public void libraryPlaylists() throws Exception {
        YouTubeMusicParser.PlaylistPage page = YouTubeMusicParser.playlists(Fixtures.json("ytmusic-library.json"));
        assertEquals("LIBRARY-PAGE-2", page.continuation);
        assertEquals(2, page.playlists.size());
        assertEquals(Arrays.asList("PLroadtrip123", "Road trip", "1025"), Arrays.asList(page.playlists.get(0)));
        assertEquals(Arrays.asList("OLAK5uy_album", "Saved album", ""), Arrays.asList(page.playlists.get(1)));
    }

    @Test
    public void answerWithoutAListIsNotAPlaylist() throws Exception {
        YouTubeMusicParser.TrackPage page = YouTubeMusicParser.firstPage(new JSONObject("{\"contents\":{}}"));
        assertFalse(page.hasList);
        assertTrue(page.tracks.isEmpty());
    }

    @Test
    public void durations() {
        assertEquals(59_000, YouTubeMusicParser.parseDuration("0:59"));
        assertEquals(3_723_000, YouTubeMusicParser.parseDuration("1:02:03"));
        assertEquals(0, YouTubeMusicParser.parseDuration("Live"));
        assertEquals(0, YouTubeMusicParser.parseDuration(null));
    }
}
