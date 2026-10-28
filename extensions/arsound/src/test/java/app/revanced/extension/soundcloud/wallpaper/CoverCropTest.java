package app.revanced.extension.soundcloud.wallpaper;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class CoverCropTest {
    @Test
    public void aFullHdPictureIsHalvedForAPhoneCell() {
        // 1080x1920 for a cell of 328x437: the half (540x960) still covers it, a quarter would not.
        assertEquals(2, CoverCrop.sampleSize(1080, 1920, 328, 437));
    }

    @Test
    public void aPictureOfTheCellsOwnSizeIsNotShrunk() {
        assertEquals(1, CoverCrop.sampleSize(328, 437, 328, 437));
        assertEquals(1, CoverCrop.sampleSize(500, 800, 328, 437));
    }

    @Test
    public void aHugePictureIsShrunkByManyStepsButStaysAtLeastTheCell() {
        int sample = CoverCrop.sampleSize(7680, 4320, 215, 383);
        assertEquals(8, sample);
        // Shrunk by the sample, the picture still covers the cell on both sides.
        assertEquals(true, 7680 / sample >= 215 && 4320 / sample >= 383);
    }

    @Test
    public void emptySizesGiveNoShrinking() {
        assertEquals(1, CoverCrop.sampleSize(0, 100, 10, 10));
        assertEquals(1, CoverCrop.sampleSize(100, 100, 0, 10));
    }

    @Test
    public void aTallPictureInAWiderCellLosesItsTopAndBottom() {
        // 540x960 in a cell of 3:4: the width is kept, the height cut to 720 around the middle.
        assertArrayEquals(new int[]{0, 120, 540, 720}, CoverCrop.middle(540, 960, 3, 4));
    }

    @Test
    public void aWidePictureInATallCellLosesItsSides() {
        assertArrayEquals(new int[]{656, 0, 608, 1080}, CoverCrop.middle(1920, 1080, 9, 16));
    }

    @Test
    public void aPictureOfTheCellsShapeIsTakenWhole() {
        assertArrayEquals(new int[]{0, 0, 540, 960}, CoverCrop.middle(540, 960, 9, 16));
    }
}
