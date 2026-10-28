package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SharpPolicyTest {
    private static final long MB = 1024 * 1024;

    @Test
    public void aSmallFileOnAFreeNetworkMakesASharpCell() {
        assertTrue(SharpPolicy.gridMayLoad(2 * MB, false));
        assertTrue(SharpPolicy.gridMayLoad(6 * MB, false));
    }

    @Test
    public void aBigFileOrAMeteredNetworkKeepsTheThumbnail() {
        assertFalse(SharpPolicy.gridMayLoad(6 * MB + 1, false));
        assertFalse(SharpPolicy.gridMayLoad(2 * MB, true));
    }

    @Test
    public void anUnknownSizeIsNotRiskedForACell() {
        assertFalse(SharpPolicy.gridMayLoad(0, false));
        assertFalse(SharpPolicy.gridMayLoad(-1, false));
    }

    @Test
    public void thePreviewTakesBiggerFilesOnAFreeNetwork() {
        assertTrue(SharpPolicy.previewMayLoad(20 * MB, false));
        assertFalse(SharpPolicy.previewMayLoad(26 * MB, false));
    }

    @Test
    public void thePreviewIsCarefulOnMobileData() {
        assertTrue(SharpPolicy.previewMayLoad(8 * MB, true));
        assertFalse(SharpPolicy.previewMayLoad(9 * MB, true));
    }

    @Test
    public void thePreviewTriesAFileOfUnknownSize() {
        assertTrue(SharpPolicy.previewMayLoad(0, false));
    }
}
