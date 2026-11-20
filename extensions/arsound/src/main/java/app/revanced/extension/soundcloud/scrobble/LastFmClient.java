package app.revanced.extension.soundcloud.scrobble;

import org.json.JSONException;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Scrobbling to Last.fm (ws.audioscrobbler.com/2.0). The user approves the app once on last.fm in the browser (the web
 * flow of auth.getToken: the app never sees a password), then every scrobble carries the session key and a signature
 * (md5 of the sorted name+value pairs plus the shared secret). Only the artist, the title, the length and the start time
 * go out. The calls block: never on the main thread.
 */
final class LastFmClient {
    private static final String API = "https://ws.audioscrobbler.com/2.0/";
    /** An API error n comes back as code {@code ERROR_BASE + n} (an HTTP status never reaches this range). */
    static final int ERROR_BASE = 20000;

    /** The user name and the key the app sends with each scrobble. */
    static final class Session {
        final String user;
        final String key;

        Session(String user, String key) {
            this.user = user;
            this.key = key;
        }
    }

    private LastFmClient() {
    }

    static boolean configured() {
        return !ScrobbleKeys.LASTFM_API_KEY.isEmpty() && !ScrobbleKeys.LASTFM_SHARED_SECRET.isEmpty();
    }

    /** md5 of the parameters sorted by name, each as name then value (format and callback left out), then the secret. */
    static String signature(Map<String, String> params, String secret) {
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
            String name = entry.getKey();
            if (name.equals("format") || name.equals("callback")) continue;
            joined.append(name).append(entry.getValue());
        }
        joined.append(secret);
        return ListenQueue.Md5.hex(joined.toString());
    }

    /** name=value pairs joined with &, percent-encoded as UTF-8, sorted by name. */
    static String formBody(Map<String, String> params) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return body.toString();
    }

    private static String encode(String text) {
        try {
            // URLEncoder writes a space as "+"; a form body wants it either way, and the signature is made on the raw text.
            return URLEncoder.encode(text, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** One call: the parameters plus api_key and api_sig, then format=json. @return The body of a successful answer. */
    private static String call(Map<String, String> given, boolean post) throws ScrobbleException {
        if (!configured()) throw new ScrobbleException(ERROR_BASE + 10, "Last.fm is not set up in this build");
        Map<String, String> params = new HashMap<>(given);
        params.put("api_key", ScrobbleKeys.LASTFM_API_KEY);
        params.put("api_sig", signature(params, ScrobbleKeys.LASTFM_SHARED_SECRET));
        params.put("format", "json"); // after the signature: it is not part of it
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", ListenBrainzClient.CLIENT);
        WebCall.Answer answer;
        if (post) {
            headers.put("Content-Type", "application/x-www-form-urlencoded");
            answer = WebCall.send(API, headers, formBody(params));
        } else {
            answer = WebCall.send(API + "?" + formBody(params), headers, null);
        }
        JSONObject json = null;
        try {
            json = new JSONObject(answer.body);
        } catch (JSONException ignored) {
            // Not JSON: judged below.
        }
        // An error comes as {"error": n, "message": "..."} (with a 4xx or 5xx status or, for some, 200).
        if (json != null && json.has("error")) {
            int code = json.optInt("error");
            String message = json.optString("message", "");
            throw new ScrobbleException(ERROR_BASE + code, "Last.fm error " + code + (message.isEmpty() ? "" : ": " + message));
        }
        if (!answer.ok()) throw new ScrobbleException(WebCall.serviceStatus(answer.status, json != null), "Last.fm answered " + answer.status);
        // A 200 that is not JSON (a captive portal, a proxy's page) is not an answer of Last.fm: asked again later.
        if (json == null) throw new ScrobbleException(0, "Last.fm gave an answer that is not JSON");
        return answer.body;
    }

    /** Step 1 of the approval: a token. Step 2: the user opens {@link #authUrl} and presses Allow. Step 3: {@link #getSession}. */
    static String getToken() throws ScrobbleException {
        Map<String, String> params = new HashMap<>();
        params.put("method", "auth.getToken");
        String body = call(params, false);
        try {
            String token = new JSONObject(body).optString("token", "");
            if (token.isEmpty()) throw new ScrobbleException(0, "Last.fm gave no token");
            return token;
        } catch (JSONException ex) {
            throw new ScrobbleException(0, "Last.fm gave no token");
        }
    }

    static String authUrl(String token) {
        return "https://www.last.fm/api/auth/?api_key=" + encode(ScrobbleKeys.LASTFM_API_KEY) + "&token=" + encode(token);
    }

    static Session getSession(String token) throws ScrobbleException {
        Map<String, String> params = new HashMap<>();
        params.put("method", "auth.getSession");
        params.put("token", token);
        String body = call(params, false);
        try {
            JSONObject session = new JSONObject(body).optJSONObject("session");
            String key = session == null ? "" : session.optString("key", "");
            if (key.isEmpty()) throw new ScrobbleException(0, "Last.fm gave no session key");
            return new Session(session.optString("name", ""), key);
        } catch (JSONException ex) {
            throw new ScrobbleException(0, "Last.fm gave no session");
        }
    }

    /** The parameters of a scrobble or an "update now playing" call. */
    static Map<String, String> listenParams(String method, String sessionKey, ScrobbleListen listen) {
        Map<String, String> params = new HashMap<>();
        params.put("method", method);
        params.put("artist", listen.artist);
        params.put("track", listen.title);
        params.put("sk", sessionKey);
        if (listen.durationMs >= 1000) params.put("duration", String.valueOf(listen.durationMs / 1000));
        if (method.equals("track.scrobble")) params.put("timestamp", String.valueOf(listen.listenedAt));
        return params;
    }

    static void submit(String sessionKey, ScrobbleListen listen) throws ScrobbleException {
        call(listenParams("track.scrobble", sessionKey, listen), true);
    }

    static void playingNow(String sessionKey, ScrobbleListen listen) throws ScrobbleException {
        call(listenParams("track.updateNowPlaying", sessionKey, listen), true);
    }

    /**
     * 4, 9, 10, 13 and 26 (no right to use the service, a dead session or key, a wrong signature) stop the channel until the
     * session changes; 8, 11, 16, 29, a rate limit, 5xx and every network failure are for later; the rest is a refused listen.
     */
    static ListenOutcome classify(int code) {
        if (code >= ERROR_BASE) {
            switch (code - ERROR_BASE) {
                case 4:
                case 9:
                case 10:
                case 13:
                case 26:
                    return ListenOutcome.REJECTED;
                case 8:
                case 11:
                case 16:
                case 29:
                    return ListenOutcome.RETRY;
                default:
                    return ListenOutcome.DROP;
            }
        }
        if (code == 401 || code == 403) return ListenOutcome.REJECTED;
        if (code == 0 || code == 429 || (code >= 500 && code < 600)) return ListenOutcome.RETRY;
        if (code >= 400 && code < 500) return ListenOutcome.DROP;
        return ListenOutcome.RETRY;
    }
}
