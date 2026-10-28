package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class SongRefTest {
    @Test
    public void aCopyOfAChosenSongIsFound() {
        SongRef chosen = new SongRef("Song", 180_000);
        assertTrue(new SongRef("Song (Official Audio)", 181_000).isCopyOfAny(Arrays.asList(new SongRef("Other", 1_000), chosen)));
    }

    @Test
    public void aSongWithoutTitleOrLengthHasNoCopies() {
        SongRef chosen = new SongRef("Song", 180_000);
        assertFalse(new SongRef("", 180_000).isCopyOfAny(Collections.singletonList(chosen)));
        assertFalse(new SongRef(null, 180_000).isCopyOfAny(Collections.singletonList(chosen)));
        assertFalse(new SongRef("Song", 0).isCopyOfAny(Collections.singletonList(chosen)));
    }

    @Test
    public void trackUrnsOfBothKindsAreOne() {
        assertEquals("soundcloud:tracks:42", TrackUrn.normalize("soundcloud:sounds:42"));
        assertEquals("soundcloud:tracks:42", TrackUrn.normalize("soundcloud:tracks:42"));
        assertEquals("null", TrackUrn.normalize(null));
    }
}
