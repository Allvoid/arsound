package app.revanced.extension.soundcloud.local;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import app.revanced.extension.shared.Logger;

/** Maps from an entry to its position in a playlist, each kept as one JSON object under a preferences key. */
final class PositionStore {
    private final String preferencesName;

    PositionStore(String preferencesName) {
        this.preferencesName = preferencesName;
    }

    /** Entry to its position, in the order they were added. */
    synchronized Map<String, Integer> read(String key) {
        Map<String, Integer> result = new LinkedHashMap<>();
        SharedPreferences preferences = LocalPrefs.open(preferencesName);
        if (preferences == null) return result;
        try {
            JSONObject json = new JSONObject(preferences.getString(key, "{}"));
            for (Iterator<String> keys = json.keys(); keys.hasNext(); ) {
                String entry = keys.next();
                result.put(entry, json.getInt(entry));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read " + key, ex);
        }
        return result;
    }

    /** An empty map removes the key. */
    synchronized void write(String key, Map<String, Integer> positions) {
        SharedPreferences preferences = LocalPrefs.open(preferencesName);
        if (preferences == null) return;
        if (positions.isEmpty()) {
            preferences.edit().remove(key).apply();
            return;
        }
        preferences.edit().putString(key, new JSONObject(positions).toString()).apply();
    }

    synchronized void put(String key, String entry, int position) {
        Map<String, Integer> positions = read(key);
        positions.put(entry, position);
        write(key, positions);
    }
}
