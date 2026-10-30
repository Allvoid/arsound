package app.revanced.extension.soundcloud.transfer;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;

import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The optional Yandex account, for collections only their owner can read (closed likes, private playlists). The
 * token comes from Yandex's own sign-in page ({@link YandexLoginActivity}); it is kept only on this phone and sent only
 * to Yandex Music.
 */
public final class YandexAccount {
    private static final String PREFERENCES = "arsound_yandex";
    private static final String TOKEN = "token";
    private static final String EXPIRES = "expires";
    private static final String NAME = "name";
    private static final String[] COOKIE_HOSTS = {"https://yandex.ru", "https://passport.yandex.ru",
            "https://oauth.yandex.ru", "https://music.yandex.ru", "https://yandex.com", "https://passport.yandex.com",
            "https://oauth.yandex.com"};

    private YandexAccount() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** The token while it is valid; null when not signed in. */
    static String token() {
        SharedPreferences preferences = preferences();
        if (preferences == null) return null;
        long expires = preferences.getLong(EXPIRES, 0);
        if (expires != 0 && expires < System.currentTimeMillis()) return null;
        return preferences.getString(TOKEN, null);
    }

    public static boolean isSignedIn() {
        return token() != null;
    }

    /** The name of the account as Yandex shows it; empty until it is known. */
    public static String name() {
        SharedPreferences preferences = preferences();
        return preferences == null ? "" : preferences.getString(NAME, "");
    }

    static void save(String token, long expiresInSeconds) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        long expires = expiresInSeconds > 0 ? System.currentTimeMillis() + expiresInSeconds * 1000 : 0;
        preferences.edit().putString(TOKEN, token).putLong(EXPIRES, expires).remove(NAME).apply();
    }

    static void saveName(String name) {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().putString(NAME, name).apply();
    }

    /** Forgets a token Yandex no longer takes; the session of the sign-in page stays, so signing in again is quick. */
    static void forgetToken() {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().remove(TOKEN).remove(EXPIRES).apply();
    }

    /**
     * The likes and the playlists of the account, likes first; null if not signed in or Yandex no longer takes the
     * token (then it is forgotten). Remembers the account's name. Blocks: call it off the main thread.
     */
    public static List<YandexPlaylist> library() throws Exception {
        String token = token();
        if (token == null) return null;
        try {
            YandexMusic.Account account = YandexMusic.account(token);
            saveName(account.name);
            return YandexMusic.library(token, account);
        } catch (YandexMusic.SignInExpiredException ex) {
            forgetToken();
            return null;
        }
    }

    /** Forgets the token and the session of the sign-in page, so the next sign-in asks again. */
    public static void signOut() {
        SharedPreferences preferences = preferences();
        if (preferences != null) preferences.edit().clear().apply();
        try {
            CookieManager cookies = CookieManager.getInstance();
            for (String host : COOKIE_HOSTS) {
                String all = cookies.getCookie(host);
                if (all == null) continue;
                // "yandex.ru" of "https://passport.yandex.ru": Yandex sets its session for the whole domain.
                String[] labels = host.substring(host.indexOf("://") + 3).split("\\.");
                String domain = labels[labels.length - 2] + "." + labels[labels.length - 1];
                for (String cookie : all.split(";")) {
                    String name = cookie.split("=", 2)[0].trim();
                    if (name.isEmpty()) continue;
                    cookies.setCookie(host, name + "=; Max-Age=0; Path=/");
                    cookies.setCookie(host, name + "=; Max-Age=0; Path=/; Domain=." + domain);
                }
            }
            cookies.flush();
        } catch (RuntimeException ex) {
            // No WebView on the phone: there is no session to forget either.
            Logger.printInfo(() -> "Yandex sign-out: cookies not cleared: " + ex);
        }
    }
}
