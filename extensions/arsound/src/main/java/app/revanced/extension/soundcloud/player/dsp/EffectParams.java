package app.revanced.extension.soundcloud.player.dsp;

/**
 * What the sound is shaped by: the ten equalizer bands and the preamp. Immutable; a new one is handed to
 * {@link SoftEffects} whenever a setting changes.
 */
public final class EffectParams {
    /** The centres of the ten bands: the classic octave bands of a graphic equalizer. */
    public static final double[] BAND_HZ = {31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final int BANDS = BAND_HZ.length;

    public static final EffectParams NONE = new EffectParams(new float[BANDS], 0f);

    /** Gain of each band in decibels. */
    public final float[] bandDb;
    public final float preampDb;

    public EffectParams(float[] bandDb, float preampDb) {
        this.bandDb = bandDb.clone();
        this.preampDb = preampDb;
    }

    /** Whether anything changes the sound: if not, the processor passes it on as it is. */
    public boolean isNeutral() {
        if (preampDb != 0f) return false;
        for (float gain : bandDb) if (gain != 0f) return false;
        return true;
    }
}
