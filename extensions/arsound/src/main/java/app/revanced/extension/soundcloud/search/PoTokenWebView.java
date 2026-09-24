package app.revanced.extension.soundcloud.search;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import app.revanced.extension.shared.Logger;

/**
 * Generates YouTube BotGuard poTokens (session + per-video) using a hidden {@link WebView}
 * running BotGuard's own JavaScript, the same technique NewPipeExtractor and yt-dlp use.
 * <p>
 * Ported from Metrolist (GPLv3): {@code PoTokenWebView.kt}, {@code JavaScriptUtil.kt} and
 * {@code PoTokenGenerator.kt}, collapsed into a single Java class and adapted to this
 * project's synchronous/blocking extraction model (NewPipeExtractor calls, which this
 * project already drives from background threads, are blocking rather than coroutine based).
 * <p>
 * A {@link WebView} may only be touched from the thread that created it, so every WebView
 * operation (creation, JS evaluation, destruction) is dispatched to a {@link Handler} bound to
 * {@link Looper#getMainLooper()}. The public API of this class is blocking: it is meant to be
 * called from a background thread (as NewPipeExtractor downloaders are), and internally uses a
 * {@link CountDownLatch} to park the calling thread until the main-thread WebView work (which
 * itself waits on network + JS callbacks) completes or times out.
 */
public final class PoTokenWebView {

