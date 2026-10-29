package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Songs Last.fm suggests for the seeds, each with a score, and the library songs left out.
 * <p>
 * Last.fm often knows an artist but not the song: for small artists (much of Russian SoundCloud) it answers
 * "similar songs" with an empty list. So the similar artists of the main artists and their best songs are always
 * asked as well, and more of them when the songs gave few candidates.
 */
final class ForYouCandidates {
    private static final int SIMILAR_PER_SEED = 30;
    /** Enough candidates from the songs: the playlist takes 40, many are not on SoundCloud or are cut per artist. */
    private static final int ENOUGH_FROM_SONGS = 160;
    /** Plenty of candidates from the songs: a few artists add variety. */
    private static final Expansion NARROW = new Expansion(8, 5, 2);
    /** Few candidates from the songs: the artists carry the playlist. */
    private static final Expansion WIDE = new Expansion(20, 8, 3);
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

    /** Where the names come from: Last.fm, or a stand-in in the tests. */
    interface Source {
        List<LastFm.Song> similarTracks(String artist, String title, int limit) throws Exception;

        List<LastFm.SimilarArtist> similarArtists(String artist, int limit) throws Exception;

        List<String> topTracks(String artist, int limit) throws Exception;
    }

    private static final Source LAST_FM = new Source() {
        @Override
        public List<LastFm.Song> similarTracks(String artist, String title, int limit) throws Exception {
            return LastFm.similarTracks(artist, title, limit);
        }

        @Override
        public List<LastFm.SimilarArtist> similarArtists(String artist, int limit) throws Exception {
            return LastFm.similarArtists(artist, limit);
        }

        @Override
        public List<String> topTracks(String artist, int limit) throws Exception {
            return LastFm.topTracks(artist, limit);
        }
    };

    /** How far the similar artists are followed. */
    private static final class Expansion {
        final int artists;
        final int similarPerArtist;
        final int songsPerArtist;

        Expansion(int artists, int similarPerArtist, int songsPerArtist) {
            this.artists = artists;
            this.similarPerArtist = similarPerArtist;
            this.songsPerArtist = songsPerArtist;
        }
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
    private final Source source;

    private int asked;
    private int answered;
    private int songsKnown;
    private int fromSongs;
    private String lastError;

    ForYouCandidates(Set<String> known) {
        this(known, LAST_FM);
    }

    ForYouCandidates(Set<String> known, Source source) {
        this.known = known;
        this.source = source;
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
        try {
            return collect(picked, progress);
        } catch (NeedsVpn ex) {
            return Status.NEEDS_VPN;
        }
    }

    private Status collect(List<LibrarySeeds.Seed> picked, RefreshProgress progress) throws Exception {
        Map<String, Double> artistWeights = new HashMap<>();
        Map<String, String> artistNames = new HashMap<>();
        for (int i = 0; i < picked.size(); i++) {
            LibrarySeeds.Seed seed = picked.get(i);
            if (i % 5 == 0) progress.say(text("Спрашиваю Last.fm: ", "Asking Last.fm: ") + (i + 1) + "/" + picked.size());
            artistWeights.merge(ForYouNames.artistKey(seed.artist), seed.weight, Double::sum);
            artistNames.putIfAbsent(ForYouNames.artistKey(seed.artist), seed.artist);
            List<LastFm.Song> similar = ask(() -> source.similarTracks(seed.artist, seed.title, SIMILAR_PER_SEED));
            if (similar == null) continue;
            if (!similar.isEmpty()) songsKnown++;
            for (LastFm.Song song : similar) {
                add(song.artist, song.title, seed.weight * song.match);
            }
        }
        // An empty list is an answer too: Last.fm knows the artist but not the song.
        if (answered == 0) return Status.NO_ANSWER;
        fromSongs = candidates.size();

        addSimilarArtistsOf(artistWeights, artistNames, progress,
                candidates.size() < ENOUGH_FROM_SONGS ? WIDE : NARROW);
        return Status.ANSWERED;
    }

    /** A request to Last.fm. */
    private interface Request<T> {
        T run() throws Exception;
    }

    /**
     * One request, counted. A lost request costs its own answer, not the run.
     *
     * @return The answer, or null if this request got none.
     * @throws NeedsVpn    Last.fm turned the Russian IP address away: no request will get through.
     * @throws IOException Any other Last.fm error (a bad key, the limit): the run stops with it.
     */
    private <T> T ask(Request<T> request) throws Exception {
        asked++;
        try {
            T answer = request.run();
            answered++;
            return answer;
        } catch (IOException ex) {
            String message = ex.getMessage();
            if (message != null && message.startsWith(RUSSIAN_IP_ERROR)) throw new NeedsVpn();
            if (message != null && message.startsWith("Last.fm error")) throw ex;
            lastError = String.valueOf(ex);
            return null;
        }
    }

    /** Last.fm turned the Russian IP address away. */
    private static final class NeedsVpn extends IOException {
    }

    /** Similar artists of the main artists and their best songs: more variety than similar tracks alone. */
    private void addSimilarArtistsOf(Map<String, Double> artistWeights, Map<String, String> artistNames,
                                     RefreshProgress progress, Expansion expansion) throws Exception {
        List<Map.Entry<String, Double>> topArtists = new ArrayList<>(artistWeights.entrySet());
        topArtists.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(expansion.artists, topArtists.size()); i++) {
            String artist = artistNames.get(topArtists.get(i).getKey());
            progress.say(text("Похожие исполнители: ", "Similar artists: ") + artist);
            List<LastFm.SimilarArtist> related = ask(() -> source.similarArtists(artist, expansion.similarPerArtist));
            if (related == null) continue;
            for (LastFm.SimilarArtist similar : related) {
                List<String> titles = ask(() -> source.topTracks(similar.name, expansion.songsPerArtist));
                if (titles == null) continue;
                for (int k = 0; k < titles.size(); k++) {
                    add(similar.name, titles.get(k), 0.6 * similar.match * (1 - 0.2 * k));
                }
            }
        }
    }

    /** What the requests gave, for the log: how many got an answer, how many songs Last.fm knew. */
    String summary() {
        return "Last.fm answered " + answered + " of " + asked + " requests, knew similar songs for " + songsKnown
                + ", " + fromSongs + " candidates from songs, " + candidates.size() + " with the artists"
                + (lastError == null ? "" : "; last failure: " + lastError);
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
