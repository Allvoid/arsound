package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.ClientProfiles;
import app.revanced.extension.soundcloud.local.BatchActivity;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;
import app.revanced.extension.soundcloud.local.SavedPlaylist;
import app.revanced.extension.soundcloud.player.ListeningStats;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * The "For you" playlist: songs picked from what you like, by Last.fm.
 * <p>
 * Seeds are the liked tracks, the tracks of library playlists, the imported files and the most played
 * tracks, read from the phone (SoundCloud's own database and Arsound's files). For each seed Last.fm
 * names songs people listen to together with it; for the main artists also similar artists and their
 * best songs. Songs already in the library and disliked ones are left out, at most two per artist.
 * The rest is looked up on SoundCloud and put into a private SoundCloud playlist, which stays empty on
 * the server: like "Imported", its tracks are added on this phone. Refreshed once a day and by hand.
 */
public final class ForYou {
    public static final String ENABLED = "for_you_enabled";
    private static final String PREFERENCES_NAME = "arsound_for_you";
    private static final String PLAYLIST_URN = "playlist_urn";
    /** The last refresh that filled the playlist; the daily one waits for it. */
    private static final String LAST_REFRESH = "last_refresh";
    private static final String LAST_ATTEMPT = "last_attempt";
    private static final String LAST_STATUS = "last_status";
    private static final String MATCHES = "matches";
    private static final String PREVIOUS = "previous";

    private static final long REFRESH_EVERY_MS = TimeUnit.HOURS.toMillis(20);
    private static final long MATCH_KEPT_MS = TimeUnit.DAYS.toMillis(14);
    private static final int MAX_SEEDS = 60;
    private static final int SEEDS_PER_ARTIST = 4;
    private static final int SIMILAR_PER_SEED = 30;
    private static final int EXPANDED_ARTISTS = 8;
    private static final int PLAYLIST_SIZE = 40;
    private static final int PER_ARTIST = 2;
    private static final int MAX_LOOKUPS = 140;

    private static volatile boolean running;

