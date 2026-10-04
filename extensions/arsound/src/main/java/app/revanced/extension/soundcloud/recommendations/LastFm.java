package app.revanced.extension.soundcloud.recommendations;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Last.fm: which songs and artists people listen to together. Only names are sent, no account.
 * All calls block: run them off the main thread.
 */
final class LastFm {
    private static final String API = "https://ws.audioscrobbler.com/2.0/";
    /** Last.fm asks clients to stay under five requests a second. */
    private static final long REQUEST_GAP_MS = 250;
    private static long lastRequest;

    static final class Song {
        final String artist;
        final String title;
        /** How close it is to the song asked about, 0..1. */
        final double match;

        Song(String artist, String title, double match) {
            this.artist = artist;
            this.title = title;
            this.match = match;
        }
    }

    private LastFm() {
    }

    static boolean hasKey() {
        return !LastFmKey.API_KEY.isEmpty();
    }

    /** Songs people play together with this one. Empty if Last.fm does not know it. */
    static List<Song> similarTracks(String artist, String title, int limit) throws Exception {
        JSONObject response = call("track.getsimilar", "artist", artist, "track", title,
                "autocorrect", "1", "limit", String.valueOf(limit));
        List<Song> songs = new ArrayList<>();
        JSONObject similar = response.optJSONObject("similartracks");
        JSONArray tracks = similar == null ? null : similar.optJSONArray("track");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            JSONObject track = tracks.getJSONObject(i);
            JSONObject trackArtist = track.optJSONObject("artist");
            if (trackArtist == null) continue;
            songs.add(new Song(trackArtist.optString("name"), track.optString("name"), track.optDouble("match", 0)));
        }
        return songs;
    }

    /** Artists people listen to together with this one, closest first. */
    static List<Song> similarArtists(String artist, int limit) throws Exception {
        JSONObject response = call("artist.getsimilar", "artist", artist, "autocorrect", "1",
                "limit", String.valueOf(limit));
        List<Song> artists = new ArrayList<>();
        JSONObject similar = response.optJSONObject("similarartists");
        JSONArray list = similar == null ? null : similar.optJSONArray("artist");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject item = list.getJSONObject(i);
            artists.add(new Song(item.optString("name"), null, item.optDouble("match", 0)));
        }
        return artists;
    }

    /** The most played songs of an artist. */
    static List<String> topTracks(String artist, int limit) throws Exception {
        JSONObject response = call("artist.gettoptracks", "artist", artist, "autocorrect", "1",
                "limit", String.valueOf(limit));
        List<String> titles = new ArrayList<>();
        JSONObject top = response.optJSONObject("toptracks");
        JSONArray tracks = top == null ? null : top.optJSONArray("track");
        for (int i = 0; tracks != null && i < tracks.length(); i++) {
            titles.add(tracks.getJSONObject(i).optString("name"));
        }
        return titles;
    }

    private static JSONObject call(String method, String... parameters) throws Exception {
        StringBuilder url = new StringBuilder(API).append("?method=").append(method)
                .append("&format=json&api_key=").append(LastFmKey.API_KEY);
        for (int i = 0; i + 1 < parameters.length; i += 2) {
            url.append('&').append(parameters[i]).append('=').append(URLEncoder.encode(parameters[i + 1], "UTF-8"));
        }
        synchronized (LastFm.class) {
            long wait = lastRequest + REQUEST_GAP_MS - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastRequest = System.currentTimeMillis();
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url.toString()).openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("User-Agent", "Arsound");
        int code = connection.getResponseCode();
        InputStream stream = code / 100 == 2 ? connection.getInputStream() : connection.getErrorStream();
        String body = stream == null ? "" : read(stream);
        JSONObject json = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        // Unknown songs come back as error 6 ("not found"): no similar songs, not a failure.
        if (json.has("error") && json.optInt("error") != 6) {
            throw new java.io.IOException("Last.fm error " + json.optInt("error") + ": " + json.optString("message"));
        }
        if (code / 100 != 2 && !json.has("error")) throw new java.io.IOException("Last.fm HTTP " + code);
        return json;
    }

    private static String read(InputStream stream) throws Exception {
        try (InputStream input = stream) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }
}
