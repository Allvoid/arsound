package app.revanced.extension.soundcloud.transfer;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.soundcloud.network.StreamText;
import app.revanced.extension.soundcloud.transfer.TransferSource.NotAvailableException;
import app.revanced.extension.soundcloud.transfer.TransferSource.SignInExpiredException;

/**
 * Yandex Music's own API (the one its apps use): playlists, albums and likes as lists of tracks. Public collections
 * are read without an account; a private one needs the token of its owner ({@link YandexAccount}). Blocks: call it
 * off the main thread.
 */
final class YandexMusic {
    private static final String API = "https://api.music.yandex.net";
    /** Tracks asked for at once by their numbers. */
    private static final int TRACKS_PER_REQUEST = 200;
    private static final int MAX_PAGES = 100;

    /** The signed-in account. */
    static final class Account {
        final String uid;
        final String login;
        final String name;

        Account(String uid, String login, String name) {
            this.uid = uid;
            this.login = login;
            this.name = name;
        }
    }

    private YandexMusic() {
    }

    /** The title and tracks of what the link points at. @param token Null without an account. */
    static SourceCollection load(YandexLink link, String token) throws Exception {
        switch (link.kind) {
            case LIKES:
                return new SourceCollection(likesTitle(), likes(link.owner, token));
            case ALBUM: {
                JSONObject album = call("/albums/" + link.id + "/with-tracks", token, null);
                List<SourceTrack> tracks = new ArrayList<>();
                JSONArray volumes = album.optJSONArray("volumes");
                for (int v = 0; volumes != null && v < volumes.length(); v++) {
                    JSONArray volume = volumes.optJSONArray(v);
                    for (int i = 0; volume != null && i < volume.length(); i++) addTrack(tracks, volume.optJSONObject(i));
                }
                return new SourceCollection(album.optString("title", text("Альбом", "Album")), tracks);
            }
            default:
                return playlist(link, token);
        }
    }

    static String likesTitle() {
        return text("Мне нравится из Яндекс Музыки", "Liked on Yandex Music");
    }

    private static SourceCollection playlist(YandexLink link, String token) throws Exception {
        String path = link.owner == null ? "/playlist/" + link.id
                : "/users/" + encode(link.owner) + "/playlists/" + link.id;
        JSONObject playlist = call(path, token, null);
        List<SourceTrack> tracks = new ArrayList<>();
        List<String> bare = new ArrayList<>();
        readItems(playlist.optJSONArray("tracks"), tracks, bare);
        // Long playlists may come in pages.
        JSONObject pager = playlist.optJSONObject("pager");
        int total = playlist.optInt("trackCount", 0);
        if (pager != null) {
            int perPage = pager.optInt("perPage", 0);
            for (int page = 1; perPage > 0 && page * perPage < pager.optInt("total", 0) && page < MAX_PAGES; page++) {
                JSONObject next = call(path + "?page=" + page + "&pageSize=" + perPage, token, null);
                JSONArray items = next.optJSONArray("tracks");
                if (items == null || items.length() == 0) break;
                readItems(items, tracks, bare);
            }
        }
        tracks.addAll(tracksById(bare, token));
        if (tracks.isEmpty() && total > 0) {
            throw new NotAvailableException("The playlist lists " + total + " tracks but gave none");
        }
        String title = playlist.optString("title", "");
        // The likes as a playlist ("/users/<login>/playlists/3") have the same name as the likes of SoundCloud.
        if ("3".equals(link.id) && link.owner != null) title = likesTitle();
        return new SourceCollection(title.isEmpty() ? text("Плейлист из Яндекс Музыки", "Yandex Music playlist") : title, tracks);
    }

