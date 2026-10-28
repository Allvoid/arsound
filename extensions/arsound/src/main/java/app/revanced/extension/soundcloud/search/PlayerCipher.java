package app.revanced.extension.soundcloud.search;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.network.StreamText;

/**
 * Deciphers YouTube's {@code signatureCipher}/{@code n} playback-URL obfuscation.
 * <p>
 * Ported from innertubex's Faraday/Zemer cipher solving path: {@code ZemerCipherSolver.kt},
 * {@code RemotePlayerConfigParser.kt}, {@code RemotePlayerConfigStore.kt}, {@code
 * QuickJsEngine.kt} ({@code setupYoutubeGlobals}) and {@code YouTubeCipherService.kt}. innertubex
 * runs the derived JS expressions in QuickJS (a native embedded JS engine); this project has no
 * QuickJS dependency, so the same expressions are instead evaluated in a hidden, network-locked
 * {@link WebView} on the main thread.
 * <p>
 * The player's own cipher JS is never fully loaded here. Faraday/Zemer already reverse-engineers
 * YouTube's obfuscated player into two small, hash-keyed JS expression templates per player
 * version ({@code sig} and {@code nClass}, see {@link PlayerConfig}) that are published at
 * {@code https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json}.
 * This class fetches and caches that table, resolves the current player hash from
 * {@code https://www.youtube.com/iframe_api}, and evaluates the matching expression.
 */
public final class PlayerCipher {

    private static final String PLAYER_CONFIGS_URL =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json";
    private static final String IFRAME_API_URL = "https://www.youtube.com/iframe_api";

