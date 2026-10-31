package app.revanced.extension.soundcloud.search;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.arsound.shaded.newpipe.extractor.MediaFormat;
import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.network.StreamText;

/**
 * Asks for a track as the YouTube Music web player does, the way the site (and Metrolist, whose approach this
 * follows) does it: the web player with the session cookies if there are any, a proof-of-origin token from Google's
 * BotGuard for the player request and another one for the stream ({@link PoTokenWebView}), and the stream link
 * deciphered by YouTube's own player script ({@link PlayerCipher}).
 */
final class YouTubeWebPlayer {
    private static final String MUSIC_ORIGIN = "https://music.youtube.com";
    private static final String WEB_REMIX_VERSION = "1.20260121.03.00";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private YouTubeWebPlayer() {
    }

    /** Visitor data, data sync id and client version from the YouTube Music page of the account or the visitor. */
    private static final class MusicPage {
        final String visitorData;
        final String dataSyncId;
        final String clientVersion;

        MusicPage(String visitorData, String dataSyncId, String clientVersion) {
            this.visitorData = visitorData;
            this.dataSyncId = dataSyncId;
            this.clientVersion = clientVersion;
        }
    }

    /**
     * The best audio of a track.
     *
     * @param cookies The session of the account, or null to ask as a visitor.
     */
    /**
     * The hidden WebViews of the lookups (the player script, the token minter) are closed this long after the last
     * lookup: they held the whole player script in memory for as long as the app lived.
     */
    private static final long IDLE_RELEASE_MS = 3 * 60_000;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable RELEASE_IDLE = () -> {
        PoTokenWebView.releaseAll();
        PlayerCipher.releaseSolver();
    };

    static AudioStream audio(String videoId, String cookies) throws Exception {
        MAIN.removeCallbacks(RELEASE_IDLE);
        try {
            return lookUp(videoId, cookies);
        } finally {
            MAIN.postDelayed(RELEASE_IDLE, IDLE_RELEASE_MS);
        }
    }

    /** A lookup given up (another preview was tapped) stops before it waits for the locks of the shared WebViews. */
    private static void stopIfGivenUp() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("The lookup was given up");
    }

    private static AudioStream lookUp(String videoId, String cookies) throws Exception {
        Context context = Utils.getContext();
        if (context == null) throw new IOException("No context");

        MusicPage page = musicPage(cookies);
        String visitorData = page.visitorData;
        String version = page.clientVersion != null ? page.clientVersion : WEB_REMIX_VERSION;
        // A signed-in session is bound to the account's data sync id, an anonymous one to the visitor.
        String session = page.dataSyncId != null ? page.dataSyncId : visitorData;
        if (session == null) throw new IOException("YouTube Music gave no session data");
        stopIfGivenUp();
        String[] tokens = PoTokenWebView.tokens(context, session, videoId);
        stopIfGivenUp();

        JSONObject clientContext = new JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", version)
                .put("hl", "en");
        if (visitorData != null) clientContext.put("visitorData", visitorData);
        JSONObject body = new JSONObject()
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
                .put("context", new JSONObject().put("client", clientContext))
                .put("serviceIntegrityDimensions", new JSONObject().put("poToken", tokens[0]))
                .put("playbackContext", new JSONObject().put("contentPlaybackContext", new JSONObject()
                        .put("signatureTimestamp", PlayerCipher.signatureTimestamp(context))));

        JSONObject response = postPlayerRequest(body, version, visitorData, cookies);

        JSONObject playability = response.optJSONObject("playabilityStatus");
        String status = playability == null ? "" : playability.optString("status");
        String reason = playability == null ? "" : playability.optString("reason");
        PlayabilityVerdict verdict = PlayabilityVerdict.of(cookies != null, status, reason);
        if (verdict != PlayabilityVerdict.PLAYABLE) {
            // The saved session is dropped, else the sign-in page would find its cookies and save the same dead
            // session again.
            if (verdict.dropsSession()) YouTubeAccount.signOut();
            throw verdict.failure(status, reason);
        }
        if (!response.has("streamingData")) throw new IOException("No streaming data");

        JSONObject best = bestAudioFormat(response.getJSONObject("streamingData").optJSONArray("adaptiveFormats"));
        if (best == null) throw new IOException("No m4a audio for " + videoId);

        stopIfGivenUp();
        String url = PlayerCipher.decipherUrl(context, best.optString("signatureCipher", null), best.optString("url", null));
        url += (url.contains("?") ? "&" : "?") + "pot=" + URLEncoder.encode(tokens[1], "UTF-8");

        return new AudioStream.Builder()
                .setId(String.valueOf(best.optInt("itag")))
                .setContent(url, true)
                .setMediaFormat(MediaFormat.M4A)
                .setAverageBitrate(best.optInt("averageBitrate", best.optInt("bitrate")) / 1000)
                .build();
    }

    private static JSONObject postPlayerRequest(JSONObject body, String version, String visitorData, String cookies) throws Exception {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(MUSIC_ORIGIN + "/youtubei/v1/player?prettyPrint=false").openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT);
        connection.setRequestProperty("Origin", MUSIC_ORIGIN);
        connection.setRequestProperty("X-Origin", MUSIC_ORIGIN);
        connection.setRequestProperty("X-YouTube-Client-Name", "67");
        connection.setRequestProperty("X-YouTube-Client-Version", version);
        if (visitorData != null) connection.setRequestProperty("X-Goog-Visitor-Id", visitorData);
        if (cookies != null) {
            connection.setRequestProperty("X-Goog-AuthUser", "0");
            connection.setRequestProperty("Cookie", cookies);
            connection.setRequestProperty("Authorization", YouTubeSession.authorization(cookies, MUSIC_ORIGIN));
        }
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        try (InputStream input = connection.getResponseCode() < 400
                ? connection.getInputStream() : connection.getErrorStream()) {
            return new JSONObject(StreamText.readUtf8(input));
        }
    }

    /** The m4a format with the highest bitrate, or null if there is none. */
    private static JSONObject bestAudioFormat(JSONArray formats) throws Exception {
        JSONObject best = null;
        for (int i = 0; formats != null && i < formats.length(); i++) {
            JSONObject format = formats.getJSONObject(i);
            if (!format.optString("mimeType").startsWith("audio/mp4")) continue;
            if (best == null || format.optInt("bitrate") > best.optInt("bitrate")) best = format;
        }
        return best;
    }

    private static MusicPage musicPage(String cookies) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(MUSIC_ORIGIN + "/").openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT);
        if (cookies != null) connection.setRequestProperty("Cookie", cookies);
        String html;
        try (InputStream input = connection.getInputStream()) {
            html = StreamText.readUtf8(input);
        }
        String visitor = find(html, "VISITOR_DATA");
        String dataSync = find(html, "DATASYNC_ID");
        if (dataSync != null && dataSync.contains("||")) dataSync = dataSync.substring(0, dataSync.indexOf("||"));
        if (dataSync != null && dataSync.isEmpty()) dataSync = null;
        return new MusicPage(visitor, dataSync, find(html, "INNERTUBE_CLIENT_VERSION"));
    }

    private static String find(String html, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }
}
