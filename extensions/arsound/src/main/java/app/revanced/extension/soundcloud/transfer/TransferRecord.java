package app.revanced.extension.soundcloud.transfer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A finished transfer, as the settings list it. */
public final class TransferRecord {
    /** {@link YandexLink#key()} of the collection. */
    public final String key;
    public final String title;
    public final long time;
    public final int total;
    public final int found;
    /** The SoundCloud playlists made, one per 500 tracks. */
    public final List<String> playlists;
    /** "Artist — Title" of the tracks not found. */
    public final List<String> missing;

    TransferRecord(String key, String title, long time, int total, int found, List<String> playlists, List<String> missing) {
        this.key = key;
        this.title = title;
        this.time = time;
        this.total = total;
        this.found = found;
        this.playlists = playlists;
        this.missing = missing;
    }

    JSONObject toJson() throws Exception {
        return new JSONObject().put("key", key).put("title", title).put("time", time).put("total", total)
                .put("found", found).put("playlists", new JSONArray(playlists)).put("missing", new JSONArray(missing));
    }

    static TransferRecord fromJson(JSONObject json) {
        return new TransferRecord(json.optString("key"), json.optString("title"), json.optLong("time"),
                json.optInt("total"), json.optInt("found"), strings(json.optJSONArray("playlists")),
                strings(json.optJSONArray("missing")));
    }

    private static List<String> strings(JSONArray array) {
        if (array == null) return Collections.emptyList();
        List<String> values = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) values.add(array.optString(i));
        return values;
    }
}
