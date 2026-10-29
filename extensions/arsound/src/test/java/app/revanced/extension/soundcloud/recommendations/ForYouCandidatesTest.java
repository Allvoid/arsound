package app.revanced.extension.soundcloud.recommendations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public class ForYouCandidatesTest {
    /** Last.fm as it answers for small artists: the artists are known, their songs are not. */
    private static class ArtistsOnly implements ForYouCandidates.Source {
        int topTrackRequests;

        @Override
        public List<LastFm.Song> similarTracks(String artist, String title, int limit) throws Exception {
            return Collections.emptyList();
        }

        @Override
        public List<LastFm.SimilarArtist> similarArtists(String artist, int limit) {
            List<LastFm.SimilarArtist> artists = new ArrayList<>();
            for (int i = 0; i < limit; i++) artists.add(new LastFm.SimilarArtist(artist + " friend " + i, 0.9 - i * 0.1));
            return artists;
        }

        @Override
        public List<String> topTracks(String artist, int limit) {
            topTrackRequests++;
            List<String> titles = new ArrayList<>();
            for (int i = 0; i < limit; i++) titles.add(artist + " hit " + i);
            return titles;
        }
    }

    private static List<LibrarySeeds.Seed> seeds(int artists) {
        List<LibrarySeeds.Seed> seeds = new ArrayList<>();
        for (int i = 0; i < artists; i++) seeds.add(new LibrarySeeds.Seed("Artist " + i, "Song " + i, artists - i));
        return seeds;
    }

    @Test
    public void unknownSongsStillGiveTheSimilarArtistsSongs() throws Exception {
        ArtistsOnly source = new ArtistsOnly();
        ForYouCandidates candidates = new ForYouCandidates(new HashSet<>(), source);
        assertEquals(ForYouCandidates.Status.ANSWERED, candidates.gather(seeds(25), new RefreshProgress(null)));
        // Few candidates from the songs: 20 artists, 8 similar each, 3 songs of each.
        assertEquals(20 * 8, source.topTrackRequests);
        assertEquals(20 * 8 * 3, candidates.ranked(new HashSet<>()).size());
    }

    @Test
    public void noAnswerOnlyWhenNoRequestGotThrough() throws Exception {
        ForYouCandidates.Source offline = new ArtistsOnly() {
            @Override
            public List<LastFm.Song> similarTracks(String artist, String title, int limit) throws Exception {
                throw new IOException("timeout");
            }
        };
        ForYouCandidates candidates = new ForYouCandidates(new HashSet<>(), offline);
        assertEquals(ForYouCandidates.Status.NO_ANSWER, candidates.gather(seeds(3), new RefreshProgress(null)));
        assertTrue(candidates.summary().contains("answered 0 of 3"));
    }

    @Test
    public void theRussianAddressErrorAsksForVpn() throws Exception {
        ForYouCandidates.Source refused = new ArtistsOnly() {
            @Override
            public List<LastFm.Song> similarTracks(String artist, String title, int limit) throws Exception {
                throw new IOException("Last.fm error 11: Service Offline");
            }
        };
        ForYouCandidates candidates = new ForYouCandidates(new HashSet<>(), refused);
        assertEquals(ForYouCandidates.Status.NEEDS_VPN, candidates.gather(seeds(3), new RefreshProgress(null)));
    }

    @Test
    public void songsOfTheLibraryAreLeftOut() throws Exception {
        HashSet<String> known = new HashSet<>();
        known.add(ForYouNames.songKey("Artist 0 friend 0", "Artist 0 friend 0 hit 0"));
        ForYouCandidates candidates = new ForYouCandidates(known, new ArtistsOnly());
        candidates.gather(seeds(1), new RefreshProgress(null));
        for (ForYouCandidates.Candidate candidate : candidates.ranked(new HashSet<>())) {
            assertFalse(candidate.title.equals("Artist 0 friend 0 hit 0"));
        }
    }
}
