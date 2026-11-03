package app.revanced.extension.soundcloud.player.dsp;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * The sound shaping of the player, in software: preamp, a low shelf, ten peaking bands, a reverb and a limiter, on 16-bit
 * or float PCM with any number of channels and any sample rate. The coefficients are worked out for the sample rate
 * of the stream when it is configured, and again when the settings change. The limiter holds the sound under a ceiling,
 * so boosts cannot clip. Nothing here is Android's: it is the same on every phone, whatever audio effects the firmware
 * has. Used by {@code SoftEqualizerProcessor}. Not thread safe: one stream, one thread; the parameters come from another
 * thread through {@link #setParams} (a volatile reference), and nothing is locked or allocated while the sound runs.
 * <p>
 * A change of the settings never jumps: the coefficients of the filters and the gains glide from the old to the new
 * values in about 20 milliseconds, frame by frame, so dragging a slider makes no click. A band that was off
 * is started from a flat filter with an empty state (no old tail plays); a band set back to flat glides to flat and
 * leaves the chain only when its tail has died away. While all this is going on the sound is shaped as usual, and
 * only once everything has settled flat does the processor go back to copying the bytes.
 */
public final class SoftEffects {
    public static final int ENCODING_PCM_16BIT = 2;
    public static final int ENCODING_PCM_FLOAT = 4;
    /** The limit of the output, as a part of full scale. */
    public static final float CEILING = 0.97f;
    /** The limiter lets the gain back up to 1 in about this many milliseconds. */
    private static final double RELEASE_MS = 120;
    /** A change of the settings glides over about this many milliseconds. */
    static final double GLIDE_MS = 20;
    /**
     * When the settings go flat the limiter lets the gain back up this fast (a time constant, in milliseconds), in step
     * with the glide: else a sound that was held down would come back slowly, as a dip in the level after the change.
     */
    private static final double FLAT_RELEASE_MS = GLIDE_MS / 4;
    /**
     * Once the limiter has let the gain back up this far, it counts as released: stepping aside to a plain copy then
     * makes a step of 0.2 percent at most, which no one hears.
     */
    private static final double LIMITER_RELEASED = 0.998;
    /** Width of a band in octaves. */
    private static final double BAND_OCTAVES = 1.0;
    private static final float[] REVERB_FEEDBACK = {0f, 0.78f, 0.85f, 0.91f};
    private static final float[] REVERB_WET = {0f, 0.25f, 0.4f, 0.55f};
    /** The place of the bass shelf among the filters; the ten bands follow it. */
    private static final int BASS = 0;
    private static final int FILTERS = EffectParams.BANDS + 1;

    private volatile EffectParams params = EffectParams.NONE;
    private EffectParams applied;
    private int sampleRate;
    private int channels;
    private int encoding;
    private final Biquad[] filters = new Biquad[FILTERS];
    /** The filters the sound goes through. */
    private final boolean[] filterOn = new boolean[FILTERS];
    /** The filters on their way out: set to flat, they glide to flat and leave when their tail has died away. */
    private final boolean[] filterLeaving = new boolean[FILTERS];
    /** A scratch filter to work a design out in, so the real one can glide to it. */
    private final Biquad designer = new Biquad();
    private final SoftReverb reverb = new SoftReverb();
    private final Ramp preamp = new Ramp(1);
    private final Ramp wet = new Ramp(0);
    private final Ramp feedback = new Ramp(0);
    private boolean reverbOn;
    private boolean reverbLeaving;
    /** Whether anything is gliding: only then does a frame cost the extra steps. */
    private boolean gliding;
    private int glideFrames;
    private double limiterGain = 1;
    private double[] frameBuffer = new double[0];
    private double releasePerSample;
    private double flatReleasePerSample;

    public SoftEffects() {
        for (int i = 0; i < filters.length; i++) filters[i] = new Biquad();
    }

    /** Whether a stream of this encoding can be shaped. */
    public static boolean supports(int encoding) {
        return encoding == ENCODING_PCM_16BIT || encoding == ENCODING_PCM_FLOAT;
    }

    public void setParams(EffectParams params) {
        this.params = params == null ? EffectParams.NONE : params;
    }

    /** A new stream: its format. The state of the filters is cleared, and the settings are taken at once on the first buffer. */
    public void configure(int sampleRate, int channels, int encoding) {
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.encoding = encoding;
        for (Biquad filter : filters) filter.setChannels(channels);
        reverb.configure(sampleRate, channels);
        releasePerSample = 1 - Math.exp(-1.0 / (RELEASE_MS / 1000.0 * sampleRate));
        flatReleasePerSample = 1 - Math.exp(-1.0 / (FLAT_RELEASE_MS / 1000.0 * sampleRate));
        glideFrames = Math.max(1, (int) Math.round(GLIDE_MS / 1000.0 * sampleRate));
        limiterGain = 1;
        // Nothing plays yet, so there is nothing to glide from: no filter in the chain, the gains at rest.
        Arrays.fill(filterOn, false);
        Arrays.fill(filterLeaving, false);
        reverbOn = false;
        reverbLeaving = false;
        gliding = false;
        preamp.snap(1);
        wet.snap(0);
        feedback.snap(0);
        applied = null;
    }

    /** The sound goes on from another place (a seek): the tails of the filters and the reverb are cleared. */
    public void flush() {
        for (Biquad filter : filters) filter.clear();
        reverb.clear();
        limiterGain = 1;
    }

    /**
     * The filters and the gains for the parameters of now, if they changed: they start to glide there. What did not
     * change is left alone (a glide that is going carries on to where it was going), so a drag of one slider costs one band.
     */
    private void refresh() {
        EffectParams now = params;
        if (now == applied) return;
        EffectParams before = applied;
        // The first parameters of a stream are taken at once; later ones glide.
        int frames = before == null ? 0 : glideFrames;
        applied = now;
        if (before == null || before.bassDb != now.bassDb) retune(BASS, now.bassDb, frames);
        for (int i = 0; i < EffectParams.BANDS; i++) {
            if (before == null || before.bandDb[i] != now.bandDb[i]) retune(BASS + 1 + i, now.bandDb[i], frames);
        }
        if (before == null || before.preampDb != now.preampDb) preamp.glideTo(Math.pow(10, now.preampDb / 20.0), frames);
        if (before == null || before.reverb != now.reverb) retuneReverb(now.reverb, frames);
        if (frames > 0) gliding = true;
    }

    /** Works out the filter of an index (the bass shelf or a band) for a gain in decibels, inside {@code into}. */
    private boolean design(Biquad into, int index, float db) {
        return index == BASS
                ? into.lowShelf(EffectParams.BASS_CORNER_HZ, db, sampleRate)
                : into.peaking(EffectParams.BAND_HZ[index - BASS - 1], db, BAND_OCTAVES, sampleRate);
    }

    /**
     * Brings one filter to a gain. A filter that was out of the chain comes in from a flat design with an empty state, so
     * no tail of an earlier time plays, and glides to the new design; one set to flat glides to flat and is marked to
     * leave once it has gone quiet (see {@link #retireSettled}).
     */
    private void retune(int index, float db, int frames) {
        if (!design(designer, index, db)) {
            // The centre is too near the top of this sample rate: the band cannot be made, it passes the sound.
            filterOn[index] = false;
            filterLeaving[index] = false;
            return;
        }
        Biquad filter = filters[index];
        if (db == 0f) {
            if (!filterOn[index]) return;
            filterLeaving[index] = true;
        } else {
            if (!filterOn[index]) {
                design(filter, index, 0f);
                filter.clear();
                filterOn[index] = true;
            }
            filterLeaving[index] = false;
        }
        filter.glideTo(designer, frames);
    }

    /** The reverb for a strength (0 for none): it starts from an empty room and its wet gain glides up, or glides down to leave. */
    private void retuneReverb(int level, int frames) {
        if (level > 0) {
            int index = Math.min(3, level);
            if (!reverbOn) {
                reverb.clear();
                wet.snap(0);
                feedback.snap(REVERB_FEEDBACK[index]);
                reverbOn = true;
            }
            reverbLeaving = false;
            feedback.glideTo(REVERB_FEEDBACK[index], frames);
            wet.glideTo(REVERB_WET[index], frames);
            reverb.setFeedback((float) feedback.value());
        } else if (reverbOn) {
            reverbLeaving = true;
            wet.glideTo(0, frames);
        }
    }

    /**
     * Takes out of the chain what has settled at flat: a filter that finished gliding to flat and whose state has died
     * away would pass the sound as it is, and the reverb whose wet gain reached nothing is not heard.
     */
    private void retireSettled() {
        for (int i = 0; i < FILTERS; i++) {
            if (filterLeaving[i] && !filters[i].isGliding() && filters[i].isQuiet()) {
                filterOn[i] = false;
                filterLeaving[i] = false;
            }
        }
        if (reverbLeaving && !wet.isGliding()) {
            reverbOn = false;
            reverbLeaving = false;
        }
    }

    /** Whether nothing is left in the chain: no filter, no reverb, the preamp at rest at 1. */
    private boolean isChainIdle() {
        for (boolean on : filterOn) if (on) return false;
        return !reverbOn && !preamp.isGliding() && preamp.value() == 1.0;
    }

    /** One frame of everything that glides. */
    private void stepGlides() {
        boolean any = false;
        for (int i = 0; i < FILTERS; i++) {
            if (!filterOn[i]) continue;
            filters[i].advance();
            any |= filters[i].isGliding();
        }
        preamp.next();
        wet.next();
        if (feedback.isGliding()) reverb.setFeedback((float) feedback.next());
        gliding = any || preamp.isGliding() || wet.isGliding() || feedback.isGliding();
    }

    /**
     * Shapes the PCM in {@code input} (from its position to its limit, which it consumes) into {@code output} (from its
     * position; it must have room for as many bytes). The bytes are in the order of the buffers.
     *
     * @return Whether anything shapes the sound; if not, the bytes were copied as they were.
     */
    public boolean process(ByteBuffer input, ByteBuffer output) {
        // What settled since the last buffer goes out first, so a setting that brings it back starts it from nothing.
        retireSettled();
        refresh();
        // Flat settings are a plain copy, but only once the glide to flat is over and the limiter let go, or the sound would jump.
        boolean flat = applied.isNeutral() && isChainIdle();
        if (flat && limiterGain >= LIMITER_RELEASED) {
            output.put(input);
            return false;
        }
        // Flat and settled, with the limiter still coming back: the sound is the input itself, so the limiter only lets
        // go and holds nothing down (a loud master would else be held under the ceiling for as long as it plays).
        boolean clamp = !flat;
        double release = applied.isNeutral() ? flatReleasePerSample : releasePerSample;
        boolean isFloat = encoding == ENCODING_PCM_FLOAT;
        int sampleBytes = isFloat ? 4 : 2;
        int frames = input.remaining() / (sampleBytes * channels);
        if (frameBuffer.length != channels) frameBuffer = new double[channels];
        for (int frame = 0; frame < frames; frame++) {
            if (gliding) stepGlides();
            double peak = 0;
            for (int channel = 0; channel < channels; channel++) {
                double x = isFloat ? input.getFloat() : input.getShort() / 32768.0;
                double y = shape(channel, x);
                frameBuffer[channel] = y;
                peak = Math.max(peak, Math.abs(y));
            }
            // One gain for all the channels of a frame, so the stereo image does not shift.
            double gain = limitGain(peak, clamp, release);
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
        double x = sample * preamp.value();
        for (int i = 0; i < FILTERS; i++) if (filterOn[i]) x = filters[i].process(channel, x);
        if (reverbOn) x += wet.value() * reverb.process(channel, (float) x);
        return x;
    }

    /**
     * Peak limiter: the gain drops at once so the frame stays under the ceiling, and comes back slowly.
     *
     * @param clamp   False to let the gain come back without holding anything down.
     * @param release How much of the way back to 1 the gain goes in one frame.
     */
    private double limitGain(double peak, boolean clamp, double release) {
        double released = limiterGain + (1 - limiterGain) * release;
        // The gain comes back slowly, but never above what keeps this frame under the ceiling.
        limiterGain = clamp && peak * released > CEILING ? CEILING / peak : released;
        return limiterGain;
    }
}
