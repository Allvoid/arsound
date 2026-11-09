package app.revanced.extension.soundcloud.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class VkParserTest {
    @Test
    public void tracksOfAudioGet() throws Exception {
        VkParser.TrackPage page = VkParser.tracks(Fixtures.json("vk-audio-get.json"));
        assertEquals(412, page.count);
        assertEquals(4, page.items);
        assertEquals(3, page.tracks.size());

        SourceTrack first = page.tracks.get(0);
        assertEquals("vk:1234_456239017", first.id);
        assertEquals(Collections.singletonList("Земфира"), first.artists);
        assertEquals("Хочешь?", first.title);
        assertNull(first.version);
        assertEquals(213_000, first.durationMs);

        SourceTrack second = page.tracks.get(1);
        assertEquals("vk:-2001_456239016", second.id);
        assertEquals(Arrays.asList("Скриптонит", "Андрей Леницкий"), second.artists);
        assertEquals("Космос (Remix)", second.wantedTitle());

        // No artist records: the text of "artist" split.
        assertEquals(Arrays.asList("Unknown", "Other", "Third"), page.tracks.get(2).artists);
    }

    @Test
    public void playlistsReadThroughTheirOriginal() throws Exception {
        List<VkParser.Playlist> playlists = VkParser.playlists(Fixtures.json("vk-playlists.json"));
        assertEquals(2, playlists.size());
        VkParser.Playlist own = playlists.get(0);
        assertEquals("1234", own.owner);
        assertEquals("12", own.id);
        assertEquals("abc123", own.accessKey);
        assertEquals("В дорогу", own.title);
        assertEquals(57, own.count);
        VkParser.Playlist followed = playlists.get(1);
        assertEquals("-2000123", followed.owner);
        assertEquals("4567", followed.id);
        assertEquals("def456", followed.accessKey);
    }

    @Test
    public void errors() throws Exception {
        assertEquals(5, VkParser.errorCode(new JSONObject("{\"error\":{\"error_code\":5,\"error_msg\":\"auth\"}}")));
        assertEquals(0, VkParser.errorCode(new JSONObject("{\"response\":{}}")));
        assertEquals("Mix", VkParser.playlistTitle(new JSONObject("{\"response\":{\"title\":\" Mix \"}}")));
    }
}
