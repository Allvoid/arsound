package app.revanced.extension.soundcloud.download;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.RegionGuard;

/**
 * Asks the public SoundCloud API as other clients when the app itself is offered no stream.
 * <p>
 * The same track is often described differently for the web player than for the phone app: the web
 * player gets a progressive file where the app only gets HLS, or the other way round. The client id
 * of the web player is not hard-coded here, because it changes: it is read from the player script on
 * soundcloud.com, the way the site itself loads it, and kept for a day.
 * <p>
 * This only widens the search for tracks the user may already play in full: tracks the web player
 * calls a preview or a subscription track are refused here as well, and DRM streams are left alone.
 */
public final class ClientProfiles {
    private static final long CLIENT_ID_LIFETIME_MS = 24 * 60 * 60 * 1000L;
    private static final Pattern CLIENT_ID = Pattern.compile("client_id\\s*[:=]\\s*\"([A-Za-z0-9]{20,})\"");
    private static final Pattern SCRIPT = Pattern.compile("<script[^>]+src=\"(https://[^\"]+\\.js)\"");

    /** The user agents tried with the web client id, oldest player last. */
    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) "
                    + "Version/17.0 Safari/605.1.15",
    };

    /** After a lookup that found no id the scripts are not downloaded again for this long: they are megabytes. */
    private static final long NOT_FOUND_BACKOFF_MS = 5 * 60 * 1000L;
    /** After the region guard stopped a lookup: short, the user may turn a VPN on. */
    private static final long BLOCKED_BACKOFF_MS = 5 * 1000L;
    /** After a lookup that failed on the network: shorter, as the network may be back soon. */
    private static final long ERROR_BACKOFF_MS = 60 * 1000L;
    /** An id that the API refused is only forgotten if it is older than this; a fresh one would be read again. */
    private static final long MIN_REJECTED_AGE_MS = 2 * 60 * 1000L;
    /** How much of a script is searched, so a broken server cannot stream without end. */
    private static final int MAX_SCRIPT_CHARS = 16 * 1024 * 1024;
    /** The end of the text kept between two reads, longer than the whole "client_id" match: it may be cut in half. */
    private static final int SCRIPT_OVERLAP_CHARS = 512;

    private static final Object CLIENT_ID_LOCK = new Object();
    private static volatile String cachedClientId;
    private static volatile long cachedAt;
    /** No new lookup of the client id before this time. */
    private static volatile long nextLookupAt;

    private ClientProfiles() {
    }

    /**
     * Asks other clients for a stream of the track.
     *
     * @return A source that can be downloaded, or null if no client offers one.
     */
    static TrackSource search(String trackId) {
        String clientId = clientId();
        if (clientId == null) return null;

        TrackSource found = null;
        for (String userAgent : USER_AGENTS) {
            try {
                String body = get("https://api-v2.soundcloud.com/tracks/" + trackId + "?client_id=" + clientId,
                        userAgent, clientId);
                if (body == null) continue;

                JSONObject track = new JSONObject(body);
                // The web player signs its stream requests with this token instead of an OAuth header.
                String authorization = track.optString("track_authorization");
                TrackSource source = DownloadTrackPatch.sourceOfTrack(trackId, track,
                        endpoint -> resolveStream(endpoint, clientId, authorization, userAgent));
                if (source.isDownloadable()) {
                    Logger.printInfo(() -> "Profile search found a stream for " + trackId);
                    return source;
                }
                found = source;
            } catch (Exception ex) {
                Logger.printInfo(() -> "Profile search failed for " + trackId + ": " + ex);
            }
        }
        return found;
    }

    /** A track found by the web search. */
    public static final class FoundTrack {
        public final String id;
        public final String title;
        public final String user;
        /** ALLOW, MONETIZE, SNIP (preview only) or BLOCK. */
        public final String policy;
        /** Full length in milliseconds, 0 if the search did not say. */
        public final long duration;

        FoundTrack(String id, String title, String user, String policy, long duration) {
            this.id = id;
            this.title = title;
            this.user = user;
            this.policy = policy;
            this.duration = duration;
        }

        /** Playable in full, not just a preview. */
        public boolean isFull() {
            return "ALLOW".equals(policy) || "MONETIZE".equals(policy);
        }
    }

    /** Searches SoundCloud tracks the way the web site does. Blocks: call it off the main thread. */
    public static List<FoundTrack> searchTracks(String query) throws Exception {
        String clientId = clientId();
        if (clientId == null) throw new java.io.IOException("No web client id");
        String body = get("https://api-v2.soundcloud.com/search/tracks?limit=20&q="
                + java.net.URLEncoder.encode(query, "UTF-8") + "&client_id=" + clientId, USER_AGENTS[0], clientId);
        if (body == null) throw new java.io.IOException("SoundCloud search failed");
        org.json.JSONArray collection = new JSONObject(body).optJSONArray("collection");
        List<FoundTrack> tracks = new ArrayList<>();
        for (int i = 0; collection != null && i < collection.length(); i++) {
            JSONObject track = collection.getJSONObject(i);
            JSONObject user = track.optJSONObject("user");
            tracks.add(new FoundTrack(String.valueOf(track.optLong("id")), track.optString("title"),
                    user == null ? "" : user.optString("username"), track.optString("policy"),
                    track.optLong("full_duration", track.optLong("duration"))));
        }
        return tracks;
    }

    /** Asks the web player's endpoint for the file or playlist behind a transcoding. */
    private static String resolveStream(String endpoint, String clientId, String authorization, String userAgent)
            throws Exception {
        if (endpoint == null || endpoint.isEmpty()) return null;
        String url = endpoint + (endpoint.contains("?") ? "&" : "?") + "client_id=" + clientId;
        if (authorization != null && !authorization.isEmpty()) {
            url += "&track_authorization=" + java.net.URLEncoder.encode(authorization, "UTF-8");
        }

        String body = get(url, userAgent);
        if (body == null) return null;
        String streamUrl = new JSONObject(body).optString("url");
        return streamUrl.isEmpty() ? null : streamUrl;
    }

    private static boolean isFresh(String clientId) {
        return clientId != null && System.currentTimeMillis() - cachedAt < CLIENT_ID_LIFETIME_MS;
    }

    /**
     * The client id of the web player, read from its script and kept for a day.
     * Returns the old id, or null, if a new one cannot be had right now.
     */
    private static String clientId() {
        String cached = cachedClientId;
        if (isFresh(cached) || System.currentTimeMillis() < nextLookupAt) return cached;

        // One thread reads the scripts while the others wait for its answer, instead of each downloading them.
        synchronized (CLIENT_ID_LOCK) {
            cached = cachedClientId;
            if (isFresh(cached) || System.currentTimeMillis() < nextLookupAt) return cached;

            long backoff = ERROR_BACKOFF_MS;
            try {
                String found = readClientId();
                if (found != null) {
                    cachedClientId = found;
                    cachedAt = System.currentTimeMillis();
                    nextLookupAt = 0;
                    Logger.printInfo(() -> "Web client id found");
                    return found;
                }
                backoff = NOT_FOUND_BACKOFF_MS;
            } catch (RegionGuard.BlockedException ex) {
                // Nothing was sent, and the user may switch a VPN on at any moment: tried again soon, but the threads
                // waiting for this lock do not each sit out the guard's pause.
                Logger.printInfo(() -> "Could not read the web client id: " + ex);
                nextLookupAt = System.currentTimeMillis() + BLOCKED_BACKOFF_MS;
                return cached;
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not read the web client id: " + ex);
            }
            nextLookupAt = System.currentTimeMillis() + backoff;
            return cached;
        }
    }

    /**
     * Reads the home page and the player scripts it names.
     *
     * @return The client id, or null if the site has none to be found.
     */
    private static String readClientId() throws Exception {
        String page = get("https://soundcloud.com/", USER_AGENTS[0]);
        // A refused or failed page says nothing about the id: the shorter pause of a network error applies.
        if (page == null) throw new java.io.IOException("The SoundCloud home page could not be read");

        List<String> scripts = new ArrayList<>();
        Matcher matcher = SCRIPT.matcher(page);
        while (matcher.find()) scripts.add(matcher.group(1));
        // The id sits in one of the last bundles, so they are read from the end.
        for (int i = scripts.size() - 1; i >= 0; i--) {
            String id = findClientId(scripts.get(i));
            if (id != null) return id;
        }
        Logger.printInfo(() -> "No web client id in the player scripts");
        return null;
    }

    /**
     * Searches a script for the client id while it downloads, without holding the script, which is megabytes of
     * text, in memory.
     *
     * @return The client id, or null if the script has none or could not be read.
     */
    private static String findClientId(String url) throws Exception {
        HttpURLConnection connection = connect(url, USER_AGENTS[0]);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                char[] chunk = new char[64 * 1024];
                // The end of the previous read comes first, as the id may lie across the border of two reads.
                StringBuilder window = new StringBuilder();
                int total = 0;
                int read;
                while ((read = reader.read(chunk)) != -1) {
                    window.append(chunk, 0, read);
                    Matcher id = CLIENT_ID.matcher(window);
                    if (id.find()) return id.group(1);
                    total += read;
                    if (total >= MAX_SCRIPT_CHARS) return null;
                    int excess = window.length() - SCRIPT_OVERLAP_CHARS;
                    if (excess > 0) window.delete(0, excess);
                }
                return null;
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Forgets a client id that the API refused, so the next call reads a new one if the site has rotated it.
     */
    private static void forget(String rejectedId) {
        synchronized (CLIENT_ID_LOCK) {
            // An id that was read from the site a moment ago and is refused all the same would just be read again.
            boolean fresh = System.currentTimeMillis() - cachedAt < MIN_REJECTED_AGE_MS;
            if (!rejectedId.equals(cachedClientId) || fresh) return;
            cachedClientId = null;
            Logger.printInfo(() -> "The web client id was refused, a new one is read next time");
        }
    }

    private static HttpURLConnection connect(String url, String userAgent) throws Exception {
        RegionGuard.throwIfBlocked(new URL(url).getHost());
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        return connection;
    }

    /** @return The response body, or null if the request failed. */
    private static String get(String url, String userAgent) throws Exception {
        return get(url, userAgent, null);
    }

    /**
     * @param clientId The web client id sent with the request, which is forgotten if the API answers 401; or null.
     * @return The response body, or null if the request failed.
     */
    private static String get(String url, String userAgent, String clientId) throws Exception {
        HttpURLConnection connection = connect(url, userAgent);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                if (code == HttpURLConnection.HTTP_UNAUTHORIZED && clientId != null) forget(clientId);
                return null;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
                return body.toString();
            }
        } finally {
            connection.disconnect();
        }
    }
}
