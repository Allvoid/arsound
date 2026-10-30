package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DuplicatePassTest {
    private static SongFingerprint song(String title, long duration) {
        return new SongRef(title, duration).fingerprint(false);
    }

    @Test
    public void firstCopyStaysAndLaterCopiesGo() {
        DuplicatePass pass = new DuplicatePass(urn -> false);
        assertTrue(pass.keeps(song("Song", 180_000)));
        assertFalse(pass.keeps(song("Song (Official Audio)", 181_000)));
        assertTrue(pass.keeps(song("Another", 180_000)));
        assertEquals(1, pass.removed());
    }

    @Test
    public void aSongWithoutTitleDataAlwaysStays() {
        DuplicatePass pass = new DuplicatePass(urn -> false);
        assertTrue(pass.keeps(null));
        assertTrue(pass.keeps(null));
        assertEquals(0, pass.removed());
    }

    @Test
    public void dislikedTracksAreCountedAsRemoved() {
        DuplicatePass pass = new DuplicatePass(urn -> "bad".equals(urn));
        assertTrue(pass.dislikes("bad"));
        assertFalse(pass.dislikes("good"));
        assertFalse(pass.dislikes(null));
        assertEquals(1, pass.removed());
    }

    @Test
    public void aSeededSongMakesItsCopiesGo() {
        DuplicatePass pass = new DuplicatePass(urn -> false);
        pass.keep(song("Playing now", 200_000));
        assertFalse(pass.keeps(song("Playing now", 200_500)));
        assertTrue(pass.keeps(song("Playing now", 230_000)));
    }

    @Test
    public void aTrackInTheListAlreadyIsNotAddedAgain() {
        DuplicatePass pass = new DuplicatePass(urn -> false);
        pass.keepTrack("soundcloud:tracks:1");
        assertTrue(pass.hasTrack("soundcloud:sounds:1"));
        assertFalse(pass.hasTrack("soundcloud:tracks:2"));
        assertFalse(pass.hasTrack(null));
        assertEquals(1, pass.removed());
    }

    @Test
    public void aPassHasNoMemoryOfEarlierPasses() {
        new DuplicatePass(urn -> false).keeps(song("Plain pass song", 100_000));
        assertTrue(new DuplicatePass(urn -> false).keeps(song("Plain pass song", 100_000)));
    }

    @Test
    public void mergedVersionsOfASongAreCopiesWhateverTheirLength() {
        DuplicatePass pass = new DuplicatePass(urn -> false);
        assertTrue(pass.keeps(new SongRef("Song", 180_000).fingerprint(true)));
        assertFalse(pass.keeps(new SongRef("Song (Sped Up)", 150_000).fingerprint(true)));
    }

    @Test
    public void separateVersionsStayApart() {
        DuplicatePass pass = new DuplicatePass(urn -> false);
        assertTrue(pass.keeps(new SongRef("Song", 180_000).fingerprint(false)));
        assertTrue(pass.keeps(new SongRef("Song (Sped Up)", 150_000).fingerprint(false)));
    }
}
