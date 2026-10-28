package app.revanced.extension.soundcloud.widget;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;

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
        Closeable response = null;
        try {
            File folder = target.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return false;
            response = execute(url);
            int code = (int) response.getClass().getMethod("code").invoke(response);
            if (code != 200) {
                Logger.printInfo(() -> "Widget: cover answered " + code + ": " + url);
                if (code >= 400 && code < 500 && code != 408 && code != 429) CoverFiles.markMiss(miss);
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
            CoverFiles.trim(folder);
            return true;
        } catch (Throwable ex) {
            // A network error or the country block: the cover is asked for again at the next update.
            Throwable cause = ex instanceof InvocationTargetException && ex.getCause() != null ? ex.getCause() : ex;
            Logger.printInfo(() -> "Widget: could not download the cover " + url + ": " + cause);
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
