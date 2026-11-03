package app.revanced.extension.soundcloud.player.dsp;

/**
 * What the sound is shaped by: the ten equalizer bands, the preamp, the bass shelf of the "bass boost" version and the
 * strength of the reverb. Immutable; a new one is handed to {@link SoftEffects} whenever a setting changes.
 */
public final class EffectParams {
    /** The centres of the ten bands: the classic octave bands of a graphic equalizer. */
    public static final double[] BAND_HZ = {31.25, 62.5, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
    public static final int BANDS = BAND_HZ.length;
    /** The bass shelf turns at this frequency. */
    public static final double BASS_CORNER_HZ = 110;

    public static final EffectParams NONE = new EffectParams(new float[BANDS], 0f, 0f, 0);

    /** Gain of each band in decibels. */
    public final float[] bandDb;
    public final float preampDb;
    /** Gain of the low shelf in decibels, 0 for none. */
    public final float bassDb;
    /** 0 for no reverb, 1 to 3 weak to strong. */
    public final int reverb;

    public EffectParams(float[] bandDb, float preampDb, float bassDb, int reverb) {
        this.bandDb = bandDb.clone();
        this.preampDb = preampDb;
        this.bassDb = bassDb;
        this.reverb = reverb;
    }

    /** Whether anything changes the sound: if not, the processor passes it on as it is. */
    public boolean isNeutral() {
        if (preampDb != 0f || bassDb != 0f || reverb != 0) return false;
        for (float gain : bandDb) if (gain != 0f) return false;
        return true;
    }
}
