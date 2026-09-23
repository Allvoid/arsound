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
import app.arsound.shaded.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager;
import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * An optional YouTube Music account for the Arsound search. Anonymous requests cannot get age-restricted
 * tracks; with the account's cookies the player answers as for a signed-in listener.
 * <p>
 * The cookies stay in the app's private storage and go only to youtube.com. NewPipe asks YouTube as the
 * Android app, which ignores cookies, so age-restricted tracks are asked for here as the TV app, the
 * client that accepts a signed-in web session without extra tokens.
 */
public final class YouTubeAccount {
    private static final String PREFERENCES_NAME = "arsound_youtube_account";
    private static final String COOKIES = "cookies";
    private static final String NAME = "name";
    private static final String ORIGIN = "https://www.youtube.com";
    private static final String TV_CLIENT_VERSION = "7.20250923.13.00";
    private static final String TV_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version";

    private YouTubeAccount() {
    }

    /** No account: the track is age-restricted and needs a signed-in account. */
    public static final class SignInRequiredException extends IOException {
        SignInRequiredException() {
            super("The track is age-restricted: sign in to YouTube Music");
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
    }

    private static String cookie(String cookies, String name) {
        for (String part : cookies.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) return trimmed.substring(name.length() + 1);
        }
        return null;
    }

    /** The header Google's web apps use to prove the session: SHA-1 of time, SAPISID and origin. */
    private static String authorization(String cookies) throws Exception {
        String sapisid = cookie(cookies, "SAPISID");
        long time = System.currentTimeMillis() / 1000;
        byte[] hash = MessageDigest.getInstance("SHA-1")
                .digest((time + " " + sapisid + " " + ORIGIN).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
        return "SAPISIDHASH " + time + "_" + hex;
    }

    /** The best audio of an age-restricted track, asked for with the account. */
    static AudioStream audio(String videoId) throws Exception {
        String cookies = cookies();
        if (cookies == null || cookie(cookies, "SAPISID") == null) throw new SignInRequiredException();

        JSONObject body = new JSONObject()
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
                .put("context", new JSONObject().put("client", new JSONObject()
                        .put("clientName", "TVHTML5")
                        .put("clientVersion", TV_CLIENT_VERSION)
                        .put("hl", "en")))
                .put("playbackContext", new JSONObject().put("contentPlaybackContext", new JSONObject()
                        .put("signatureTimestamp", YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId))));

        HttpURLConnection connection = (HttpURLConnection)
                new URL(ORIGIN + "/youtubei/v1/player?prettyPrint=false").openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", TV_USER_AGENT);
        connection.setRequestProperty("Origin", ORIGIN);
        connection.setRequestProperty("X-Origin", ORIGIN);
        connection.setRequestProperty("X-YouTube-Client-Name", "7");
        connection.setRequestProperty("X-YouTube-Client-Version", TV_CLIENT_VERSION);
        connection.setRequestProperty("Cookie", cookies);
        connection.setRequestProperty("Authorization", authorization(cookies));
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
            Logger.printInfo(() -> "Signed-in player: " + status + " " + reason);
            throw new IOException("YouTube refused the signed-in request: " + status + " " + reason);
        }

        JSONArray formats = response.getJSONObject("streamingData").optJSONArray("adaptiveFormats");
        JSONObject best = null;
        for (int i = 0; formats != null && i < formats.length(); i++) {
            JSONObject format = formats.getJSONObject(i);
            if (!format.optString("mimeType").startsWith("audio/mp4")) continue;
            if (best == null || format.optInt("bitrate") > best.optInt("bitrate")) best = format;
        }
        if (best == null) throw new IOException("No m4a audio for " + videoId);

        String url = best.optString("url", null);
        if (url == null) {
            // Protected links carry the signature apart; the player script turns it into the real one.
            String cipher = best.getString("signatureCipher");
            String s = null, sp = "signature", base = null;
            for (String part : cipher.split("&")) {
                int equals = part.indexOf('=');
                String key = part.substring(0, equals);
                String value = java.net.URLDecoder.decode(part.substring(equals + 1), "UTF-8");
                if (key.equals("s")) s = value;
                else if (key.equals("sp")) sp = value;
                else if (key.equals("url")) base = value;
            }
            url = base + "&" + sp + "=" + java.net.URLEncoder.encode(
                    YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, s), "UTF-8");
        }
        url = YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url);

        return new AudioStream.Builder()
                .setId(String.valueOf(best.optInt("itag")))
                .setContent(url, true)
                .setMediaFormat(MediaFormat.M4A)
                .setAverageBitrate(best.optInt("averageBitrate", best.optInt("bitrate")) / 1000)
                .build();
    }

    private static String read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toString("UTF-8");
    }
}
