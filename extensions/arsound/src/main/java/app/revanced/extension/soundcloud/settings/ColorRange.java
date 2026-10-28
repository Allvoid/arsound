package app.revanced.extension.soundcloud.settings;

/**
 * The part of the colour square that a picker offers: saturation from 0 up to {@link #saturationMax}, brightness from
 * {@link #valueMin} up to {@link #valueMax}. A dark background lives in the lowest tenth of the full square, where a
 * finger cannot tell one shade from the next, so a picker for a background shows only the part that matters, many times
 * larger. Pure numbers with no Android.
 */
final class ColorRange {
    /** Every saturation and brightness: for an accent. */
    static final ColorRange FULL = new ColorRange(1f, 0f, 1f);
    /** Dark backgrounds: they never get brighter than this, whatever the hue. */
    static final ColorRange DARK = new ColorRange(1f, 0f, .28f);
    /** Light backgrounds: pale and hardly saturated. */
    static final ColorRange LIGHT = new ColorRange(.3f, .82f, 1f);

    final float saturationMax;
    final float valueMin;
    final float valueMax;

    ColorRange(float saturationMax, float valueMin, float valueMax) {
        this.saturationMax = saturationMax;
        this.valueMin = valueMin;
        this.valueMax = valueMax;
    }

    /** The saturation at a position across the square, 0 (left) to 1 (right). */
    float saturationAt(float across) {
        return clamp(across) * saturationMax;
    }

    /** The brightness at a position down the square, 0 (top, the brightest) to 1 (bottom, the darkest). */
    float valueAt(float down) {
        return valueMax - clamp(down) * (valueMax - valueMin);
    }

    /** Where a saturation sits across the square; one outside the range sits at the edge. */
    float acrossOf(float saturation) {
        return saturationMax <= 0f ? 0f : clamp(saturation / saturationMax);
    }

    /** Where a brightness sits down the square; one outside the range sits at the edge. */
    float downOf(float value) {
        float span = valueMax - valueMin;
        return span <= 0f ? 0f : clamp((valueMax - value) / span);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
