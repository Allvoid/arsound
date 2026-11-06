package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The last wave kept on the phone: its tracks in their order, the one that played last, and the titles, uploaders and
 * cover addresses found for them. The "My Wave" tab shows them after the app was closed, without asking anything again.
 * Only the newest {@link #KEEP} tracks are kept. Written in the background ({@code apply}); read once.
 */
final class WaveSession {
    private static final String PREFERENCES_NAME = "arsound_wave_session";
    /** "tracks_v2": the first kept the uploader where the player shows the artist; those are read again. */
    private static final String QUEUE = "queue", CURRENT = "current", TRACKS = "tracks_v2";
    /** How many tracks of the last wave are kept, the newest ones. */
    static final int KEEP = 60;

    private WaveSession() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** The tracks of the last wave, the oldest first. */
    static List<String> queue() {
        List<String> queue = new ArrayList<>();
        try {
            SharedPreferences preferences = preferences();
            if (preferences == null) return queue;
            JSONArray array = new JSONArray(preferences.getString(QUEUE, "[]"));
            for (int i = 0; i < array.length(); i++) queue.add(array.getString(i));
        } catch (Exception ex) {
            Logger.printInfo(() -> "My Wave: the last wave was not read: " + ex);
        }
        return queue;
    }

    /** The track of the last wave that played last, or null. */
    static String current() {
        SharedPreferences preferences = preferences();
        return preferences == null ? null : preferences.getString(CURRENT, null);
    }

    /** Keeps the wave's tracks (the newest {@link #KEEP}) and the one playing. */
    static void save(List<String> queue, String current) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        List<String> kept = queue.subList(Math.max(0, queue.size() - KEEP), queue.size());
        preferences.edit().putString(QUEUE, new JSONArray(kept).toString()).putString(CURRENT, current).apply();
    }

    /** Whether every one of the tracks is kept with its artist (in the current form). */
    static boolean tracksCover(List<String> urns) {
        return tracks().keySet().containsAll(urns);
    }

    /** Title, artist and cover address of the tracks found before, by urn. */
    static Map<String, String[]> tracks() {
        return read(TRACKS);
    }

    /**
     * The tracks the first version of the list kept, with the uploader in place of the artist: shown while the phone
     * cannot ask for the artist, never written again.
     */
    static Map<String, String[]> oldTracks() {
        return read("tracks");
    }

    private static Map<String, String[]> read(String key) {
        Map<String, String[]> tracks = new HashMap<>();
        try {
            SharedPreferences preferences = preferences();
            if (preferences == null) return tracks;
            JSONObject object = new JSONObject(preferences.getString(key, "{}"));
            for (Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                String urn = keys.next();
                JSONArray parts = object.getJSONArray(urn);
                tracks.put(urn, new String[]{parts.optString(0), parts.optString(1), parts.isNull(2) ? null : parts.optString(2)});
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "My Wave: the known tracks were not read: " + ex);
        }
        return tracks;
    }

    /**
     * Adds the tracks found to those known; only the tracks of {@code keepFor} (the wave's) stay, so the file does not
     * grow with every wave.
     */
    static synchronized void putTracks(Map<String, String[]> found, List<String> keepFor) {
        SharedPreferences preferences = preferences();
        if (preferences == null || found.isEmpty()) return;
        Map<String, String[]> all = tracks();
        all.putAll(found);
        try {
            JSONObject object = new JSONObject();
            for (String urn : keepFor) {
                String[] track = all.get(urn);
                if (track == null) continue;
                JSONArray parts = new JSONArray();
                for (String part : track) parts.put(part == null ? JSONObject.NULL : part);
                object.put(urn, parts);
            }
            preferences.edit().putString(TRACKS, object.toString()).apply();
        } catch (Exception ex) {
            Logger.printInfo(() -> "My Wave: the known tracks were not kept: " + ex);
        }
    }
}
