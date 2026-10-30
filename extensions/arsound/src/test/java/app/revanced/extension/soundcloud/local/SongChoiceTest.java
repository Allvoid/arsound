package app.revanced.extension.soundcloud.local;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.revanced.extension.soundcloud.download.ClientProfiles.FoundTrack;

public class SongChoiceTest {
    private static FoundTrack track(String id, String title, String user, long duration) {
        return new FoundTrack(id, title, user, "ALLOW", duration);
    }

    private static final List<String> ARTISTS = Arrays.asList("Земфира", "Guest");

    @Test
    public void theArtistsUploadOfTheSameLengthComesFirst() {
        List<FoundTrack> results = Arrays.asList(
                track("1", "Земфира - Хочешь? (live)", "fan", 260_000),
                track("2", "Хочешь?", "Земфира", 400_000),
                track("3", "Хочешь?", "Земфира Official", 215_000));
        assertEquals("3", SongChoice.pick(results, ARTISTS, "Хочешь?", 214_000).id);
    }

    @Test
    public void anotherUploaderCountsOnlyWithTheSameLength() {
        List<FoundTrack> results = Collections.singletonList(track("4", "Zemfira - Хочешь?", "label records", 214_500));
        assertEquals("4", SongChoice.pick(results, ARTISTS, "Хочешь?", 214_000).id);
        assertNull(SongChoice.pick(results, ARTISTS, "Хочешь?", 230_000));
        assertNull(SongChoice.pick(results, ARTISTS, "Хочешь?", 0));
    }

    @Test
    public void aMuchLongerUploadByTheArtistIsAnotherVersion() {
        List<FoundTrack> results = Collections.singletonList(track("5", "Хочешь?", "Земфира", 400_000));
        assertNull(SongChoice.pick(results, ARTISTS, "Хочешь?", 214_000));
        // Unknown length: the artist and the title are enough.
        assertEquals("5", SongChoice.pick(results, ARTISTS, "Хочешь?", 0).id);
    }

    @Test
    public void previewsSnippetsAndVariantsAreSkipped() {
        List<FoundTrack> results = Arrays.asList(
                new FoundTrack("6", "Хочешь?", "Земфира", "SNIP", 214_000),
                track("7", "Хочешь? (snippet)", "Земфира", 214_000),
                track("8", "Хочешь? (slowed + reverb)", "someone", 214_000));
        assertNull(SongChoice.pick(results, ARTISTS, "Хочешь?", 214_000));
    }

    @Test
    public void aDjsEditIsNotTheSong() {
        List<FoundTrack> results = Arrays.asList(
                track("11", "Imael Angel - Bad Times (Franco Lippi Edit Mix)", "Housechart", 168_000),
                track("12", "Imael Angel - BAD TIMES", "fan", 168_500));
        List<String> artists = Collections.singletonList("Imael Angel");
        assertEquals("12", SongChoice.pick(results, artists, "BAD TIMES", 168_000).id);
        // Unless the wanted title names it.
        assertEquals("11", SongChoice.pick(results, artists, "BAD TIMES (Franco Lippi Edit Mix)", 168_000).id);
        assertEquals("13", SongChoice.pick(Collections.singletonList(track("13", "Malo 2.0 (Editorial)", "X", 1_000)),
                Collections.singletonList("X"), "Malo 2.0", 0).id);
    }

    @Test
    public void aGuestArtistCountsToo() {
        List<FoundTrack> results = Collections.singletonList(track("9", "Хочешь?", "Guest", 214_000));
        assertEquals("9", SongChoice.pick(results, ARTISTS, "Хочешь?", 214_000).id);
    }

    @Test
    public void theVersionMustBeNamed() {
        List<FoundTrack> results = Collections.singletonList(track("10", "Хочешь?", "Земфира", 214_000));
        assertNull(SongChoice.pick(results, ARTISTS, "Хочешь? (Live Version)", 214_000));
    }
}
