package app.revanced.extension.soundcloud.transfer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Reads the answers of VK's API methods for music ({@code audio.get}, {@code audio.getPlaylists}). Pure: no network. */
final class VkParser {
    /** The tracks of one answer of {@code audio.get} and the count of the whole list. */
    static final class TrackPage {
        final List<SourceTrack> tracks;
        final int count;
        /** How many items the answer held, those without a title too: the next page starts after them. */
        final int items;

        TrackPage(List<SourceTrack> tracks, int count, int items) {
            this.tracks = tracks;
            this.count = count;
            this.items = items;
        }
    }

    /** A playlist of {@code audio.getPlaylists}, read through the original of a playlist the user follows. */
    static final class Playlist {
        final String owner;
        final String id;
        final String accessKey;
        final String title;
        final int count;

        Playlist(String owner, String id, String accessKey, String title, int count) {
            this.owner = owner;
            this.id = id;
            this.accessKey = accessKey;
            this.title = title;
            this.count = count;
        }
    }

    private VkParser() {
    }

    /** {"response": {"count": …, "items": [{"artist", "title", "duration", …}]}}. */
    static TrackPage tracks(JSONObject answer) {
        JSONObject response = answer.optJSONObject("response");
        JSONArray items = response == null ? null : response.optJSONArray("items");
        List<SourceTrack> tracks = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            SourceTrack track = track(items.optJSONObject(i));
            if (track != null) tracks.add(track);
        }
        int count = response == null ? 0 : response.optInt("count", tracks.size());
        return new TrackPage(tracks, count, items == null ? 0 : items.length());
    }

    static SourceTrack track(JSONObject audio) {
        if (audio == null) return null;
        String title = audio.optString("title", "").trim();
        if (title.isEmpty()) return null;
        List<String> artists = new ArrayList<>();
        addNames(audio.optJSONArray("main_artists"), artists);
        if (artists.isEmpty()) {
            for (String name : audio.optString("artist", "").split(",|\\sfeat\\.\\s|\\sft\\.\\s|\\s&\\s")) {
                String trimmed = name.trim();
                if (!trimmed.isEmpty()) artists.add(trimmed);
            }
        }
        addNames(audio.optJSONArray("featured_artists"), artists);
        String version = audio.optString("subtitle", "");
        String id = "vk:" + audio.optLong("owner_id") + "_" + audio.optLong("id");
        return new SourceTrack(id, artists, title, version, audio.optLong("duration", 0) * 1000);
    }

    private static void addNames(JSONArray people, List<String> names) {
        for (int i = 0; people != null && i < people.length(); i++) {
            JSONObject person = people.optJSONObject(i);
            String name = person == null ? "" : person.optString("name", "").trim();
            if (!name.isEmpty() && !names.contains(name)) names.add(name);
        }
    }

    /** {"response": {"count": …, "items": [{"id", "owner_id", "title", "count", "access_key", "original"?}]}}. */
    /** How many items an answer of {@code audio.getPlaylists} held, those without a title too. */
    static int itemCount(JSONObject answer) {
        JSONObject response = answer.optJSONObject("response");
        JSONArray items = response == null ? null : response.optJSONArray("items");
        return items == null ? 0 : items.length();
    }

    static List<Playlist> playlists(JSONObject answer) {
        JSONObject response = answer.optJSONObject("response");
        JSONArray items = response == null ? null : response.optJSONArray("items");
        List<Playlist> playlists = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title", "").trim();
            if (title.isEmpty()) continue;
            // A playlist the user follows is a copy; its tracks are read from the original.
            JSONObject original = item.optJSONObject("original");
            JSONObject from = original != null && original.has("playlist_id") ? original : null;
            String owner = String.valueOf(from != null ? from.optLong("owner_id") : item.optLong("owner_id"));
            String id = String.valueOf(from != null ? from.optLong("playlist_id") : item.optLong("id"));
            String key = from != null ? from.optString("access_key", "") : item.optString("access_key", "");
            playlists.add(new Playlist(owner, id, key, title, item.optInt("count", SourceList.UNKNOWN_COUNT)));
        }
        return playlists;
    }

    /** The title of {@code audio.getPlaylistById}, empty if there is none. */
    static String playlistTitle(JSONObject answer) {
        JSONObject response = answer.optJSONObject("response");
        return response == null ? "" : response.optString("title", "").trim();
    }

    /** VK's error code of an answer, 0 when it holds none. */
    static int errorCode(JSONObject answer) {
        JSONObject error = answer.optJSONObject("error");
        return error == null ? 0 : error.optInt("error_code", -1);
    }
}
