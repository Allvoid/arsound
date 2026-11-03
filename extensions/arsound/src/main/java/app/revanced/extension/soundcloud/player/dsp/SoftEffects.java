package app.revanced.extension.soundcloud.player.dsp;

import java.nio.ByteBuffer;

/**
 * The sound shaping of the player, in software: preamp, a low shelf, ten peaking bands, a reverb and a limiter, on 16-bit
 * or float PCM with any number of channels and any sample rate. The coefficients are worked out for the sample rate
 * of the stream when it is configured, and again when the settings change. The limiter holds the sound under a ceiling,
 * so boosts cannot clip. Nothing here is Android's: it is the same on every phone, whatever audio effects the firmware
 * has. Used by {@code SoftEqualizerProcessor}. Not thread safe: one stream, one thread; the parameters come from another
 * thread through {@link #setParams} (a volatile reference).
 */
public final class SoftEffects {
    public static final int ENCODING_PCM_16BIT = 2;
    public static final int ENCODING_PCM_FLOAT = 4;
    /** The limit of the output, as a part of full scale. */
    public static final float CEILING = 0.97f;
    /** The limiter lets the gain back up to 1 in about this many milliseconds. */
    private static final double RELEASE_MS = 120;
    /** Width of a band in octaves. */
    private static final double BAND_OCTAVES = 1.0;
    private static final float[] REVERB_FEEDBACK = {0f, 0.78f, 0.85f, 0.91f};
    private static final float[] REVERB_WET = {0f, 0.25f, 0.4f, 0.55f};

    private volatile EffectParams params = EffectParams.NONE;
    private EffectParams applied;
    private int sampleRate;
    private int channels;
    private int encoding;
    private final Biquad[] bands = new Biquad[EffectParams.BANDS];
    private final Biquad bass = new Biquad();
    private final SoftReverb reverb = new SoftReverb();
    private boolean[] bandOn = new boolean[EffectParams.BANDS];
    private boolean bassOn;
    private double preamp = 1;
    private float wet;
    private boolean reverbOn;
    private double limiterGain = 1;
    private double[] frameBuffer = new double[0];
    private double releasePerSample;

    public SoftEffects() {
        for (int i = 0; i < bands.length; i++) bands[i] = new Biquad();
    }

    /** Whether a stream of this encoding can be shaped. */
    public static boolean supports(int encoding) {
        return encoding == ENCODING_PCM_16BIT || encoding == ENCODING_PCM_FLOAT;
    }

    public void setParams(EffectParams params) {
        this.params = params == null ? EffectParams.NONE : params;
    }

    /** A new stream: its format. The state of the filters is cleared. */
    public void configure(int sampleRate, int channels, int encoding) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.encoding = encoding;
        for (Biquad band : bands) band.setChannels(channels);
        bass.setChannels(channels);
        reverb.configure(sampleRate, channels);
        releasePerSample = 1 - Math.exp(-1.0 / (RELEASE_MS / 1000.0 * sampleRate));
        limiterGain = 1;
        applied = null;
    }

    /** The sound goes on from another place (a seek): the tails of the filters and the reverb are cleared. */
    public void flush() {
        for (Biquad band : bands) band.clear();
        bass.clear();
        reverb.clear();
        limiterGain = 1;
    }

    /** The coefficients for the parameters of now, if they changed. */
    private void refresh() {
        EffectParams now = params;
        if (now == applied) return;
        applied = now;
        for (int i = 0; i < bands.length; i++) {
            bandOn[i] = now.bandDb[i] != 0f && bands[i].peaking(EffectParams.BAND_HZ[i], now.bandDb[i], BAND_OCTAVES, sampleRate);
        }
        bassOn = now.bassDb != 0f && bass.lowShelf(EffectParams.BASS_CORNER_HZ, now.bassDb, sampleRate);
        preamp = Math.pow(10, now.preampDb / 20.0);
        reverbOn = now.reverb > 0;
        if (reverbOn) {
            reverb.setFeedback(REVERB_FEEDBACK[Math.min(3, now.reverb)]);
            wet = REVERB_WET[Math.min(3, now.reverb)];
        }
    }

    /**
     * Shapes the PCM in {@code input} (from its position to its limit, which it consumes) into {@code output} (from its
     * position; it must have room for as many bytes). The bytes are in the order of the buffers.
     *
     * @return Whether anything shapes the sound; if not, the bytes were copied as they were.
     */
    public boolean process(ByteBuffer input, ByteBuffer output) {
        refresh();
        if (applied.isNeutral()) {
            output.put(input);
            return false;
        }
        boolean isFloat = encoding == ENCODING_PCM_FLOAT;
        int sampleBytes = isFloat ? 4 : 2;
        int frames = input.remaining() / (sampleBytes * channels);
        if (frameBuffer.length != channels) frameBuffer = new double[channels];
        for (int frame = 0; frame < frames; frame++) {
            double peak = 0;
            for (int channel = 0; channel < channels; channel++) {
                double x = isFloat ? input.getFloat() : input.getShort() / 32768.0;
                double y = shape(channel, x);
                frameBuffer[channel] = y;
                peak = Math.max(peak, Math.abs(y));
            }
            // One gain for all the channels of a frame, so the stereo image does not shift.
            double gain = limitGain(peak);
            for (int channel = 0; channel < channels; channel++) {
                double y = frameBuffer[channel] * gain;
                if (isFloat) output.putFloat((float) y);
                else output.putShort((short) Math.round(Math.max(-1.0, Math.min(32767 / 32768.0, y)) * 32768.0));
            }
        }
        // A part of a frame at the end (a broken buffer) is passed on as it is.
        output.put(input);
        return true;
    }

    private double shape(int channel, double sample) {
        double x = sample * preamp;
        if (bassOn) x = bass.process(channel, x);
        for (int i = 0; i < bands.length; i++) if (bandOn[i]) x = bands[i].process(channel, x);
        if (reverbOn) x += wet * reverb.process(channel, (float) x);
        return x;
    }

    /** Peak limiter: the gain drops at once so the frame stays under the ceiling, and comes back slowly. */
    private double limitGain(double peak) {
        double released = limiterGain + (1 - limiterGain) * releasePerSample;
        // The gain comes back slowly, but never above what keeps this frame under the ceiling.
        limiterGain = peak * released > CEILING ? CEILING / peak : released;
        return limiterGain;
    }
}
