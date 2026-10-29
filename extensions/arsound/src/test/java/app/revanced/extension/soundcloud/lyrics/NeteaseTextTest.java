package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NeteaseTextTest {
    private static final LyricsRecord SONG = new LyricsRecord(16686599, "Numb", "Linkin Park", 185.6, false, "", "");

    /** The top of NetEase's text of "Numb": the authors first, then the words. */
    @Test
    public void theAuthorsAreNotWordsToSing() {
        LyricsRecord song = NeteaseClient.withText(SONG,
                "[00:00.000] 作词 : Brad Delson/Chester Bennington\n"
                        + "[00:01.000] 作曲 : Brad Delson/Chester Bennington\n"
                        + "[00:21.460]I'm tired of being what you want me to be\n"
                        + "[00:25.220]Feeling so faithless, lost under the surface\n"
                        + "[00:30.260]Don't know what you're expecting of me\n");
        assertTrue(song.plain.isEmpty());
        assertFalse(song.synced.contains("作"));
        Lyrics lyrics = LrcParser.parseSynced(song.synced);
        assertEquals("I'm tired of being what you want me to be", lyrics.lines[0]);
        assertEquals(21_460, lyrics.times[0]);
    }

    @Test
    public void wordsWithoutTimingsArePlain() {
        LyricsRecord song = NeteaseClient.withText(SONG, "Первая строка\nВторая строка");
        assertTrue(song.synced.isEmpty());
        assertEquals("Первая строка\nВторая строка", song.plain);
    }

    @Test
    public void pureMusicIsInstrumental() {
        LyricsRecord song = NeteaseClient.withText(SONG, "[00:00.00] 纯音乐，请欣赏");
        assertTrue(song.instrumental);
        assertFalse(song.hasText());
    }

    @Test
    public void noTextIsNoText() {
        LyricsRecord song = NeteaseClient.withText(SONG, "");
        assertFalse(song.hasText() || song.instrumental);
    }

    @Test
    public void wordsFoundThereAreCreditedToNetease() {
        Lyrics lyrics = new Lyrics(new String[]{"a"}, new long[]{0}).withSource(Lyrics.Source.NETEASE);
        assertSame(Lyrics.Source.NETEASE, lyrics.source);
        assertEquals(0, lyrics.times[0]);
    }
}
