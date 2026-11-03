package app.revanced.extension.soundcloud.player;

import app.revanced.extension.soundcloud.player.dsp.EffectParams;

/**
 * Band levels saved by an older version were for the few bands of the phone's own equalizer (five on most phones, at
 * about 60, 230, 910, 3600 and 14000 Hz). The player's equalizer has ten bands, so those levels are put onto the ten by
 * their place on the frequency scale. Pure arithmetic, so it runs in the JVM tests.
 */
final class LegacyLevels {
    private static final double LOW_HZ = 60, HIGH_HZ = 14000;

    private LegacyLevels() {
    }

    /**
     * @param saved Levels in millibels as saved, any number of bands.
     * @param bands How many bands are wanted (the ten of the equalizer).
     */
    static short[] onTenBands(short[] saved, int bands) {
        short[] levels = new short[bands];
        if (saved.length == 0) return levels;
        if (saved.length == bands) return saved.clone();
        if (saved.length == 1) {
            java.util.Arrays.fill(levels, saved[0]);
            return levels;
        }
        for (int band = 0; band < bands; band++) {
            double hz = bands == EffectParams.BANDS ? EffectParams.BAND_HZ[band]
                    : LOW_HZ * Math.pow(HIGH_HZ / LOW_HZ, band / (double) (bands - 1));
            // Where on the old bands (spread evenly on the log scale from 60 to 14000 Hz) this band lies.
            double position = (Math.log(hz) - Math.log(LOW_HZ)) / (Math.log(HIGH_HZ) - Math.log(LOW_HZ)) * (saved.length - 1);
            position = Math.max(0, Math.min(saved.length - 1, position));
            int lower = (int) Math.floor(position);
            int upper = Math.min(saved.length - 1, lower + 1);
            double level = saved[lower] + (saved[upper] - saved[lower]) * (position - lower);
            levels[band] = (short) Math.max(AudioEqualizer.BAND_MIN, Math.min(AudioEqualizer.BAND_MAX, Math.round(level / 50.0) * 50));
        }
        return levels;
    }
}
