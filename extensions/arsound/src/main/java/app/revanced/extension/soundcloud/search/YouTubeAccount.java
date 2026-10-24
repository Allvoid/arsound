package app.revanced.extension.soundcloud.search;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import app.arsound.shaded.newpipe.extractor.MediaFormat;
import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * An optional YouTube Music account for the Arsound search. Anonymous requests cannot get age-restricted
 * tracks; with the account's cookies the player answers as for a signed-in listener.
 * <p>
 * The cookies stay in the app's private storage and go only to youtube.com. NewPipe asks YouTube as the
 * Android app, which ignores cookies, so age-restricted tracks are asked for here as the YouTube Music
 * web player ({@link PoTokenWebView} for its tokens, {@link PlayerCipher} for its stream links).
 */
public final class YouTubeAccount {
    private static final String PREFERENCES_NAME = "arsound_youtube_account";
    private static final String COOKIES = "cookies";
    private static final String NAME = "name";
    private static final String ORIGIN = "https://www.youtube.com";

    private YouTubeAccount() {
    }

    /** No account: the track is age-restricted and needs a signed-in account. */
    public static final class SignInRequiredException extends IOException {
        SignInRequiredException() {
            super("The track is age-restricted: sign in to YouTube Music");
        }

        SignInRequiredException(String message) {
            super(message);
        }
    }

    /**
     * YouTube took the requests of this network for a bot ("Sign in to confirm that you're not a bot"), and refused
     * the web player with BotGuard tokens too. Without an account, signing in may help; with one, another network.
     */
    public static final class BotCheckException extends IOException {
        BotCheckException(String message) {
            super(message);
        }
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static String cookies() {
        SharedPreferences preferences = preferences();
        return preferences == null ? null : preferences.getString(COOKIES, null);
    }

    public static boolean isSignedIn() {
        String cookies = cookies();
        return cookies != null && cookie(cookies, "SAPISID") != null;
    }

    /** The account e-mail or name, if the sign-in page showed it; otherwise empty. */
    public static String accountName() {
        SharedPreferences preferences = preferences();
        return preferences == null ? "" : preferences.getString(NAME, "");
    }

    /** Saves the session of the sign-in screen. @return False if the browser has no YouTube session. */
    static boolean saveSession(String name) {
        String cookies = CookieManager.getInstance().getCookie(ORIGIN);
        if (cookies == null || cookie(cookies, "SAPISID") == null) return false;
        SharedPreferences preferences = preferences();
        if (preferences == null) return false;
        preferences.edit().putString(COOKIES, cookies).putString(NAME, name == null ? "" : name).apply();
        return true;
    }

    public static void signOut() {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().clear().apply();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        // The hidden WebViews of the age-restricted lookups stay alive otherwise. Neither call waits for
        // the lookup lock, which a lookup holds for tens of seconds: the sign-out click runs on the main thread.
        PoTokenWebView.releaseAll();
        PlayerCipher.releaseSolver();
    }

    private static String cookie(String cookies, String name) {
        for (String part : cookies.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) return trimmed.substring(name.length() + 1);
        }
        return null;
    }

