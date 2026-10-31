package app.revanced.extension.soundcloud.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;
import app.revanced.extension.soundcloud.recommendations.ForYou;

/**
 * Finds the title and the artist of the tracks in the statistics. Titles of SoundCloud tracks are looked up once and
 * kept: in SoundCloud's own database first, which works without a network, then on the server. Imported files give
 * their tags.
 * <p>
 * Uploads by others are often titled "Artist - Title" under the uploader's name: the artist is taken from the title
 * then, the same way as for the "For you" playlist.
 */
final class TrackTitleResolver {
    private static final String TITLES = "titles";
    /**
     * Tracks the server did not return when their titles were asked for (deleted, private, blocked in
     * the region), by track id, with the time of the answer.
     */
    private static final String NOT_FOUND = "not_found";
    private static final long NOT_FOUND_RETRY_MS = 3L * 24 * 60 * 60 * 1000;

    private TrackTitleResolver() {
    }

    /**
     * Sets the title and the artist of each entry. Blocks: may ask SoundCloud for the titles.
     *
     * @param preferences Where the titles found are kept; may be null.
     */
    static void resolve(List<ListeningStats.Entry> entries, SharedPreferences preferences) {
        JSONObject titles = storedObject(preferences, TITLES);
        JSONObject notFound = storedObject(preferences, NOT_FOUND);
        long now = System.currentTimeMillis();
        List<String> missing = new ArrayList<>();
        for (ListeningStats.Entry entry : entries) {
            File local = LocalAdditions.importedFileOf(entry.key);
            if (local != null) {
                // Kept per file: the statistics resolve every listen of the period, the same files many times.
                LocalMusic.Track track = local.isFile() ? LocalMusic.track(local) : null;
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
            // A track the server did not return a short while ago is not asked for again at once.
            if (id != null && now - notFound.optLong(id, 0) > NOT_FOUND_RETRY_MS) ids.add(id);
        }
        for (int start = 0; start < ids.size(); start += 50) {
            try {
                List<String> batch = ids.subList(start, Math.min(ids.size(), start + 50));
                String[] response = DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks?ids="
                        + String.join(",", batch));
                if (response[1] == null) break;
                JSONArray array = new JSONArray(response[1]);
                Set<String> returned = new HashSet<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONObject track = array.getJSONObject(i);
                    JSONObject user = track.optJSONObject("user");
                    putTitle(titles, "soundcloud:tracks:" + track.getLong("id"), track.optString("title"),
                            user == null ? "" : user.optString("username"));
                    returned.add(String.valueOf(track.getLong("id")));
                    changed = true;
                }
                // The answer was read to the end, so an id missing from it is not available to this user.
                // Not marked after a failure: a dropped connection must not look like a deleted track.
                for (String id : batch) {
                    if (returned.contains(id)) continue;
                    notFound.put(id, now);
                    changed = true;
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "Listening stats: no track titles: " + ex);
                break;
            }
        }
        if (changed && preferences != null) {
            preferences.edit().putString(TITLES, titles.toString()).putString(NOT_FOUND, notFound.toString()).apply();
        }

        for (ListeningStats.Entry entry : entries) {
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

    private static JSONObject storedObject(SharedPreferences preferences, String key) {
        try {
            return new JSONObject(preferences == null ? "{}" : preferences.getString(key, "{}"));
        } catch (Exception ex) {
            return new JSONObject();
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

    private static void name(ListeningStats.Entry entry, String title, String uploader) {
        String[] names = ForYou.artistAndTitle(title, uploader);
        entry.artist = names != null ? names[0] : "";
        entry.title = names != null ? names[1] : title;
    }

    /** Titles and uploaders of tracks from SoundCloud's own database, without the network. */
    static Map<String, String[]> storedTitles(List<String> urns) {
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
                        + "WHERE t.urn IN (" + String.join(",", Collections.nCopies(part.size(), "?")) + ")";
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
}
