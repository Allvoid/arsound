package app.revanced.extension.soundcloud.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class UrnsTest {
    @Test
    public void trackIdIsTheNumberOfATrackUrn() {
        assertEquals("123", Urns.trackId("soundcloud:tracks:123"));
    }

    @Test
    public void otherTextHasNoTrackId() {
        assertNull(Urns.trackId(null));
        assertNull(Urns.trackId("soundcloud:playlists:123"));
        assertNull(Urns.trackId("soundcloud:tracks:abc"));
        assertNull(Urns.trackId("xsoundcloud:tracks:123"));
        assertNull(Urns.trackId("soundcloud:tracks:123:extra"));
    }

    @Test
    public void playlistIdIsTheNumberOfAPlaylistUrn() {
        assertEquals("45", Urns.playlistId("soundcloud:playlists:45"));
        assertNull(Urns.playlistId("soundcloud:system-playlists:45"));
        assertNull(Urns.playlistId("soundcloud:tracks:45"));
    }

    @Test
    public void urnsAreBuiltFromIds() {
        assertEquals("soundcloud:tracks:7", Urns.track("7"));
        assertEquals("soundcloud:playlists:8", Urns.playlist("8"));
        assertEquals("7", Urns.trackId(Urns.track("7")));
    }
}
