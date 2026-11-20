package app.revanced.extension.soundcloud.scrobble;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * ListenBrainz (api.listenbrainz.org): a listen goes out with the user's token in the Authorization header. Only the
 * artist, the title, the length and the start time are sent. The calls block: never on the main thread.
 */
final class ListenBrainzClient {
    private static final String BASE = "https://api.listenbrainz.org";
    static final String CLIENT = "Arsound Android";

    private ListenBrainzClient() {
    }

    /** The body of a submit-listens call; {@code listenType} is {@code single} or {@code playing_now}. */
    static String body(String listenType, ScrobbleListen listen) {
        try {
            JSONObject info = new JSONObject().put("media_player", CLIENT).put("submission_client", CLIENT);
            if (listen.durationMs > 0) info.put("duration_ms", listen.durationMs);
            JSONObject metadata = new JSONObject()
                    .put("artist_name", listen.artist).put("track_name", listen.title).put("additional_info", info);
            JSONObject one = new JSONObject().put("track_metadata", metadata);
            // A "playing now" call must not carry a time.
            if ("single".equals(listenType)) one.put("listened_at", listen.listenedAt);
            return new JSONObject().put("listen_type", listenType).put("payload", new JSONArray().put(one)).toString();
        } catch (JSONException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static void submit(String token, ScrobbleListen listen) throws ScrobbleException {
        post(token, "single", listen);
    }

    static void playingNow(String token, ScrobbleListen listen) throws ScrobbleException {
        post(token, "playing_now", listen);
    }

    private static void post(String token, String type, ScrobbleListen listen) throws ScrobbleException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Token " + token);
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", CLIENT);
        WebCall.Answer answer = WebCall.send(BASE + "/1/submit-listens", headers, body(type, listen));
        if (!answer.ok()) throw new ScrobbleException(statusOf(answer), "ListenBrainz answered " + answer.status);
    }

    /** Asks whether the token is known. @return The user name. @throws ScrobbleException 401 when it is not valid. */
    static String validate(String token) throws ScrobbleException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Token " + token);
        headers.put("User-Agent", CLIENT);
        WebCall.Answer answer = WebCall.send(BASE + "/1/validate-token", headers, null);
        if (!answer.ok()) throw new ScrobbleException(statusOf(answer), "ListenBrainz answered " + answer.status);
        try {
            JSONObject json = new JSONObject(answer.body);
            if (!json.optBoolean("valid", false)) throw new ScrobbleException(401, "The token is not valid");
            return json.optString("user_name", "");
        } catch (JSONException ex) {
            throw new ScrobbleException(0, "The token check is not JSON");
        }
    }

    /** The status of a failed answer for {@link #classify}: a 401 or 403 that is not ListenBrainz's own JSON error is no refusal. */
    private static int statusOf(WebCall.Answer answer) {
        return WebCall.serviceStatus(answer.status, isServiceError(answer.body));
    }

    /** Whether the body is an error as ListenBrainz writes it: JSON with a {@code code} and an {@code error}. */
    static boolean isServiceError(String body) {
        if (body == null) return false;
        try {
            JSONObject json = new JSONObject(body);
            return json.has("error") || json.has("code");
        } catch (JSONException ex) {
            return false;
        }
    }

    /**
     * Whether the text can be a token: printable ASCII without spaces. Anything else cannot go into a header, so it is
     * refused before a request is made.
     */
    static boolean isTokenText(String token) {
        if (token == null || token.isEmpty()) return false;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x21 || c > 0x7E) return false;
        }
        return true;
    }

    /** What an answer code means: 401 and 403 stop the channel; limits, a broken service and the network are for later. */
    static ListenOutcome classify(int code) {
        if (code == 401 || code == 403) return ListenOutcome.REJECTED;
        if (code == 0 || code == 429 || (code >= 500 && code < 600)) return ListenOutcome.RETRY;
        if (code >= 400 && code < 500) return ListenOutcome.DROP;
        return ListenOutcome.RETRY;
    }
}
