package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;
import app.revanced.extension.soundcloud.player.ListeningStats;

/**
 * The songs "For you" starts from, read from the phone: liked tracks, the tracks of library playlists, the imported
 * files and the most played tracks. Also remembers the key of every song of the library, seed or not, so the
 * recommendations leave them out.
 */
final class LibrarySeeds {
    private static final int MAX_SEEDS = 60;
    private static final int SEEDS_PER_ARTIST = 4;

    /** A song of the library and how much it counts. */
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

    /** By song key, so a song met again is found at once and its key is computed once. */
    private final Map<String, Seed> seeds = new LinkedHashMap<>();
    private final Set<String> known = new HashSet<>();

    private LibrarySeeds() {
    }

    /** Reads the library. Blocks. */
    static LibrarySeeds read(Context context) {
        LibrarySeeds library = new LibrarySeeds();
        library.collect(context);
        return library;
    }

    List<Seed> all() {
        return new ArrayList<>(seeds.values());
    }

    /** The key of every song in the library. */
    Set<String> knownKeys() {
        return known;
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

    private void collect(Context context) {
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
            playlists.remove(ForYouPrefs.playlistUrn());
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

            addTitlesOf(core, weights);

            for (LocalMusic.Track track : LocalMusic.getTracks(context)) {
                add(track.artist, track.title, 0.6);
            }
            for (String path : files) {
                LocalMusic.Track track = LocalMusic.readTrack(new File(path));
                if (track != null) add(track.artist, track.title, 0.5);
            }

            // The most played tracks of the last three months count more.
            if (ListeningStats.isEnabled()) {
                ListeningStats.Summary summary = ListeningStats.summarize(
                        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90), 40);
                for (ListeningStats.Entry entry : summary.topTracks) {
                    // The statistics already took the artist out of the title where it was there.
                    String[] names = entry.artist != null && !entry.artist.isEmpty()
                            ? new String[]{entry.artist, entry.title} : ForYouNames.artistAndTitle(entry.title, null);
                    if (names != null && entry.plays > 0) {
                        add(names[0], names[1], Math.min(1.5, 0.4 + 0.15 * entry.plays));
                    }
                }
            }
        } finally {
            if (collections != null) collections.close();
            if (core != null) core.close();
        }
    }

    private void add(String artist, String title, double weight) {
        if (artist == null || title == null || artist.trim().isEmpty() || title.trim().isEmpty()) return;
        String key = ForYouNames.songKey(artist, title);
        known.add(key);
        Seed existing = seeds.get(key);
        if (existing != null) {
            existing.weight = Math.max(existing.weight, weight) + 0.2;
            return;
        }
        seeds.put(key, new Seed(artist.trim(), title.trim(), weight));
    }

    /** Looks the titles of the tracks up in SoundCloud's own database, 400 at a time. */
    private void addTitlesOf(SQLiteDatabase core, Map<String, Double> weights) {
        if (core == null || weights.isEmpty()) return;
        List<String> urns = new ArrayList<>(weights.keySet());
        for (int start = 0; start < urns.size(); start += 400) {
            List<String> part = urns.subList(start, Math.min(urns.size(), start + 400));
            String query = "SELECT t.urn, t.title, u.username FROM Tracks t "
                    + "LEFT JOIN TrackUserJoin j ON j.trackUrn = t.urn LEFT JOIN Users u ON u.urn = j.userUrn "
                    + "WHERE t.urn IN (" + quoted(part) + ")";
            try (Cursor cursor = core.rawQuery(query, null)) {
                while (cursor.moveToNext()) {
                    String[] names = ForYouNames.artistAndTitle(cursor.getString(1), cursor.getString(2));
                    if (names != null) add(names[0], names[1], weights.get(cursor.getString(0)));
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
    List<Seed> pick() {
        List<Seed> sorted = all();
        // Equal weights: a different mix each day.
        Collections.shuffle(sorted);
        sorted.sort((a, b) -> Double.compare(b.weight, a.weight));
        Map<String, Integer> perArtist = new HashMap<>();
        List<Seed> picked = new ArrayList<>();
        for (Seed seed : sorted) {
            String artist = ForYouNames.artistKey(seed.artist);
            int count = perArtist.getOrDefault(artist, 0);
            if (count >= SEEDS_PER_ARTIST) continue;
            perArtist.put(artist, count + 1);
            picked.add(seed);
            if (picked.size() == MAX_SEEDS) break;
        }
        return picked;
    }
}
