package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.junit.Test;

public class HiddenLikesTest {
    private static final File SONG = new File("/imported/song.mp3");

    /** A storage in memory that counts how it is used. */
    private static final class MemoryStorage implements HiddenLikes.Storage {
        Set<String> saved;
        int loads;
        int saves;

        MemoryStorage(Set<String> saved) {
            this.saved = saved;
        }

        @Override
        public Set<String> load() {
            loads++;
            return saved == null ? null : new HashSet<>(saved);
        }

        @Override
        public void save(Set<String> paths) {
            saves++;
            saved = new HashSet<>(paths);
        }
    }

    @Test
    public void nothingIsHiddenAtFirst() {
        assertFalse(new HiddenLikes(new MemoryStorage(new HashSet<>())).isHidden(SONG));
    }

    @Test
    public void hidingAndShowingAgain() {
        MemoryStorage storage = new MemoryStorage(new HashSet<>());
        HiddenLikes hidden = new HiddenLikes(storage);

        assertTrue(hidden.hide(SONG));
        assertTrue(hidden.isHidden(SONG));
        assertEquals(Collections.singleton(SONG.getPath()), storage.saved);

        assertTrue(hidden.show(SONG));
        assertFalse(hidden.isHidden(SONG));
        assertTrue(storage.saved.isEmpty());
    }

    @Test
    public void repeatedRequestsChangeNothingAndWriteNothing() {
        MemoryStorage storage = new MemoryStorage(new HashSet<>());
        HiddenLikes hidden = new HiddenLikes(storage);

        assertFalse(hidden.show(SONG));
        assertTrue(hidden.hide(SONG));
        assertFalse(hidden.hide(SONG));
        assertEquals(1, storage.saves);
    }

    @Test
    public void startsWithWhatWasKept() {
        HiddenLikes hidden = new HiddenLikes(new MemoryStorage(new HashSet<>(Collections.singleton(SONG.getPath()))));
        assertTrue(hidden.isHidden(SONG));
        assertFalse(hidden.isHidden(new File("/imported/other.mp3")));
    }

    @Test
    public void storageIsReadOnce() {
        MemoryStorage storage = new MemoryStorage(new HashSet<>());
        HiddenLikes hidden = new HiddenLikes(storage);
        hidden.isHidden(SONG);
        hidden.hide(SONG);
        hidden.isHidden(SONG);
        assertEquals(1, storage.loads);
    }

    @Test
    public void storageThatIsNotAvailableYetIsAskedAgain() {
        MemoryStorage storage = new MemoryStorage(null);
        HiddenLikes hidden = new HiddenLikes(storage);
        assertFalse(hidden.isHidden(SONG));
        assertNull(storage.saved);

        storage.saved = new HashSet<>(Collections.singleton(SONG.getPath()));
        assertTrue(hidden.isHidden(SONG));
    }

    @Test
    public void savedSetIsACopy() {
        MemoryStorage storage = new MemoryStorage(new HashSet<>());
        HiddenLikes hidden = new HiddenLikes(storage);
        hidden.hide(SONG);
        Set<String> saved = storage.saved;
        hidden.show(SONG);
        assertEquals(Collections.singleton(SONG.getPath()), saved);
    }
}
