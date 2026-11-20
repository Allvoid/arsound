package app.revanced.extension.soundcloud.scrobble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ScrobblerRulesTest {
    @Test
    public void aTrackThatCouldNotBeLookedUpIsAskedForAgainAFewTimes() {
        assertTrue(Scrobbler.shouldAskAgain(null, true, 0));
        assertTrue(Scrobbler.shouldAskAgain(null, true, 3));
        assertFalse(Scrobbler.shouldAskAgain(null, true, 4));
    }

    @Test
    public void aTrackWithNoArtistOrWithNamesIsNotAskedForAgain() {
        assertFalse(Scrobbler.shouldAskAgain(null, false, 0));
        assertFalse(Scrobbler.shouldAskAgain(new String[]{"Artist", "Song"}, false, 0));
    }

    @Test
    public void theLastFmConnectionJudgesTheSameCodesInBothSteps() {
        // Used by both the first and the last step of the approval.
        assertEquals(Scrobbler.Status.OFFLINE, Scrobbler.connectStatus(0));
        assertEquals(Scrobbler.Status.OFFLINE, Scrobbler.connectStatus(500));
        assertEquals(Scrobbler.Status.OFFLINE, Scrobbler.connectStatus(503));
        assertEquals(Scrobbler.Status.OFFLINE, Scrobbler.connectStatus(429));
        assertEquals(Scrobbler.Status.OFFLINE, Scrobbler.connectStatus(LastFmClient.ERROR_BASE + 11));
        assertEquals(Scrobbler.Status.INVALID, Scrobbler.connectStatus(LastFmClient.ERROR_BASE + 10));
        assertEquals(Scrobbler.Status.INVALID, Scrobbler.connectStatus(403));
    }
}
