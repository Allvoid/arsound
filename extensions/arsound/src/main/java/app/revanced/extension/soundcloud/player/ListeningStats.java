package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;
import app.revanced.extension.soundcloud.recommendations.ForYou;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * Listening statistics, kept only on this phone. The player reports the start of every track and
 * every switch between playing and not playing; the time a track really played is written to a
 * file, one line per listen: start time, track urn, milliseconds played.
 * <p>
 * A listen counts as a play from {@link #PLAY_THRESHOLD_MS} on, shorter ones only add time.
 */
@SuppressWarnings("unused")
public final class ListeningStats {
    public static final String ENABLED = "listening_stats";
    public static final long PLAY_THRESHOLD_MS = 30_000;
    private static final String PREFERENCES_NAME = "arsound_listening_stats";
    /** The listen in progress, saved on every pause so it survives the app being closed. */
    private static final String PENDING = "pending";
    private static final String TITLES = "titles";

    private static String currentUrn;
    private static long currentStart;
    private static long playedMs;
    private static long playingSince;

    private ListeningStats() {
    }

    public static boolean isEnabled() {
        return Settings.getBoolean(ENABLED, true);
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static File file() {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), "arsound/listening.tsv");
    }

    // region Recording

    /**
     * Injection point: {@code BaseExoPlayer.f(PlaybackItem)}, a queue item starts.
     *
     * @param item The {@code PlaybackItem}.
     */
    public static synchronized void onPlaybackItem(Object item) {
        try {
            Object urn = item.getClass().getMethod("h").invoke(item);
            String value = String.valueOf(urn);
            // Kept even with the statistics off: unplayed downloads are counted from it.
            app.revanced.extension.soundcloud.download.UnplayedDownloads.onTrackStarted(value);
            if (isResume(item, value)) return;
            finishListen();
            if (!isEnabled()) return;
            // Ads and other items that are not tracks are not counted.
            if (!value.contains(":tracks:")) return;
            currentUrn = value;
            currentStart = System.currentTimeMillis();
        } catch (Exception ex) {
            Logger.printException(() -> "Listening stats: could not read the playback item", ex);
        }
    }

    /**
     * Play after a pause gives the player the same item again, starting where it stopped. That is the
     * same listen going on: counted as a new one, a track paused once became two short listens, and
     * often neither reached a play. Also continues the listen saved at a pause if the app was closed.
     *
     * @return True if the listen goes on.
     */
    private static boolean isResume(Object item, String urn) {
        long position;
        try {
            position = ((Number) item.getClass().getMethod("e").invoke(item)).longValue();
        } catch (Exception ex) {
            return false;
        }
        // From the start it is a new listen, also of the same track.
        if (position <= 0) return false;
        if (urn.equals(currentUrn)) return true;
        if (currentUrn != null || !isEnabled()) return false;
        SharedPreferences preferences = preferences();
        String pending = preferences == null ? null : preferences.getString(PENDING, null);
        String[] parts = pending == null ? new String[0] : pending.split("\t");
        if (parts.length != 3 || !parts[1].equals(urn)) return false;
        long start;
        long played;
        try {
            start = Long.parseLong(parts[0]);
            played = Long.parseLong(parts[2]);
        } catch (NumberFormatException ex) {
            // A damaged mark of an earlier listen: forgotten, or it would be read again at every track.
            if (preferences != null) preferences.edit().remove(PENDING).apply();
            return false;
        }
        currentUrn = urn;
        currentStart = start;
        playedMs = played;
        playingSince = 0;
        Logger.printInfo(() -> "Listening stats: the listen saved at the pause goes on: " + urn);
        return true;
    }

    /**
     * Injection point: {@code onPlayerStateChanged} of the player listener.
     *
     * @param playWhenReady Whether the user wants the track to play.
     * @param state         The ExoPlayer state; 3 is ready.
     */
    public static synchronized void onPlayerState(boolean playWhenReady, int state) {
        if (currentUrn == null) return;
        long now = System.currentTimeMillis();
        boolean playing = playWhenReady && state == 3;
        if (playing && playingSince == 0) {
            playingSince = now;
        } else if (!playing && playingSince != 0) {
            playedMs += now - playingSince;
            playingSince = 0;
            savePending();
        }
        // The track ended by itself.
        if (state == 4) finishListen();
    }

    private static void finishListen() {
        if (currentUrn == null) {
            flushPending();
            return;
        }
        if (playingSince != 0) playedMs += System.currentTimeMillis() - playingSince;
        String urn = currentUrn;
        long start = currentStart;
        long played = playedMs;
        currentUrn = null;
        playedMs = 0;
        playingSince = 0;
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(PENDING).apply();
        if (played >= 1000) append(start, urn, played);
    }

    private static void savePending() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        preferences.edit().putString(PENDING, currentStart + "\t" + currentUrn + "\t" + playedMs).apply();
    }

    /** A listen the app was closed in the middle of. */
    private static void flushPending() {
        SharedPreferences preferences = preferences();
        String pending = preferences == null ? null : preferences.getString(PENDING, null);
        if (pending == null) return;
        preferences.edit().remove(PENDING).apply();
        String[] parts = pending.split("\t");
        if (parts.length == 3) append(Long.parseLong(parts[0]), parts[1], Long.parseLong(parts[2]));
    }

    private static void append(long start, String urn, long played) {
        Logger.printInfo(() -> "Listening stats: " + urn + " played " + played / 1000 + " s");
        Utils.runOnBackgroundThread(() -> {
            synchronized (ListeningStats.class) {
                File file = file();
                if (file == null) return;
                //noinspection ResultOfMethodCallIgnored
                file.getParentFile().mkdirs();
                try (FileWriter writer = new FileWriter(file, true)) {
                    writer.write(start + "\t" + urn + "\t" + played + "\n");
                } catch (Exception ex) {
                    Logger.printException(() -> "Listening stats: could not write", ex);
                }
            }
        });
    }

    public static synchronized void clear() {
        File file = file();
        //noinspection ResultOfMethodCallIgnored
        if (file != null) file.delete();
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(PENDING).apply();
    }

    // endregion

    // region Reading

    public static final class Entry {
        public final String key;
        public String title;
        public String artist;
        public int plays;
        public long playedMs;

        Entry(String key) {
            this.key = key;
        }
    }

    public static final class Summary {
        public long playedMs;
        public int plays;
        public int tracks;
        public final List<Entry> topTracks = new ArrayList<>();
        public final List<Entry> topArtists = new ArrayList<>();
    }

    /**
     * Sums up the listens since the given time. Blocks: may ask SoundCloud for track titles.
     *
     * @param since Epoch milliseconds, 0 for all time.
     */
    /** The start of the last listen of each track in the statistics, by its urn. */
    public static Map<String, Long> lastListens() {
        Map<String, Long> result = new HashMap<>();
        File file = file();
        if (file == null || !file.isFile()) return result;
        synchronized (ListeningStats.class) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t");
                    if (parts.length != 3) continue;
                    long start = Long.parseLong(parts[0]);
                    Long known = result.get(parts[1]);
                    if (known == null || known < start) result.put(parts[1], start);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Listening stats: could not read", ex);
            }
        }
        return result;
    }

    public static Summary summarize(long since, int limit) {
        Summary summary = new Summary();
        Map<String, Entry> tracks = new HashMap<>();
        File file = file();
        if (file != null && file.isFile()) {
            synchronized (ListeningStats.class) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split("\t");
                        long started;
                        long played;
                        try {
                            if (parts.length != 3) continue;
                            started = Long.parseLong(parts[0]);
                            played = Long.parseLong(parts[2]);
                        } catch (NumberFormatException ex) {
                            // One damaged line must not hide the rest of the history.
                            continue;
                        }
                        if (started < since) continue;
                        Entry entry = tracks.get(parts[1]);
                        if (entry == null) tracks.put(parts[1], entry = new Entry(parts[1]));
                        entry.playedMs += played;
                        summary.playedMs += played;
                        if (played >= PLAY_THRESHOLD_MS) {
                            entry.plays++;
                            summary.plays++;
                        }
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "Listening stats: could not read", ex);
                }
            }
        }
        addCurrent(summary, tracks, since);
        summary.tracks = tracks.size();

        List<Entry> sorted = new ArrayList<>(tracks.values());
        sorted.sort((a, b) -> a.plays != b.plays ? Integer.compare(b.plays, a.plays) : Long.compare(b.playedMs, a.playedMs));
        // Artists need the titles of all tracks, not only the top ones.
        resolveTitles(sorted);
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) summary.topTracks.add(sorted.get(i));

        // One artist is written in different ways ("CUPSIZE", "Cupsize", "Cupsize & ..."): counted
        // together and named as written on the tracks played the longest.
        Map<String, Entry> artists = new HashMap<>();
        Map<String, Map<String, Long>> spellings = new HashMap<>();
        for (Entry track : sorted) {
            if (track.artist == null || track.artist.trim().isEmpty()) continue;
            String key = ForYou.artistKey(track.artist);
            if (key.isEmpty()) key = track.artist.trim().toLowerCase(Locale.ROOT);
            Entry artist = artists.get(key);
            if (artist == null) artists.put(key, artist = new Entry(key));
            artist.plays += track.plays;
            artist.playedMs += track.playedMs;
            Map<String, Long> names = spellings.get(key);
            if (names == null) spellings.put(key, names = new HashMap<>());
            names.merge(track.artist.trim(), track.playedMs, Long::sum);
        }
        for (Entry artist : artists.values()) {
            long longest = -1;
            for (Map.Entry<String, Long> name : spellings.get(artist.key).entrySet()) {
                if (name.getValue() > longest) {
                    longest = name.getValue();
                    artist.title = name.getKey();
                }
            }
        }
        List<Entry> artistList = new ArrayList<>(artists.values());
        artistList.sort((a, b) -> Long.compare(b.playedMs, a.playedMs));
        for (int i = 0; i < Math.min(limit, artistList.size()); i++) summary.topArtists.add(artistList.get(i));
        return summary;
    }

    /** The listen still going on or paused: it is written only when the next track starts. */
    private static void addCurrent(Summary summary, Map<String, Entry> tracks, long since) {
        String urn;
        long start;
        long played;
        synchronized (ListeningStats.class) {
            urn = currentUrn;
            start = currentStart;
            played = playedMs + (playingSince != 0 ? System.currentTimeMillis() - playingSince : 0);
        }
        if (urn == null) {
            SharedPreferences preferences = preferences();
            String pending = preferences == null ? null : preferences.getString(PENDING, null);
            String[] parts = pending == null ? new String[0] : pending.split("\t");
            if (parts.length != 3) return;
            start = Long.parseLong(parts[0]);
            urn = parts[1];
            played = Long.parseLong(parts[2]);
        }
        if (start < since || played < 1000) return;
        Entry entry = tracks.get(urn);
        if (entry == null) tracks.put(urn, entry = new Entry(urn));
        entry.playedMs += played;
        summary.playedMs += played;
        if (played >= PLAY_THRESHOLD_MS) {
            entry.plays++;
            summary.plays++;
        }
    }

    /**
     * Titles of SoundCloud tracks are looked up once and kept: in SoundCloud's own database first, which
     * works without a network, then on the server. Imported files give their tags.
     * <p>
     * Uploads by others are often titled "Artist - Title" under the uploader's name: the artist is
     * taken from the title then, the same way as for the "For you" playlist.
     */
    private static void resolveTitles(List<Entry> entries) {
        SharedPreferences preferences = preferences();
        JSONObject titles;
        try {
            titles = new JSONObject(preferences == null ? "{}" : preferences.getString(TITLES, "{}"));
        } catch (Exception ex) {
            titles = new JSONObject();
        }
        List<String> missing = new ArrayList<>();
        for (Entry entry : entries) {
            File local = LocalAdditions.importedFileOf(entry.key);
            if (local != null) {
                LocalMusic.Track track = local.isFile() ? LocalMusic.readTrack(local) : null;
                if (track != null && !track.artist.isEmpty()) {
                    entry.title = track.title;
                    entry.artist = track.artist;
                } else {
                    name(entry, track != null ? track.title : local.getName(), "");
                }
            } else if (!titles.has(entry.key)) {
                missing.add(entry.key);
            }
        }

        boolean changed = false;
        if (!missing.isEmpty()) {
            for (Map.Entry<String, String[]> stored : storedTitles(missing).entrySet()) {
                putTitle(titles, stored.getKey(), stored.getValue()[0], stored.getValue()[1]);
                missing.remove(stored.getKey());
                changed = true;
            }
        }
        List<String> ids = new ArrayList<>();
        for (String urn : missing) {
            String id = DownloadTrackPatch.parseTrackId(urn);
            if (id != null) ids.add(id);
        }
        for (int start = 0; start < ids.size(); start += 50) {
            try {
                String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks?ids="
                        + String.join(",", ids.subList(start, Math.min(ids.size(), start + 50))));
                if (response[1] == null) break;
                JSONArray array = new JSONArray(response[1]);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject track = array.getJSONObject(i);
                    JSONObject user = track.optJSONObject("user");
                    putTitle(titles, "soundcloud:tracks:" + track.getLong("id"), track.optString("title"),
                            user == null ? "" : user.optString("username"));
                    changed = true;
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Listening stats: no track titles: " + ex);
                break;
            }
        }
        if (changed && preferences != null) preferences.edit().putString(TITLES, titles.toString()).apply();

        for (Entry entry : entries) {
            if (entry.title != null) continue;
            JSONArray known = titles.optJSONArray(entry.key);
            if (known != null) {
                name(entry, known.optString(0), known.optString(1));
            } else {
                entry.title = entry.key;
                entry.artist = "";
            }
        }
    }

    /** Kept as SoundCloud gives them: the title and the uploader. */
    private static void putTitle(JSONObject titles, String urn, String title, String uploader) {
        try {
            titles.put(urn, new JSONArray().put(title).put(uploader == null ? "" : uploader));
        } catch (Exception ignored) {
            // Only a null key throws.
        }
    }

    private static void name(Entry entry, String title, String uploader) {
        String[] names = ForYou.artistAndTitle(title, uploader);
        entry.artist = names != null ? names[0] : "";
        entry.title = names != null ? names[1] : title;
    }

    /** Titles and uploaders of tracks from SoundCloud's own database, without the network. */
    private static Map<String, String[]> storedTitles(List<String> urns) {
        Map<String, String[]> found = new HashMap<>();
        Context context = Utils.getContext();
        File path = context == null ? null : context.getDatabasePath("core.db");
        if (path == null || !path.isFile()) return found;
        try (SQLiteDatabase core = SQLiteDatabase.openDatabase(path.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            // SQLite takes at most 999 values in one query.
            for (int start = 0; start < urns.size(); start += 400) {
                List<String> part = urns.subList(start, Math.min(urns.size(), start + 400));
                String query = "SELECT t.urn, t.title, u.username FROM Tracks t "
                        + "LEFT JOIN TrackUserJoin j ON j.trackUrn = t.urn LEFT JOIN Users u ON u.urn = j.userUrn "
                        + "WHERE t.urn IN (" + String.join(",", java.util.Collections.nCopies(part.size(), "?")) + ")";
                try (Cursor cursor = core.rawQuery(query, part.toArray(new String[0]))) {
                    while (cursor.moveToNext()) {
                        if (cursor.getString(1) == null) continue;
                        found.put(cursor.getString(0), new String[]{cursor.getString(1), cursor.getString(2)});
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Listening stats: could not read stored track titles: " + ex);
        }
        return found;
    }

    // endregion
}
