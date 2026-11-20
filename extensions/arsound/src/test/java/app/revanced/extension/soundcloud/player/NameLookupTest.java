package app.revanced.extension.soundcloud.player;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.junit.Test;


public class NameLookupTest {
    private static final String URN = "soundcloud:tracks:42";

    @Test
    public void aTrackNothingIsKnownOfIsUnresolvedAndAsksAgain() {
        ListeningStats.Entry entry = new ListeningStats.Entry(URN);
        TrackTitleResolver.settle(entry, null);
        // The title is a placeholder, no longer the urn: only the flag tells the scrobbler to retry.
        assertFalse(URN.equals(entry.title));
        ListeningStats.NameLookup found = ListeningStats.lookupOf(entry);
        assertNull(found.names);
        assertTrue(found.unresolved);
    }

    @Test
    public void aKnownTrackIsResolvedWithItsNames() {
        ListeningStats.Entry entry = new ListeningStats.Entry(URN);
        TrackTitleResolver.settle(entry, new JSONArray().put("Song").put("Band"));
        ListeningStats.NameLookup found = ListeningStats.lookupOf(entry);
        assertNotNull(found.names);
        assertEquals("Band", found.names[0]);
        assertEquals("Song", found.names[1]);
        assertFalse(found.unresolved);
    }

    @Test
    public void aKnownTrackWithoutAnArtistIsNotRetried() {
        ListeningStats.Entry entry = new ListeningStats.Entry(URN);
        entry.title = "Title";
        entry.artist = "";
        ListeningStats.NameLookup found = ListeningStats.lookupOf(entry);
        assertNull(found.names);
        assertFalse(found.unresolved);
    }
}
