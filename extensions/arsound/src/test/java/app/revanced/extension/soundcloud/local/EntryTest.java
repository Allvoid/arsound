package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collections;

import org.junit.Test;

public class EntryTest {
    @Test
    public void aFileEntryKeepsItsStoredText() {
        Entry entry = Entry.parse("file:/data/imported/My  Song.mp3");
        assertTrue(entry instanceof FileEntry);
        assertEquals("file:/data/imported/My  Song.mp3", entry.stored());
    }

    @Test
    public void aFileEntryFromAFileUsesThePrefix() {
        assertEquals("file:" + new File("/a/b.mp3").getPath(), Entry.ofFile(new File("/a/b.mp3")).stored());
    }

    @Test
    public void aTrackEntryIsEverythingElse() {
        Entry entry = Entry.parse("soundcloud:tracks:123");
        assertTrue(entry instanceof TrackEntry);
        assertEquals("soundcloud:tracks:123", entry.stored());
    }

    @Test
    public void aFileHasNoTitleToLoad() {
        Entry entry = Entry.parse("file:/a/b.mp3");
        assertNull(entry.trackIdToLoad());
        assertNull(entry.cachedTitle(null));
    }

    @Test
    public void aFileIsNamedAfterItsTagsOrItsFileName() {
        File file = new File("/a/b.mp3");
        Entry entry = Entry.ofFile(file);
        assertEquals("b.mp3", entry.label(Collections.<LocalMusic.Track>emptyList(), null));
        LocalMusic.Track tagged = new LocalMusic.Track(file, "Tagged title", "Artist", 1000);
        assertEquals("Tagged title", entry.label(Collections.singletonList(tagged), null));
    }
}