    private ForYou() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, true);
    }

    public static String title() {
        return "ru".equals(Locale.getDefault().getLanguage()) ? "Для вас" : "For you";
    }

    private static final Set<String> KNOWN_TITLES = new HashSet<>(java.util.Arrays.asList("Для вас", "For you"));

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** The playlist urn, or null before the first refresh. */
    public static String getUrn() {
        SharedPreferences preferences = preferences();
        return preferences == null ? null : preferences.getString(PLAYLIST_URN, null);
    }

    public static boolean isRunning() {
        return running;
    }

    /** What the last refresh did, for the settings screen. */
    public static String status() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return "";
        String status = preferences.getString(LAST_STATUS, "");
        long time = preferences.getLong(LAST_ATTEMPT, 0);
        if (time == 0) return status;
        String when = android.text.format.DateFormat.getDateFormat(Utils.getContext()).format(new java.util.Date(time))
                + " " + android.text.format.DateFormat.getTimeFormat(Utils.getContext()).format(new java.util.Date(time));
        return status.isEmpty() ? when : when + " · " + status;
    }

    private static boolean russian() {
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    private static String text(String russian, String english) {
        return russian() ? russian : english;
    }

    /** Called when the app starts (from {@link SavedPlaylist}): refreshes once a day, in the background. */
    public static void onAppStart() {
        if (!isEnabled() || !LastFm.hasKey()) return;
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        if (System.currentTimeMillis() - preferences.getLong(LAST_REFRESH, 0) < REFRESH_EVERY_MS) return;
        Utils.runOnBackgroundThread(() -> {
            try {
                // After the saved playlist check, which shares the start with this.
                Thread.sleep(20_000);
                // On a Russian IP nothing can be fetched; the next start tries again.
                if ("RU".equals(app.revanced.extension.soundcloud.network.RegionGuard.lastCountry())) return;
                refresh(null);
            } catch (Exception ex) {
                Logger.printException(() -> "For you: refresh on start failed", ex);
            }
        });
    }

    /** Progress and the result of a refresh, on the main thread. May be null. */
    public interface Listener {
        void onProgress(String message);
    }

    /** Builds the playlist again. Blocks: call it off the main thread. */
    public static void refresh(Listener listener) {
        if (running) return;
        running = true;
        String result;
        boolean filled = false;
        try {
            result = build(listener);
            filled = result.startsWith(FILLED);
            if (filled) result = result.substring(FILLED.length());
        } catch (Exception ex) {
            Logger.printException(() -> "For you: refresh failed", ex);
            result = text("Ошибка: ", "Error: ") + ex.getMessage();
        } finally {
            running = false;
        }
        String status = result;
        SharedPreferences preferences = preferences();
        if (preferences != null) {
            SharedPreferences.Editor editor = preferences.edit()
                    .putLong(LAST_ATTEMPT, System.currentTimeMillis()).putString(LAST_STATUS, status);
            if (filled) editor.putLong(LAST_REFRESH, System.currentTimeMillis());
            editor.apply();
        }
        Logger.printInfo(() -> "For you: " + status);
        report(listener, status);
    }

    private static void report(Listener listener, String message) {
        if (listener != null) Utils.runOnMainThread(() -> listener.onProgress(message));
    }

    // region Seeds

    static final class Seed {
        final String artist;
        final String title;
        double weight;

        Seed(String artist, String title, double weight) {
            this.artist = artist;
            this.title = title;
            this.weight = weight;
        }
    }

    /** Artist and title of a SoundCloud upload: "Artist - Title" uploads name the artist in the title. */
    static String[] artistAndTitle(String title, String uploader) {
        if (title == null) return null;
        for (String dash : new String[]{" - ", " – ", " — "}) {
            int index = title.indexOf(dash);
            if (index > 0 && index < title.length() - dash.length()) {
                return new String[]{title.substring(0, index).trim(), title.substring(index + dash.length()).trim()};
            }
        }
        if (uploader == null || uploader.isEmpty()) return null;
        return new String[]{uploader.trim(), title.trim()};
    }

    static String artistKey(String artist) {
        String value = artist == null ? "" : artist.toLowerCase(Locale.ROOT);
        // The first of several artists: "A, B", "A & B", "A feat. B".
        value = value.split("\\s*(,|&|\\bfeat\\.?|\\bft\\.?|\\bx\\b|\\bи\\b)\\s*")[0];
        return value.replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    static String songKey(String artist, String title) {
        return artistKey(artist) + "|" + DuplicateFilter.songKey(title);
    }

    private static SQLiteDatabase open(Context context, String name) {
        File path = context.getDatabasePath(name);
        if (!path.isFile()) return null;
        try {
            return SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Exception ex) {
            Logger.printException(() -> "For you: could not open " + name, ex);
            return null;
        }
    }

    private static List<String> strings(SQLiteDatabase database, String query) {
        List<String> values = new ArrayList<>();
        if (database == null) return values;
        try (Cursor cursor = database.rawQuery(query, null)) {
            while (cursor.moveToNext()) values.add(cursor.getString(0));
        } catch (Exception ex) {
            Logger.printException(() -> "For you: query failed: " + query, ex);
        }
        return values;
    }

    /** The library as seeds. {@code known} gets the key of every song in it, seed or not. */
    private static List<Seed> collectSeeds(Context context, Set<String> known) {
        Map<String, Double> weights = new LinkedHashMap<>();
        SQLiteDatabase collections = open(context, "collections.db");
        SQLiteDatabase core = open(context, "core.db");
        List<String> playlists = new ArrayList<>();
        try {
            List<String> likes = strings(collections, "SELECT urn FROM likes WHERE urn LIKE 'soundcloud:tracks:%' "
                    + "AND removedAt IS NULL ORDER BY createdAt DESC");
            for (int i = 0; i < likes.size(); i++) weights.merge(likes.get(i), i < 50 ? 1.0 : 0.6, Math::max);

            playlists.addAll(strings(collections, "SELECT target_urn FROM posts WHERE target_urn LIKE 'soundcloud:playlists:%'"));
            playlists.addAll(strings(collections, "SELECT urn FROM likes WHERE urn LIKE 'soundcloud:playlists:%' AND removedAt IS NULL"));
            playlists.remove(getUrn());
            String in = quoted(playlists);
            if (!in.isEmpty()) {
                for (String urn : strings(core, "SELECT trackUrn FROM PlaylistTrackJoin WHERE removedAt IS NULL AND playlistUrn IN (" + in + ")")) {
                    weights.merge(urn, 0.5, Math::max);
                }
            }

            // Tracks added on this phone to library playlists.
            List<String> files = new ArrayList<>();
            for (String playlist : playlists) {
                for (String entry : LocalAdditions.getEntries(playlist)) {
                    if (entry.startsWith("soundcloud:tracks:")) weights.merge(entry, 0.5, Math::max);
                    else if (entry.startsWith("file:")) files.add(entry.substring("file:".length()));
                }
            }

            List<Seed> seeds = new ArrayList<>();
            resolveTitles(core, weights, seeds, known);

            for (LocalMusic.Track track : LocalMusic.getTracks(context)) {
                addSeed(seeds, known, track.artist, track.title, 0.6);
            }
            for (String path : files) {
                LocalMusic.Track track = LocalMusic.readTrack(new File(path));
                if (track != null) addSeed(seeds, known, track.artist, track.title, 0.5);
            }

            // The most played tracks of the last three months count more.
            if (ListeningStats.isEnabled()) {
                ListeningStats.Summary summary = ListeningStats.summarize(
                        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90), 40);
                for (ListeningStats.Entry entry : summary.topTracks) {
                    String[] names = artistAndTitle(entry.title, entry.artist);
                    if (names != null && entry.plays > 0) {
                        addSeed(seeds, known, names[0], names[1], Math.min(1.5, 0.4 + 0.15 * entry.plays));
                    }
                }
            }
            return seeds;
        } finally {
            if (collections != null) collections.close();
            if (core != null) core.close();
        }
    }

    private static void addSeed(List<Seed> seeds, Set<String> known, String artist, String title, double weight) {
        if (artist == null || title == null || artist.trim().isEmpty() || title.trim().isEmpty()) return;
        String key = songKey(artist, title);
        known.add(key);
        for (Seed seed : seeds) {
            if (songKey(seed.artist, seed.title).equals(key)) {
                seed.weight = Math.max(seed.weight, weight) + 0.2;
                return;
            }
        }
        seeds.add(new Seed(artist.trim(), title.trim(), weight));
    }

    private static void resolveTitles(SQLiteDatabase core, Map<String, Double> weights, List<Seed> seeds, Set<String> known) {
        if (core == null || weights.isEmpty()) return;
        List<String> urns = new ArrayList<>(weights.keySet());
        for (int start = 0; start < urns.size(); start += 400) {
            List<String> part = urns.subList(start, Math.min(urns.size(), start + 400));
            String query = "SELECT t.urn, t.title, u.username FROM Tracks t "
                    + "LEFT JOIN TrackUserJoin j ON j.trackUrn = t.urn LEFT JOIN Users u ON u.urn = j.userUrn "
                    + "WHERE t.urn IN (" + quoted(part) + ")";
            try (Cursor cursor = core.rawQuery(query, null)) {
                while (cursor.moveToNext()) {
                    String[] names = artistAndTitle(cursor.getString(1), cursor.getString(2));
                    if (names != null) addSeed(seeds, known, names[0], names[1], weights.get(cursor.getString(0)));
                }
            } catch (Exception ex) {
                Logger.printException(() -> "For you: could not read track titles", ex);
            }
        }
    }

    private static String quoted(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null) continue;
            if (builder.length() > 0) builder.append(',');
            builder.append('\'').append(value.replace("'", "''")).append('\'');
        }
        return builder.toString();
    }

    /** The heaviest seeds, a few per artist so one artist does not fill the list. */
    private static List<Seed> pickSeeds(List<Seed> seeds) {
        List<Seed> sorted = new ArrayList<>(seeds);
        // Equal weights: a different mix each day.
        Collections.shuffle(sorted);
        sorted.sort((a, b) -> Double.compare(b.weight, a.weight));
        Map<String, Integer> perArtist = new HashMap<>();
        List<Seed> picked = new ArrayList<>();
        for (Seed seed : sorted) {
            String artist = artistKey(seed.artist);
            int count = perArtist.getOrDefault(artist, 0);
            if (count >= SEEDS_PER_ARTIST) continue;
            perArtist.put(artist, count + 1);
            picked.add(seed);
            if (picked.size() == MAX_SEEDS) break;
        }
        return picked;
    }

    // endregion

    // region Candidates

    static final class Candidate {
        final String artist;
        final String title;
        double score;

        Candidate(String artist, String title) {
            this.artist = artist;
            this.title = title;
        }
    }

    private static void addCandidate(Map<String, Candidate> candidates, Set<String> known, String artist, String title, double score) {
        if (artist == null || title == null || artist.isEmpty() || title.isEmpty()) return;
        String key = songKey(artist, title);
        if (known.contains(key)) return;
        Candidate candidate = candidates.get(key);
        if (candidate == null) candidates.put(key, candidate = new Candidate(artist, title));
        candidate.score += score;
    }

    // endregion

    /** Marks a result that filled the playlist. */
    private static final String FILLED = "[filled]";

    private static String needsVpn() {
        return text("Нужен VPN: Last.fm и SoundCloud не работают с российского IP",
                "Needs a VPN: Last.fm and SoundCloud do not serve Russian IP addresses");
    }

    private static String build(Listener listener) throws Exception {
        Context context = Utils.getContext();
        if (context == null) throw new IllegalStateException("No context");
        if (!LastFm.hasKey()) return text("Нет ключа Last.fm в сборке", "No Last.fm key in this build");
        if ("RU".equals(app.revanced.extension.soundcloud.network.RegionGuard.lastCountry())) return needsVpn();

        report(listener, text("Собираю вашу библиотеку…", "Reading your library…"));
        Set<String> known = new HashSet<>();
        List<Seed> seeds = collectSeeds(context, known);
        if (seeds.isEmpty()) return text("В библиотеке нет треков, от которых можно оттолкнуться", "No tracks in the library to start from");
        List<Seed> picked = pickSeeds(seeds);
        Logger.printInfo(() -> "For you: " + seeds.size() + " seeds, asking about " + picked.size());

        Map<String, Candidate> candidates = new HashMap<>();
        Map<String, Double> artistWeights = new HashMap<>();
        Map<String, String> artistNames = new HashMap<>();
        int answered = 0;
        for (int i = 0; i < picked.size(); i++) {
            Seed seed = picked.get(i);
            if (i % 5 == 0) report(listener, text("Спрашиваю Last.fm: ", "Asking Last.fm: ") + (i + 1) + "/" + picked.size());
            artistWeights.merge(artistKey(seed.artist), seed.weight, Double::sum);
            artistNames.putIfAbsent(artistKey(seed.artist), seed.artist);
            try {
                List<LastFm.Song> similar = LastFm.similarTracks(seed.artist, seed.title, SIMILAR_PER_SEED);
                if (!similar.isEmpty()) answered++;
                for (LastFm.Song song : similar) {
                    addCandidate(candidates, known, song.artist, song.title, seed.weight * song.match);
                }
            } catch (java.io.IOException ex) {
                // Error 11: Last.fm turns away Russian IP addresses.
                if (ex.getMessage() != null && ex.getMessage().startsWith("Last.fm error 11")) return needsVpn();
                if (ex.getMessage() != null && ex.getMessage().startsWith("Last.fm error")) throw ex;
                Logger.printInfo(() -> "For you: Last.fm did not answer for " + seed.artist + " - " + seed.title + ": " + ex);
            }
        }
        if (answered == 0 && candidates.isEmpty()) {
            return text("Last.fm не ответил — проверьте интернет", "Last.fm did not answer, check the connection");
        }

        // Similar artists of the main artists and their best songs: more variety than similar tracks alone.
        List<Map.Entry<String, Double>> topArtists = new ArrayList<>(artistWeights.entrySet());
        topArtists.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (int i = 0; i < Math.min(EXPANDED_ARTISTS, topArtists.size()); i++) {
            String artist = artistNames.get(topArtists.get(i).getKey());
            report(listener, text("Похожие исполнители: ", "Similar artists: ") + artist);
            try {
                for (LastFm.Song similar : LastFm.similarArtists(artist, 5)) {
                    List<String> titles = LastFm.topTracks(similar.artist, 2);
                    for (int k = 0; k < titles.size(); k++) {
                        addCandidate(candidates, known, similar.artist, titles.get(k), 0.6 * similar.match * (1 - 0.3 * k));
                    }
                }
            } catch (java.io.IOException ex) {
                Logger.printInfo(() -> "For you: no similar artists for " + artist + ": " + ex);
            }
        }

        // Songs of the last playlist step back, so each day brings new ones.
        SharedPreferences preferences = preferences();
        Set<String> previous = preferences == null ? Collections.emptySet()
                : preferences.getStringSet(PREVIOUS, Collections.emptySet());
        for (Map.Entry<String, Candidate> entry : candidates.entrySet()) {
            if (previous.contains(entry.getKey())) entry.getValue().score *= 0.3;
        }
        List<Candidate> ranked = new ArrayList<>(candidates.values());
        ranked.sort((a, b) -> Double.compare(b.score, a.score));
        Logger.printInfo(() -> "For you: " + ranked.size() + " candidates");

        List<String> entries = match(ranked, listener, previous);
        if (entries == null) {
            return text("SoundCloud недоступен (российский IP или нет сети) — плейлист не изменён",
                    "SoundCloud is not reachable (Russian IP or no network), playlist unchanged");
        }
        if (entries.isEmpty()) return text("Ничего нового не нашлось в SoundCloud", "Nothing new found on SoundCloud");

        report(listener, text("Обновляю плейлист…", "Updating the playlist…"));
        String urn = ensurePlaylist();
        if (urn == null) {
            return text("Не удалось создать плейлист «" + title() + "» в SoundCloud", "Could not create the \"" + title() + "\" playlist on SoundCloud");
        }
        LocalAdditions.setEntries(urn, entries);
        Utils.runOnMainThread(() -> LocalAdditions.notifyPlaylistChanged(urn));
        return FILLED + text("в плейлисте " + entries.size() + " треков", entries.size() + " tracks in the playlist");
    }

    /**
     * Looks the best candidates up on SoundCloud, a few at a time; matches are remembered for two weeks.
     * Null if SoundCloud could not be reached at all.
     */
    private static List<String> match(List<Candidate> ranked, Listener listener, Set<String> previous) throws Exception {
        SharedPreferences preferences = preferences();
        JSONObject cache;
        try {
            cache = new JSONObject(preferences == null ? "{}" : preferences.getString(MATCHES, "{}"));
        } catch (Exception ex) {
            cache = new JSONObject();
        }
        long now = System.currentTimeMillis();
        Set<String> disliked = new HashSet<>(TrackDislikes.getAll().keySet());

        List<String> entries = new ArrayList<>();
        Set<String> chosenKeys = new HashSet<>();
        Map<String, Integer> perArtist = new HashMap<>();
        int lookups = 0;
        int failures = 0;
        int index = 0;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            while (index < ranked.size() && entries.size() < PLAYLIST_SIZE && lookups < MAX_LOOKUPS) {
                // The next few candidates whose artist still has room.
                List<Candidate> batch = new ArrayList<>();
                while (index < ranked.size() && batch.size() < 8) {
                    Candidate candidate = ranked.get(index++);
                    if (perArtist.getOrDefault(artistKey(candidate.artist), 0) >= PER_ARTIST) continue;
                    batch.add(candidate);
                }
                List<Future<String>> results = new ArrayList<>();
                for (Candidate candidate : batch) {
                    String key = songKey(candidate.artist, candidate.title);
                    JSONObject cached = cache.optJSONObject(key);
                    if (cached != null && now - cached.optLong("time") < MATCH_KEPT_MS) {
                        String urn = cached.optString("urn");
                        results.add(pool.submit(() -> urn));
                        continue;
                    }
                    lookups++;
                    results.add(pool.submit(() -> {
                        ClientProfiles.FoundTrack found = BatchActivity.findOnSoundCloud(candidate.artist, candidate.title);
                        return found == null ? "" : "soundcloud:tracks:" + found.id;
                    }));
                }
                for (int i = 0; i < batch.size(); i++) {
                    Candidate candidate = batch.get(i);
                    String key = songKey(candidate.artist, candidate.title);
                    String urn;
                    try {
                        urn = results.get(i).get(60, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        failures++;
                        Logger.printInfo(() -> "For you: SoundCloud search failed for " + key + ": " + ex);
                        continue;
                    }
                    cache.put(key, new JSONObject().put("urn", urn).put("time", now));
                    if (urn.isEmpty() || disliked.contains(urn) || entries.contains(urn)) continue;
                    // The last batch may find more than the playlist takes.
                    if (entries.size() >= PLAYLIST_SIZE) continue;
                    if (perArtist.getOrDefault(artistKey(candidate.artist), 0) >= PER_ARTIST) continue;
                    perArtist.merge(artistKey(candidate.artist), 1, Integer::sum);
                    entries.add(urn);
                    chosenKeys.add(key);
                }
                report(listener, text("Ищу в SoundCloud: найдено ", "Searching SoundCloud: found ")
                        + entries.size() + "/" + PLAYLIST_SIZE);
                // Every search failing means SoundCloud is out of reach, not that the songs are missing.
                if (entries.isEmpty() && failures >= 8 && failures == lookups) return null;
            }
        } finally {
            pool.shutdownNow();
        }

        // Old matches are dropped so the store does not grow for ever.
        JSONObject kept = new JSONObject();
        java.util.Iterator<String> keys = cache.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject value = cache.optJSONObject(key);
            if (value != null && now - value.optLong("time") < MATCH_KEPT_MS) kept.put(key, value);
        }
        if (preferences != null) {
            preferences.edit().putString(MATCHES, kept.toString()).putStringSet(PREVIOUS, chosenKeys).apply();
        }
        return entries;
    }

    /** The playlist on SoundCloud: the stored one, an existing empty one with the title, or a new one. */
    private static String ensurePlaylist() throws Exception {
        SharedPreferences preferences = preferences();
        if (preferences == null) return null;
        String urn = preferences.getString(PLAYLIST_URN, null);
        if (urn != null) {
            String id = urn.substring(urn.lastIndexOf(':') + 1);
            String[] response = app.revanced.extension.soundcloud.download.DownloadTrackPatch
                    .apiGet("https://api-v2.soundcloud.com/playlists/" + id);
            // Only a definite "not found" means it was deleted; network errors keep the playlist.
            if (!"404".equals(response[0])) return urn;
            Logger.printInfo(() -> "For you: the playlist was deleted, creating it again");
        }
        // After a reinstall the stored urn is gone, but the playlist still exists.
        for (Object item : app.revanced.extension.soundcloud.offline.PlaylistPreloader.libraryItems("LOCAL_ONLY")) {
            try {
                String title = String.valueOf(item.getClass().getMethod("getTitle").invoke(item));
                int tracks = (Integer) item.getClass().getMethod("getTracksCount").invoke(item);
                if (tracks == 0 && KNOWN_TITLES.contains(title)) {
                    String found = String.valueOf(item.getClass().getMethod("getUrn").invoke(item));
                    preferences.edit().putString(PLAYLIST_URN, found).apply();
                    return found;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "For you: could not read a library playlist", ex);
            }
        }

        Object operations = SavedPlaylist.getPlaylistOperations();
        if (operations == null) return null;
        Object single = operations.getClass()
                .getMethod("createNewPlaylist", String.class, boolean.class, List.class)
                .invoke(operations, title(), false, Collections.emptyList());
        Object result = Rx.blockingFirst(single, 30, TimeUnit.SECONDS);
        if (result == null || !result.getClass().getName().endsWith("PlaylistCreationResult$Success")) {
            Logger.printInfo(() -> "For you: could not create the playlist: " + result);
            return null;
        }
        Object playlist = result.getClass().getMethod("getPlaylist").invoke(result);
        String created = String.valueOf(playlist.getClass().getMethod("getUrn").invoke(playlist));
        preferences.edit().putString(PLAYLIST_URN, created).apply();
        Logger.printInfo(() -> "For you: created the playlist " + created);
        return created;
    }
}