    // Ported from Metrolist (GPLv3), originally NewPipe: BotGuard bootstrap page loaded into the hidden WebView.
    private static final String PO_TOKEN_HTML =
            "<!DOCTYPE html>\n" +
            "<html lang=\"en\"><head><title></title><script>\n" +
            "    /**\n" +
            "     * BotGuard client for generating poTokens.\n" +
            "     * Updated to fix JavaScript 'this' binding issues and match BgUtils v3.2.0 patterns.\n" +
            "     */\n" +
            "\n" +
            "    // Global state for BotGuard\n" +
            "    var bgVmFunctions = null;\n" +
            "    var bgVm = null;\n" +
            "    var bgProgram = null;\n" +
            "    var poTokenMinter = null;  // Minter callback - created ONCE during init, reused for all tokens\n" +
            "\n" +
            "    function loadBotGuard(challengeData) {\n" +
            "      bgVm = window[challengeData.globalName];\n" +
            "      bgProgram = challengeData.program;\n" +
            "      bgVmFunctions = null;\n" +
            "\n" +
            "      if (!bgVm)\n" +
            "        throw new Error('[BotGuardClient]: VM not found in the global object');\n" +
            "\n" +
            "      if (!bgVm.a)\n" +
            "        throw new Error('[BotGuardClient]: Could not load program');\n" +
            "\n" +
            "      // Use explicit variable capture instead of 'this' to avoid binding issues\n" +
            "      var vmFunctionsCallback = function (\n" +
            "        asyncSnapshotFunction,\n" +
            "        shutdownFunction,\n" +
            "        passEventFunction,\n" +
            "        checkCameraFunction\n" +
            "      ) {\n" +
            "        bgVmFunctions = {\n" +
            "          asyncSnapshotFunction: asyncSnapshotFunction,\n" +
            "          shutdownFunction: shutdownFunction,\n" +
            "          passEventFunction: passEventFunction,\n" +
            "          checkCameraFunction: checkCameraFunction\n" +
            "        };\n" +
            "      };\n" +
            "\n" +
            "      // Execute the BotGuard program\n" +
            "      try {\n" +
            "        bgVm.a(bgProgram, vmFunctionsCallback, true, undefined, function () {/** no-op */ }, [ [], [] ]);\n" +
            "      } catch (e) {\n" +
            "        throw new Error('[BotGuardClient]: Failed to execute program: ' + e.message);\n" +
            "      }\n" +
            "\n" +
            "      // Wait for vmFunctions to be populated (async callback)\n" +
            "      return new Promise(function (resolve, reject) {\n" +
            "        var attempts = 0;\n" +
            "        var maxAttempts = 10000; // 10 seconds at 1ms intervals\n" +
            "        var checkInterval = setInterval(function () {\n" +
            "          if (bgVmFunctions && bgVmFunctions.asyncSnapshotFunction) {\n" +
            "            clearInterval(checkInterval);\n" +
            "            resolve({\n" +
            "              vmFunctions: bgVmFunctions,\n" +
            "              vm: bgVm,\n" +
            "              program: bgProgram\n" +
            "            });\n" +
            "          } else if (attempts >= maxAttempts) {\n" +
            "            clearInterval(checkInterval);\n" +
            "            reject(new Error('[BotGuardClient]: Timeout waiting for asyncSnapshotFunction'));\n" +
            "          }\n" +
            "          attempts++;\n" +
            "        }, 1);\n" +
            "      });\n" +
            "    }\n" +
            "\n" +
            "    /**\n" +
            "     * Takes a snapshot asynchronously using the loaded BotGuard.\n" +
            "     */\n" +
            "    function snapshot(botguard, args) {\n" +
            "      return new Promise(function (resolve, reject) {\n" +
            "        if (!botguard.vmFunctions || !botguard.vmFunctions.asyncSnapshotFunction) {\n" +
            "          return reject(new Error('[BotGuardClient]: Async snapshot function not found'));\n" +
            "        }\n" +
            "\n" +
            "        try {\n" +
            "          botguard.vmFunctions.asyncSnapshotFunction(\n" +
            "            function (response) { resolve(response); },\n" +
            "            [\n" +
            "              args.contentBinding,\n" +
            "              args.signedTimestamp,\n" +
            "              args.webPoSignalOutput,\n" +
            "              args.skipPrivacyBuffer\n" +
            "            ]\n" +
            "          );\n" +
            "        } catch (e) {\n" +
            "          reject(new Error('[BotGuardClient]: Snapshot failed: ' + e.message));\n" +
            "        }\n" +
            "      });\n" +
            "    }\n" +
            "\n" +
            "    function runBotGuard(challengeData) {\n" +
            "      var interpreterJavascript = challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;\n" +
            "\n" +
            "      if (interpreterJavascript) {\n" +
            "        new Function(interpreterJavascript)();\n" +
            "      } else {\n" +
            "        throw new Error('[BotGuardClient]: Could not load VM - no interpreter JavaScript');\n" +
            "      }\n" +
            "\n" +
            "      var webPoSignalOutput = [];\n" +
            "\n" +
            "      return loadBotGuard({\n" +
            "        globalName: challengeData.globalName,\n" +
            "        program: challengeData.program\n" +
            "      }).then(function (botguard) {\n" +
            "        return snapshot(botguard, { webPoSignalOutput: webPoSignalOutput });\n" +
            "      }).then(function (botguardResponse) {\n" +
            "        return { webPoSignalOutput: webPoSignalOutput, botguardResponse: botguardResponse };\n" +
            "      });\n" +
            "    }\n" +
            "\n" +
            "    /**\n" +
            "     * Creates the poToken minter callback. MUST be called exactly ONCE during initialization,\n" +
            "     * right after runBotGuard completes. The minter is stored globally and reused for all tokens.\n" +
            "     * NOTE: getMinter() may return a Promise, so this function is async.\n" +
            "     * @param webPoSignalOutput - Array from BotGuard containing the minter factory function\n" +
            "     * @param integrityToken - The integrity token (as bytes/Uint8Array)\n" +
            "     * @returns Promise that resolves when minter is ready\n" +
            "     */\n" +
            "    async function createPoTokenMinter(webPoSignalOutput, integrityToken) {\n" +
            "      console.log('[createPoTokenMinter] ENTER - webPoSignalOutput type: ' + typeof webPoSignalOutput + ', length: ' + (webPoSignalOutput ? webPoSignalOutput.length : 'null'));\n" +
            "      console.log('[createPoTokenMinter] integrityToken type: ' + typeof integrityToken + ', is array: ' + Array.isArray(integrityToken));\n" +
            "\n" +
            "      // Get the minter factory function from webPoSignalOutput\n" +
            "      var getMinter = webPoSignalOutput[0];\n" +
            "      console.log('[createPoTokenMinter] getMinter type: ' + typeof getMinter);\n" +
            "\n" +
            "      if (!getMinter) {\n" +
            "        throw new Error('PMD:Undefined - webPoSignalOutput[0] is not defined');\n" +
            "      }\n" +
            "\n" +
            "      if (typeof getMinter !== 'function') {\n" +
            "        throw new Error('PMD:NotFunction - webPoSignalOutput[0] is not a function, got: ' + typeof getMinter);\n" +
            "      }\n" +
            "\n" +
            "      // Create the mint callback by passing the integrity token - THIS MUST ONLY HAPPEN ONCE!\n" +
            "      // NOTE: getMinter() may return a Promise, so we await it\n" +
            "      console.log('[createPoTokenMinter] Calling getMinter(integrityToken)...');\n" +
            "      var mintCallback;\n" +
            "      try {\n" +
            "        var minterResult = getMinter(integrityToken);\n" +
            "        console.log('[createPoTokenMinter] getMinter returned type: ' + typeof minterResult + ', isPromise: ' + (minterResult && typeof minterResult.then === 'function'));\n" +
            "        // Handle both sync and async getMinter\n" +
            "        if (minterResult && typeof minterResult.then === 'function') {\n" +
            "          console.log('[createPoTokenMinter] getMinter returned a Promise, awaiting...');\n" +
            "          mintCallback = await minterResult;\n" +
            "          console.log('[createPoTokenMinter] Promise resolved, mintCallback type: ' + typeof mintCallback);\n" +
            "        } else {\n" +
            "          mintCallback = minterResult;\n" +
            "        }\n" +
            "      } catch (e) {\n" +
            "        console.log('[createPoTokenMinter] getMinter EXCEPTION: ' + e.message);\n" +
            "        throw new Error('GMC:Failed - getMinter() threw: ' + e.message);\n" +
            "      }\n" +
            "\n" +
            "      if (!mintCallback) {\n" +
            "        throw new Error('APF:Undefined - mintCallback is undefined');\n" +
            "      }\n" +
            "\n" +
            "      if (typeof mintCallback !== 'function') {\n" +
            "        throw new Error('APF:NotFunction - mintCallback is not a function, got: ' + typeof mintCallback);\n" +
            "      }\n" +
            "\n" +
            "      // Store the minter globally for reuse\n" +
            "      poTokenMinter = mintCallback;\n" +
            "      console.log('[createPoTokenMinter] SUCCESS - Minter created and stored globally (type: ' + typeof mintCallback + ')');\n" +
            "    }\n" +
            "\n" +
            "    /**\n" +
            "     * Mints a poToken using the pre-created minter callback.\n" +
            "     * The minter MUST have been created during initialization via createPoTokenMinter().\n" +
            "     * NOTE: mintCallback() may return a Promise, so this function is async.\n" +
            "     * @param identifier - The identifier to bind the token to (as Uint8Array)\n" +
            "     * @returns Promise<Uint8Array> containing the poToken\n" +
            "     */\n" +
            "    async function obtainPoToken(identifier) {\n" +
            "      if (!poTokenMinter) {\n" +
            "        throw new Error('MNT:NotInit - poTokenMinter was not initialized. Call createPoTokenMinter first.');\n" +
            "      }\n" +
            "\n" +
            "      // Mint the token with the identifier\n" +
            "      // NOTE: mintCallback() may return a Promise, so we await it\n" +
            "      var result;\n" +
            "      try {\n" +
            "        var mintResult = poTokenMinter(identifier);\n" +
            "        // Handle both sync and async mintCallback\n" +
            "        if (mintResult && typeof mintResult.then === 'function') {\n" +
            "          console.log('[obtainPoToken] mintCallback returned a Promise, awaiting...');\n" +
            "          result = await mintResult;\n" +
            "        } else {\n" +
            "          result = mintResult;\n" +
            "        }\n" +
            "      } catch (e) {\n" +
            "        throw new Error('MNT:Failed - mintCallback() threw: ' + e.message);\n" +
            "      }\n" +
            "\n" +
            "      if (!result) {\n" +
            "        throw new Error('YNJ:Undefined - mint result is undefined');\n" +
            "      }\n" +
            "\n" +
            "      if (!(result instanceof Uint8Array)) {\n" +
            "        throw new Error('ODM:Invalid - result is not Uint8Array, got: ' + Object.prototype.toString.call(result));\n" +
            "      }\n" +
            "\n" +
            "      // Validate token size (expected 110-128 bytes per BgUtils documentation)\n" +
            "      if (result.length < 100 || result.length > 140) {\n" +
            "        console.warn('[obtainPoToken] Token size ' + result.length + ' bytes may be outside expected range (110-128)');\n" +
            "      }\n" +
            "\n" +
            "      return result;\n" +
            "    }\n" +
            "</script></head><body></body></html>\n" +
            "";

