package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WallpaperKeepTest {
    @Test
    public void aJpegOfOrdinarySizeIsKeptAsItIs() {
        assertTrue(WallpaperKeep.canKeepAsIs(true, 1080, 1920));
        assertTrue(WallpaperKeep.canKeepAsIs(true, 2560, 1440));
    }

    @Test
    public void otherFormatsAreConverted() {
        assertFalse(WallpaperKeep.canKeepAsIs(false, 1080, 1920));
    }

    @Test
    public void aBigJpegIsShrunk() {
        assertFalse(WallpaperKeep.canKeepAsIs(true, 3840, 2160));
        assertFalse(WallpaperKeep.canKeepAsIs(true, 1080, 2561));
    }

    @Test
    public void anUnreadableSizeIsNotKept() {
        assertFalse(WallpaperKeep.canKeepAsIs(true, 0, 0));
    }
}
