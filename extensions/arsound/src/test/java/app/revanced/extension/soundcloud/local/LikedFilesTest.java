package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class LikedFilesTest {
    private static final File SONG = new File("/imported/Night Drive.mp3");
    private static final File OTHER = new File("/imported/Rock Anthem.flac");
    private static final File NO_EXTENSION = new File("/imported/driver");

    private static List<File> all() {
        return Arrays.asList(SONG, OTHER, NO_EXTENSION);
    }

    @Test
    public void keepsTheOrderGiven() {
        assertEquals(all(), new LikedFiles(all(), file -> false).all());
    }

    @Test
    public void leavesOutRemovedFiles() {
        LikedFiles liked = new LikedFiles(all(), file -> file.equals(OTHER));
        assertEquals(Arrays.asList(SONG, NO_EXTENSION), liked.all());
        assertEquals(2, liked.size());
        assertFalse(liked.contains(OTHER));
        assertTrue(liked.contains(SONG));
    }

    @Test
    public void searchComparesTheNameIgnoringCase() {
        LikedFiles liked = new LikedFiles(all(), file -> false);
        assertEquals(Arrays.asList(SONG, NO_EXTENSION), liked.matching("DRIVE"));
        assertEquals(Arrays.asList(SONG), liked.matching("night"));
        assertEquals(Arrays.asList(NO_EXTENSION), liked.matching("Driver"));
    }

    @Test
    public void searchIgnoresTheExtension() {
        LikedFiles liked = new LikedFiles(all(), file -> false);
        assertTrue(liked.matching("mp3").isEmpty());
        assertTrue(liked.matching(".flac").isEmpty());
    }

    @Test
    public void emptyOrBlankSearchGivesEverything() {
        LikedFiles liked = new LikedFiles(all(), file -> false);
        assertEquals(all(), liked.matching(""));
        assertEquals(all(), liked.matching("   "));
        assertEquals(all(), liked.matching(null));
    }

    @Test
    public void searchIsTrimmed() {
        assertEquals(Arrays.asList(OTHER), new LikedFiles(all(), file -> false).matching("  rock "));
    }

    @Test
    public void searchDoesNotFindRemovedFiles() {
        LikedFiles liked = new LikedFiles(all(), file -> file.equals(OTHER));
        assertTrue(liked.matching("rock").isEmpty());
    }
}
