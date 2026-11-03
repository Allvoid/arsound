package app.revanced.extension.soundcloud.player.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

public class SoftEffectsTest {
    private static float[] bands(int index, float db) {
        float[] bands = new float[EffectParams.BANDS];
        bands[index] = db;
        return bands;
    }

    private static ByteBuffer floats(float[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.nativeOrder());
        for (float sample : samples) buffer.putFloat(sample);
        buffer.flip();
        return buffer;
    }

    private static float[] readFloats(ByteBuffer buffer) {
        buffer.flip();
        float[] out = new float[buffer.remaining() / 4];
        for (int i = 0; i < out.length; i++) out[i] = buffer.getFloat();
        return out;
    }

    private static float[] sine(double hz, int sampleRate, int frames, int channels, double amplitude) {
        float[] samples = new float[frames * channels];
        for (int i = 0; i < frames; i++) {
            for (int c = 0; c < channels; c++) samples[i * channels + c] = (float) (amplitude * Math.sin(2 * Math.PI * hz * i / sampleRate));
        }
        return samples;
    }

    /** The largest absolute sample of the second half (after the filters settle). */
    private static double peakOfSecondHalf(float[] samples) {
        double peak = 0;
        for (int i = samples.length / 2; i < samples.length; i++) peak = Math.max(peak, Math.abs(samples[i]));
        return peak;
    }

    private static float[] run(SoftEffects effects, float[] input) {
        ByteBuffer out = ByteBuffer.allocate(input.length * 4).order(ByteOrder.nativeOrder());
        effects.process(floats(input), out);
        return readFloats(out);
    }

    private static final int RATE = 44100;
    /** Ten milliseconds, the size of a buffer the player hands over. */
    private static final int BLOCK = 441;
    /** The steepest a sine of 0.8 (0.2 lifted by 12 dB) at 1 kHz climbs in one sample, with some room for the glide. */
    private static final double STEP_BOUND = 0.8 * 2 * Math.PI * 1000 / RATE * 1.15;

    private static float[] flat() {
        return new float[EffectParams.BANDS];
    }

