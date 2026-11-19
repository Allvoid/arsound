package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class PastedLyricsTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void aTextIsKeptAndReadBackWithItsTimes() throws Exception {
        File folder = new File(temp.getRoot(), "pasted");
        LyricsAligner.Result result = LyricsAligner.align("la la la one\nla la la two", null, null, 90_000);
        Lyrics lyrics = PastedLyrics.toLyrics(result);
        assertEquals(Lyrics.Source.PASTED_APPROX, lyrics.source);
        PastedLyrics.write(folder, "soundcloud:tracks:42", "la la la one\nla la la two", lyrics, 90_000);

        Lyrics read = PastedLyrics.read(folder, "soundcloud:tracks:42");
        assertArrayEquals(lyrics.lines, read.lines);
        assertArrayEquals(lyrics.times, read.times);
        assertEquals(Lyrics.Source.PASTED_APPROX, read.source);
        assertTrue(read.isPasted());
        assertEquals("la la la one\nla la la two", PastedLyrics.readRaw(folder, "soundcloud:tracks:42"));
    }

    @Test
    public void aPlainTextHasNoTimes() throws Exception {
        File folder = temp.newFolder("plain");
        Lyrics lyrics = PastedLyrics.toLyrics(LyricsAligner.align("la la\nlo lo", null, null, 0));
        PastedLyrics.write(folder, "a", "la la\nlo lo", lyrics, 0);
        Lyrics read = PastedLyrics.read(folder, "a");
        assertNull(read.times);
        assertEquals(Lyrics.Source.PASTED, read.source);
    }

    @Test
    public void removingForgetsTheTextAndOtherTracksStay() throws Exception {
        File folder = temp.newFolder("two");
        Lyrics lyrics = PastedLyrics.toLyrics(LyricsAligner.align("la la\nlo lo", null, null, 0));
        PastedLyrics.write(folder, "a", "x", lyrics, 0);
        PastedLyrics.write(folder, "b", "y", lyrics, 0);
        PastedLyrics.delete(folder, "a");
        assertNull(PastedLyrics.read(folder, "a"));
        assertNull(PastedLyrics.readRaw(folder, "a"));
        assertEquals("y", PastedLyrics.readRaw(folder, "b"));
    }

    @Test
    public void aDamagedFileReadsAsNothing() throws Exception {
        File folder = temp.newFolder("bad");
        java.nio.file.Files.write(PastedLyrics.fileOf(folder, "a").toPath(), "{not json".getBytes("UTF-8"));
        assertNull(PastedLyrics.read(folder, "a"));
    }

    @Test
    public void trackNamesBecomeSafeFileNames() {
        File file = PastedLyrics.fileOf(new File("f"), "soundcloud:tracks:1/../x");
        assertEquals("soundcloud_tracks_1_.._x.json", file.getName());
    }

    @Test
    public void aFileThatIsGoneCountsAsDeleted() throws Exception {
        File folder = temp.newFolder("gone");
        assertTrue(PastedLyrics.delete(folder, "never-saved"));
        PastedLyrics.write(folder, "a", "x", PastedLyrics.toLyrics(LyricsAligner.align("la la\nlo lo", null, null, 0)), 0);
        assertTrue(PastedLyrics.delete(folder, "a"));
        assertFalse(PastedLyrics.fileOf(folder, "a").exists());
    }

    @Test
    public void aTextOverTheLimitIsRefusedAndOneAtTheLimitIsNot() {
        String atLimit = new String(new char[PastedLyrics.MAX_CHARS]).replace('\0', 'a');
        String overLimit = atLimit + "a";
        assertFalse(PastedLyrics.tooLong(atLimit));
        assertTrue(PastedLyrics.tooLong(overLimit));
        assertFalse(PastedLyrics.tooLong(null));
    }
}
