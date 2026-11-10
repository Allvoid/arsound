package app.revanced.extension.soundcloud.network;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.shared.Toasts;

/**
 * The answers of the server to the likes the app sends ({@code /likes/tracks/create} and its three siblings).
 * <p>
 * SoundCloud writes a like to its own database first and sends it to the server after; when the server refuses, the
 * app quietly takes the heart back a moment later and says nothing, so a phone whose likes "disappear" looks the same
 * as one with a bug in the mod. Here every such answer goes to the log (code, and for a refusal the first words of
 * the body), and a refusal also shows one short toast, so the user sees at once that the server, not the phone, said no.
 * <p>
 * Only the answers of likes written to the server are looked at; reading likes and every other request pass untouched.
 */
final class LikeWrites {
    /** The most characters of the server's body that go to the log. */
    static final int BODY_LOG_CHARS = 300;
    /** The refusal toast is not repeated within this time: a burst of likes would cover the screen. */
    private static final long TOAST_GAP_MS = 15_000;
    private static final AtomicLong lastToast = new AtomicLong();

    private LikeWrites() {
    }

    /** True for a request that likes or un-likes a track or a playlist on the server. */
    static boolean isLikeWrite(String host, String path, String method) {
        if (host == null || path == null || method == null) return false;
        if (!host.equals("soundcloud.com") && !host.endsWith(".soundcloud.com")) return false;
        if (method.equals("GET") || method.equals("HEAD")) return false;
        return path.equals("/likes/tracks/create") || path.equals("/likes/tracks/delete")
                || path.equals("/likes/playlists/create") || path.equals("/likes/playlists/delete");
    }

    /** True when the server turned the request down, as opposed to not answering it (5xx) or accepting it. */
    static boolean isRefusal(int code) {
        return code >= 400 && code < 500;
    }

    /** The line of the log: what was asked, what the server said. */
    static String logLine(String path, int code, String body) {
        String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (text.length() > BODY_LOG_CHARS) text = text.substring(0, BODY_LOG_CHARS) + "…";
        return "Like request " + path + " answered " + code + (isRefusal(code) && !text.isEmpty() ? ": " + text : "");
    }

    /** The toast for a refusal. */
    static String toast(int code, boolean russian) {
        if (code == 429) {
            return russian
                    ? "Arsound: SoundCloud не принял лайк (429): слишком много запросов. Лайк не сохранится, подождите."
                    : "Arsound: SoundCloud did not take the like (429): too many requests. It will not be kept, wait a while.";
        }
        if (code == 401 || code == 403) {
            return russian
                    ? "Arsound: SoundCloud не принял лайк (" + code + "): аккаунт или сеть (VPN) закрыты для лайков. Лайк не сохранится."
                    : "Arsound: SoundCloud did not take the like (" + code + "): the account or the network (VPN) is shut out of likes. It will not be kept.";
        }
        return russian
                ? "Arsound: SoundCloud не принял лайк (код " + code + "). Лайк не сохранится."
                : "Arsound: SoundCloud did not take the like (code " + code + "). It will not be kept.";
    }

    /**
     * Looks at the answer to a request that was sent and, when it is a like written to the server, logs it. Never
     * throws: a failure here must not touch the answer the app gets.
     *
     * @param request  The {@code okhttp3.Request}.
     * @param response The {@code okhttp3.Response} the server gave; it is left unread.
     */
    static void watch(String host, String path, Object request, Object response) {
        try {
            String method = (String) request.getClass().getMethod("method").invoke(request);
            if (!isLikeWrite(host, path, method)) return;
            int code = (Integer) response.getClass().getMethod("code").invoke(response);
            String body = isRefusal(code) ? peek(response) : null;
            Logger.printInfo(() -> logLine(path, code, body));
            if (isRefusal(code)) showToast(code);
        } catch (Exception | LinkageError ex) {
            Logger.printException(() -> "Could not log the answer to a like", ex);
        }
    }

    /** A like written to the server that got no answer at all (no network, a cut connection): the log says so. */
    static void failed(String host, String path, Object request, Exception error) {
        try {
            String method = (String) request.getClass().getMethod("method").invoke(request);
            if (isLikeWrite(host, path, method)) Logger.printInfo(() -> "Like request " + path + " got no answer: " + error);
        } catch (Exception | LinkageError ex) {
            Logger.printException(() -> "Could not log the failure of a like", ex);
        }
    }

    /** The first bytes of the body without taking them from the app ({@code Response.peekBody}). */
    private static String peek(Object response) {
        try {
            Method peekBody = response.getClass().getMethod("peekBody", long.class);
            Object body = peekBody.invoke(response, 2048L);
            return (String) body.getClass().getMethod("string").invoke(body);
        } catch (Exception ex) {
            return null;
        }
    }

    private static void showToast(int code) {
        long now = System.currentTimeMillis();
        long before = lastToast.get();
        if (now - before < TOAST_GAP_MS || !lastToast.compareAndSet(before, now)) return;
        if (Utils.getContext() == null) return;
        Toasts.showLongOnMainThread(Utils.getContext(), toast(code, Lang.russian()));
    }
}
