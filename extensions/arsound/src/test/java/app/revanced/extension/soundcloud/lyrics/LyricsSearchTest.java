package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

public class LyricsSearchTest {
    private static final String SQL = "\n        SELECT Tracks.* FROM Tracks\n        WHERE  (\n"
            + "              Tracks.title LIKE '%' || ? || '%' COLLATE NOCASE\n"
            + "          ) AND likes.removedAt IS NULL \n        ORDER BY created_at DESC\n        LIMIT ? OFFSET ?\n    ";

    @Test
    public void theWhereGetsTheTracksFoundByTheirWords() {
        String widened = LyricsSearch.widenSql(SQL, new LinkedHashSet<>(Arrays.asList("soundcloud:tracks:1", "soundcloud:tracks:22")));
        assertTrue(widened.contains("OR Tracks.urn IN ('soundcloud:tracks:1','soundcloud:tracks:22') ) AND likes.removedAt IS NULL"));
        // The four bound parameters stay where they were.
        assertEquals(SQL.split("[?]", -1).length, widened.split("[?]", -1).length);
    }

    @Test
    public void anotherShapeOrStrangeUrnsLeaveTheSqlAlone() {
        assertEquals("SELECT 1", LyricsSearch.widenSql("SELECT 1", new LinkedHashSet<>(Arrays.asList("soundcloud:tracks:1"))));
        assertEquals(SQL, LyricsSearch.widenSql(SQL, new LinkedHashSet<>(Arrays.asList("x') OR 1=1 --"))));
    }

    @Test
    public void everyWordMustBeInTheLyrics() {
        Map<String, String> texts = new LinkedHashMap<>();
        texts.put("soundcloud:tracks:1", "я иду по улице один");
        texts.put("soundcloud:tracks:2", "улица фонарь аптека");
        assertEquals(Arrays.asList("soundcloud:tracks:1"), LyricsSearch.matching("Улице, ОДИН", texts));
        assertEquals(2, LyricsSearch.matching("улиц", texts).size());
        assertTrue(LyricsSearch.matching("ул", texts).isEmpty());
    }

    @Test
    public void olderCacheNamesGiveTheirTrackBack() {
        assertEquals("soundcloud:tracks:123", LyricsSearch.urnOfSafeName("soundcloud_tracks_123"));
        assertNull(LyricsSearch.urnOfSafeName("file__data_x_mp3"));
    }
}