    private static final String TAG = "PoTokenWebView";

    /** Same key NewPipeExtractor/Metrolist use to request a BotGuard challenge for YouTube Music/Web. */
    private static final String REQUEST_KEY = "O43z0dpjhgX20SCx4KAo";
    private static final String GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw";
    private static final String JS_INTERFACE = "PoTokenWebView";
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3";

    // Init does network round-trips (botguard Create/GenerateIT) plus JS execution; a WebView
    // that hasn't finished after this long has a dead/wedged renderer or dead network.
    private static final long INIT_TIMEOUT_MS = 20_000L;
    // A live renderer mints a poToken in well under a second; 8s leaves slack for a slow device.
    private static final long GENERATE_TIMEOUT_MS = 8_000L;
    // Leave 10 minutes of margin on the token's reported expiration, just to be safe.
    private static final long EXPIRATION_MARGIN_MS = TimeUnit.MINUTES.toMillis(10);

    /** One cached minter per session id; recreated when expired, dead, or the session id changes. */
    private static final Map<String, PoTokenWebView> CACHE = new ConcurrentHashMap<>();

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, CountDownLatchResult> pendingTokenRequests = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong requestCounter = new java.util.concurrent.atomic.AtomicLong();

    private WebView webView;
    private volatile boolean closed;
    private volatile boolean dead;
    private volatile long expirationTimeMs;

