package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.shared.Logger;

/** Songs Last.fm suggests for the seeds, each with a score, and the library songs left out. */
final class ForYouCandidates {
    private static final int SIMILAR_PER_SEED = 30;
    private static final int EXPANDED_ARTISTS = 8;
    /** Last.fm turns away Russian IP addresses with this error. */
    private static final String RUSSIAN_IP_ERROR = "Last.fm error 11";

    /** How asking Last.fm went. */
    enum Status {
        /** Candidates were found, or Last.fm answered and knew nothing. */
        ANSWERED,
        /** Last.fm turned the request away because of the Russian IP address. */
        NEEDS_VPN,
        /** Not one request got an answer. */
        NO_ANSWER
    }

    static final class Candidate {
        final String artist;
        final String title;
        double score;

        Candidate(String artist, String title) {
            this.artist = artist;
            this.title = title;
        }
    }

    private final Map<String, Candidate> candidates = new HashMap<>();
    /** Song keys of the library: not suggested again. */
    private final Set<String> known;

    ForYouCandidates(Set<String> known) {
        this.known = known;
    }

    private void add(String artist, String title, double score) {
        if (artist == null || title == null || artist.isEmpty() || title.isEmpty()) return;
        String key = ForYouNames.songKey(artist, title);
        if (known.contains(key)) return;
        Candidate candidate = candidates.get(key);
        if (candidate == null) candidates.put(key, candidate = new Candidate(artist, title));
        candidate.score += score;
    }

    /** Asks Last.fm about the seeds, then about the artists that weigh most. Blocks. */
    Status gather(List<LibrarySeeds.Seed> picked, RefreshProgress progress) throws Exception {
        Map<String, Double> artistWeights = new HashMap<>();
        Map<String, String> artistNames = new HashMap<>();
        int answered = 0;
        for (int i = 0; i < picked.size(); i++) {
            LibrarySeeds.Seed seed = picked.get(i);
            if (i % 5 == 0) progress.say(text("Спрашиваю Last.fm: ", "Asking Last.fm: ") + (i + 1) + "/" + picked.size());
            artistWeights.merge(ForYouNames.artistKey(seed.artist), seed.weight, Double::sum);
            artistNames.putIfAbsent(ForYouNames.artistKey(seed.artist), seed.artist);
            try {
                List<LastFm.Song> similar = LastFm.similarTracks(seed.artist, seed.title, SIMILAR_PER_SEED);
                if (!similar.isEmpty()) answered++;
                for (LastFm.Song song : similar) {
                    add(song.artist, song.title, seed.weight * song.match);
                }
            } catch (IOException ex) {
                if (ex.getMessage() != null && ex.getMessage().startsWith(RUSSIAN_IP_ERROR)) return Status.NEEDS_VPN;
                if (ex.getMessage() != null && ex.getMessage().startsWith("Last.fm error")) throw ex;
                Logger.printInfo(() -> "For you: Last.fm did not answer for " + seed.artist + " - " + seed.title + ": " + ex);
            }
        }
        if (answered == 0 && candidates.isEmpty()) return Status.NO_ANSWER;

        addSimilarArtistsOf(artistWeights, artistNames, progress);
        return Status.ANSWERED;
    }

    /** Similar artists of the main artists and their best songs: more variety than similar tracks alone. */
    private void addSimilarArtistsOf(Map<String, Double> artistWeights, Map<String, String> artistNames,
                                     RefreshProgress progress) throws Exception {
        List<Map.Entry<String, Double>> topArtists = new ArrayList<>(artistWeights.entrySet());
        topArtists.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(EXPANDED_ARTISTS, topArtists.size()); i++) {
            String artist = artistNames.get(topArtists.get(i).getKey());
            progress.say(text("Похожие исполнители: ", "Similar artists: ") + artist);
            List<LastFm.SimilarArtist> related;
            try {
                related = LastFm.similarArtists(artist, 5);
            } catch (IOException ex) {
                Logger.printInfo(() -> "For you: no similar artists for " + artist + ": " + ex);
                continue;
            }
            for (LastFm.SimilarArtist similar : related) {
                // One failed request costs one artist, not the rest of the list.
                try {
                    List<String> titles = LastFm.topTracks(similar.name, 2);
                    for (int k = 0; k < titles.size(); k++) {
                        add(similar.name, titles.get(k), 0.6 * similar.match * (1 - 0.3 * k));
                    }
                } catch (IOException ex) {
                    Logger.printInfo(() -> "For you: no top songs for " + similar.name + ": " + ex);
                }
            }
        }
    }

    /**
     * The candidates, best first. Songs of the last playlist step back, so each day brings new ones.
     *
     * @param previous The song keys of the last playlist.
     */
    List<Candidate> ranked(Set<String> previous) {
        for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
            if (previous.contains(entry.getKey())) entry.getValue().score *= 0.3;
        }
        List<Candidate> ranked = new ArrayList<>(candidates.values());
        ranked.sort((a, b) -> Double.compare(b.score, a.score));
        return ranked;
    }
}
