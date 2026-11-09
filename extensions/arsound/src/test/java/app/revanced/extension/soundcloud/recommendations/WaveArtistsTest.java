package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WaveArtistsTest {
    @Test
    public void theArtistsSurviveTheirRecord() {
        List<WaveArtists.Artist> artists = Arrays.asList(new WaveArtists.Artist(7, "Кино"), new WaveArtists.Artist(9, " sqwore "));
        List<WaveArtists.Artist> back = WaveArtists.parse(WaveArtists.format(artists));
        assertEquals(2, back.size());
        assertEquals(7, back.get(0).id);
        assertEquals("Кино", back.get(0).name);
        assertEquals("sqwore", back.get(1).name);
        assertEquals("Кино, sqwore", WaveArtists.names(back));
    }

    @Test
    public void aBrokenRecordIsNoArtists() {
        assertTrue(WaveArtists.parse("not json").isEmpty());
        assertTrue(WaveArtists.parse(null).isEmpty());
        // Doubles and accounts without an id are left out.
        assertEquals(1, WaveArtists.parse("[{\"id\":5,\"name\":\"A\"},{\"id\":5,\"name\":\"A\"},{\"name\":\"B\"}]").size());
    }

    @Test
    public void tracksTakeTurnsBetweenTheArtists() {
        List<String> mixed = WaveArtists.interleave(Arrays.asList(
                Arrays.asList("a1", "a2", "a3"), Collections.singletonList("b1"), Arrays.asList("c1", "a2")));
        assertEquals(Arrays.asList("a1", "b1", "c1", "a2", "a3"), mixed);
    }

    @Test
    public void theTracksOfAnArtistLeaveOutSnippetsAndBlocked() throws Exception {
        String body = "{\"collection\":[{\"id\":1,\"policy\":\"ALLOW\"},{\"id\":2,\"policy\":\"SNIP\"},"
                + "{\"id\":3,\"policy\":\"BLOCK\"},{\"track\":{\"id\":4}},{\"id\":0}]}";
        assertEquals(Arrays.asList("soundcloud:tracks:1", "soundcloud:tracks:4"), WaveArtists.parseTracks(body));
    }

    @Test
    public void artistsComeFromASearchAndFromTheUploadersOfLikes() throws Exception {
        String search = "{\"collection\":[{\"kind\":\"user\",\"id\":11,\"username\":\"Кино\"},{\"kind\":\"user\",\"id\":12,\"username\":\"\"}]}";
        List<WaveArtists.Artist> found = WaveArtists.parseArtists(search);
        assertEquals(1, found.size());
        assertEquals(11, found.get(0).id);

        String likes = "{\"collection\":[{\"track\":{\"id\":1,\"user\":{\"id\":21,\"username\":\"sqwore\"}}},"
                + "{\"track\":{\"id\":2,\"user\":{\"id\":21,\"username\":\"sqwore\"}}},"
                + "{\"track\":{\"id\":3,\"user\":{\"id\":22,\"username\":\"нэнси холод\"}}}]}";
        List<WaveArtists.Artist> uploaders = WaveArtists.parseArtists(likes);
        assertEquals(2, uploaders.size());
        assertEquals("sqwore", uploaders.get(0).name);
        assertEquals("нэнси холод", uploaders.get(1).name);
    }

    @Test
    public void followersAreShortened() {
        assertEquals("999", WaveArtists.compact(999));
        assertEquals("12K", WaveArtists.compact(12_000));
        assertEquals("12.3K", WaveArtists.compact(12_345));
        assertEquals("1.2M", WaveArtists.compact(1_234_567));
    }

    @Test
    public void aSearchKeepsTheFollowersOfEachAccount() throws Exception {
        String search = "{\"collection\":[{\"kind\":\"user\",\"id\":11,\"username\":\"sqwore\",\"followers_count\":250000}]}";
        assertEquals(250000, WaveArtists.parseArtists(search).get(0).followers);
    }
}