    private CountDownLatch initLatch;
    private volatile Exception initError;

    private PoTokenWebView() {
    }

    /**
     * Returns {sessionToken, videoToken} for the given session/video id pair, creating (and
     * caching) a BotGuard minter for {@code sessionId} if none alive exists yet.
     * <p>
     * Blocking: safe to call from a background thread only (never from the main thread, since it
     * waits on main-thread WebView work).
     *
     * @throws Exception if BotGuard initialization or token minting fails or times out; on any
     *                    failure the underlying WebView is destroyed so a later call starts fresh.
     */
    public static synchronized String[] tokens(Context ctx, String sessionId, String videoId) throws Exception {
        PoTokenWebView cached = CACHE.get(sessionId);
        if (cached != null && (cached.closed || cached.dead || cached.isExpired())) {
            cached.close();
            CACHE.remove(sessionId, cached);
            cached = null;
        }

        PoTokenWebView minter = cached;
        String sessionToken;
        try {
            if (minter == null) {
                minter = new PoTokenWebView();
                minter.initBlocking(ctx.getApplicationContext());
                // The streaming poToken must be minted exactly once, right after init, before
                // any other (video) token is requested.
                sessionToken = minter.generatePoTokenBlocking(sessionId);
                CACHE.put(sessionId, minter);
            } else {
                // Reuse: the streaming/session token was already minted when this entry was created.
                sessionToken = minter.generatePoTokenBlocking(sessionId);
            }

            String videoToken = minter.generatePoTokenBlocking(videoId);
            return new String[]{sessionToken, videoToken};
        } catch (Exception ex) {
            // Any failure invalidates the cache entry: the next call must build a fresh WebView.
            CACHE.remove(sessionId, minter);
            if (minter != null) {
                minter.close();
            }
            throw ex;
        }
    }

    private boolean isExpired() {
        return System.currentTimeMillis() >= expirationTimeMs;
    }

    //region Initialization

    private void initBlocking(Context context) throws Exception {
        initLatch = new CountDownLatch(1);
        mainHandler.post(() -> initOnMainThread(context));

        if (!initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            dead = true;
            close();
            throw new PoTokenException("PoTokenWebView init timed out after " + INIT_TIMEOUT_MS + "ms");
        }
        if (initError != null) {
            throw initError;
        }
    }

