package app.revanced.extension.soundcloud.recommendations;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.ClientProfiles;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.local.BatchActivity;

/**
 * Makes one batch of "My Wave": reads the taste (the playing track, the likes, the tracks heard to a good length),
 * asks the sources the user ticked about a few of those seeds, puts the offers in order ({@link WaveMixer}) and gives the
 * SoundCloud tracks to play. The seeds and sources are asked together. Blocking: call it off the main thread.
 */
final class WaveBuilder {
    private static final int SEEDS = 3;
    /** Names from Last.fm and Deezer are looked up on SoundCloud: this many per source and seed. */
    private static final int LOOKUPS = 8;
    private static final int POOL_THREADS = 4;
    /** How many questions to the sources run at once: every seed and source is asked together, not one after another. */
    private static final int ASK_THREADS = 6;
    /** How long the whole batch waits for the slowest source. */
    private static final long ASK_WAIT_SECONDS = 25;
    /** The likes are read again after this long; a wave started soon after the last one does not ask again. */
    private static final long LIKES_FRESH_MS = 10 * 60 * 1000L;
    private static List<String> cachedLikes;
    private static long likesReadAt;
    private static final String TRACKS = "soundcloud:tracks:";

    private final Random random = new Random();
    private final Set<String> alreadyQueued;

    /** @param alreadyQueued The urns this wave has queued: they never come again. */
    WaveBuilder(Collection<String> alreadyQueued) {
        this.alreadyQueued = new HashSet<>(alreadyQueued);
    }

    /**
     * @param playing The urn of the playing track, or null when the wave starts.
     * @return The urns of the next tracks ({@code soundcloud:tracks:ID}); empty if nothing was found.
     */
    List<String> build(String playing, int count) throws Exception {
        return build(playing, count, WaveSource.chosen(), SEEDS);
    }

    /**
     * The first tracks of a wave, fast: SoundCloud's related tracks of two seeds, a request each, asked together. The
     * slower sources (names looked up on SoundCloud one by one) join with the next batch, while these already play.
     * When SoundCloud's own source is off, the full batch is built.
     */
    List<String> buildQuick(int count) throws Exception {
        if (!WaveSource.SOUNDCLOUD.isOn()) return build(null, count);
        return build(null, count, java.util.Collections.singletonList(WaveSource.SOUNDCLOUD), 2);
    }

    private List<String> build(String playing, int count, List<WaveSource> sources, int seeds) throws Exception {
        WaveLedger ledger = WaveMemory.ledger();
        Set<String> disliked = new HashSet<>(TrackDislikes.getAll().keySet());
        // A skipped track rests a few days and then may come again; a disliked one never does.
        Set<String> never = new HashSet<>(ledger.resting(System.currentTimeMillis()));
        never.addAll(disliked);
        never.addAll(alreadyQueued);
        if (playing != null) never.add(playing);
        List<String> likes = liked();

        // The playing track weighs most, then the likes, then what was heard to a good length.
        Map<String, Integer> pool = new LinkedHashMap<>();
        if (playing != null && !ledger.isSkipped(playing) && !disliked.contains(playing)) pool.put(playing, 4);
        for (String urn : ledger.heardNewestFirst()) if (!never.contains(urn) && !pool.containsKey(urn)) pool.put(urn, 2);
        for (String urn : likes) if (!never.contains(urn)) pool.put(urn, 3);
        if (pool.isEmpty()) return new ArrayList<>();

        List<WaveMixer.Offer> offers = new ArrayList<>();
        ExecutorService askers = Executors.newFixedThreadPool(ASK_THREADS);
        try {
            List<Future<List<WaveMixer.Offer>>> answers = new ArrayList<>();
            for (String seed : WaveMixer.pickSeeds(pool, seeds, random)) {
                for (WaveSource source : sources) {
                    answers.add(askers.submit(() -> {
                        try {
                            return ask(source, seed);
                        } catch (Exception ex) {
                            Logger.printInfo(() -> "My Wave: " + source.key + " gave nothing for " + seed + ": " + ex);
                            return new ArrayList<>();
                        }
                    }));
                }
            }
            long until = System.currentTimeMillis() + ASK_WAIT_SECONDS * 1000;
            for (Future<List<WaveMixer.Offer>> answer : answers) {
                try {
                    // The order of the answers is kept, so the same answers always mix the same way.
                    offers.addAll(answer.get(Math.max(0, until - System.currentTimeMillis()), TimeUnit.MILLISECONDS));
                } catch (Exception ex) {
                    Logger.printInfo(() -> "My Wave: a source was too slow: " + ex);
                }
            }
        } finally {
            askers.shutdownNow();
        }
        List<String> mixed = new WaveMixer(never, likes, random).mix(offers, count * 2);
        // A track already in the queue under another urn kind, or a re-upload of a disliked song, is left out.
        List<SongRef> dislikedSongs = TrackDislikes.songs();
        List<String> result = new ArrayList<>();
        for (String urn : mixed) {
            if (result.size() >= count) break;
            SongRef song = KnownTracks.get(urn);
            if (song != null && song.isCopyOfAny(dislikedSongs)) continue;
            result.add(urn);
        }
        Logger.printInfo(() -> "My Wave: " + result.size() + " tracks from " + sources + ", seeds " + pool.size());
        return result;
    }