    private static float[] append(float[] head, float[] tail) {
        float[] joined = Arrays.copyOf(head, head.length + tail.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }

    private static double peakBetween(float[] samples, int from, int to) {
        double peak = 0;
        for (int i = from; i < to; i++) peak = Math.max(peak, Math.abs(samples[i]));
        return peak;
    }

    /** The peak of each millisecond (44 samples, about one period of 1 kHz) from a sample on. */
    private static double[] envelope(float[] samples, int from, int milliseconds) {
        double[] envelope = new double[milliseconds];
        for (int k = 0; k < milliseconds; k++) envelope[k] = peakBetween(samples, from + k * 44, from + (k + 1) * 44);
        return envelope;
    }

    /** The biggest change from one sample to the next. */
    private static double maxStep(float[] samples) {
        double step = 0;
        for (int i = 1; i < samples.length; i++) step = Math.max(step, Math.abs(samples[i] - samples[i - 1]));
        return step;
    }

    /**
     * A mono sine fed through one {@link SoftEffects} block by block, as the player does, with the settings changing
     * between the blocks. It keeps all it was given and all it got back.
     */
    private static final class Feed {
        final SoftEffects effects = new SoftEffects();
        final double hz;
        double amplitude;
        float[] input = new float[0];
        float[] output = new float[0];
        /** What the last block said: whether the sound was shaped, or copied as it was. */
        boolean lastShaped;
        private long position;

        Feed(double hz, double amplitude) {
            this.hz = hz;
            this.amplitude = amplitude;
            effects.configure(RATE, 1, SoftEffects.ENCODING_PCM_FLOAT);
        }

        void set(float[] bands, float preampDb) {
            effects.setParams(new EffectParams(bands, preampDb, 0f, 0));
        }

        /** Plays this many blocks; the sine goes on where it stopped. */
        void play(int blocks) {
            for (int b = 0; b < blocks; b++) {
                float[] block = new float[BLOCK];
                for (int i = 0; i < BLOCK; i++) block[i] = (float) (amplitude * Math.sin(2 * Math.PI * hz * (position + i) / RATE));
                position += BLOCK;
                ByteBuffer out = ByteBuffer.allocate(BLOCK * 4).order(ByteOrder.nativeOrder());
                lastShaped = effects.process(floats(block), out);
                input = append(input, block);
                output = append(output, readFloats(out));
            }
        }

        int length() {
            return output.length;
        }
    }

    // region Biquad

    @Test
    public void aPeakingBandLiftsItsCentreByItsGainAndLeavesFarAwayAlone() {
        Biquad band = new Biquad();
        band.setChannels(1);
        assertTrue(band.peaking(1000, 6, 1.0, 44100));
        assertEquals(Math.pow(10, 6 / 20.0), band.magnitudeAt(1000, 44100), 0.01);
        assertEquals(1.0, band.magnitudeAt(31.25, 44100), 0.03);
        assertEquals(1.0, band.magnitudeAt(16000, 44100), 0.1);
    }

    @Test
    public void aPeakingBandCutsByANegativeGain() {
        Biquad band = new Biquad();
        band.setChannels(1);
        band.peaking(250, -9, 1.0, 48000);
        assertEquals(Math.pow(10, -9 / 20.0), band.magnitudeAt(250, 48000), 0.01);
    }

    @Test
    public void aLowShelfLiftsTheLowsAndLeavesTheHighs() {
        Biquad shelf = new Biquad();
        shelf.setChannels(1);
        shelf.lowShelf(110, 10, 44100);
        assertEquals(Math.pow(10, 10 / 20.0), shelf.magnitudeAt(30, 44100), 0.05);
        assertEquals(1.0, shelf.magnitudeAt(5000, 44100), 0.02);
    }

    @Test
    public void aBandNearTheTopOfALowSampleRateIsLeftOut() {
        Biquad band = new Biquad();
        band.setChannels(1);
        assertFalse(band.peaking(16000, 12, 1.0, 22050));
        assertEquals(1.0, band.magnitudeAt(5000, 22050), 1e-9);
    }

    @Test
    public void theSameBandAtAnotherSampleRateStillLiftsItsCentre() {
        for (int rate : new int[]{8000, 22050, 44100, 48000, 96000}) {
            Biquad band = new Biquad();
            band.setChannels(1);
            if (!band.peaking(1000, 6, 1.0, rate)) continue;
            assertEquals("rate " + rate, Math.pow(10, 6 / 20.0), band.magnitudeAt(1000, rate), 0.02);
        }
    }

    @Test
    public void aFlatBandAndAFlatShelfPassTheSoundExactlyWhileTheirStateIsEmpty() {
        Biquad band = new Biquad();
        band.setChannels(1);
        assertTrue(band.peaking(1000, 0, 1.0, 44100));
        Biquad shelf = new Biquad();
        shelf.setChannels(1);
        assertTrue(shelf.lowShelf(110, 0, 44100));
        for (int i = 0; i < 200; i++) {
            double x = Math.sin(i * 0.37) * 0.8;
            assertEquals(x, band.process(0, x), 1e-12);
            assertEquals(x, shelf.process(0, x), 1e-12);
        }
        assertTrue(band.isQuiet());
        assertTrue(shelf.isQuiet());
    }

    @Test
    public void aFilterIsQuietOnlyWhenItsStateHasDiedAway() {
        Biquad band = new Biquad();
        band.setChannels(2);
        band.peaking(100, 12, 1.0, 44100);
        assertTrue(band.isQuiet());
        for (int i = 0; i < 200; i++) band.process(0, Math.sin(i * 0.1));
        assertFalse(band.isQuiet());
        band.clear();
        assertTrue(band.isQuiet());
    }

    @Test
    public void aGlideGoesFromOneDesignToTheOtherInTheGivenFramesAndLandsOnIt() {
        Biquad target = new Biquad();
        target.peaking(1000, 12, 1.0, 44100);
        Biquad moving = new Biquad();
        moving.setChannels(1);
        moving.peaking(1000, 0, 1.0, 44100);
        moving.glideTo(target, 100);
        assertTrue(moving.isGliding());
        double previous = moving.magnitudeAt(1000, 44100);
        assertEquals(1.0, previous, 1e-9);
        for (int i = 1; i < 100; i++) {
            moving.advance();
            double now = moving.magnitudeAt(1000, 44100);
            assertTrue("step " + i, now >= previous - 1e-12);
            assertTrue(moving.isGliding());
            previous = now;
        }
        // Half way it is half way, not at one end.
        Biquad half = new Biquad();
        half.peaking(1000, 0, 1.0, 44100);
        half.glideTo(target, 100);
        for (int i = 0; i < 50; i++) half.advance();
        assertEquals(2.0, half.magnitudeAt(1000, 44100), 0.05);
        moving.advance();
        assertFalse(moving.isGliding());
        assertEquals(target.magnitudeAt(1000, 44100), moving.magnitudeAt(1000, 44100), 1e-12);
        // Further advances change nothing.
        moving.advance();
        assertEquals(target.magnitudeAt(1000, 44100), moving.magnitudeAt(1000, 44100), 1e-12);
    }

    @Test
    public void aGlideOfNoFramesIsAJumpAndASettingAtOnceStopsAGlide() {
        Biquad target = new Biquad();
        target.peaking(1000, 12, 1.0, 44100);
        Biquad moving = new Biquad();
        moving.setChannels(1);
        moving.glideTo(target, 0);
        assertFalse(moving.isGliding());
        assertEquals(target.magnitudeAt(1000, 44100), moving.magnitudeAt(1000, 44100), 1e-12);
        moving.peaking(1000, 0, 1.0, 44100);
        moving.glideTo(target, 50);
        moving.peaking(1000, -6, 1.0, 44100);
        assertFalse(moving.isGliding());
        assertEquals(Math.pow(10, -6 / 20.0), moving.magnitudeAt(1000, 44100), 0.01);
    }

    @Test
    public void aFilterThatGlidesBackAndForthStaysStable() {
        Random random = new Random(1);
        for (double hz : new double[]{31.25, 1000, 16000}) {
            Biquad target = new Biquad();
            Biquad band = new Biquad();
            band.setChannels(1);
            band.peaking(hz, 12, 1.0, 44100);
            double peak = 0;
            for (int block = 0; block < 60; block++) {
                target.peaking(hz, block % 2 == 0 ? -12 : 12, 1.0, 44100);
                band.glideTo(target, 882);
                for (int i = 0; i < 441; i++) {
                    band.advance();
                    peak = Math.max(peak, Math.abs(band.process(0, random.nextDouble() * 0.6 - 0.3)));
                }
            }
            assertTrue("hz " + hz + " peak " + peak, peak < 3.0);
        }
    }

    // endregion

    // region Ramp

    @Test
    public void aRampGoesInAStraightLineAndLandsOnItsTarget() {
        Ramp ramp = new Ramp(1);
        assertFalse(ramp.isGliding());
        assertEquals(1.0, ramp.next(), 0.0);
        ramp.glideTo(3, 4);
        assertTrue(ramp.isGliding());
        assertEquals(1.5, ramp.next(), 1e-12);
        assertEquals(2.0, ramp.next(), 1e-12);
        assertEquals(2.5, ramp.next(), 1e-12);
        assertEquals(3.0, ramp.next(), 0.0);
        assertFalse(ramp.isGliding());
        assertEquals(3.0, ramp.next(), 0.0);
        assertEquals(3.0, ramp.value(), 0.0);
    }

    @Test
    public void aRampOfNoFramesJumpsAndANewTargetStartsFromWhereItIs() {
        Ramp ramp = new Ramp(0);
        ramp.glideTo(1, 0);
        assertEquals(1.0, ramp.value(), 0.0);
        assertFalse(ramp.isGliding());
        ramp.glideTo(2, 2);
        assertEquals(1.5, ramp.next(), 1e-12);
        ramp.glideTo(0.5, 2);
        assertEquals(1.0, ramp.next(), 1e-12);
        assertEquals(0.5, ramp.next(), 0.0);
        ramp.glideTo(4, 3);
        ramp.snap(7);
        assertFalse(ramp.isGliding());
        assertEquals(7.0, ramp.next(), 0.0);
    }

    // endregion

    // region Gliding settings

    @Test
    public void switchingABandOnAndOffAndOnAgainMakesNoJump() {
        Feed feed = new Feed(1000, 0.2);
        feed.set(flat(), 0f);
        feed.play(5);
        feed.set(bands(5, 12f), 0f);
        feed.play(30);
        feed.set(flat(), 0f);
        feed.play(30);
        feed.set(bands(5, 12f), 0f);
        feed.play(30);
        // The sound is a sine of 0.2 or of 0.8, so no sample is further from the last than a sine of 0.8 allows;
        // a band cut in or out at once would jump by half of the difference.
        double step = maxStep(feed.output);
        assertTrue("step " + step, step < STEP_BOUND);
        assertEquals(0.8, peakBetween(feed.output, feed.length() - BLOCK, feed.length()), 0.02);
    }

    @Test
    public void aGainThatFallsRampsDownInAboutTwentyMilliseconds() {
        Feed feed = new Feed(1000, 0.2);
        feed.set(bands(5, 12f), 0f);
        feed.play(20);
        int change = feed.length();
        feed.set(flat(), 0f);
        feed.play(5);
        double[] envelope = envelope(feed.output, change, 40);
        // Not a step: the first millisecond is still the lifted sound ...
        assertTrue("first " + envelope[0], envelope[0] > 0.7);
        // ... half way through the glide it is in between ...
        assertTrue("middle " + envelope[10], envelope[10] > 0.3 && envelope[10] < 0.55);
        // ... it never climbs on the way down ...
        for (int k = 1; k <= 20; k++) assertTrue("ms " + k, envelope[k] <= envelope[k - 1] + 0.01);
        // ... and it has arrived at the plain sound after the glide.
        assertEquals(0.2, envelope[25], 0.02);
        assertEquals(0.2, envelope[39], 0.02);
    }

    @Test
    public void aGainThatRisesRampsUpInAboutTwentyMilliseconds() {
        Feed feed = new Feed(1000, 0.2);
        feed.set(flat(), 0f);
        feed.play(20);
        int change = feed.length();
        feed.set(bands(5, 12f), 0f);
        feed.play(5);
        double[] envelope = envelope(feed.output, change, 40);
        assertTrue("first " + envelope[0], envelope[0] < 0.3);
        assertTrue("middle " + envelope[10], envelope[10] > 0.3 && envelope[10] < 0.55);
        for (int k = 1; k <= 20; k++) assertTrue("ms " + k, envelope[k] >= envelope[k - 1] - 0.01);
        assertEquals(0.8, envelope[25], 0.03);
        assertEquals(0.8, envelope[39], 0.03);
    }

    @Test
    public void aSliderDraggedMakesNoJump() {
        // A new setting every 10 ms, shorter than a glide, each from wherever the last one had got to.
        Feed fast = new Feed(1000, 0.2);
        fast.set(flat(), 0f);
        fast.play(5);
        for (int k = 0; k < 40; k++) {
            fast.set(bands(5, k % 2 == 0 ? 12f : -12f), 0f);
            fast.play(1);
        }
        double fastStep = maxStep(fast.output);
        assertTrue("step " + fastStep, fastStep < STEP_BOUND);
        // And a slow drag up in steps of one decibel.
        Feed slow = new Feed(1000, 0.2);
        slow.set(flat(), 0f);
        slow.play(5);
        for (int db = 1; db <= 12; db++) {
            slow.set(bands(5, db), 0f);
            slow.play(1);
        }
        slow.play(5);
        double slowStep = maxStep(slow.output);
        assertTrue("step " + slowStep, slowStep < STEP_BOUND);
        assertEquals(0.8, peakBetween(slow.output, slow.length() - BLOCK, slow.length()), 0.02);
    }

    @Test
    public void thePreampGlidesToo() {
        Feed feed = new Feed(1000, 0.4);
        feed.set(flat(), 0f);
        feed.play(5);
        int change = feed.length();
        feed.set(flat(), -12f);
        feed.play(5);
        double[] envelope = envelope(feed.output, change, 40);
        assertTrue("first " + envelope[0], envelope[0] > 0.3);
        assertTrue("middle " + envelope[10], envelope[10] > 0.15 && envelope[10] < 0.3);
        assertEquals(0.4 * Math.pow(10, -12 / 20.0), envelope[30], 0.01);
    }

    @Test
    public void aBandSwitchedOnPlaysNoTailOfAnEarlierTime() {
        Feed feed = new Feed(1000, 0.2);
        feed.set(bands(5, 12f), 0f);
        feed.play(20);
        // The band is set to flat and the sound stops: the band glides to flat and leaves once its tail has died away.
        feed.amplitude = 0;
        feed.set(flat(), 0f);
        feed.play(100);
        int silence = feed.length();
        feed.set(bands(5, 12f), 0f);
        feed.play(1);
        for (int i = silence; i < feed.length(); i++) assertEquals("sample " + i, 0f, feed.output[i], 1e-6f);
    }

    @Test
    public void aReverbSwitchedOnPlaysNoTailOfAnEarlierTime() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        EffectParams reverb = new EffectParams(flat(), 0f, 0f, 3);
        effects.setParams(reverb);
        float[] impulse = new float[4410];
        impulse[0] = 0.5f;
        run(effects, impulse);
        effects.setParams(new EffectParams(flat(), 0f, 0f, 0));
        run(effects, new float[44100]);
        effects.setParams(reverb);
        for (float sample : run(effects, new float[8820])) assertEquals(0f, sample, 1e-9f);
    }

