package app.revanced.extension.soundcloud.player.dsp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

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
        effects.setParams(new EffectParams(new float[EffectParams.BANDS], -6f));
        double peak = peakOfSecondHalf(run(effects, sine(1000, 44100, 4410, 1, 0.5)));
        assertEquals(0.5 * Math.pow(10, -6 / 20.0), peak, 0.005);
    }

    @Test
    public void aBandAtTheEqualizerLiftsItsFrequency() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(bands(5, 6f), 0f));
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
        effects.setParams(new EffectParams(bands(5, 12f), 12f));
        float[] out = run(effects, sine(1000, 44100, 8820, 2, 0.9));
        double peak = 0;
        for (float sample : out) peak = Math.max(peak, Math.abs(sample));
        assertTrue("peak " + peak, peak <= SoftEffects.CEILING + 1e-6);
    }

    @Test
    public void sixteenBitOutputNeverWrapsAround() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_16BIT);
        effects.setParams(new EffectParams(new float[EffectParams.BANDS], 12f));
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
            effects.setParams(new EffectParams(new float[EffectParams.BANDS], -6f));
            float[] out = run(effects, sine(500, 48000, 4800, channels, 0.5));
            assertEquals("channels " + channels, 0.25, peakOfSecondHalf(out), 0.005);
        }
    }

    @Test
    public void aChangeOfSampleRateWorksTheCoefficientsAgain() {
        SoftEffects effects = new SoftEffects();
        effects.setParams(new EffectParams(bands(5, 6f), 0f));
        for (int rate : new int[]{44100, 48000, 22050, 96000}) {
            effects.configure(rate, 1, SoftEffects.ENCODING_PCM_FLOAT);
            double peak = peakOfSecondHalf(run(effects, sine(1000, rate, rate / 5, 1, 0.2)));
            assertEquals("rate " + rate, 0.4, peak, 0.01);
        }
    }



    @Test
    public void flushClearsTheTails() {
        SoftEffects effects = new SoftEffects();
        effects.configure(44100, 1, SoftEffects.ENCODING_PCM_FLOAT);
        effects.setParams(new EffectParams(bands(0, 12f), 0f));
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
