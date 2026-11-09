package app.revanced.extension.soundcloud.player;

import static app.revanced.extension.soundcloud.player.PlayNext.Kind.AD;
import static app.revanced.extension.soundcloud.player.PlayNext.Kind.PUT_NEXT;
import static app.revanced.extension.soundcloud.player.PlayNext.Kind.QUEUE;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class PlayNextTest {
    @Test
    public void theFirstGoesRightAfterThePlayingTrack() {
        assertEquals(6, PlayNext.position(5, Arrays.asList(QUEUE, QUEUE)));
    }

    @Test
    public void laterOnesGoAfterTheOnesPutNextBefore() {
        // Tapped A, then B, then C: A, B, C play in that order, then the queue as it was.
        assertEquals(7, PlayNext.position(5, Arrays.asList(PUT_NEXT, QUEUE)));
        assertEquals(8, PlayNext.position(5, Arrays.asList(PUT_NEXT, PUT_NEXT, QUEUE)));
    }

    @Test
    public void adsAreStepped() {
        assertEquals(8, PlayNext.position(5, Arrays.asList(AD, PUT_NEXT, QUEUE, PUT_NEXT)));
    }

    @Test
    public void everythingAheadPutNextGoesToTheEndNeverBeforeThePlayingTrack() {
        // SoundCloud's own count ended at -1 here and the track went in front of the playing one.
        assertEquals(8, PlayNext.position(5, Arrays.asList(PUT_NEXT, PUT_NEXT)));
        assertEquals(6, PlayNext.position(5, Collections.emptyList()));
    }

    @Test
    public void anEmptyPlaceBeforeTheStartIsTheFirst() {
        assertEquals(0, PlayNext.position(-1, Collections.emptyList()));
    }
}
