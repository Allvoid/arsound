package app.revanced.extension.soundcloud.recommendations;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.soundcloud.network.StreamText;

/**
 * Deezer's free public API (no key, no account): the artist radio, which is a mix of the artist's songs and those of
 * similar artists. Only names are sent. All calls block: run them off the main thread.
 */
final class DeezerApi {
    private static final String API = "https://api.deezer.com/";

    static final class Song {
        final String artist;
        final String title;

        Song(String artist, String title) {
            this.artist = artist;
            this.title = title;
        }
    }

    private DeezerApi() {
    }

    /** The songs of the radio of an artist, empty if Deezer does not know the artist. */
    static List<Song> artistRadio(String artist, int limit) throws Exception {
        JSONObject found = get("search/artist?limit=1&q=" + URLEncoder.encode(artist, "UTF-8"));
        JSONArray artists = found.optJSONArray("data");
        if (artists == null || artists.length() == 0) return new ArrayList<>();
        long id = artists.getJSONObject(0).getLong("id");
        JSONObject radio = get("artist/" + id + "/radio?limit=" + limit);
        List<Song> songs = new ArrayList<>();
        JSONArray tracks = radio.optJSONArray("data");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject track = tracks.getJSONObject(i);
            JSONObject trackArtist = track.optJSONObject("artist");
            if (trackArtist == null) continue;
            songs.add(new Song(trackArtist.optString("name"), track.optString("title")));
        }
        return songs;
    }

    private static JSONObject get(String path) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API + path).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", "Arsound");
        int code = connection.getResponseCode();
        InputStream stream = code / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
        String body = "";
        if (stream != null) {
            try (InputStream input = stream) {
                body = StreamText.readUtf8(input);
            }
        }
        JSONObject json;
        try {
            json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        } catch (JSONException ex) {
            throw new IOException("Deezer sent a reply that is not JSON, HTTP " + code);
        }
        // Deezer answers 200 with an "error" object for a wrong request.
        if (json.has("error")) throw new IOException("Deezer error: " + json.optJSONObject("error"));
        if (code / 100 != 2) throw new IOException("Deezer HTTP " + code);
        return json;
    }
}
