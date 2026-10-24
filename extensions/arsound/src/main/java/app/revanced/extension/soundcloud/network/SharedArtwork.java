package app.revanced.extension.soundcloud.network;

import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;

/**
 * One download per cover.
 * <p>
 * When a track starts, the player page and the notification load the same 500x500 cover at the same moment, through
 * two image loaders (Coil and Picasso) with caches of their own, and the lists load the small one again. On a slow
 * connection every cover then came twice. Here the first request of a cover downloads it, a request for the same
 * cover made meanwhile waits for it, and a request made shortly after is answered from memory: all of them get the
 * same bytes and headers. Covers on sndcdn.com never change under the same address, so this cannot show a stale one.
 */
final class SharedArtwork {
    /** Covers are answered from memory this long after their download; the loaders' own caches take over after. */
    private static final long KEEP_MS = 60_000;
    /** A request for a cover that is downloading waits at most this long, then downloads it itself. */
    private static final long WAIT_MS = 20_000;
    /** Bigger answers are not kept: covers are a few tens of kilobytes. */
    private static final long MAX_BYTES = 2_000_000;
    private static final int MAX_KEPT = 12;

    interface Call {
        Object proceed() throws Throwable;
    }

    private static final class Entry {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Object response;
        volatile Object mediaType;
        volatile byte[] body;
        volatile long at;
    }

    /** By address, oldest first; guarded by itself. */
    private static final Map<String, Entry> entries = new LinkedHashMap<>(16, .75f, true);

    private static volatile Method code, body, contentLength, contentType, bytes, newBuilder,
            builderRequest, builderBody, build, create;
    private static volatile boolean createBytesFirst;

    private SharedArtwork() {
    }

    /** Covers of tracks, playlists and users. */
    static boolean applies(String host, String path) {
        return host.endsWith(".sndcdn.com") && (path.startsWith("/artworks-") || path.startsWith("/avatars-"));
    }

    /**
     * @param url     The cover's address, the key.
     * @param request The {@code okhttp3.Request} of the caller, put into a shared answer.
     * @param call    Sends the request on along the chain.
     * @return The {@code okhttp3.Response}.
     */
    static Object load(String url, Object request, Call call) throws Throwable {
        Entry entry;
        boolean first;
        synchronized (entries) {
            entry = entries.get(url);
            if (entry != null && entry.body != null && System.currentTimeMillis() - entry.at > KEEP_MS) {
                entries.remove(url);
                entry = null;
            }
            first = entry == null;
            if (first) {
                entry = new Entry();
                entries.put(url, entry);
                trim();
            }
        }

        if (!first) {
            if (entry.body == null) {
                try {
                    entry.done.await(WAIT_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    // OkHttp interrupts a cancelled call.
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Canceled");
                }
            }
            if (entry.body != null) {
                Entry shared = entry;
                Logger.printInfo(() -> "Cover shared, not downloaded again: " + url);
                return copy(shared, request);
            }
            return call.proceed();
        }

        boolean kept = false;
        try {
            Object response = call.proceed();
            byte[] data = read(entry, response);
            if (data == null) return response;
            entry.response = response;
            entry.at = System.currentTimeMillis();
            // Last, and volatile: a waiter that sees the body also sees the response, the type and the time.
            entry.body = data;
            kept = true;
            return copy(entry, request);
        } finally {
            if (!kept) {
                synchronized (entries) {
                    entries.remove(url, entry);
                }
            }
            entry.done.countDown();
        }
    }

    private static void trim() {
        Iterator<Entry> iterator = entries.values().iterator();
        while (entries.size() > MAX_KEPT && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * Reads the body of a successful answer; the caller publishes it. Null leaves the answer untouched: an error, or
     * too big.
     */
    private static byte[] read(Entry entry, Object response) throws Throwable {
        lookUp(response);
        if ((int) code.invoke(response) != 200) return null;
        Object responseBody = body.invoke(response);
        if (responseBody == null || (long) contentLength.invoke(responseBody) > MAX_BYTES) return null;
        entry.mediaType = contentType.invoke(responseBody);
        // Reading the whole body also completes the loader's own disk cache entry.
        return (byte[]) bytes.invoke(responseBody);
    }

    /** The kept answer with a fresh body, for the given request. */
    private static Object copy(Entry entry, Object request) throws Throwable {
        Object builder = newBuilder.invoke(entry.response);
        builderRequest.invoke(builder, request);
        Object responseBody = createBytesFirst
                ? create.invoke(null, entry.body, entry.mediaType)
                : create.invoke(null, entry.mediaType, entry.body);
        builderBody.invoke(builder, responseBody);
        return build.invoke(builder);
    }

    /** OkHttp is not obfuscated; its methods are found by name once. */
    private static void lookUp(Object response) throws Exception {
        if (create != null) return;
        ClassLoader loader = response.getClass().getClassLoader();
        Class<?> responseClass = Class.forName("okhttp3.Response", false, loader);
        Class<?> bodyClass = Class.forName("okhttp3.ResponseBody", false, loader);
        Class<?> requestClass = Class.forName("okhttp3.Request", false, loader);
        Class<?> mediaTypeClass = Class.forName("okhttp3.MediaType", false, loader);
        Class<?> builderClass = Class.forName("okhttp3.Response$Builder", false, loader);
        code = responseClass.getMethod("code");
        body = responseClass.getMethod("body");
        newBuilder = responseClass.getMethod("newBuilder");
        contentLength = bodyClass.getMethod("contentLength");
        contentType = bodyClass.getMethod("contentType");
        bytes = bodyClass.getMethod("bytes");
        builderRequest = builderClass.getMethod("request", requestClass);
        builderBody = builderClass.getMethod("body", bodyClass);
        build = builderClass.getMethod("build");
        Method found;
        try {
            found = bodyClass.getMethod("create", byte[].class, mediaTypeClass);
            createBytesFirst = true;
        } catch (NoSuchMethodException ex) {
            found = bodyClass.getMethod("create", mediaTypeClass, byte[].class);
            createBytesFirst = false;
        }
        create = found;
    }
}