    /** Items of a playlist: with the track, or only with its number (then asked for separately). */
    private static void readItems(JSONArray items, List<SourceTrack> tracks, List<String> bare) {
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            JSONObject track = item.optJSONObject("track");
            if (track != null) addTrack(tracks, track);
            else bare.add(trackKey(item));
        }
    }

    /** The likes of a person: only numbers in the list, the tracks are asked for by them. */
    private static List<SourceTrack> likes(String owner, String token) throws Exception {
        JSONObject result = call("/users/" + encode(owner) + "/likes/tracks", token, null);
        JSONObject library = result.optJSONObject("library");
        JSONArray items = library == null ? null : library.optJSONArray("tracks");
        List<String> keys = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) keys.add(trackKey(item));
        }
        if (keys.isEmpty()) {
            // A closed profile shows an empty list to everyone else.
            throw new NotAvailableException(token == null ? "No public likes" : "No likes");
        }
        return tracksById(keys, token);
    }

    /** "id:albumId", or the id alone, as {@code /tracks} takes them. */
    private static String trackKey(JSONObject item) {
        String id = item.optString("id");
        String album = item.optString("albumId", "");
        return album.isEmpty() ? id : id + ":" + album;
    }

    /** The tracks for their numbers, in the same order. */
    private static List<SourceTrack> tracksById(List<String> keys, String token) throws Exception {
        List<SourceTrack> tracks = new ArrayList<>();
        for (int start = 0; start < keys.size(); start += TRACKS_PER_REQUEST) {
            List<String> part = keys.subList(start, Math.min(keys.size(), start + TRACKS_PER_REQUEST));
            String body = "track-ids=" + encode(String.join(",", part)) + "&with-positions=false";
            JSONArray found = callArray("/tracks", token, body);
            for (int i = 0; i < found.length(); i++) addTrack(tracks, found.optJSONObject(i));
        }
        return tracks;
    }

    private static void addTrack(List<SourceTrack> tracks, JSONObject track) {
        if (track == null) return;
        String title = track.optString("title", "").trim();
        if (title.isEmpty()) return;
        List<String> artists = new ArrayList<>();
        JSONArray names = track.optJSONArray("artists");
        for (int i = 0; names != null && i < names.length(); i++) {
            JSONObject artist = names.optJSONObject(i);
            String name = artist == null ? "" : artist.optString("name", "").trim();
            if (!name.isEmpty()) artists.add(name);
        }
        String version = track.isNull("version") ? null : track.optString("version", null);
        tracks.add(new SourceTrack(track.optString("id"), artists, title, version, track.optLong("durationMs", 0)));
    }

    /** Who the token belongs to. */
    static Account account(String token) throws Exception {
        JSONObject status = call("/account/status", token, null);
        JSONObject account = status.optJSONObject("account");
        if (account == null || !account.has("uid")) throw new SignInExpiredException("Yandex token expired");
        String login = account.optString("login", "");
        String name = account.optString("displayName", account.optString("fullName", login));
        return new Account(account.optString("uid"), login, name.isEmpty() ? login : name);
    }

    /** The likes and the playlists of the account, likes first. */
    static List<SourceList> library(String token, Account account) throws Exception {
        List<SourceList> entries = new ArrayList<>();
        JSONObject likes = call("/users/" + account.uid + "/likes/tracks", token, null);
        JSONObject library = likes.optJSONObject("library");
        JSONArray liked = library == null ? null : library.optJSONArray("tracks");
        entries.add(new SourceList(new YandexLink(YandexLink.Kind.LIKES, account.uid, null),
                text("Мне нравится", "Liked tracks"), liked == null ? 0 : liked.length(), true));
        JSONArray playlists = callArray("/users/" + account.uid + "/playlists/list", token, null);
        for (int i = 0; i < playlists.length(); i++) {
            JSONObject playlist = playlists.optJSONObject(i);
            if (playlist == null) continue;
            entries.add(new SourceList(new YandexLink(YandexLink.Kind.PLAYLIST, account.uid,
                    String.valueOf(playlist.optLong("kind"))), playlist.optString("title"), playlist.optInt("trackCount"),
                    false));
        }
        return entries;
    }

    private static JSONObject call(String path, String token, String form) throws Exception {
        Object result = new JSONObject(request(path, token, form)).opt("result");
        if (!(result instanceof JSONObject)) throw new NotAvailableException("No result for " + path);
        return (JSONObject) result;
    }

    private static JSONArray callArray(String path, String token, String form) throws Exception {
        Object result = new JSONObject(request(path, token, form)).opt("result");
        if (!(result instanceof JSONArray)) throw new NotAvailableException("No list for " + path);
        return (JSONArray) result;
    }

    /** The body of a 200 answer. @param form A form to post, null for a GET. */
    private static String request(String path, String token, String form) throws IOException {
        IOException failure = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            // "Stop" works between the requests: a long list is read in dozens of them, and a request ignores it.
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Transfer stopped");
            if (attempt > 0) pause(attempt * 1_500L);
            HttpURLConnection connection = (HttpURLConnection) new URL(API + path).openConnection();
            try {
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(20_000);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Accept-Language", text("ru", "en"));
                if (token != null) connection.setRequestProperty("Authorization", "OAuth " + token);
                if (form != null) {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    try (OutputStream out = connection.getOutputStream()) {
                        out.write(form.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = connection.getResponseCode();
                if (code == 200) {
                    try (InputStream in = connection.getInputStream()) {
                        return StreamText.readUtf8(in);
                    }
                }
                drain(connection);
                if (code == 401 && token != null) throw new SignInExpiredException("Yandex token expired");
                if (code == 400 || code == 403 || code == 404 || code == 401) {
                    throw new NotAvailableException("Yandex Music answered HTTP " + code + " for " + path);
                }
                failure = new IOException("Yandex Music answered HTTP " + code + " for " + path);
            } catch (NotAvailableException | SignInExpiredException ex) {
                throw ex;
            } catch (IOException ex) {
                failure = ex;
            } finally {
                connection.disconnect();
            }
        }
        throw failure;
    }

    private static void drain(HttpURLConnection connection) {
        try (InputStream error = connection.getErrorStream()) {
            if (error != null) while (error.read() != -1) {
                // Nothing to keep.
            }
        } catch (IOException ignored) {
        }
    }

    private static void pause(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Stopped");
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ex) {
            return value;
        }
    }
}