    @Test
    public void aReverbSwitchedOffFadesAwayAndLeavesTheDrySound() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(flat(), 0f, 0f, 3));
        float[] noise = new float[44100];
        Random random = new Random(3);
        for (int i = 0; i < noise.length; i++) noise[i] = (float) (random.nextDouble() - 0.5);
        run(effects, noise);
        effects.setParams(new EffectParams(flat(), 0f, 0f, 0));
        float[] after = run(effects, noise);
        // At the start the wet part is still there, going down; after the glide the sound is the dry one.
        double wetAtStart = 0;
        for (int i = 0; i < 20; i++) wetAtStart = Math.max(wetAtStart, Math.abs(after[i] - noise[i]));
        assertTrue("wet at start " + wetAtStart, wetAtStart > 0.001);
        for (int i = 2000; i < after.length; i++) assertEquals("sample " + i, noise[i], after[i], 1e-6f);
    }

    @Test
    public void flatSettingsAreAPlainCopyAgainOnceTheGlideToFlatIsOver() {
        Feed feed = new Feed(1000, 0.2);
        feed.set(bands(5, 12f), 0f);
        feed.play(20);
        assertTrue(feed.lastShaped);
        feed.set(flat(), 0f);
        feed.play(1);
        // Still gliding down: the sound is shaped, not copied.
        assertTrue(feed.lastShaped);
        feed.play(10);
        assertFalse(feed.lastShaped);
        for (int i = feed.length() - BLOCK; i < feed.length(); i++) assertEquals(feed.input[i], feed.output[i], 0f);
    }

    @Test
    public void goingFlatUnderTheLimiterComesBackToTheLevelFastAndEndsAsAPlainCopy() {
        Feed feed = new Feed(1000, 0.9);
        feed.set(bands(5, 12f), 6f);
        feed.play(30);
        assertEquals(SoftEffects.CEILING, peakBetween(feed.output, feed.length() - BLOCK, feed.length()), 0.01);
        int change = feed.length();
        feed.set(flat(), 0f);
        feed.play(40);
        // The limiter does not hold the sound down for long once nothing lifts it (it would, at its slow release, for half a second).
        assertEquals(0.9, peakBetween(feed.output, change + BLOCK * 4, change + BLOCK * 5), 0.04);
        assertFalse(feed.lastShaped);
        for (int i = feed.length() - BLOCK; i < feed.length(); i++) assertEquals(feed.input[i], feed.output[i], 0f);
    }

    @Test
    public void aLoudMasterIsNotHeldUnderTheCeilingOnceTheSettingsAreFlat() {
        Feed feed = new Feed(1000, 1.0);
        feed.set(bands(5, 6f), 0f);
        feed.play(10);
        feed.set(flat(), 0f);
        feed.play(60);
        assertFalse(feed.lastShaped);
        assertEquals(1.0, peakBetween(feed.output, feed.length() - BLOCK, feed.length()), 0.01);
    }

    @Test
    public void theFirstSettingsOfAStreamAreTakenAtOnce() {
        // A new stream has nothing playing to glide from: the very first buffer is already shaped in full.
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(flat(), -6f, 0f, 0));
        float[] out = run(effects, sine(1000, 44100, 441, 1, 0.5));
        assertEquals(0.25, peakBetween(out, 0, 44), 0.005);
        // The same after another configure.
        effects.setParams(new EffectParams(flat(), -12f, 0f, 0));
        run(effects, sine(1000, 44100, 441, 1, 0.5));
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        out = run(effects, sine(1000, 44100, 441, 1, 0.5));
        assertEquals(0.5 * Math.pow(10, -12 / 20.0), peakBetween(out, 0, 44), 0.005);
    }

    // endregion

    @Test
    public void nothingSetPassesTheBytesAsTheyAre() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 2, SoftEffects.ENCODING_PCM_16BIT);
        ByteBuffer in = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 8; i++) in.putShort((short) (i * 1000 - 3000));
        in.flip();
        ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
        assertFalse(effects.process(in, out));
        out.flip();
        for (int i = 0; i < 8; i++) assertEquals(i * 1000 - 3000, out.getShort());
    }

    @Test
    public void thePreampSetsTheLevel() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(new float[EffectParams.BANDS], -6f, 0f, 0));
        double peak = peakOfSecondHalf(run(effects, sine(1000, 44100, 4410, 1, 0.5)));
        assertEquals(0.5 * Math.pow(10, -6 / 20.0), peak, 0.005);
    }

    @Test
    public void aBandAtTheEqualizerLiftsItsFrequency() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(bands(5, 6f), 0f, 0f, 0));
        double lifted = peakOfSecondHalf(run(effects, sine(1000, 44100, 8820, 1, 0.2)));
        assertEquals(0.2 * 2.0, lifted, 0.01);
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        double apart = peakOfSecondHalf(run(effects, sine(62.5, 44100, 8820, 1, 0.2)));
        assertEquals(0.2, apart, 0.02);
    }

    @Test
    public void theLimiterKeepsABoostedLoudSoundUnderTheCeiling() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 2, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(bands(5, 12f), 12f, 10f, 0));
        float[] out = run(effects, sine(1000, 44100, 8820, 2, 0.9));
        double peak = 0;
        for (float sample : out) peak = Math.max(peak, Math.abs(sample));
        assertTrue("peak " + peak, peak <= SoftEffects.CEILING + 1e-6);
    }

    @Test
    public void sixteenBitOutputNeverWrapsAround() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_16BIT);
        effects.setParams(new EffectParams(new float[EffectParams.BANDS], 12f, 0f, 0));
        ByteBuffer in = ByteBuffer.allocate(2 * 4410).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4410; i++) in.putShort((short) Math.round(32000 * Math.sin(2 * Math.PI * 440 * i / 44100.0)));
        in.flip();
        ByteBuffer out = ByteBuffer.allocate(2 * 4410).order(ByteOrder.nativeOrder());
        effects.process(in, out);
        out.flip();
        int previous = 0;
        for (int i = 0; i < 4410; i++) {
            short value = out.getShort();
            assertTrue(Math.abs(value) <= 32767 * SoftEffects.CEILING + 2);
            // A sine does not jump: a wrapped sample would.
            if (i > 0) assertTrue(Math.abs(value - previous) < 12000);
            previous = value;
        }
    }

    @Test
    public void monoStereoAndSurroundAreAllShaped() {
        for (int channels : new int[]{1, 2, 6}) {
            SoftEffects effects = new SoftEffects();
            effects.configure(48000, channels, SoftEffects.ENCODING_PCM_FLOAT);
            effects.setParams(new EffectParams(new float[EffectParams.BANDS], -6f, 0f, 0));
            float[] out = run(effects, sine(500, 48000, 4800, channels, 0.5));
            assertEquals("channels " + channels, 0.25, peakOfSecondHalf(out), 0.005);
        }
    }

    @Test
    public void aChangeOfSampleRateWorksTheCoefficientsAgain() {
        SoftEffects effects = new SoftEffects();
        effects.setParams(new EffectParams(bands(5, 6f), 0f, 0f, 0));
        for (int rate : new int[]{44100, 48000, 22050, 96000}) {
            effects.configure(rate, 1, SoftEffects.ENCODING_PCM_FLOAT);
            double peak = peakOfSecondHalf(run(effects, sine(1000, rate, rate / 5, 1, 0.2)));
            assertEquals("rate " + rate, 0.4, peak, 0.01);
        }
    }

    @Test
    public void theReverbLeavesATailAfterAnImpulse() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(new float[EffectParams.BANDS], 0f, 0f, 3));
        float[] impulse = new float[44100];
        impulse[0] = 0.5f;
        float[] out = run(effects, impulse);
        double tail = 0;
        for (int i = 4000; i < out.length; i++) tail += Math.abs(out[i]);
        assertTrue("tail " + tail, tail > 0.5);
    }

    @Test
    public void noReverbLeavesNoTail() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(new float[EffectParams.BANDS], 1f, 0f, 0));
        float[] impulse = new float[22050];
        impulse[0] = 0.5f;
        float[] out = run(effects, impulse);
        for (int i = 10; i < out.length; i++) assertEquals(0f, out[i], 1e-6f);
    }

    @Test
    public void flushClearsTheTails() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(new float[EffectParams.BANDS], 0f, 0f, 2));
        float[] impulse = new float[4410];
        impulse[0] = 0.5f;
        run(effects, impulse);
        effects.flush();
        float[] silence = run(effects, new float[8820]);
        for (float sample : silence) assertEquals(0f, sample, 1e-9f);
    }

    @Test
    public void onlySixteenBitAndFloatAreShaped() {
        assertTrue(SoftEffects.supports(SoftEffects.ENCODING_PCM_16BIT));
        assertTrue(SoftEffects.supports(SoftEffects.ENCODING_PCM_FLOAT));
        assertFalse(SoftEffects.supports(21));
    }
}