    /** What one source offers for one seed track. */
    private List<WaveMixer.Offer> ask(WaveSource source, String seed) throws Exception {
        List<WaveMixer.Offer> offers = new ArrayList<>();
        String id = seed.substring(seed.lastIndexOf(':') + 1);
        switch (source) {
            case SOUNDCLOUD:
                for (String urn : relatedOnSoundCloud(id)) offers.add(new WaveMixer.Offer(urn, 1.0));
                return offers;
            case LASTFM: {
                if (!LastFm.hasKey()) return offers;
                String[] name = nameOf(id);
                List<String[]> names = new ArrayList<>();
                for (LastFm.Song song : LastFm.similarTracks(name[0], name[1], LOOKUPS * 2)) {
                    names.add(new String[]{song.artist, song.title});
                }
                return lookUp(names, 1.0);
            }
            default: {
                String[] name = nameOf(id);
                List<String[]> names = new ArrayList<>();
                for (DeezerApi.Song song : DeezerApi.artistRadio(name[0], LOOKUPS * 2)) {
                    names.add(new String[]{song.artist, song.title});
                }
                return lookUp(names, 0.9);
            }
        }
    }

    /** The tracks SoundCloud calls related to the seed. */
    private static List<String> relatedOnSoundCloud(String id) throws Exception {
        String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks/" + id + "/related?limit=30");
        List<String> urns = new ArrayList<>();
        if (response[1] == null) return urns;
        JSONArray tracks = new JSONObject(response[1]).optJSONArray("collection");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            long related = tracks.getJSONObject(i).optLong("id", 0);
            if (related != 0) urns.add(TRACKS + related);
        }
        return urns;
    }

    /** The artist and the title of a track, for the sources that go by names. */
    private static String[] nameOf(String id) throws Exception {
        String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks/" + id);
        if (response[1] == null) throw new java.io.IOException("SoundCloud did not answer: HTTP " + response[0]);
        JSONObject track = new JSONObject(response[1]);
        JSONObject user = track.optJSONObject("user");
        return ForYouNames.artistAndTitle(track.optString("title"), user == null ? "" : user.optString("username"));
    }

    /** Looks names up on SoundCloud, a few at a time. */
    private static List<WaveMixer.Offer> lookUp(List<String[]> names, double score) {
        List<WaveMixer.Offer> offers = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(POOL_THREADS);
        try {
            List<Future<ClientProfiles.FoundTrack>> found = new ArrayList<>();
            for (String[] name : names.subList(0, Math.min(names.size(), LOOKUPS))) {
                found.add(pool.submit(() -> BatchActivity.findOnSoundCloud(name[0], name[1])));
            }
            for (Future<ClientProfiles.FoundTrack> future : found) {
                try {
                    ClientProfiles.FoundTrack track = future.get(30, TimeUnit.SECONDS);
                    if (track != null && !"BLOCK".equals(track.policy) && !"SNIP".equals(track.policy)) {
                        offers.add(new WaveMixer.Offer(TRACKS + track.id, score));
                    }
                } catch (Exception ex) {
                    Logger.printInfo(() -> "My Wave: a name was not found on SoundCloud: " + ex);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return offers;
    }

    /** The urns of the likes of the account, the newest first; empty if SoundCloud does not answer. */
    private static List<String> liked() {
        List<String> kept = freshLikes();
        if (kept != null) return kept;
        // Asked outside the lock: a request that hangs must not hold up the other builds.
        List<String> liked = new ArrayList<>();
        try {
            String[] me = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/me");
            if (me[1] == null) return keep(liked);
            long userId = new JSONObject(me[1]).getLong("id");
            String[] response = DownloadTrackPatch.apiGet(
                    "https://api-v2.soundcloud.com/users/" + userId + "/track_likes?limit=40");
            if (response[1] == null) return keep(liked);
            JSONArray items = new JSONObject(response[1]).optJSONArray("collection");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject track = items.getJSONObject(i).optJSONObject("track");
                if (track != null && track.optLong("id", 0) != 0) liked.add(TRACKS + track.getLong("id"));
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "My Wave: could not read the likes: " + ex);
        }
        return keep(liked);
    }

    private static synchronized List<String> freshLikes() {
        return cachedLikes != null && System.currentTimeMillis() - likesReadAt < LIKES_FRESH_MS ? cachedLikes : null;
    }

    /** Keeps the likes read; an empty answer (no network) is not kept, so the next wave asks again. */
    private static synchronized List<String> keep(List<String> liked) {
        if (!liked.isEmpty()) {
            cachedLikes = liked;
            likesReadAt = System.currentTimeMillis();
        }
        return liked;
    }
}
