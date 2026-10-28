package app.revanced.extension.soundcloud.local;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.shared.Logger;

/**
 * What the user added to playlists on this phone, and the playlists opened recently (the targets to pick from).
 * Every change reads and writes under one lock: two imports must not lose each other.
 */
final class AdditionsStore {
    private static final String ADDITIONS = "additions";
    private static final String RECENT_PLAYLISTS = "recent_playlists";
    private static final int RECENT_LIMIT = 30;

    private AdditionsStore() {
    }

    /** Playlist urn to its local entries, in order of addition. */
    private static synchronized Map<String, List<String>> readAdditions() {
        Map<String, List<String>> result = new LinkedHashMap<>();
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences == null) return result;
        try {
            JSONObject json = new JSONObject(preferences.getString(ADDITIONS, "{}"));
            for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String playlist = keys.next();
                JSONArray entries = json.getJSONArray(playlist);
                List<String> list = new ArrayList<>();
                for (int i = 0; i < entries.length(); i++) list.add(entries.getString(i));
                result.put(playlist, list);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read local additions", ex);
        }
        return result;
    }

    private static synchronized void writeAdditions(Map<String, List<String>> additions) {
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences == null) return;
        try {
            JSONObject json = new JSONObject();
            for (Map.Entry<String, List<String>> entry : additions.entrySet()) {
                if (!entry.getValue().isEmpty()) json.put(entry.getKey(), new JSONArray(entry.getValue()));
            }
            preferences.edit().putString(ADDITIONS, json.toString()).apply();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save local additions", ex);
        }
    }

    /** Entries added by the user to this playlist. */
    static List<String> entries(String playlistUrn) {
        List<String> entries = readAdditions().get(playlistUrn);
        return entries == null ? new ArrayList<>() : entries;
    }

    /** @return False if the entry was already added. */
    static synchronized boolean add(String playlistUrn, String entry) {
        Map<String, List<String>> additions = readAdditions();
        List<String> entries = additions.get(playlistUrn);
        if (entries == null) additions.put(playlistUrn, entries = new ArrayList<>());
        if (entries.contains(entry)) return false;
        entries.add(entry);
        writeAdditions(additions);
        return true;
    }

    /**
     * Adds many entries with one read and one write, in the given order.
     *
     * @return How many were new: the entries already in the playlist, and repeats, are left out.
     */
    static synchronized int addAll(String playlistUrn, Collection<String> newEntries) {
        Map<String, List<String>> additions = readAdditions();
        List<String> entries = additions.get(playlistUrn);
        if (entries == null) additions.put(playlistUrn, entries = new ArrayList<>());
        Set<String> known = new HashSet<>(entries);
        int added = 0;
        for (String entry : newEntries) {
            if (!known.add(entry)) continue;
            entries.add(entry);
            added++;
        }
        if (added > 0) writeAdditions(additions);
        return added;
    }

    /** Replaces the added entries of a playlist, keeping the given order. */
    static synchronized void setEntries(String playlistUrn, List<String> entries) {
        Map<String, List<String>> additions = readAdditions();
        additions.put(playlistUrn, new ArrayList<>(entries));
        writeAdditions(additions);
    }

    static synchronized void remove(String playlistUrn, String entry) {
        Map<String, List<String>> additions = readAdditions();
        List<String> entries = additions.get(playlistUrn);
        if (entries != null && entries.remove(entry)) writeAdditions(additions);
    }

    /**
     * Removes an entry from every playlist, once its file is deleted: the next file with the same name gets
     * the same path and must not turn up in playlists it was never added to. One write for all playlists.
     *
     * @return The playlists that held the entry.
     */
    static synchronized List<String> forget(String entry) {
        Map<String, List<String>> additions = readAdditions();
        List<String> playlists = new ArrayList<>();
        for (Map.Entry<String, List<String>> playlist : additions.entrySet()) {
            if (playlist.getValue().removeIf(entry::equals)) playlists.add(playlist.getKey());
        }
        if (!playlists.isEmpty()) writeAdditions(additions);
        return playlists;
    }

    /** Opened playlists, so they can be picked as a target. Newest first: urn and title. */
    static synchronized List<String[]> recentPlaylists() {
        List<String[]> result = new ArrayList<>();
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences == null) return result;
        try {
            JSONArray json = new JSONArray(preferences.getString(RECENT_PLAYLISTS, "[]"));
            for (int i = 0; i < json.length(); i++) {
                JSONObject item = json.getJSONObject(i);
                result.add(new String[]{item.getString("urn"), item.optString("title")});
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read recent playlists", ex);
        }
        return result;
    }

    static synchronized void rememberPlaylist(String urn, String title) {
        SharedPreferences preferences = LocalPrefs.open();
        if (preferences == null) return;
        List<String[]> recent = recentPlaylists();
        recent.removeIf(item -> item[0].equals(urn));
        recent.add(0, new String[]{urn, title});
        try {
            JSONArray json = new JSONArray();
            for (int i = 0; i < Math.min(recent.size(), RECENT_LIMIT); i++) {
                json.put(new JSONObject().put("urn", recent.get(i)[0]).put("title", recent.get(i)[1]));
            }
            preferences.edit().putString(RECENT_PLAYLISTS, json.toString()).apply();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save recent playlists", ex);
        }
    }
}
