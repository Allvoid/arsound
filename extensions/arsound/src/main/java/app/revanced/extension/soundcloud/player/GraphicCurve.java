package app.revanced.extension.soundcloud.player;

/**
 * The curves of the built-in equalizer presets are written on the bands of a classic 10-band graphic equalizer; a phone
 * has its own bands (often five). This puts a curve onto them. Pure arithmetic, so it runs in the JVM tests.
 */
final class GraphicCurve {
    /** The frequencies of a classic 10-band graphic equalizer, on which the built-in presets are written. */
    static final int[] GRAPHIC_HZ = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};

    private GraphicCurve() {
    }

    /**
     * A 10-band curve on the phone's bands: interpolated on a logarithmic frequency scale.
     *
     * @param decibels The curve, one value per classic band.
     * @param centerHz The centre frequency of each band of the phone.
     * @param minLevel The lowest level the phone's bands take, in millibels.
     * @param maxLevel The highest.
     * @return Millibels per band of the phone, in half-decibel steps, within the range.
     */
    static short[] onBands(float[] decibels, int[] centerHz, short minLevel, short maxLevel) {
        int bands = centerHz.length;
        short[] levels = new short[bands];
        for (int band = 0; band < bands; band++) {
            double hz = Math.max(GRAPHIC_HZ[0], Math.min(GRAPHIC_HZ[GRAPHIC_HZ.length - 1], centerHz[band]));
            int upper = 1;
            while (upper < GRAPHIC_HZ.length - 1 && GRAPHIC_HZ[upper] < hz) upper++;
            double position = (Math.log(hz) - Math.log(GRAPHIC_HZ[upper - 1]))
                    / (Math.log(GRAPHIC_HZ[upper]) - Math.log(GRAPHIC_HZ[upper - 1]));
            double db = decibels[upper - 1] + (decibels[upper] - decibels[upper - 1]) * Math.max(0, Math.min(1, position));
            // Half-decibel steps, as the curve on the settings screen moves.
            int millibels = (int) Math.round(db * 2) * 50;
            levels[band] = (short) Math.max(minLevel, Math.min(maxLevel, millibels));
        }
        return levels;
    }
}
