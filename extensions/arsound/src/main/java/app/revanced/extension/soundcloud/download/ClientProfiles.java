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
final class ClientProfiles {
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

    private static volatile String cachedClientId;
    private static volatile long cachedAt;

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
                String body = get("https://api-v2.soundcloud.com/tracks/" + trackId + "?client_id=" + clientId, userAgent);
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

    /** The client id of the web player, read from its script and kept for a day. */
    private static String clientId() {
        String cached = cachedClientId;
        if (cached != null && System.currentTimeMillis() - cachedAt < CLIENT_ID_LIFETIME_MS) return cached;

        try {
            String page = get("https://soundcloud.com/", USER_AGENTS[0]);
            if (page == null) return cached;

            List<String> scripts = new ArrayList<>();
            Matcher matcher = SCRIPT.matcher(page);
            while (matcher.find()) scripts.add(matcher.group(1));
            // The id sits in one of the last bundles, so they are read from the end.
            for (int i = scripts.size() - 1; i >= 0; i--) {
                String script = get(scripts.get(i), USER_AGENTS[0]);
                if (script == null) continue;
                Matcher id = CLIENT_ID.matcher(script);
                if (!id.find()) continue;
                cachedClientId = id.group(1);
                cachedAt = System.currentTimeMillis();
                Logger.printInfo(() -> "Web client id found");
                return cachedClientId;
            }
            Logger.printInfo(() -> "No web client id in the player scripts");
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read the web client id: " + ex);
        }
        return cached;
    }

    /** @return The response body, or null if the request failed. */
    private static String get(String url, String userAgent) throws Exception {
        app.revanced.extension.soundcloud.network.RegionGuard.throwIfBlocked(new URL(url).getHost());
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
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
