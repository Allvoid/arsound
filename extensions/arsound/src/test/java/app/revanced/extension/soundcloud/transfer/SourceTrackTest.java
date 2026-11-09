package app.revanced.extension.soundcloud.transfer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SourceTrackTest {
    @Test
    public void versionGoesToTheWantedTitleOnly() {
        SourceTrack track = new SourceTrack("1", Arrays.asList("VILLIAN", "madk1d"), "Монолог", "Remix", 150_000);
        assertEquals("Монолог (Remix)", track.wantedTitle());
        assertEquals("VILLIAN Монолог", track.query());
        assertEquals("VILLIAN, madk1d — Монолог (Remix)", track.label());
    }

    @Test
    public void blankVersionIsNone() {
        SourceTrack track = new SourceTrack("2", Collections.emptyList(), "Intro", "  ", 0);
        assertNull(track.version);
        assertEquals("Intro", track.wantedTitle());
        assertEquals("Intro", track.label());
        assertEquals("Intro", track.query());
    }
}
