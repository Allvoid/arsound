package app.revanced.extension.soundcloud.search;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.shared.Logger;

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
 * version ({@code sig} and {@code nClass}, see {@link Player_configs}) that are published at
 * {@code https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json}.
 * This class fetches and caches that table, resolves the current player hash from
 * {@code https://www.youtube.com/iframe_api}, and evaluates the matching expression.
 */
public final class PlayerCipher {

    private static final String PLAYER_CONFIGS_URL =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json";
    private static final String IFRAME_API_URL = "https://www.youtube.com/iframe_api";

    private static final long CONFIG_CACHE_TTL_MS = TimeUnit.HOURS.toMillis(6);
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
    private static volatile String cachedPlayerHash;
    private static volatile PlayerConfig cachedResolvedConfig;

    private PlayerCipher() {
    }

    /**
     * A single hash-keyed entry from {@code player_configs.json}: a JS expression template for
     * deriving the signature ({@code sig}, with {@code INPUT} standing in for the ciphered
     * value), the class name used to n-transform ({@code nClass}), and the signature timestamp
     * ({@code sts}) YouTube's player API expects to be echoed back.
     */
    private static final class PlayerConfig {
        final String sigJsExpression;
        final String nClass;
        final int sts;

        PlayerConfig(String sigJsExpression, String nClass, int sts) {
            this.sigJsExpression = sigJsExpression;
            this.nClass = nClass;
            this.sts = sts;
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
        if (signatureCipherOrNull != null) {
            Map<String, String> params = parseQueryString(signatureCipherOrNull);
            String s = params.get("s");
            String urlParam = params.get("url");
            if (s == null || urlParam == null) {
                throw new IOException("signatureCipher missing 's' or 'url'");
            }
            String sp = params.containsKey("sp") ? params.get("sp") : "signature";

            resolveConfig(ctx);
            deciphered = callSolver(ctx, "_cipherSigFunc", s);
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
            resolveConfig(ctx);
            String transformed = callSolver(ctx, "_nTransformFunc", n);
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
        return resolveConfig(ctx).sts;
    }

    //region Player hash / config resolution

    private static PlayerConfig resolveConfig(Context ctx) throws Exception {
        String playerHash = fetchPlayerHash();
        Map<String, PlayerConfig> configs = fetchConfigs();

        PlayerConfig config = configs.get(playerHash);
        if (config == null) {
            // Not found directly: search every entry's aliases (player_configs.json groups
            // several player hashes under one config when YouTube reuses the same cipher logic
            // across builds).
            config = findByAlias(configs, playerHash);
        }
        if (config == null) {
            throw new IOException("Player " + playerHash + " is not in the cipher config");
        }

        cachedPlayerHash = playerHash;
        cachedResolvedConfig = config;
        return config;
    }

    private static PlayerConfig findByAlias(Map<String, PlayerConfig> configs, String hash) {
        // player_configs.json's schema nests "aliases" inside each hash's own object; since we
        // parse the whole document generically below, aliases are flattened into the same map
        // (each alias hash is inserted as its own key pointing at the parent's PlayerConfig) at
        // parse time, so a direct map lookup already covers this. This method stays as a defensive
        // no-op fallback in case of a parser edge case.
        return configs.get(hash);
    }

    private static String fetchPlayerHash() throws Exception {
        // The script has its slashes escaped inside strings: "player\/7460dd14\/".
        String html = httpGet(IFRAME_API_URL, NETWORK_READ_TIMEOUT_MS).replace("\\/", "/");
        for (Pattern pattern : PLAYER_HASH_PATTERNS) {
            Matcher m = pattern.matcher(html);
            if (m.find()) {
                return m.group(1);
            }
        }
        throw new IOException("Could not resolve YouTube player hash from iframe_api");
    }

    private static Map<String, PlayerConfig> fetchConfigs() throws Exception {
        Map<String, PlayerConfig> local = cachedConfigs;
        if (local != null && System.currentTimeMillis() - cachedConfigsAtMs < CONFIG_CACHE_TTL_MS) {
            return local;
        }

        String json = httpGet(PLAYER_CONFIGS_URL, CONFIG_FETCH_TIMEOUT_MS);
        Map<String, PlayerConfig> parsed = parsePlayerConfigs(json);

        cachedConfigs = parsed;
        cachedConfigsAtMs = System.currentTimeMillis();
        return parsed;
    }

    /**
     * Parses {@code player_configs.json}: {@code {"schemaVersion":1,"players":{"<hash>":
     * {"sig":"...","nClass":"...","sts":12345,"aliases":["<hash2>",...]}}}}. Each entry's
     * "sig" is a JS expression such as {@code Tl(48,5831,INPUT)}, matching innertubex's
     * {@code RemotePlayerConfigParser} expectations.
     */
    private static Map<String, PlayerConfig> parsePlayerConfigs(String json) throws IOException {
        Object rootObj = new SimpleJsonParser(json).parse();
        if (!(rootObj instanceof Map)) {
            throw new IOException("player_configs.json root is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) rootObj;

        Object schemaVersionObj = root.get("schemaVersion");
        if (!(schemaVersionObj instanceof Number) || ((Number) schemaVersionObj).intValue() != 1) {
            throw new IOException("Unsupported player_configs.json schemaVersion: " + schemaVersionObj);
        }

        Object playersObj = root.get("players");
        if (!(playersObj instanceof Map)) {
            throw new IOException("player_configs.json 'players' is not an object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> players = (Map<String, Object>) playersObj;

        Map<String, PlayerConfig> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : players.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> obj = (Map<String, Object>) entry.getValue();

            Object sigObj = obj.get("sig");
            Object nClassObj = obj.get("nClass");
            Object stsObj = obj.get("sts");
            if (!(sigObj instanceof String) || !(nClassObj instanceof String) || !(stsObj instanceof Number)) {
                continue; // malformed entry: skip, mirroring RemotePlayerConfigParser's per-entry skip behavior
            }

            PlayerConfig config = new PlayerConfig((String) sigObj, (String) nClassObj, ((Number) stsObj).intValue());
            result.put(entry.getKey(), config);

            Object aliasesObj = obj.get("aliases");
            if (aliasesObj instanceof List) {
                for (Object alias : (List<?>) aliasesObj) {
                    if (alias instanceof String) {
                        result.put((String) alias, config);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Builds the n-transform JS expression the same way innertubex's
     * {@code RemotePlayerConfigParser.buildNJsExpression} does: it instantiates the player's own
     * n-transform class ({@code nClass}) against a synthetic {@code googlevideo.com} URL and reads
     * back its {@code n} query parameter, since the class's real entry point operates on a whole
     * URL object rather than a bare string.
     */
    private static String buildNJsExpression(String nClass) {
        return "(function(n){try{var u=new g." + nClass + "('https://x.googlevideo.com/videoplayback?n='+n,true);"
                + "var t=u.get('n');return(t&&t!==n)?t:n;}catch(e){return n;}})(INPUT)";
    }

    //endregion

    //region JS evaluation (hidden WebView with the player script)

    private static final String PLAYER_IIFE_TRAILER = "})(_yt_player);";
    private static final String BASE_JS_URL = "https://www.youtube.com/s/player/%s/player_ias.vflset/en_US/base.js";

    /** The WebView holding the player script of {@link #solverHash}; recreated when the player changes. */
    private static WebView solver;
    private static String solverHash;

    /**
     * Calls {@code window._cipherSigFunc} or {@code window._nTransformFunc} of the loaded player.
     * <p>
     * Like innertubex's ZemerCipherSolver, the whole player script (base.js) runs, with two exports
     * put inside its closure right before {@code })(_yt_player);}: the expressions from the config
     * refer to its private functions and classes, which exist only there. The script is handed to
     * the page through a JavaScript interface and run with a global eval: put into the HTML it could
     * end the script tag early, and it is too big to send whole with evaluateJavascript.
     */
    private static String callSolver(Context ctx, String function, String input) throws Exception {
        String hash = cachedPlayerHash;
        PlayerConfig config = cachedResolvedConfig;
        if (hash == null || config == null) throw new IOException("No resolved player");
        Handler main = new Handler(Looper.getMainLooper());

        if (solver == null || !hash.equals(solverHash)) {
            String playerCode = httpGet(String.format(BASE_JS_URL, hash), CONFIG_FETCH_TIMEOUT_MS);
            String sigExpression = config.sigJsExpression.replace("INPUT", "sig");
            String nExpression = buildNJsExpression(config.nClass).replace("INPUT", "n");
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
                throw new IOException("The player script did not load in " + JS_EVAL_TIMEOUT_MS + " ms");
            }
            if (loadError.get() != null) {
                dropSolver(main);
                throw new IOException("The player script failed: " + loadError.get());
            }
            solverHash = hash;
            Logger.printInfo(() -> "Player script " + hash + " loaded for deciphering");
        }

        CountDownLatch answered = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        String script = "(function(){var v=window." + function + "(" + jsStringLiteral(input) + ");"
                + "return v==null?null:String(v);})()";
        main.post(() -> solver.evaluateJavascript(script, value -> {
            result.set(unquoteJsString(value));
            answered.countDown();
        }));
        if (!answered.await(JS_EVAL_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            dropSolver(main);
            throw new IOException(function + " did not answer");
        }
        return result.get();
    }

    private static void dropSolver(Handler main) {
        WebView old = solver;
        solver = null;
        solverHash = null;
        if (old != null) main.post(old::destroy);
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
            return readStream(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
    }

    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }

    //endregion

    /**
     * A minimal recursive-descent JSON object/array parser sufficient for player_configs.json's
     * shape (nested objects, arrays of strings, string/number/boolean/null leaves). Avoids pulling
     * in a JSON dependency for this single call site.
     */
    private static final class SimpleJsonParser {
        private final String s;
        private int pos;

        SimpleJsonParser(String s) {
            this.s = s;
        }

        Object parse() throws IOException {
            skipWhitespace();
            return parseValue();
        }

        private Object parseValue() throws IOException {
            skipWhitespace();
            if (pos >= s.length()) {
                throw new IOException("Unexpected end of JSON");
            }
            char c = s.charAt(pos);
            if (c == '{') {
                return parseObject();
            } else if (c == '[') {
                return parseArray();
            } else if (c == '"') {
                return parseString();
            } else if (c == 'n' && s.startsWith("null", pos)) {
                pos += 4;
                return null;
            } else if (c == 't' && s.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            } else if (c == 'f' && s.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            } else {
                return parseNumber();
            }
        }

        private Map<String, Object> parseObject() throws IOException {
            Map<String, Object> map = new HashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (pos >= s.length() || s.charAt(pos) != ':') {
                    throw new IOException("Expected ':' in JSON object");
                }
                pos++;
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new IOException("Unterminated JSON object");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    break;
                } else {
                    throw new IOException("Malformed JSON object");
                }
            }
            return map;
        }

        private List<Object> parseArray() throws IOException {
            java.util.List<Object> list = new java.util.ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new IOException("Unterminated JSON array");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    break;
                } else {
                    throw new IOException("Malformed JSON array");
                }
            }
            return list;
        }

        private String parseString() throws IOException {
            if (pos >= s.length() || s.charAt(pos) != '"') {
                throw new IOException("Expected string in JSON");
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        break;
                    }
                    char esc = s.charAt(pos++);
                    switch (esc) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            String hex = s.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                            break;
                        default:
                            sb.append(esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IOException("Unterminated JSON string");
        }

        private Number parseNumber() {
            int start = pos;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || "+-.eE".indexOf(s.charAt(pos)) >= 0)) {
                pos++;
            }
            String numStr = s.substring(start, pos);
            if (numStr.contains(".") || numStr.contains("e") || numStr.contains("E")) {
                return Double.parseDouble(numStr);
            }
            return Long.parseLong(numStr);
        }

        private void skipWhitespace() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }
    }
}
