package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

public class PlaylistCoversMergeTest {
    private static String coversJson(String... pairs) {
        Map<String, String> covers = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) covers.put(pairs[i], pairs[i + 1]);
        return PlaylistCoversMerge.write(covers);
    }

    @Test
    public void coversSurviveWritingAndReading() {
        Map<String, String> covers = new LinkedHashMap<>();
        covers.put("soundcloud:playlists:42", "/9j/4AAQSkZJRg==");
        covers.put("arsound:saved", "AAAA");
        Map<String, String> back = PlaylistCoversMerge.read(PlaylistCoversMerge.write(covers));
        assertEquals(covers, back);
    }

    @Test
    public void nothingToCarryIsLeftOut() {
        PlaylistCoversMerge merge = new PlaylistCoversMerge();
        assertNull(merge.outgoing("{}"));
        assertNull(merge.outgoing(""));
        assertNull(merge.outgoing(null));
        assertNull(merge.incoming("not json", null));
    }

    @Test
    public void aValidFileComesBackAsItIs() {
        String file = coversJson("soundcloud:playlists:1", "QUJD");
        assertEquals(file, new PlaylistCoversMerge().incoming(file, "{}"));
    }

    @Test
    public void entriesThatDoNotLookRightAreDropped() {
        String file = coversJson(
                "soundcloud:playlists:1", "QUJD",
                "has space", "QUJD",
                "../escape", "QUJD",
                "soundcloud:playlists:2", "not base64 !!",
                "soundcloud:playlists:3", "");
        Map<String, String> read = PlaylistCoversMerge.read(file);
        assertNotNull(read);
        assertEquals(Collections.singletonMap("soundcloud:playlists:1", "QUJD"), read);
    }

    @Test
    public void valuesOfOtherTypesAreDropped() {
        Map<String, String> read = PlaylistCoversMerge.read("{\"a\": 5, \"b\": [\"x\"], \"c\": \"QUJD\"}");
        assertEquals(Collections.singletonMap("c", "QUJD"), read);
    }

    @Test
    public void aHugePictureAndAHugeCountAreCut() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 400_001; i++) big.append('A');
        assertTrue(PlaylistCoversMerge.read(coversJson("p:1", big.toString())).isEmpty());

        Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i < 500; i++) many.put("p:" + i, "QUJD");
        assertEquals(400, PlaylistCoversMerge.read(PlaylistCoversMerge.write(many)).size());
    }

    @Test
    public void notAnObjectIsNotCovers() {
        assertNull(PlaylistCoversMerge.read("[\"a\"]"));
        assertNull(PlaylistCoversMerge.read(5L));
    }

    @Test
    public void onlyTheMadeUpKeyTravelsFromTheCoversFile() {
        TransferPlan.Source source = TransferPlan.find("arsound_local_covers");
        assertNotNull(source);
        assertTrue(source.carries(PlaylistCoversMerge.KEY));
        assertFalse(source.carries("playlist:soundcloud:playlists:1"));
        assertFalse(source.carries("covers_squared"));
        assertFalse(source.carries("offline:soundcloud:playlists:1"));
    }
}
