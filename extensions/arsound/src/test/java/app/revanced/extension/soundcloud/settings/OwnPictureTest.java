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
    public void aFileOfUnknownSizeIsAssumedToBeAsBigAsTheLimit() {
        assertFalse(OwnPicture.hasRoom(-1, 300 * MB));
        assertTrue(OwnPicture.hasRoom(-1, 400 * MB + OwnPicture.RESERVE_BYTES));
    }

    @Test
    public void aFullStorageIsToldFromOtherFailures() {
        assertTrue(OwnPicture.isNoSpace(new IOException("write failed: ENOSPC (No space left on device)")));
        assertTrue(OwnPicture.isNoSpace(new RuntimeException("x", new IOException("No space left on device"))));
        assertFalse(OwnPicture.isNoSpace(new IOException("Permission denied")));
        assertFalse(OwnPicture.isNoSpace(null));
    }
}
