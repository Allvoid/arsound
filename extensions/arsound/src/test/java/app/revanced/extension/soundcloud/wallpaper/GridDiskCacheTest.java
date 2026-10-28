package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class GridDiskCacheTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static byte[] bytes(int count) {
        return new byte[count];
    }

    @Test
    public void whatIsPutCanBeGotBack() throws Exception {
        GridDiskCache cache = new GridDiskCache(new File(temp.getRoot(), "grid"), 1000);
        assertNull(cache.get("abc_100x200"));
        cache.put("abc_100x200", bytes(10));
        File file = cache.get("abc_100x200");
        assertNotNull(file);
        assertEquals(10, file.length());
    }

    @Test
    public void aKeyCannotLeaveTheFolder() throws Exception {
        File folder = new File(temp.getRoot(), "grid");
        GridDiskCache cache = new GridDiskCache(folder, 1000);
        cache.put("../../evil", bytes(5));
        assertEquals(folder.getCanonicalPath(), cache.get("../../evil").getParentFile().getCanonicalPath());
        assertEquals("______evil.jpg", GridDiskCache.safeName("../../evil"));
    }

    @Test
    public void theLongestUnusedFilesGoFirstWhenTheFolderIsFull() throws Exception {
        File folder = new File(temp.getRoot(), "grid");
        GridDiskCache cache = new GridDiskCache(folder, 100);
        cache.put("old", bytes(40));
        cache.put("middle", bytes(40));
        cache.put("new", bytes(40));
        new File(folder, GridDiskCache.safeName("old")).setLastModified(1_000_000L);
        new File(folder, GridDiskCache.safeName("middle")).setLastModified(2_000_000L);
        new File(folder, GridDiskCache.safeName("new")).setLastModified(3_000_000L);
        cache.trim();
        assertFalse(new File(folder, GridDiskCache.safeName("old")).exists());
        assertTrue(new File(folder, GridDiskCache.safeName("new")).exists());
    }

    @Test
    public void usingAFileKeepsItFromBeingTheOldest() throws Exception {
        File folder = new File(temp.getRoot(), "grid");
        GridDiskCache cache = new GridDiskCache(folder, 100);
        cache.put("a", bytes(60));
        cache.put("b", bytes(60));
        new File(folder, GridDiskCache.safeName("a")).setLastModified(1_000_000L);
        new File(folder, GridDiskCache.safeName("b")).setLastModified(2_000_000L);
        // "a" is read again, so "b" is the one used longest ago.
        assertNotNull(cache.get("a"));
        cache.trim();
        assertTrue(new File(folder, GridDiskCache.safeName("a")).exists());
        assertFalse(new File(folder, GridDiskCache.safeName("b")).exists());
    }

    @Test
    public void aFolderUnderItsLimitIsLeftAlone() throws Exception {
        File folder = new File(temp.getRoot(), "grid");
        GridDiskCache cache = new GridDiskCache(folder, 1000);
        cache.put("a", bytes(100));
        cache.put("b", bytes(100));
        cache.trim();
        assertEquals(2, folder.listFiles().length);
    }
}
