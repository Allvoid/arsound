package app.revanced.extension.soundcloud.transfer;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebSettings;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The VK account of the transfer. VK gives music to no app but its own, so Arsound reads it the way VK's own website
 * does: the user signs in on VK's page ({@link VkLoginActivity}), the session stays in the cookies of that page (the
 * WebView's, private to the app), and for each transfer the website's short-lived access is asked for with them
 * ({@code login.vk.ru/?act=web_token}, VK's web app 6287487). That access is kept only in memory. The password is
 * never seen and nothing but who is signed in is stored here.
 */
final class VkAccount {
    /** VK's own website: the only app whose access the music methods take. */
    static final String WEB_APP_ID = "6287487";
    private static final String PREFERENCES = "arsound_vk";
    private static final String USER = "user";
    private static final String NAME = "name";
    /** "ru" or "com": the domain the session is on. */
    private static final String DOMAIN = "domain";
    private static final String[] DOMAINS = {"ru", "com"};
    private static final String[] COOKIE_HOSTS = {"https://vk.%s", "https://m.vk.%s", "https://login.vk.%s",
            "https://id.vk.%s", "https://api.vk.%s"};

    /** The current access and when it ends (ms); null when none was asked for yet. */
    private static volatile String token;
    private static volatile long tokenExpires;

    private VkAccount() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static boolean isSignedIn() {
        SharedPreferences preferences = preferences();
        return preferences != null && !preferences.getString(USER, "").isEmpty();
    }

    /** The number of the signed-in person, empty when not signed in. */
    static String userId() {
        SharedPreferences preferences = preferences();
        return preferences == null ? "" : preferences.getString(USER, "");
    }

    static String name() {
        SharedPreferences preferences = preferences();
        return preferences == null ? "" : preferences.getString(NAME, "");
    }

    static String domain() {
        SharedPreferences preferences = preferences();
        String domain = preferences == null ? null : preferences.getString(DOMAIN, null);
        return "com".equals(domain) ? "com" : "ru";
    }

    static void saveName(String name) {
        SharedPreferences preferences = preferences();
        if (preferences != null && name != null && !name.isEmpty()) preferences.edit().putString(NAME, name).apply();
    }

    /**
     * Whether the cookies of the sign-in page hold a VK session; if they do, it is remembered. Blocks: off the main
     * thread.
     */
    static boolean takeSession() {
        for (String domain : DOMAINS) {
            try {
                String[] access = webToken(domain);
                if (access == null) continue;
                SharedPreferences preferences = preferences();
                if (preferences == null) return false;
                preferences.edit().putString(USER, access[1]).putString(DOMAIN, domain).remove(NAME).apply();
                remember(access);
                return true;
            } catch (Exception ex) {
                Logger.printInfo(() -> "VK sign-in: no session on vk." + domain + ": " + ex);
            }
        }
        return false;
    }

    /**
     * The access for the API; asked for again when it is about to end or when VK refused it.
     *
     * @param refused The access VK just refused, or null.
     * @throws TransferSource.SignInRequiredException If no one is signed in.
     * @throws TransferSource.SignInExpiredException If the session is gone (then it is forgotten).
     */
    static synchronized String token(String refused) throws Exception {
        if (!isSignedIn()) throw new TransferSource.SignInRequiredException("Not signed in to VK");
        boolean fresh = token != null && System.currentTimeMillis() < tokenExpires - 60_000;
        if (fresh && (refused == null || !refused.equals(token))) return token;
        String[] access = webToken(domain());
        if (access == null || !access[1].equals(userId())) {
            forget();
            throw new TransferSource.SignInExpiredException("The VK session is gone");
        }
        remember(access);
        return token;
    }

    private static synchronized void remember(String[] access) {
        token = access[0];
        long expires = 0;
        try {
            expires = Long.parseLong(access[2]) * 1000;
        } catch (NumberFormatException ignored) {
            // Then it is asked for again in a quarter of an hour.
        }
        tokenExpires = expires > System.currentTimeMillis() ? expires : System.currentTimeMillis() + 15 * 60_000;
    }

    /**
     * Asks VK's website for its access with the cookies of the session: {access token, user id, expires (s)}, or null
     * if there is no session on that domain.
     */
    private static String[] webToken(String domain) throws Exception {
        String cookies = CookieManager.getInstance().getCookie("https://login.vk." + domain);
        if (cookies == null || cookies.isEmpty()) return null;
        Map<String, String> headers = headers(domain);
        headers.put("Cookie", cookies);
        String body = SourceHttp.post("https://login.vk." + domain + "/?act=web_token", headers,
                "application/x-www-form-urlencoded", "version=1&app_id=" + WEB_APP_ID + "&access_token=");
        JSONObject answer = new JSONObject(body);
        if (!"okay".equals(answer.optString("type"))) {
            String why = answer.optString("error_info", answer.optString("type"));
            Logger.printInfo(() -> "VK web token: " + why);
            // Only "unauthorized" means there is no session; anything else is a failure of the moment, and must not
            // sign the user out.
            if ("unauthorized".equals(why)) return null;
            throw new IOException("VK did not give its web token: " + why);
        }
        JSONObject data = answer.optJSONObject("data");
        String access = data == null ? "" : data.optString("access_token", "");
        String user = data == null ? "" : String.valueOf(data.optLong("user_id"));
        if (access.isEmpty() || "0".equals(user)) throw new IOException("VK gave a web token without its parts");
        return new String[]{access, user, String.valueOf(data.optLong("expires"))};
    }

    /** What the website sends: its origin and the browser of the sign-in page. */
    static Map<String, String> headers(String domain) {
        Map<String, String> headers = new HashMap<>();
        headers.put("Origin", "https://vk." + domain);
        headers.put("Referer", "https://vk." + domain + "/");
        headers.put("Accept", "application/json");
        String agent = userAgent();
        if (agent != null) headers.put("User-Agent", agent);
        return headers;
    }

    private static String agent;

    private static synchronized String userAgent() {
        if (agent == null) {
            try {
                Context context = Utils.getContext();
                if (context != null) agent = WebSettings.getDefaultUserAgent(context);
            } catch (RuntimeException ex) {
                // No WebView: the default of the connection is sent.
            }
        }
        return agent;
    }

    static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ex) {
            return value;
        }
    }

    /**
     * Forgets who is signed in and the access; the cookies stay, so signing in again is quick. Not under the lock of
     * {@link #token}, which waits for the network: the sign-out runs on the main thread.
     */
    private static void forget() {
        token = null;
        tokenExpires = 0;
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().clear().apply();
    }

    /** Forgets the account and the session of the sign-in page, so the next sign-in asks again. */
    static void signOut() {
        forget();
        try {
            CookieManager cookies = CookieManager.getInstance();
            for (String domain : DOMAINS) {
                for (String pattern : COOKIE_HOSTS) {
                    String host = String.format(pattern, domain);
                    String all = cookies.getCookie(host);
                    if (all == null) continue;
                    for (String cookie : all.split(";")) {
                        String name = cookie.split("=", 2)[0].trim();
                        if (name.isEmpty()) continue;
                        cookies.setCookie(host, name + "=; Max-Age=0; Path=/");
                        cookies.setCookie(host, name + "=; Max-Age=0; Path=/; Domain=.vk." + domain);
                    }
                }
            }
            cookies.flush();
        } catch (RuntimeException ex) {
            Logger.printInfo(() -> "VK sign-out: cookies not cleared: " + ex);
        }
    }
}