    private void initOnMainThread(Context context) {
        try {
            webView = new WebView(context);
            android.webkit.WebSettings settings = webView.getSettings();
            //noinspection SetJavaScriptEnabled we want to use JavaScript!
            settings.setJavaScriptEnabled(true);
            settings.setUserAgentString(USER_AGENT);
            settings.setBlockNetworkLoads(true); // the WebView does not need internet access itself

            webView.addJavascriptInterface(new JsBridge(), JS_INTERFACE);

            webView.setWebChromeClient(new WebChromeClient() {
                @Override
                public boolean onConsoleMessage(ConsoleMessage message) {
                    String msg = message.message();
                    switch (message.messageLevel()) {
                        case ERROR:
                            Logger.printInfo(() -> "PoToken JS[error]: " + msg);
                            break;
                        case WARNING:
                            Logger.printInfo(() -> "PoToken JS[warn]: " + msg);
                            break;
                        default:
                            break;
                    }
                    if (msg != null && msg.contains("Uncaught")) {
                        String formatted = "\"" + msg + "\", source: " + message.sourceId()
                                + " (" + message.lineNumber() + ")";
                        onInitializationError(new PoTokenException(formatted));
                    }
                    return super.onConsoleMessage(message);
                }
            });

            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                    Logger.printInfo(() -> "PoToken WebView render process gone");
                    dead = true;
                    onInitializationError(new PoTokenException("WebView render process gone"));
                    // Consume the event so the framework doesn't kill the app process.
                    return true;
                }
            });

            // Appends a call to downloadAndRunBotguard() right before the closing </script> tag,
            // mirroring Metrolist's approach of driving the whole init sequence from within the
            // loaded page once it is ready.
            String data = PO_TOKEN_HTML.replaceFirst("</script>",
                    "\n" + JS_INTERFACE + ".downloadAndRunBotguard()</script>");
            webView.loadDataWithBaseURL("https://www.youtube.com", data, "text/html", "utf-8", null);
        } catch (Exception e) {
            onInitializationError(e);
        }
    }

    /** Called from JS after the page has loaded, to kick off the BotGuard Create request. */
    private void downloadAndRunBotguard() {
        makeBotguardServiceRequest("https://www.youtube.com/api/jnn/v1/Create",
                "[ \"" + REQUEST_KEY + "\" ]", this::onCreateResponse);
    }

    private void onCreateResponse(String responseBody) {
        try {
            String parsedChallengeData = parseChallengeData(responseBody);
            evaluateOnMainThread("try {"
                    + "data = " + parsedChallengeData + ";"
                    + "runBotGuard(data).then(function (result) {"
                    + "  this.webPoSignalOutput = result.webPoSignalOutput;"
                    + "  " + JS_INTERFACE + ".onRunBotguardResult(result.botguardResponse);"
                    + "}, function (error) {"
                    + "  " + JS_INTERFACE + ".onJsInitializationError(error + \"\\n\" + error.stack);"
                    + "});"
                    + "} catch (error) {"
                    + JS_INTERFACE + ".onJsInitializationError(error + \"\\n\" + error.stack);"
                    + "}");
        } catch (Exception e) {
            onInitializationError(e);
        }
    }

    /** Called from JS after BotGuard produced a botguardResponse, to kick off GenerateIT. */
    private void onRunBotguardResult(String botguardResponse) {
        makeBotguardServiceRequest("https://www.youtube.com/api/jnn/v1/GenerateIT",
                "[ \"" + REQUEST_KEY + "\", \"" + botguardResponse + "\" ]",
                this::onGenerateItResponse);
    }

    private void onGenerateItResponse(String responseBody) {
        try {
            IntegrityTokenData tokenData = parseIntegrityTokenData(responseBody);
            // Leave 10 minutes of margin, just to be sure.
            expirationTimeMs = System.currentTimeMillis()
                    + TimeUnit.SECONDS.toMillis(tokenData.expirationTimeSeconds) - EXPIRATION_MARGIN_MS;

            evaluateOnMainThread("try {"
                    + "this.integrityToken = " + tokenData.integrityTokenU8Array + ";"
                    + "createPoTokenMinter(webPoSignalOutput, integrityToken).then(function() {"
                    + "  " + JS_INTERFACE + ".onMinterCreated();"
                    + "}).catch(function(error) {"
                    + "  " + JS_INTERFACE + ".onJsInitializationError(error + \"\\n\" + (error.stack || ''));"
                    + "});"
                    + "} catch (error) {"
                    + JS_INTERFACE + ".onJsInitializationError(error + \"\\n\" + error.stack);"
                    + "}");
        } catch (Exception e) {
            onInitializationError(new PoTokenException("parseIntegrityTokenData failed: " + e.getMessage()));
        }
    }

    private void onMinterCreated() {
        Logger.printInfo(() -> "PoToken minter created successfully");
        if (initLatch != null) {
            initLatch.countDown();
        }
    }

    private void onInitializationError(Exception error) {
        initError = error;
        dead = true;
        close();
        if (initLatch != null) {
            initLatch.countDown();
        }
    }

    //endregion

    //region Obtaining poTokens

    private String generatePoTokenBlocking(String identifier) throws Exception {
        if (dead || closed) {
            throw new PoTokenException("PoToken WebView is dead/closed - instance must be recreated");
        }

        // Continuations are keyed by a per-call unique key, not the raw identifier: concurrent
        // calls for the same identifier would otherwise silently overwrite each other's result.
        String requestKey = identifier + "#" + requestCounter.incrementAndGet();
        CountDownLatchResult result = new CountDownLatchResult();
        pendingTokenRequests.put(requestKey, result);

        mainHandler.post(() -> {
            String u8Identifier = stringToU8(identifier);
            evaluateOnMainThread("(function() {"
                    + "var requestKey = \"" + requestKey + "\";"
                    + "try {"
                    + "  var u8Identifier = " + u8Identifier + ";"
                    + "  obtainPoToken(u8Identifier).then(function(poTokenU8) {"
                    + "    " + JS_INTERFACE + ".onObtainPoTokenResult(requestKey, poTokenU8.join(\",\"));"
                    + "  }).catch(function(error) {"
                    + "    " + JS_INTERFACE + ".onObtainPoTokenError(requestKey, error + \"\\n\" + (error.stack || ''));"
                    + "  });"
                    + "} catch (error) {"
                    + "  " + JS_INTERFACE + ".onObtainPoTokenError(requestKey, error + \"\\n\" + error.stack);"
                    + "}"
                    + "})()");
        });

        try {
            if (!result.latch.await(GENERATE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                // A renderer that never answers is wedged/dead: fail fast, the next call recreates.
                dead = true;
                pendingTokenRequests.remove(requestKey);
                close();
                throw new PoTokenException("poToken generation timed out after " + GENERATE_TIMEOUT_MS + "ms");
            }
        } finally {
            pendingTokenRequests.remove(requestKey);
        }

        if (result.error.get() != null) {
            dead = true;
            close();
            throw new PoTokenException(result.error.get());
        }
        return result.value.get();
    }

    private void onObtainPoTokenError(String requestKey, String error) {
        CountDownLatchResult result = pendingTokenRequests.get(requestKey);
        if (result != null) {
            result.error.set(error);
            result.latch.countDown();
        }
    }

    private void onObtainPoTokenResult(String requestKey, String poTokenU8) {
        CountDownLatchResult result = pendingTokenRequests.get(requestKey);
        if (result == null) {
            return;
        }
        try {
            result.value.set(u8ToBase64(poTokenU8));
        } catch (Exception e) {
            result.error.set(e.getMessage());
        } finally {
            result.latch.countDown();
        }
    }

    //endregion

    //region Networking (HttpURLConnection, mirrors Metrolist's makeBotguardServiceRequest headers)

    private interface ResponseHandler {
        void onResponse(String responseBody);
    }

    private void makeBotguardServiceRequest(String url, String jsonBody, ResponseHandler handler) {
        // Runs off the main thread: this method is only ever invoked from JS-interface callbacks
        // (JavaBridge thread) or from initOnMainThread's kickoff, and network I/O must not block
        // the main thread.
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                try {
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setConnectTimeout((int) GENERATE_TIMEOUT_MS);
                    connection.setReadTimeout((int) GENERATE_TIMEOUT_MS);
                    connection.setRequestProperty("User-Agent", USER_AGENT);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("Content-Type", "application/json+protobuf");
                    connection.setRequestProperty("x-goog-api-key", GOOGLE_API_KEY);
                    connection.setRequestProperty("x-user-agent", "grpc-web-javascript/0.1");

                    byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
                    try (OutputStream os = connection.getOutputStream()) {
                        os.write(bytes);
                    }

                    int code = connection.getResponseCode();
                    String body = code == 200 ? readStream(connection.getInputStream()) : null;
                    if (body == null || body.isEmpty()) {
                        onInitializationError(new PoTokenException(
                                "Invalid botguard response (code=" + code + ", empty body)"));
                        return;
                    }
                    handler.onResponse(body);
                } finally {
                    connection.disconnect();
                }
            } catch (Exception e) {
                onInitializationError(e);
            }
        }, "PoTokenWebView-net").start();
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

    //region WebView lifecycle

    private void evaluateOnMainThread(String script) {
        mainHandler.post(() -> {
            if (webView != null && !closed) {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    private void close() {
        if (closed) {
            return;
        }
        closed = true;

        // WebView methods must run on the thread that created it (main); some callers arrive
        // from background threads (network callbacks, JS-interface callbacks).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            destroyWebView();
        } else {
            mainHandler.post(this::destroyWebView);
        }

        // Fail any pending token requests instead of leaving their callers waiting for the timeout.
        for (Map.Entry<String, CountDownLatchResult> entry : pendingTokenRequests.entrySet()) {
            entry.getValue().error.compareAndSet(null, "PoTokenWebView closed");
            entry.getValue().latch.countDown();
        }
        pendingTokenRequests.clear();
    }

    private void destroyWebView() {
        if (webView == null) {
            return;
        }
        try {
            webView.clearHistory();
            webView.clearCache(true);
            webView.loadUrl("about:blank");
            webView.onPause();
            webView.removeAllViews();
            webView.destroy();
        } catch (Exception e) {
            Logger.printInfo(() -> "PoToken WebView teardown failed", e);
        } finally {
            webView = null;
        }
    }

    //endregion

    /** JavaScript-callable interface bridging BotGuard's async JS callbacks back into Java. */
    private final class JsBridge {
        @JavascriptInterface
        public void downloadAndRunBotguard() {
            PoTokenWebView.this.downloadAndRunBotguard();
        }

        @JavascriptInterface
        public void onJsInitializationError(String error) {
            PoTokenWebView.this.onInitializationError(new PoTokenException(error));
        }

        @JavascriptInterface
        public void onRunBotguardResult(String botguardResponse) {
            PoTokenWebView.this.onRunBotguardResult(botguardResponse);
        }

        @JavascriptInterface
        public void onMinterCreated() {
            PoTokenWebView.this.onMinterCreated();
        }

        @JavascriptInterface
        public void onObtainPoTokenError(String requestKey, String error) {
            PoTokenWebView.this.onObtainPoTokenError(requestKey, error);
        }

        @JavascriptInterface
        public void onObtainPoTokenResult(String requestKey, String poTokenU8) {
            PoTokenWebView.this.onObtainPoTokenResult(requestKey, poTokenU8);
        }
    }

    private static final class CountDownLatchResult {
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> value = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
    }

    /** Thrown for any BotGuard/poToken related failure (init or per-token generation). */
    public static final class PoTokenException extends Exception {
        PoTokenException(String message) {
            super(message);
        }
    }

    //region JSON / byte<->JS helpers (ported from JavaScriptUtil.kt; minimal hand-rolled JSON
    // array parsing since the Create/GenerateIT responses are simple fixed-shape arrays and this
    // project has no JSON dependency wired in for this package)

    private static final class IntegrityTokenData {
        final String integrityTokenU8Array;
        final long expirationTimeSeconds;

        IntegrityTokenData(String integrityTokenU8Array, long expirationTimeSeconds) {
            this.integrityTokenU8Array = integrityTokenU8Array;
            this.expirationTimeSeconds = expirationTimeSeconds;
        }
    }

    /**
     * Parses the raw challenge data obtained from the Create endpoint and returns a JS object
     * literal (as source text) that can be embedded directly in an evaluated JS snippet.
     */
    private static String parseChallengeData(String rawChallengeData) throws PoTokenException {
        Object[] scrambled = JsonArrayParser.parseArray(rawChallengeData);

        Object[] challengeData;
        if (scrambled.length > 1 && scrambled[1] instanceof String) {
            String descrambled = descramble((String) scrambled[1]);
            challengeData = JsonArrayParser.parseArray(descrambled);
        } else {
            challengeData = JsonArrayParser.asArray(scrambled[0]);
        }

        String messageId = String.valueOf(challengeData[0]);
        String interpreterHash = String.valueOf(challengeData[3]);
        String program = String.valueOf(challengeData[4]);
        String globalName = String.valueOf(challengeData[5]);
        String clientExperimentsStateBlob = String.valueOf(challengeData[7]);

        String safeScriptValue = findFirstString(challengeData[1]);
        String trustedResourceUrlValue = findFirstString(challengeData[2]);

        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"messageId\":").append(jsonQuote(messageId)).append(",");
        sb.append("\"interpreterJavascript\":{");
        sb.append("\"privateDoNotAccessOrElseSafeScriptWrappedValue\":")
                .append(safeScriptValue == null ? "null" : jsonQuote(safeScriptValue)).append(",");
        sb.append("\"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue\":")
                .append(trustedResourceUrlValue == null ? "null" : jsonQuote(trustedResourceUrlValue));
        sb.append("},");
        sb.append("\"interpreterHash\":").append(jsonQuote(interpreterHash)).append(",");
        sb.append("\"program\":").append(jsonQuote(program)).append(",");
        sb.append("\"globalName\":").append(jsonQuote(globalName)).append(",");
        sb.append("\"clientExperimentsStateBlob\":").append(jsonQuote(clientExperimentsStateBlob));
        sb.append("}");
        return sb.toString();
    }

    /**
     * Parses the raw integrity token data obtained from the GenerateIT endpoint into a JS
     * {@code Uint8Array} literal (as source text) plus the token's lifetime in seconds.
     */
    private static IntegrityTokenData parseIntegrityTokenData(String rawIntegrityTokenData) throws PoTokenException {
        Object[] data = JsonArrayParser.parseArray(rawIntegrityTokenData);
        String integrityTokenU8Array = base64ToU8((String) data[0]);
        long expirationTimeSeconds = ((Number) data[1]).longValue();
        return new IntegrityTokenData(integrityTokenU8Array, expirationTimeSeconds);
    }

    /** Converts a string (the identifier passed to {@code obtainPoToken}) to a JS Uint8Array literal. */
    private static String stringToU8(String identifier) {
        return newUint8Array(identifier.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Takes a poToken encoded as comma separated byte values (the output of Uint8Array::toString()
     * in JavaScript) and converts it to poToken's specific base64 representation.
     */
    private static String u8ToBase64(String poToken) {
        String[] parts = poToken.split(",");
        byte[] bytes = new byte[parts.length == 1 && parts[0].isEmpty() ? 0 : parts.length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (Integer.parseInt(parts[i].trim()) & 0xFF);
        }
        String base64 = Base64.getEncoder().withoutPadding().encodeToString(bytes);
        return base64.replace('+', '-').replace('/', '_');
    }

    /** Decodes the scrambled challenge: base64-decode, then add 97 to each byte. */
    private static String descramble(String scrambledChallenge) throws PoTokenException {
        byte[] decoded = base64ToByteArray(scrambledChallenge);
        byte[] result = new byte[decoded.length];
        for (int i = 0; i < decoded.length; i++) {
            result[i] = (byte) (decoded[i] + 97);
        }
        return new String(result, StandardCharsets.UTF_8);
    }

    /** Decodes a YouTube-flavored base64 string into a JS Uint8Array literal (as source text). */
    private static String base64ToU8(String base64) throws PoTokenException {
        return newUint8Array(base64ToByteArray(base64));
    }

    private static String newUint8Array(byte[] contents) {
        StringBuilder sb = new StringBuilder("new Uint8Array([");
        for (int i = 0; i < contents.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(contents[i] & 0xFF);
        }
        sb.append("])");
        return sb.toString();
    }

    /** Decodes a base64 string encoded in the specific base64 representation used by YouTube. */
    private static byte[] base64ToByteArray(String base64) throws PoTokenException {
        String base64Mod = base64.replace('-', '+').replace('_', '/').replace('.', '=');
        try {
            return Base64.getDecoder().decode(base64Mod);
        } catch (IllegalArgumentException e) {
            throw new PoTokenException("Cannot base64 decode");
        }
    }

    private static String findFirstString(Object arrayOrNull) {
        if (!(arrayOrNull instanceof Object[])) {
            return null;
        }
        for (Object o : (Object[]) arrayOrNull) {
            if (o instanceof String) {
                return (String) o;
            }
        }
        return null;
    }

    private static String jsonQuote(String s) {
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
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * A minimal recursive-descent JSON array parser: the botguard Create/GenerateIT responses are
     * simple (possibly nested) JSON arrays of strings/numbers/nulls, so a full JSON library is
     * unnecessary here.
     */
    private static final class JsonArrayParser {
        private final String s;
        private int pos;

        private JsonArrayParser(String s) {
            this.s = s;
        }

        static Object[] parseArray(String json) throws PoTokenException {
            JsonArrayParser parser = new JsonArrayParser(json.trim());
            Object value = parser.parseValue();
            return asArray(value);
        }

        static Object[] asArray(Object value) throws PoTokenException {
            if (!(value instanceof Object[])) {
                throw new PoTokenException("Expected JSON array");
            }
            return (Object[]) value;
        }

        private Object parseValue() throws PoTokenException {
            skipWhitespace();
            if (pos >= s.length()) {
                throw new PoTokenException("Unexpected end of JSON");
            }
            char c = s.charAt(pos);
            if (c == '[') {
                return parseArrayValue();
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

        private Object[] parseArrayValue() throws PoTokenException {
            java.util.List<Object> list = new java.util.ArrayList<>();
            pos++; // consume '['
            skipWhitespace();
            if (pos < s.length() && s.charAt(pos) == ']') {
                pos++;
                return list.toArray();
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= s.length()) {
                    throw new PoTokenException("Unterminated JSON array");
                }
                char c = s.charAt(pos);
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    break;
                } else {
                    throw new PoTokenException("Malformed JSON array");
                }
            }
            return list.toArray();
        }

        private String parseString() throws PoTokenException {
            pos++; // consume opening quote
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
            throw new PoTokenException("Unterminated JSON string");
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

    //endregion
}
