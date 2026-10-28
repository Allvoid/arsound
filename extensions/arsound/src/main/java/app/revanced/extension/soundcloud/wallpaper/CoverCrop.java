package app.revanced.extension.soundcloud.wallpaper;

/**
 * The arithmetic of fitting a picture to a frame by covering it: how much a decoder may shrink the picture and still
 * leave it big enough, and which middle part of it the frame shows. Pure numbers, no Android; used by
 * {@link SharpLoader} for the cells of the Wallhaven grid.
 */
final class CoverCrop {
    private CoverCrop() {
    }

    /**
     * The biggest power of two a decoder can shrink the picture by (its {@code inSampleSize}) and still have a picture
     * that covers the frame at its own size or bigger, so the frame is never filled by stretching.
     */
    static int sampleSize(int sourceWidth, int sourceHeight, int frameWidth, int frameHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) return 1;
        double cover = Math.max(frameWidth / (double) sourceWidth, frameHeight / (double) sourceHeight);
        int sample = 1;
        while (cover * sample * 2 <= 1.0) sample *= 2;
        return sample;
    }

    /**
     * The middle part of the picture that fills a frame of the given shape when it is scaled to cover it.
     *
     * @return {left, top, width, height} in the picture's pixels.
     */
    static int[] middle(int sourceWidth, int sourceHeight, int frameWidth, int frameHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0 || frameWidth <= 0 || frameHeight <= 0) {
            return new int[]{0, 0, Math.max(0, sourceWidth), Math.max(0, sourceHeight)};
        }
        double frame = frameWidth / (double) frameHeight;
        if (sourceWidth / (double) sourceHeight > frame) {
            int width = Math.min(sourceWidth, (int) Math.round(sourceHeight * frame));
            return new int[]{(sourceWidth - width) / 2, 0, width, sourceHeight};
        }
        int height = Math.min(sourceHeight, (int) Math.round(sourceWidth / frame));
        return new int[]{0, (sourceHeight - height) / 2, sourceWidth, height};
    }
}
