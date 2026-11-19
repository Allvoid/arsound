package app.revanced.extension.soundcloud.scrobble;

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
}
