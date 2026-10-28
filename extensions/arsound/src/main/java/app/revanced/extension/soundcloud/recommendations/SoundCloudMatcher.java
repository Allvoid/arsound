package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.ClientProfiles;
import app.revanced.extension.soundcloud.local.BatchActivity;

/**
 * Looks the best candidates up on SoundCloud, a few at a time, and picks the tracks of the playlist: at most two per
 * artist, none disliked, no second upload of a song already picked.
 */
final class SoundCloudMatcher {
    private static final int PLAYLIST_SIZE = 40;
    private static final int PER_ARTIST = 2;
    private static final int MAX_LOOKUPS = 140;
    private static final int BATCH_SIZE = 8;
    private static final int POOL_THREADS = 4;
    /** This many failed searches, and no track found, mean SoundCloud is out of reach. */
    private static final int UNREACHABLE_FAILURES = 8;

    private final List<ForYouCandidates.Candidate> ranked;
    private final RefreshProgress progress;
    private final MatchCache cache = MatchCache.load();
    private final Set<String> disliked = new HashSet<>(TrackDislikes.getAll().keySet());
    /** Another upload of a disliked song has its own urn: it is told by the title and length. */
    private final List<SongRef> dislikedSongs = TrackDislikes.songs();

    private final List<String> entries = new ArrayList<>();
    private final Set<String> chosenKeys = new HashSet<>();
    private final Map<String, Integer> perArtist = new HashMap<>();
    /** Title and length of the songs already chosen: another upload of the same song is not taken again. */
    private final List<SongRef> chosenSongs = new ArrayList<>();
    private int index;
    private int lookups;
    private int failures;

    private SoundCloudMatcher(List<ForYouCandidates.Candidate> ranked, RefreshProgress progress) {
        this.ranked = ranked;
        this.progress = progress;
    }

    /**
     * Matches are remembered for two weeks.
     *
     * @return The track urns of the playlist, or null if SoundCloud could not be reached at all.
     */
    static List<String> match(List<ForYouCandidates.Candidate> ranked, RefreshProgress progress) throws Exception {
        return new SoundCloudMatcher(ranked, progress).run();
    }

    private List<String> run() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(POOL_THREADS);
        try {
            while (index < ranked.size() && entries.size() < PLAYLIST_SIZE && lookups < MAX_LOOKUPS) {
                List<ForYouCandidates.Candidate> batch = nextBatch();
                List<Future<MatchCache.Hit>> results = new ArrayList<>();
                for (ForYouCandidates.Candidate candidate : batch) {
                    MatchCache.Hit cached = cache.fresh(songKey(candidate));
                    if (cached != null) {
                        results.add(pool.submit(() -> cached));
                        continue;
                    }
                    lookups++;
                    results.add(pool.submit(() -> lookUp(candidate)));
                }
                for (int i = 0; i < batch.size(); i++) {
                    consider(batch.get(i), results.get(i));
                }
                progress.say(text("Ищу в SoundCloud: найдено ", "Searching SoundCloud: found ")
                        + entries.size() + "/" + PLAYLIST_SIZE);
                // Every search failing means SoundCloud is out of reach, not that the songs are missing.
                if (entries.isEmpty() && failures >= UNREACHABLE_FAILURES && failures == lookups) return null;
            }
        } finally {
            pool.shutdownNow();
        }
        cache.save(chosenKeys);
        return entries;
    }

    private static String songKey(ForYouCandidates.Candidate candidate) {
        return ForYouNames.songKey(candidate.artist, candidate.title);
    }

    private boolean artistIsFull(ForYouCandidates.Candidate candidate) {
        return perArtist.getOrDefault(ForYouNames.artistKey(candidate.artist), 0) >= PER_ARTIST;
    }

    /** The next few candidates whose artist still has room. */
    private List<ForYouCandidates.Candidate> nextBatch() {
        List<ForYouCandidates.Candidate> batch = new ArrayList<>();
        while (index < ranked.size() && batch.size() < BATCH_SIZE) {
            ForYouCandidates.Candidate candidate = ranked.get(index++);
            if (artistIsFull(candidate)) continue;
            batch.add(candidate);
        }
        return batch;
    }

    private static MatchCache.Hit lookUp(ForYouCandidates.Candidate candidate) throws Exception {
        ClientProfiles.FoundTrack found = BatchActivity.findOnSoundCloud(candidate.artist, candidate.title);
        return found == null ? MatchCache.Hit.notFound()
                : new MatchCache.Hit("soundcloud:tracks:" + found.id, found.title, found.duration);
    }

    /** Takes the answer for a candidate into the cache and, if the track fits, into the playlist. */
    private void consider(ForYouCandidates.Candidate candidate, Future<MatchCache.Hit> result) throws Exception {
        String key = songKey(candidate);
        MatchCache.Hit hit;
        try {
            hit = result.get(60, TimeUnit.SECONDS);
        } catch (Exception ex) {
            failures++;
            Logger.printInfo(() -> "For you: SoundCloud search failed for " + key + ": " + ex);
            return;
        }
        cache.put(key, hit);
        if (hit.urn.isEmpty() || disliked.contains(hit.urn) || entries.contains(hit.urn)) return;
        // The last batch may find more than the playlist takes.
        if (entries.size() >= PLAYLIST_SIZE) return;
        if (artistIsFull(candidate)) return;
        SongRef song = new SongRef(hit.title, hit.durationMs);
        if (song.isCopyOfAny(chosenSongs) || song.isCopyOfAny(dislikedSongs)) return;
        chosenSongs.add(song);
        perArtist.merge(ForYouNames.artistKey(candidate.artist), 1, Integer::sum);
        entries.add(hit.urn);
        chosenKeys.add(key);
    }
}
