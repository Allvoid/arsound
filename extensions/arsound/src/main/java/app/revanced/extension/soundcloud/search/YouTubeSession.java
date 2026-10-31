package app.revanced.extension.soundcloud.search;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

import app.revanced.extension.shared.Utils;

/**
 * The cookies of the signed-in YouTube Music account. They stay in the app's private storage and go only to
 * youtube.com.
 */
final class YouTubeSession {
    private static final String PREFERENCES_NAME = "arsound_youtube_account";
    private static final String COOKIES = "cookies";
    private static final String ORIGIN = "https://www.youtube.com";

    private YouTubeSession() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** The saved cookie header, or null if no account is signed in. */
    static String cookies() {
        SharedPreferences preferences = preferences();
        String cookies = preferences == null ? null : preferences.getString(COOKIES, null);
        return cookies != null && cookie(cookies, "SAPISID") != null ? cookies : null;
    }

    static boolean isSignedIn() {
        return cookies() != null;
    }

    /** Saves the session of the sign-in screen. @return False if the browser has no YouTube session. */
    static boolean save() {
        String cookies = CookieManager.getInstance().getCookie(ORIGIN);
        if (cookies == null || cookie(cookies, "SAPISID") == null) return false;
        SharedPreferences preferences = preferences();
        if (preferences == null) return false;
        preferences.edit().putString(COOKIES, cookies).apply();
        return true;
    }

    /** The sites whose cookies are the YouTube session; the cookies of other sites (the Yandex sign-in) stay. */
    private static final String[][] SESSION_SITES = {
            {"https://music.youtube.com", ".youtube.com"}, {"https://www.youtube.com", ".youtube.com"},
            {"https://m.youtube.com", ".youtube.com"}, {"https://accounts.google.com", ".google.com"},
            {"https://www.google.com", ".google.com"}, {"https://accounts.youtube.com", ".youtube.com"}};

    static void signOut() {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().clear().apply();
        // Only the cookies of YouTube and Google: removing all of them signed the user out of Yandex too. A cookie is
        // removed by setting it again already expired, for the site and for its whole domain.
        CookieManager cookies = CookieManager.getInstance();
        for (String[] site : SESSION_SITES) {
            String header = cookies.getCookie(site[0]);
            if (header == null) continue;
            for (String part : header.split(";")) {
                int equals = part.indexOf('=');
                String name = (equals < 0 ? part : part.substring(0, equals)).trim();
                if (name.isEmpty()) continue;
                String expired = name + "=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/";
                cookies.setCookie(site[0], expired);
                cookies.setCookie(site[0], expired + "; Domain=" + site[1]);
            }
        }
        cookies.flush();
        // The hidden WebViews of the age-restricted lookups stay alive otherwise. Neither call waits for
        // the lookup lock, which a lookup holds for tens of seconds: the sign-out click runs on the main thread.
        PoTokenWebView.releaseAll();
        PlayerCipher.releaseSolver();
    }

    /** The value of one cookie of a cookie header, or null. */
    static String cookie(String cookies, String name) {
        for (String part : cookies.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) return trimmed.substring(name.length() + 1);
        }
        return null;
    }

    /** The header Google's web apps use to prove the session: SHA-1 of time, SAPISID and origin. */
    static String authorization(String cookies, String origin) throws Exception {
        String sapisid = cookie(cookies, "SAPISID");
        long time = System.currentTimeMillis() / 1000;
        byte[] hash = MessageDigest.getInstance("SHA-1")
                .digest((time + " " + sapisid + " " + origin).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b));
        return "SAPISIDHASH " + time + "_" + hex;
    }
}
