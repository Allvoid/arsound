package app.revanced.extension.soundcloud.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public class TrackQueryTest {
    private static void assertVariant(TrackQuery.Variant variant, String artist, String track) {
        assertEquals(artist, variant.artist);
        assertEquals(track, variant.track);
    }

    @Test
    public void artistAndTitleInTheTitleBeatTheUploader() {
        TrackQuery query = TrackQuery.of("Kino - Gruppa Krovi [Official Audio]", "Some Channel", 280_000);
        assertVariant(query.variants.get(0), "Kino", "Gruppa Krovi");
    }

    @Test
    public void sideThatLooksLikeTheUploaderIsTheArtist() {
        TrackQuery query = TrackQuery.of("Song Name - Singer", "Singer", 200_000);
        assertVariant(query.variants.get(0), "Singer", "Song Name");
        assertVariant(query.variants.get(1), "Song Name", "Singer");
    }

    @Test
    public void titleWithoutSeparatorUsesTheUploader() {
        TrackQuery query = TrackQuery.of("Song Name", "Singer - Topic", 200_000);
        assertEquals(1, query.variants.size());
        assertVariant(query.variants.get(0), "Singer", "Song Name");
    }

    @Test
    public void duoArtistAlsoGetsItsFirstName() {
        TrackQuery query = TrackQuery.of("A & B - Song", "x", 100_000);
        boolean firstNameAlone = false;
        for (TrackQuery.Variant variant : query.variants) {
            if (variant.artist.equals("A") && variant.track.equals("Song")) firstNameAlone = true;
        }
        assertTrue(firstNameAlone);
    }

    @Test
    public void bracketsOfTheSongNameGetTheirOwnGuess() {
        TrackQuery query = TrackQuery.of("Song (Part 2)", "Singer", 100_000);
        assertVariant(query.variants.get(0), "Singer", "Song");
        assertVariant(query.variants.get(1), "Singer", "Song (Part 2)");
    }

    @Test
    public void neverMoreThanFourGuesses() {
        TrackQuery query = TrackQuery.of("A & B - C & D - E (Part 2)", "A & B", 100_000);
        assertTrue(query.variants.size() <= 4);
    }

    @Test
    public void emptyTitleHasNoGuesses() {
        assertTrue(TrackQuery.of("", "Singer", 100_000).variants.isEmpty());
        assertTrue(TrackQuery.of(null, null, 100_000).variants.isEmpty());
    }

    @Test
    public void versionTagFollowsTheTitle() {
        assertTrue(TrackQuery.of("Song (Remix)", "Singer", 1).versionTagged);
        assertTrue(TrackQuery.of("Song sped up", "Singer", 1).versionTagged);
        assertFalse(TrackQuery.of("Song", "Singer", 1).versionTagged);
    }

    @Test
    public void artistNamesCoverGuessUploaderAndGuests() {
        TrackQuery query = TrackQuery.of("Singer - Song (feat. Guest)", "Singer Official", 1);
        assertEquals(Arrays.asList("Singer", "Guest"), query.artistNames(query.variants.get(0)));
    }

    @Test
    public void signatureChangesWithTitleUploaderAndSeconds() {
        String base = TrackQuery.of("Song", "Singer", 200_000).signature;
        assertEquals(base, TrackQuery.of("Song", "Singer", 200_500).signature);
        assertFalse(base.equals(TrackQuery.of("Song 2", "Singer", 200_000).signature));
        assertFalse(base.equals(TrackQuery.of("Song", "Other", 200_000).signature));
        assertFalse(base.equals(TrackQuery.of("Song", "Singer", 201_000).signature));
    }

    @Test
    public void signatureHashDoesNotDrift() {
        // Answers stored on phones are keyed by this: the arithmetic of the hash must stay as it is.
        assertEquals("af63dc4c8601ec8c", TextMatch.fnv1aHex("a"));
        assertEquals("cbf29ce484222325", TextMatch.fnv1aHex(""));
    }
}
