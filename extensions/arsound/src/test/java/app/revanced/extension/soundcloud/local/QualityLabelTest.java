package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class QualityLabelTest {
    @Test
    public void aLosslessFileOfCdQualityIsJustItsFormat() {
        assertEquals("FLAC", QualityLabel.of("a.flac", "audio/flac", 44_100, 16, 900_000));
        assertEquals("FLAC", QualityLabel.of("a.flac", "audio/flac", 48_000, 16, 0));
        assertEquals("WAV", QualityLabel.of("a.wav", "audio/raw", 44_100, 16, 0));
    }

    @Test
    public void aHighResolutionFileShowsDepthAndRate() {
        assertEquals("FLAC 24/96", QualityLabel.of("a.flac", "audio/flac", 96_000, 24, 0));
        assertEquals("FLAC 24/44.1", QualityLabel.of("a.flac", "audio/flac", 44_100, 24, 0));
        assertEquals("FLAC 16/96", QualityLabel.of("a.flac", "audio/flac", 96_000, 16, 0));
    }

    @Test
    public void anUnknownDepthAtAPlainRateIsJustTheFormat() {
        assertEquals("FLAC", QualityLabel.of("a.flac", "audio/flac", 44_100, 0, 0));
        assertEquals("FLAC", QualityLabel.of("a.flac", "audio/flac", 96_000, 0, 0));
        assertEquals("FLAC", QualityLabel.of("a.flac", "audio/flac", 0, 24, 0));
    }

    @Test
    public void anM4aIsNamedByWhatIsInIt() {
        assertEquals("ALAC", QualityLabel.of("a.m4a", "audio/alac", 44_100, 16, 0));
        assertEquals("AAC 256", QualityLabel.of("a.m4a", "audio/mp4a-latm", 44_100, 0, 256_000));
    }

    @Test
    public void aLossyFileShowsItsBitRateRoundedToTheUsualStep() {
        assertEquals("MP3 320", QualityLabel.of("a.mp3", "audio/mpeg", 44_100, 0, 319_000));
        assertEquals("MP3 245", QualityLabel.of("a.mp3", "audio/mpeg", 44_100, 0, 245_000));
        assertEquals("MP3", QualityLabel.of("a.mp3", "audio/mpeg", 44_100, 0, 0));
    }

    @Test
    public void theExtensionIsEnoughWhenTheFileGaveNoCodec() {
        assertEquals("FLAC", QualityLabel.of("a.FLAC", null, 0, 0, 0));
        assertEquals("OGG", QualityLabel.of("a.oga", null, 0, 0, 0));
    }

    @Test
    public void aFileWithoutAnExtensionHasNoMark() {
        assertEquals("", QualityLabel.of("track", "audio/flac", 44_100, 16, 0));
    }
}
