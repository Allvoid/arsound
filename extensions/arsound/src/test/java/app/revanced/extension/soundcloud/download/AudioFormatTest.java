package app.revanced.extension.soundcloud.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AudioFormatTest {
    @Test
    public void formatIsToldByTheExtension() {
        assertEquals(AudioFormat.MP3, AudioFormat.ofName("Artist - Title.mp3"));
        assertEquals(AudioFormat.M4A, AudioFormat.ofName("soundcloud-1.m4a"));
        assertEquals(AudioFormat.MP4, AudioFormat.ofName("clip.mp4"));
        assertEquals(AudioFormat.AAC, AudioFormat.ofName("a.aac"));
        assertEquals(AudioFormat.OPUS, AudioFormat.ofName("a.opus"));
    }

    @Test
    public void unknownKindsHaveNoFormat() {
        assertNull(AudioFormat.ofName("a.wav"));
        assertNull(AudioFormat.ofName("mp3"));
        assertNull(AudioFormat.ofName("a.mp3.part"));
    }

    @Test
    public void capitalsCountOnlyWhenAskedFor() {
        assertNull(AudioFormat.ofName("A.MP3"));
        assertEquals(AudioFormat.MP3, AudioFormat.ofNameIgnoringCase("A.MP3"));
        assertEquals(AudioFormat.M4A, AudioFormat.ofNameIgnoringCase("A.M4a"));
    }

    @Test
    public void typesMatchTheExtensions() {
        assertEquals("audio/mpeg", AudioFormat.MP3.mimeType());
        assertEquals("audio/mp4", AudioFormat.M4A.mimeType());
        assertEquals("audio/aac", AudioFormat.AAC.mimeType());
        assertEquals("audio/ogg", AudioFormat.OPUS.mimeType());
        assertNull(AudioFormat.MP4.mimeType());
    }
}
