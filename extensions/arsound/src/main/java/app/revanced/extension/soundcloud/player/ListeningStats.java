package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;
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
            finishListen();
            if (!isEnabled()) return;
            Object urn = item.getClass().getMethod("h").invoke(item);
            String value = String.valueOf(urn);
            // Ads and other items that are not tracks are not counted.
            if (!value.contains(":tracks:")) return;
            currentUrn = value;
            currentStart = System.currentTimeMillis();
        } catch (Exception ex) {
            Logger.printException(() -> "Listening stats: could not read the playback item", ex);
        }
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
                        if (parts.length != 3 || Long.parseLong(parts[0]) < since) continue;
                        long played = Long.parseLong(parts[2]);
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

        Map<String, Entry> artists = new HashMap<>();
        for (Entry track : sorted) {
            if (track.artist == null || track.artist.isEmpty()) continue;
            Entry artist = artists.get(track.artist);
            if (artist == null) artists.put(track.artist, artist = new Entry(track.artist));
            artist.title = track.artist;
            artist.plays += track.plays;
            artist.playedMs += track.playedMs;
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

    /** Titles of SoundCloud tracks are asked for once and kept; imported files give their tags. */
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
                entry.title = track != null ? track.title : local.getName();
                entry.artist = track != null ? track.artist : "";
                continue;
            }
            JSONArray known = titles.optJSONArray(entry.key);
            if (known != null) {
                entry.title = known.optString(0);
                entry.artist = known.optString(1);
            } else {
                String id = DownloadTrackPatch.parseTrackId(entry.key);
                if (id != null) missing.add(id);
            }
        }
        for (int start = 0; start < missing.size(); start += 50) {
            try {
                String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks?ids="
                        + String.join(",", missing.subList(start, Math.min(missing.size(), start + 50))));
                if (response[1] == null) break;
                JSONArray array = new JSONArray(response[1]);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject track = array.getJSONObject(i);
                    JSONObject user = track.optJSONObject("user");
                    titles.put("soundcloud:tracks:" + track.getLong("id"), new JSONArray()
                            .put(track.optString("title"))
                            .put(user == null ? "" : user.optString("username")));
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Listening stats: no track titles: " + ex);
                break;
            }
        }
        if (!missing.isEmpty() && preferences != null) preferences.edit().putString(TITLES, titles.toString()).apply();
        for (Entry entry : entries) {
            if (entry.title != null) continue;
            JSONArray known = titles.optJSONArray(entry.key);
            entry.title = known != null ? known.optString(0) : entry.key;
            entry.artist = known != null ? known.optString(1) : "";
        }
    }

    // endregion
}
