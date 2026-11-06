package app.revanced.extension.soundcloud.recommendations;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.revanced.extension.shared.Utils;

/**
 * Where the disliked tracks are kept on this phone: urn to title for the list in the settings, and urn to the song
 * (title and length) for catching re-uploads. Callers that change both hold the {@link TrackDislikes} class monitor.
 */
final class DislikeStore {
    private static final String PREFERENCES_NAME = "arsound_dislikes";
    /** Track urn to the title and length of the song, for its re-uploads. Kept apart: the list above holds titles. */
    private static final String SONGS_NAME = "arsound_dislike_songs";

    /** Track urn to its title, read once. */
    private static volatile Map<String, String> titles;

    private DislikeStore() {
    }

    private static SharedPreferences preferences(String name) {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(name, Context.MODE_PRIVATE);
    }

    private static Map<String, String> titles() {
        Map<String, String> result = titles;
        if (result != null) return result;
        // Made once, under the lock: two first callers each made a map, and what one added the other never saw.
        synchronized (DislikeStore.class) {
            if (titles != null) return titles;
            return loadTitles();
        }
    }

    private static Map<String, String> loadTitles() {
        Map<String, String> result = new ConcurrentHashMap<>();
        SharedPreferences preferences = preferences(PREFERENCES_NAME);
        if (preferences != null) {
            for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) result.put(entry.getKey(), String.valueOf(entry.getValue()));
        }
        titles = result;
        return result;
    }

    static boolean isEmpty() {
        return titles().isEmpty();
    }

    static boolean contains(String urn) {
        return titles().containsKey(urn);
    }

    /** A copy of the disliked tracks, the newest last: urn to title. */
    static Map<String, String> copy() {
        return new LinkedHashMap<>(titles());
    }

    /** Remembers a track at once under its urn; the title comes later, see {@link #setTitle}. */
    static void add(String urn) {
        // Disliked again: the title it has stays, the phone may be offline now.
        if (!contains(urn)) setTitle(urn, urn);
    }

    static void setTitle(String urn, String title) {
        titles().put(urn, title);
        SharedPreferences preferences = preferences(PREFERENCES_NAME);
        if (preferences != null) preferences.edit().putString(urn, title).apply();
    }

    static void remove(String urn) {
        titles().remove(urn);
        SharedPreferences preferences = preferences(PREFERENCES_NAME);
        if (preferences != null) preferences.edit().remove(urn).apply();
        SharedPreferences songs = preferences(SONGS_NAME);
        if (songs != null) songs.edit().remove(urn).apply();
    }

    static void saveSong(String urn, String title, long duration) throws Exception {
        SharedPreferences songs = preferences(SONGS_NAME);
        if (songs != null) songs.edit().putString(urn, new JSONObject().put("title", title).put("dur", duration).toString()).apply();
    }

    /**
     * The title and length in milliseconds of each disliked track, so a re-upload of the same song is not
     * recommended either. Tracks disliked before the length was kept have none.
     */
    static List<SongRef> songs() {
        List<SongRef> result = new ArrayList<>();
        SharedPreferences songs = preferences(SONGS_NAME);
        if (songs == null) return result;
        for (Map.Entry<String, ?> entry : songs.getAll().entrySet()) {
            if (!contains(entry.getKey())) continue;
            try {
                JSONObject song = new JSONObject(String.valueOf(entry.getValue()));
                long duration = song.optLong("dur");
                String title = song.optString("title");
                if (duration > 0 && !title.isEmpty()) result.add(new SongRef(title, duration));
            } catch (Exception ignored) {
                // A damaged entry: only its re-uploads are not caught.
            }
        }
        return result;
    }
}
