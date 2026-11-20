package app.revanced.extension.soundcloud.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class OwnPictureTest {
    private static final long MB = 1024L * 1024;

    @Test
    public void theMessageNamesTheRealLimit() {
        assertEquals(400, OwnPicture.limitMegabytes());
        assertTrue(OwnPicture.tooBigText(false).contains("400 MB"));
        assertTrue(OwnPicture.tooBigText(true).contains("400 МБ"));
    }

    @Test
    public void aCopyNeedsItsSizePlusTheReserve() {
        assertTrue(OwnPicture.hasRoom(100 * MB, 100 * MB + OwnPicture.RESERVE_BYTES));
        assertFalse(OwnPicture.hasRoom(100 * MB, 100 * MB + OwnPicture.RESERVE_BYTES - 1));
        assertFalse(OwnPicture.hasRoom(100 * MB, 0));
    }

    @Test
    public void whatIsNotKnownIsLetThrough() {
        // A small file of unknown size on a phone with 300 MB free, or a free space that could not be read.
        assertTrue(OwnPicture.hasRoom(-1, 300 * MB));
        assertTrue(OwnPicture.hasRoom(100 * MB, -1));
        assertTrue(OwnPicture.hasRoom(-1, -1));
    }

    @Test
    public void aMissingFolderHasNoKnownFreeSpace() {
        assertEquals(-1, OwnPicture.usableOf(null));
        assertEquals(-1, OwnPicture.usableOf(new java.io.File("/no/such/folder/for/arsound")));
    }

    @Test
    public void aFullStorageIsToldFromOtherFailures() {
        assertTrue(OwnPicture.isNoSpace(new IOException("write failed: ENOSPC (No space left on device)")));
        assertTrue(OwnPicture.isNoSpace(new RuntimeException("x", new IOException("No space left on device"))));
        assertFalse(OwnPicture.isNoSpace(new IOException("Permission denied")));
        assertFalse(OwnPicture.isNoSpace(null));
    }

    @Test
    public void aReallyFullDiskIsTold() {
        // The folder is there and the system says 0: a really full storage, no room for a copy.
        assertEquals(0, OwnPicture.usableFrom(true, 0));
        assertFalse(OwnPicture.hasRoom(10 * MB, OwnPicture.usableFrom(true, 0)));
        assertEquals(-1, OwnPicture.usableFrom(false, 0));
    }
}
