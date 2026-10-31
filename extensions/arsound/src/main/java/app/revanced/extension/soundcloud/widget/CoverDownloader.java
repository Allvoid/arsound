package app.revanced.extension.soundcloud.widget;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;

/**
 * Downloads a cover of the widget with SoundCloud's own OkHttp. It is reached by reflection: the library is in the
 * app but not among the stubs the extension is compiled against.
 */
final class CoverDownloader {
    /** Covers are tens of kilobytes; anything far bigger is not a cover. */
    private static final int MAX_BYTES = 4_000_000;

    /** SoundCloud's HTTP client with the Arsound network rules, made once. */
    private static volatile Object client;

    /** The whole call of one cover, connection to last byte: a slow network held the widget's update for long. */
    private static final long CALL_TIMEOUT_SECONDS = 8;
    /** A cover that failed for a passing reason (network, 5xx, 408, 429) is not asked for again this long. */
    private static final long RETRY_AFTER_MS = 10 * 60 * 1000L;
    /**
     * After a network error no cover is asked for this long: the covers of one update were asked one after another,
     * and each waited for the network that was not there.
     */
    private static final long NETWORK_PAUSE_MS = 60 * 1000L;
    private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();
    private static volatile long networkFailedAt;

    private CoverDownloader() {
    }

    /**
     * An OkHttp client of the app's own library, built through its builder: the network patch adds to it the custom
     * DNS and the block by IP country, as to every client of SoundCloud.
     */
    private static Object client() throws Exception {
        Object known = client;
        if (known != null) return known;
        synchronized (CoverDownloader.class) {
            if (client == null) {
                Object builder = Class.forName("okhttp3.OkHttpClient$Builder", true, CoverDownloader.class.getClassLoader())
                        .getConstructor().newInstance();
                try {
                    builder.getClass().getMethod("callTimeout", long.class, TimeUnit.class)
                            .invoke(builder, CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (NoSuchMethodException ex) {
                    // An OkHttp without a call timeout: its own timeouts for connecting and reading stay.
                    Logger.printInfo(() -> "Widget: no call timeout in this OkHttp");
                }
                client = builder.getClass().getMethod("build").invoke(builder);
            }
            return client;
        }
    }

    /**
     * Downloads a cover into {@code target}, through a temporary file so a half-written one is never used. A cover
     * the server refused is marked, so the widget's every update does not ask for it again.
     */
    static boolean download(String url, File target) {
        File part = new File(target.getPath() + ".part");
        File miss = CoverFiles.missMark(target);
        if (CoverFiles.recentlyRefused(miss)) return false;
        long now = System.currentTimeMillis();
        if (now - networkFailedAt < NETWORK_PAUSE_MS) return false;
        Long failed = failedAt.get(url);
        if (failed != null && now - failed < RETRY_AFTER_MS) return false;
        Closeable response = null;
        try {
            File folder = target.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return false;
            response = execute(url);
            int code = (int) response.getClass().getMethod("code").invoke(response);
            if (code != 200) {
                Logger.printInfo(() -> "Widget: cover answered " + code + ": " + url);
                if (code >= 400 && code < 500 && code != 408 && code != 429) CoverFiles.markMiss(miss);
                else failedAt.put(url, System.currentTimeMillis());
                return false;
            }
            if (!copyBody(response, part)) {
                CoverFiles.markMiss(miss);
                return false;
            }
            if (!CoverFiles.isPicture(part)) {
                Logger.printInfo(() -> "Widget: the download is not a picture: " + CoverFiles.describe(part.getPath()));
                CoverFiles.markMiss(miss);
                return false;
            }
            if (!part.renameTo(target)) return false;
            //noinspection ResultOfMethodCallIgnored
            miss.delete();
            failedAt.remove(url);
            CoverFiles.trim(folder);
            return true;
        } catch (Throwable ex) {
            // A network error, a timeout or the country block: the covers wait a minute, this one ten.
            Throwable cause = ex instanceof InvocationTargetException && ex.getCause() != null ? ex.getCause() : ex;
            Logger.printInfo(() -> "Widget: could not download the cover " + url + ": " + cause);
            long failedNow = System.currentTimeMillis();
            failedAt.put(url, failedNow);
            if (cause instanceof java.io.IOException) networkFailedAt = failedNow;
            return false;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (Throwable ignored) {
                    // Closed with its body.
                }
            }
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    /** Asks for the address; the answer (OkHttp's {@code Response}) is closed by the caller. */
    private static Closeable execute(String url) throws Exception {
        ClassLoader loader = CoverDownloader.class.getClassLoader();
        Class<?> requestClass = Class.forName("okhttp3.Request", true, loader);
        Object requestBuilder = Class.forName("okhttp3.Request$Builder", true, loader).getConstructor().newInstance();
        requestBuilder.getClass().getMethod("url", String.class).invoke(requestBuilder, url);
        Object request = requestBuilder.getClass().getMethod("build").invoke(requestBuilder);
        Object http = client();
        Object call = http.getClass().getMethod("newCall", requestClass).invoke(http, request);
        return (Closeable) Class.forName("okhttp3.Call", true, loader).getMethod("execute").invoke(call);
    }

    /** Writes the body of the answer to the file; false when it is bigger than any cover. */
    private static boolean copyBody(Closeable response, File part) throws Exception {
        Object body = response.getClass().getMethod("body").invoke(response);
        try (InputStream input = (InputStream) body.getClass().getMethod("byteStream").invoke(body);
             FileOutputStream output = new FileOutputStream(part)) {
            byte[] buffer = new byte[16 * 1024];
            int total = 0;
            for (int read; (read = input.read(buffer)) != -1; ) {
                total += read;
                if (total > MAX_BYTES) return false;
                output.write(buffer, 0, read);
            }
        }
        return true;
    }
}
