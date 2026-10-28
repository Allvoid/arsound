package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class PcmConverterTest {
    @Test
    public void stereoShortsAreAveraged() {
        short[] stereo = {16384, 0, -32768, 32767};
        float[] mono = new float[2];
        PcmConverter.monoFromShorts(stereo, 2, 2, mono);
        assertEquals(0.25f, mono[0], 1e-4f);
        assertEquals(-0.0000076f, mono[1], 1e-4f);
    }

    @Test
    public void monoShortsAreScaledToOne() {
        float[] mono = new float[2];
        PcmConverter.monoFromShorts(new short[]{-32768, 16384}, 2, 1, mono);
        assertEquals(-1f, mono[0], 1e-6f);
        assertEquals(0.5f, mono[1], 1e-6f);
    }

    @Test
    public void floatChannelsAreAveraged() {
        float[] mono = new float[1];
        PcmConverter.monoFromFloats(new float[]{0.2f, 0.4f, 0.6f}, 1, 3, mono);
        assertEquals(0.4f, mono[0], 1e-6f);
    }
}
