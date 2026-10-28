package app.revanced.extension.soundcloud.lyrics.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class TrackAudioTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void onlyTracksAndImportedFilesAreLookedFor() {
        assertTrue(TrackAudio.isCandidate("1234567"));
        assertTrue(TrackAudio.isCandidate("local_YWJj"));
        // The player names tracks with the full urn.
        assertTrue(TrackAudio.isCandidate("soundcloud:tracks:1310817199"));
        assertTrue(TrackAudio.isCandidate("sc-local:tracks:local_YWJj"));
        assertFalse(TrackAudio.isCandidate(""));
        assertFalse(TrackAudio.isCandidate(null));
        assertFalse(TrackAudio.isCandidate("soundcloud:playlists:12"));
        assertFalse(TrackAudio.isCandidate("soundcloud:tracks:"));
        assertFalse(TrackAudio.isCandidate("local_"));
        assertFalse(TrackAudio.isCandidate("12a"));
    }

    @Test
    public void theKeyIsTheIdOrTheImportedName() {
        assertEquals("1310817199", TrackAudio.trackKey("soundcloud:tracks:1310817199"));
        assertEquals("1310817199", TrackAudio.trackKey("1310817199"));
        assertEquals("local_YWJj", TrackAudio.trackKey("sc-local:tracks:local_YWJj"));
        assertEquals(null, TrackAudio.trackKey("soundcloud:users:5"));
    }

    @Test
    public void anImportedUrnNamesItsFile() throws IOException {
        File song = temporary.newFile("песня.mp3");
        String encoded = Base64.getUrlEncoder().encodeToString(song.getPath().getBytes(StandardCharsets.UTF_8));
        assertEquals(song, TrackAudio.importedFile("local_" + encoded));
        String standard = Base64.getEncoder().encodeToString(song.getPath().getBytes(StandardCharsets.UTF_8));
        assertEquals(song, TrackAudio.importedFile("local_" + standard));
    }

    @Test
    public void aMissingOrBrokenImportedUrnNamesNothing() {
        String encoded = Base64.getUrlEncoder().encodeToString("/no/such/file.mp3".getBytes(StandardCharsets.UTF_8));
        assertNull(TrackAudio.importedFile("local_" + encoded));
        assertNull(TrackAudio.importedFile("local_!!!not base64"));
    }
}
