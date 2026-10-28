package app.revanced.extension.soundcloud.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class EarlierDownloadNamesTest {
    @Test
    public void trackIdIsTakenFromNamesOfEarlierVersions() {
        assertEquals("123", EarlierDownloadNames.trackIdOf("soundcloud-123.m4a"));
        assertEquals("123", EarlierDownloadNames.trackIdOf("soundcloud-123-45.mp3"));
        assertEquals("123", EarlierDownloadNames.trackIdOf("soundcloud-123 (2).opus"));
        assertEquals("123", EarlierDownloadNames.trackIdOf("soundcloud-123-45-6 (10).aac"));
    }

    @Test
    public void otherNamesBelongToNoTrack() {
        assertNull(EarlierDownloadNames.trackIdOf("Artist - Title.m4a"));
        assertNull(EarlierDownloadNames.trackIdOf("soundcloud-123.wav"));
        assertNull(EarlierDownloadNames.trackIdOf("soundcloud-abc.m4a"));
        assertNull(EarlierDownloadNames.trackIdOf("my-soundcloud-123.m4a"));
    }

    @Test
    public void copyNumberIsTheOneInBrackets() {
        assertEquals(0, EarlierDownloadNames.copyNumber("soundcloud-123.m4a"));
        assertEquals(1, EarlierDownloadNames.copyNumber("soundcloud-123 (1).m4a"));
        assertEquals(12, EarlierDownloadNames.copyNumber("soundcloud-123 (12).m4a"));
        assertEquals(0, EarlierDownloadNames.copyNumber("Song (3) live.m4a"));
    }
}