    /** The header Google's web apps use to prove the session: SHA-1 of time, SAPISID and origin. */
    private static String authorization(String cookies, String origin) throws Exception {
        String sapisid = cookie(cookies, "SAPISID");
        long time = System.currentTimeMillis() / 1000;
        byte[] hash = MessageDigest.getInstance("SHA-1")
                .digest((time + " " + sapisid + " " + origin).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
        return "SAPISIDHASH " + time + "_" + hex;
    }

    private static final String MUSIC_ORIGIN = "https://music.youtube.com";
    private static final String WEB_REMIX_VERSION = "1.20260121.03.00";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";

    /**
     * The best audio of an age-restricted track, asked for with the account, the way the YouTube Music
     * site does it (and Metrolist, whose approach this follows): the web player with the session
     * cookies, a proof-of-origin token from Google's BotGuard for the player request and another one
     * for the stream, and the stream link deciphered by YouTube's own player script.
     */
    static AudioStream audio(String videoId) throws Exception {
        String cookies = cookies();
        if (cookies == null || cookie(cookies, "SAPISID") == null) throw new SignInRequiredException();
        return webAudio(videoId, cookies);
    }

    /**
     * The best audio of a track that NewPipe was refused as a bot: the app clients it asks as carry no proof
     * that a real player is asking. The web player sends BotGuard's tokens, which YouTube often accepts
     * from the same network, and the account is used if there is one.
     */
    static AudioStream botCheckedAudio(String videoId) throws Exception {
        String cookies = cookies();
        boolean signedIn = cookies != null && cookie(cookies, "SAPISID") != null;
        return webAudio(videoId, signedIn ? cookies : null);
    }

    /** @param cookies The session of the account, or null to ask as a visitor. */
    private static AudioStream webAudio(String videoId, String cookies) throws Exception {
        Context context = Utils.getContext();
        if (context == null) throw new IOException("No context");

        String[] page = musicPage(cookies);
        String visitorData = page[0];
        String version = page[2] != null ? page[2] : WEB_REMIX_VERSION;
        // A signed-in session is bound to the account's data sync id, an anonymous one to the visitor.
        String session = page[1] != null ? page[1] : visitorData;
        if (session == null) throw new IOException("YouTube Music gave no session data");
        String[] tokens = PoTokenWebView.tokens(context, session, videoId);

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

        HttpURLConnection connection = (HttpURLConnection)
                new URL(MUSIC_ORIGIN + "/youtubei/v1/player?prettyPrint=false").openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
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
            connection.setRequestProperty("Authorization", authorization(cookies, MUSIC_ORIGIN));
        }
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        JSONObject response;
        try (InputStream input = connection.getResponseCode() < 400
                ? connection.getInputStream() : connection.getErrorStream()) {
            response = new JSONObject(read(input));
        }

        JSONObject playability = response.optJSONObject("playabilityStatus");
        String status = playability == null ? "" : playability.optString("status");
        if (!"OK".equalsIgnoreCase(status)) {
            String reason = playability == null ? "" : playability.optString("reason");
            // A session that Google revoked (signed out on all devices, new password) answers like a visitor
            // with LOGIN_REQUIRED. A bot check asks for it too, but says nothing about the session, so it is
            // kept. The saved session is dropped, else the sign-in page would find its cookies and save the
            // same dead session again.
            String lowerReason = reason.toLowerCase(Locale.ROOT);
            if (cookies == null) {
                if (lowerReason.contains("bot")) throw new BotCheckException("YouTube refused the visitor as a bot: " + reason);
                // Age-restricted: a visitor never gets it.
                if ("LOGIN_REQUIRED".equalsIgnoreCase(status)) throw new SignInRequiredException();
                throw new IOException("YouTube refused the visitor request: " + status + " " + reason);
            }
            // A signed-in account that has not confirmed its age is asked to "sign in to confirm your age": its
            // session is fine, and signing in again would change nothing.
            if ("LOGIN_REQUIRED".equalsIgnoreCase(status) && lowerReason.contains("age")) {
                throw new IOException("YouTube asks this account to confirm its age: " + reason);
            }
            if ("LOGIN_REQUIRED".equalsIgnoreCase(status) && !lowerReason.contains("bot")) {
                signOut();
                throw new SignInRequiredException("YouTube no longer accepts the saved session: sign in again");
            }
            if (lowerReason.contains("bot")) throw new BotCheckException("YouTube refused the account as a bot: " + reason);
            throw new IOException("YouTube refused the signed-in request: " + status + " " + reason);
        }
        if (!response.has("streamingData")) throw new IOException("No streaming data");

        JSONArray formats = response.getJSONObject("streamingData").optJSONArray("adaptiveFormats");
        JSONObject best = null;
        for (int i = 0; formats != null && i < formats.length(); i++) {
            JSONObject format = formats.getJSONObject(i);
            if (!format.optString("mimeType").startsWith("audio/mp4")) continue;
            if (best == null || format.optInt("bitrate") > best.optInt("bitrate")) best = format;
        }
        if (best == null) throw new IOException("No m4a audio for " + videoId);

        String url = PlayerCipher.decipherUrl(context, best.optString("signatureCipher", null), best.optString("url", null));
        url += (url.contains("?") ? "&" : "?") + "pot=" + java.net.URLEncoder.encode(tokens[1], "UTF-8");

        return new AudioStream.Builder()
                .setId(String.valueOf(best.optInt("itag")))
                .setContent(url, true)
                .setMediaFormat(MediaFormat.M4A)
                .setAverageBitrate(best.optInt("averageBitrate", best.optInt("bitrate")) / 1000)
                .build();
    }

    /** Visitor data, data sync id and client version from the YouTube Music page of the account or the visitor. */
    private static String[] musicPage(String cookies) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(MUSIC_ORIGIN + "/").openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", DESKTOP_USER_AGENT);
        if (cookies != null) connection.setRequestProperty("Cookie", cookies);
        String html;
        try (InputStream input = connection.getInputStream()) {
            html = read(input);
        }
        String visitor = find(html, "VISITOR_DATA");
        String dataSync = find(html, "DATASYNC_ID");
        if (dataSync != null && dataSync.contains("||")) dataSync = dataSync.substring(0, dataSync.indexOf("||"));
        if (dataSync != null && dataSync.isEmpty()) dataSync = null;
        return new String[]{visitor, dataSync, find(html, "INNERTUBE_CLIENT_VERSION")};
    }

    private static String find(String html, String key) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(html);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toString("UTF-8");
    }
}