    private static final long CONFIG_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(6);
    // A player missing from a fresh table may mean the table is older than the player: it is fetched
    // again, but not if it is that young, and not more often than this (the upstream table may lack the player too).
    private static final long CONFIG_REFETCH_MIN_AGE_MS = TimeUnit.MINUTES.toMillis(1);
    private static final long CONFIG_REFETCH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10);
    private static final long PLAYER_HASH_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    // After the script of a player failed to load, it is not downloaded and run again for every track.
    private static final long SOLVER_RETRY_DELAY_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long CONFIG_FETCH_TIMEOUT_MS = 30_000L;
    private static final long NETWORK_READ_TIMEOUT_MS = 15_000L;
    private static final long JS_EVAL_TIMEOUT_MS = 20_000L;

    // Player hash appears in URLs like:
    //   /s/player/<hash>/player_ias.vflset/en_GB/base.js
    //   /s/player/<hash>/player_ias.vflset/...
    // Matches the same shapes as innertubex's RemotePlayerConfigParser.PLAYER_HASH_PATTERNS,
    // applied here to the HTML of https://www.youtube.com/iframe_api rather than to a player
    // script URL, since this class never downloads the (multi-MB) player script itself.
    private static final Pattern[] PLAYER_HASH_PATTERNS = {
            Pattern.compile("/player/([a-f0-9]{8})/"),
            Pattern.compile("player_ias\\.vflset/[^/\"']+/([a-f0-9]{8})/"),
            Pattern.compile("/s/player/([a-f0-9]{8})/"),
    };

    private static volatile Map<String, PlayerConfig> cachedConfigs;
    private static volatile long cachedConfigsAtMs;
    private static volatile long lastConfigRefetchAtMs;
    private static volatile String fetchedPlayerHash;
    private static volatile long fetchedPlayerHashAtMs;

    private PlayerCipher() {
    }

    /** The player YouTube serves now and the cipher config the table has for it. */
    private static final class ResolvedPlayer {
        final String hash;
        final PlayerConfig config;

        ResolvedPlayer(String hash, PlayerConfig config) {
            this.hash = hash;
            this.config = config;
        }
    }

    /**
     * Deciphers a playback URL.
     * <ul>
     *     <li>If {@code signatureCipherOrNull} is given, it is parsed as a query string with
     *     fields {@code s} (the ciphered signature), {@code sp} (the query parameter name the
     *     deciphered signature must be written back to; defaults to {@code "signature"}) and
     *     {@code url} (the base playback URL). The deciphered signature is set as that query
     *     parameter on {@code url}.</li>
     *     <li>Otherwise {@code urlOrNull} is used directly.</li>
     * </ul>
     * In both cases, if the resulting URL carries an {@code n} query parameter, it is run through
     * the player's n-transform and replaced.
     *
     * @throws Exception if the player hash cannot be resolved, the config table has no entry for
     *                    it, or JS evaluation fails/times out.
     */
    public static synchronized String decipherUrl(Context ctx, String signatureCipherOrNull, String urlOrNull) throws Exception {
        String baseUrl;
        String deciphered;
        // One lookup of the player serves both the signature and the n parameter.
        ResolvedPlayer player = null;
        if (signatureCipherOrNull != null) {
            Map<String, String> params = parseQueryString(signatureCipherOrNull);
            String s = params.get("s");
            String urlParam = params.get("url");
            if (s == null || urlParam == null) {
                throw new IOException("signatureCipher missing 's' or 'url'");
            }
            String sp = params.containsKey("sp") ? params.get("sp") : "signature";

            player = resolveConfig();
            deciphered = callSolver(ctx, player, "_cipherSigFunc", s);
            if (deciphered == null) {
                throw new IOException("Failed to decipher signature");
            }
            baseUrl = setQueryParam(urlParam, sp, deciphered);
        } else {
            if (urlOrNull == null) {
                throw new IOException("Both signatureCipher and url are null");
            }
            baseUrl = urlOrNull;
        }

        Uri uri = Uri.parse(baseUrl);
        String n = uri.getQueryParameter("n");
        if (n != null) {
            if (player == null) player = resolveConfig();
            String transformed = callSolver(ctx, player, "_nTransformFunc", n);
            if (transformed != null && !transformed.isEmpty()) {
                baseUrl = setQueryParam(baseUrl, "n", transformed);
            }
        }
        return baseUrl;
    }

    /**
     * @return the signature timestamp ({@code sts}) of the currently resolved player, which
     * callers must echo back as {@code sts} in the player request to get working stream URLs.
     */
    public static synchronized int signatureTimestamp(Context ctx) throws Exception {
        return resolveConfig().config.sts;
    }

    //region Player hash / config resolution

    private static ResolvedPlayer resolveConfig() throws Exception {
        String playerHash = fetchPlayerHash();
        // player_configs.json groups several player hashes under one config when YouTube reuses the same cipher
        // logic across builds; the aliases are listed as keys of the table when it is parsed.
        PlayerConfig config = fetchConfigs(false).get(playerHash);
        if (config == null) {
            config = refetchedConfig(playerHash);
        }
        if (config == null) {
            throw new IOException("Player " + playerHash + " is not in the cipher config");
        }
        return new ResolvedPlayer(playerHash, config);
    }

    /**
     * Takes the table again for a player that a fresh table does not know: it may be older than the
     * player. Tried rarely, so a player that the upstream table lacks too does not fetch it on every play.
     */
    private static PlayerConfig refetchedConfig(String playerHash) {
        long now = System.currentTimeMillis();
        if (now - cachedConfigsAtMs < CONFIG_REFETCH_MIN_AGE_MS
                || now - lastConfigRefetchAtMs < CONFIG_REFETCH_INTERVAL_MS) {
            return null;
        }
        lastConfigRefetchAtMs = now;
        try {
            return fetchConfigs(true).get(playerHash);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not fetch the cipher config again", ex);
            return null;
        }
    }

    private static String fetchPlayerHash() throws Exception {
        // The player changes rarely, so the page is not read for every track.
        String known = fetchedPlayerHash;
        if (known != null && System.currentTimeMillis() - fetchedPlayerHashAtMs < PLAYER_HASH_CACHE_TTL_MS) {
            return known;
        }
        // The script has its slashes escaped inside strings: "player\/7460dd14\/".
        String html = httpGet(IFRAME_API_URL, NETWORK_READ_TIMEOUT_MS).replace("\\/", "/");
        for (Pattern pattern : PLAYER_HASH_PATTERNS) {
            Matcher m = pattern.matcher(html);
            if (m.find()) {
                String hash = m.group(1);
                fetchedPlayerHash = hash;
                fetchedPlayerHashAtMs = System.currentTimeMillis();
                return hash;
            }
        }
        throw new IOException("Could not resolve YouTube player hash from iframe_api");
    }

    private static Map<String, PlayerConfig> fetchConfigs(boolean force) throws Exception {
        Map<String, PlayerConfig> local = cachedConfigs;
        if (!force && local != null && System.currentTimeMillis() - cachedConfigsAtMs < CONFIG_CACHE_TTL_MS) {
            return local;
        }

        String json = httpGet(PLAYER_CONFIGS_URL, CONFIG_FETCH_TIMEOUT_MS);
        Map<String, PlayerConfig> parsed = PlayerConfig.parseTable(json);

        cachedConfigs = parsed;
        cachedConfigsAtMs = System.currentTimeMillis();
        return parsed;
    }

    //endregion

    //region JS evaluation (hidden WebView with the player script)

    private static final String PLAYER_IIFE_TRAILER = "})(_yt_player);";
    private static final String BASE_JS_URL = "https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js";

    private static final String RENDERER_GONE = "render process gone";

    /**
     * The WebView holding the player script of {@link #solverHash}; recreated when the player changes.
     * Written on the main thread (renderer loss) and on the worker, hence volatile.
     */
    private static volatile WebView solver;
    private static volatile String solverHash;
    /** The wait of the lookup running now for the answer of the player script, or null. */
    private static volatile CountDownLatch pendingAnswer;

    // The last player whose script failed to load, and how: told again for a while instead of loading it anew.
    private static String failedHash;
    private static long failedAtMs;
    private static String failedMessage;

    /**
     * Calls {@code window._cipherSigFunc} or {@code window._nTransformFunc} of the loaded player.
     * <p>
     * Like innertubex's ZemerCipherSolver, the whole player script (base.js) runs, with two exports
     * put inside its closure right before {@code })(_yt_player);}: the expressions from the config
     * refer to its private functions and classes, which exist only there. The script is handed to
     * the page through a JavaScript interface and run with a global eval: put into the HTML it could
     * end the script tag early, and it is too big to send whole with evaluateJavascript.
     */
    private static String callSolver(Context ctx, ResolvedPlayer player, String function, String input) throws Exception {
        String hash = player.hash;
        PlayerConfig config = player.config;
        Handler main = new Handler(Looper.getMainLooper());

        if (solver == null || !hash.equals(solverHash)) {
            if (hash.equals(failedHash) && System.currentTimeMillis() - failedAtMs < SOLVER_RETRY_DELAY_MS) {
                throw new IOException(failedMessage + " (not tried again yet)");
            }
            String playerCode = httpGet(String.format(BASE_JS_URL, hash), CONFIG_FETCH_TIMEOUT_MS);
            String sigExpression = config.sigJsExpression.replace("INPUT", "sig");
            String nExpression = config.nJsExpression().replace("INPUT", "n");
            String exports = "; window._cipherSigFunc=function(sig){try{return " + sigExpression + ";}catch(e){return null;}};"
                    + "window._nTransformFunc=function(n){try{return " + nExpression + ";}catch(e){return n;}}; ";
            String modified = playerCode.contains(PLAYER_IIFE_TRAILER)
                    ? playerCode.replace(PLAYER_IIFE_TRAILER, exports + PLAYER_IIFE_TRAILER)
                    : playerCode + "\n" + exports;

            CountDownLatch loaded = new CountDownLatch(1);
            AtomicReference<String> loadError = new AtomicReference<>();
            main.post(() -> {
                try {
                    if (solver != null) solver.destroy();
                    WebView webView = new WebView(ctx.getApplicationContext());
                    //noinspection SetJavaScriptEnabled the player script is JavaScript
                    webView.getSettings().setJavaScriptEnabled(true);
                    webView.getSettings().setBlockNetworkLoads(true);
                    webView.setWebViewClient(new WebViewClient() {
                        @Override
                        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                            // Not consumed, the framework would take the whole app down with the renderer.
                            Logger.printInfo(() -> "Cipher WebView render process gone");
                            if (solver == view) {
                                solver = null;
                                solverHash = null;
                            }
                            try {
                                view.destroy();
                            } catch (Exception ex) {
                                Logger.printInfo(() -> "Cipher WebView teardown failed", ex);
                            }
                            // A load that still waits for the script ends now; the next call builds a new WebView.
                            loadError.compareAndSet(null, RENDERER_GONE);
                            loaded.countDown();
                            CountDownLatch waiting = pendingAnswer;
                            if (waiting != null) waiting.countDown();
                            return true;
                        }
                    });
                    webView.addJavascriptInterface(new Object() {
                        @android.webkit.JavascriptInterface
                        public String code() {
                            return modified;
                        }

                        @android.webkit.JavascriptInterface
                        public void done(String error) {
                            if (error != null && !error.isEmpty()) loadError.set(error);
                            loaded.countDown();
                        }
                    }, "ArsoundPlayer");
                    String html = "<!DOCTYPE html><html><head><script>"
                            + "var _yt_player={};"
                            + "try{(0,eval)(ArsoundPlayer.code());"
                            + "ArsoundPlayer.done(typeof window._cipherSigFunc==='function'?'':'no exports');}"
                            + "catch(e){ArsoundPlayer.done(String(e));}"
                            + "</script></head><body></body></html>";
                    webView.loadDataWithBaseURL("https://www.youtube.com/watch?v=arsound", html, "text/html", "utf-8", null);
                    solver = webView;
                } catch (Exception e) {
                    loadError.set(String.valueOf(e));
                    loaded.countDown();
                }
            });
            if (!loaded.await(JS_EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                dropSolver(main);
                // A slow phone or a cold WebView says nothing about the player: the next call may try again.
                throw new IOException("The player script did not load in " + JS_EVAL_TIMEOUT_MS + " ms");
            }
            String error = loadError.get();
            if (error != null) {
                dropSolver(main);
                String message = "The player script failed: " + error;
                // A dead renderer says nothing about the player: the next call may try again at once.
                throw RENDERER_GONE.equals(error) ? new IOException(message) : rememberLoadFailure(hash, message);
            }
            solverHash = hash;
            failedHash = null;
            Logger.printInfo(() -> "Player script " + hash + " loaded for deciphering");
        }

        CountDownLatch answered = new CountDownLatch(1);
        // A renderer that dies now never calls back: its handler ends this wait instead of the full timeout.
        pendingAnswer = answered;
        AtomicReference<String> result = new AtomicReference<>();
        String script = "(function(){var v=window." + function + "(" + jsStringLiteral(input) + ");"
                + "return v==null?null:String(v);})()";
        main.post(() -> {
            // The solver may have been dropped by a timeout while this waited for the main thread.
            WebView current = solver;
            if (current == null) {
                answered.countDown();
                return;
            }
            current.evaluateJavascript(script, value -> {
                result.set(unquoteJsString(value));
                answered.countDown();
            });
        });
        boolean done = answered.await(JS_EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        pendingAnswer = null;
        if (!done) {
            dropSolver(main);
            throw new IOException(function + " did not answer");
        }
        if (result.get() == null && solver == null) throw new IOException(function + ": " + RENDERER_GONE);
        return result.get();
    }

    private static IOException rememberLoadFailure(String hash, String message) {
        failedHash = hash;
        failedAtMs = System.currentTimeMillis();
        failedMessage = message;
        return new IOException(message);
    }

    private static void dropSolver(Handler main) {
        WebView old = solver;
        solver = null;
        solverHash = null;
        if (old != null) main.post(old::destroy);
    }

    /**
     * Closes the WebView with the player script, for a sign-out: it holds the whole script in memory.
     * Not synchronized, unlike the lookups: one may hold the lock for tens of seconds, and a sign-out
     * click runs on the main thread. A lookup that is running fails and the next one loads the script again.
     */
    public static void releaseSolver() {
        dropSolver(new Handler(Looper.getMainLooper()));
    }

    private static String jsStringLiteral(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                default:
                    sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** evaluateJavascript's callback delivers a JSON-encoded string (or the literal "null"). */
    private static String unquoteJsString(String jsonValue) {
        if (jsonValue == null || jsonValue.equals("null")) {
            return null;
        }
        String v = jsonValue;
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1)
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t");
        }
        return v;
    }

    //endregion

    //region URL / query helpers

    private static Map<String, String> parseQueryString(String query) {
        Map<String, String> result = new HashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            result.put(Uri.decode(key), Uri.decode(value));
        }
        return result;
    }

    private static String setQueryParam(String url, String name, String value) {
        Uri uri = Uri.parse(url);
        Uri.Builder builder = uri.buildUpon().clearQuery();
        boolean replaced = false;
        for (String key : uri.getQueryParameterNames()) {
            if (key.equals(name)) {
                builder.appendQueryParameter(key, value);
                replaced = true;
            } else {
                for (String v : uri.getQueryParameters(key)) {
                    builder.appendQueryParameter(key, v);
                }
            }
        }
        if (!replaced) {
            builder.appendQueryParameter(name, value);
        }
        return builder.build().toString();
    }

    //endregion

    //region Networking

    private static String httpGet(String url, long timeoutMs) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout((int) timeoutMs);
            connection.setReadTimeout((int) timeoutMs);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3");

            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " fetching " + url);
            }
            return StreamText.readUtf8(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    //endregion
}
