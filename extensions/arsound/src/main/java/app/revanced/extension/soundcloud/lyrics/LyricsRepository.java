package app.revanced.extension.soundcloud.lyrics;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.network.RegionGuard;

/**
 * Where the lyrics of a track come from: memory, then the files on the phone, then LRCLIB.
 * <p>
 * One thread does the lookups one after another, so flicking through tracks does not make a flood of requests, and a
 * track that is not wanted any more is dropped before its request goes out. The internet is asked only for a track
 * the user wants the lyrics of: nothing is looked up while the lyrics button is off.
 */
public final class LyricsRepository {
    /** A lookup in progress; cancelled when another track is wanted. */
    static final class Request implements LyricsFinder.Cancel {
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }

    interface Callback {
        /** On the main thread. */
        void onResult(LyricsResult result);
    }

    /** A pause before the first request: a track that is skipped at once is never asked for. */
    private static final long DEBOUNCE_MS = 400;
    private static final int MEMORY_SIZE = 8;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "Arsound lyrics");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    });

    /** The last answers, newest last; looked up by track and name, so a renamed track is not served an old text. */
    private static final Map<String, LyricsResult> MEMORY = new LinkedHashMap<String, LyricsResult>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, LyricsResult> eldest) {
            return size() > MEMORY_SIZE;
        }
    };

    private static LyricsCache cache;

    private LyricsRepository() {
    }

    private static synchronized LyricsCache cache() {
        if (cache == null) {
            Context context = Utils.getContext();
            File root = context == null ? new File(System.getProperty("java.io.tmpdir", "."))
                    : context.getCacheDir();
            cache = new LyricsCache(new File(new File(root, "arsound"), "lyrics"));
        }
        return cache;
    }

    private static String memoryKey(TrackInfo info) {
        return info.urn + "|" + info.query().signature;
    }

    /** The answer for the track if it is in memory: no waiting, no thread. */
    static LyricsResult peek(TrackInfo info) {
        synchronized (MEMORY) {
            return MEMORY.get(memoryKey(info));
        }
    }

    private static void remember(TrackInfo info, LyricsResult result) {
        if (!result.isStorable()) return;
        synchronized (MEMORY) {
            MEMORY.put(memoryKey(info), result);
        }
    }

    /** Looks the track up and calls back on the main thread, unless the request is cancelled first. */
    static Request request(final TrackInfo info, final Callback callback) {
        final Request request = new Request();
        WORKER.execute(new Runnable() {
            @Override
            public void run() {
                if (request.isCancelled()) return;
                LyricsResult result;
                try {
                    result = lookUp(info, request);
                } catch (Throwable ex) {
                    Logger.printInfo(() -> "Lyrics: the search failed: " + ex.getMessage());
                    result = LyricsResult.error();
                }
                final LyricsResult answer = result;
                remember(info, answer);
                if (request.isCancelled()) return;
                Utils.runOnMainThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!request.isCancelled()) callback.onResult(answer);
                    }
                });
            }
        });
        return request;
    }

    private static LyricsResult lookUp(TrackInfo info, Request request) throws IOException {
        TrackQuery query = info.query();
        LyricsResult stored = cache().read(info.urn, query.signature);
        if (stored != null) return stored;
        if (query.durationMs > LyricsFinder.MAX_TRACK_MS || query.variants.isEmpty()) return LyricsResult.missing();
        if (!isOnline()) return LyricsResult.offline();

        // Wait out a quick skip; stop at once if the track is not wanted any more.
        for (long waited = 0; waited < DEBOUNCE_MS; waited += 50) {
            if (request.isCancelled()) throw new IOException("Lookup cancelled");
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", ex);
            }
        }
        LyricsFinder finder = new LyricsFinder(new LrclibClient(new LrclibClient.Gate() {
            @Override
            public void check() throws IOException {
                RegionGuard.throwIfBlockedAnyHost();
            }
        }), request);
        Logger.printInfo(() -> "Lyrics: asking LRCLIB for " + info.urn);
        LyricsResult result = finder.find(query);
        Logger.printInfo(() -> "Lyrics: " + info.urn + " -> " + result.status
                + (result.lyrics == null ? "" : result.lyrics.isTimed() ? ", synced" : ", plain"));
        cache().write(info.urn, query.signature, result);
        return result;
    }

    private static boolean isOnline() {
        try {
            return Utils.isNetworkConnected();
        } catch (Throwable ex) {
            // Without the permission to ask, the request itself tells.
            return true;
        }
    }

    /** Space the stored texts take, in bytes. Reads the files: not on the main thread. */
    public static long storedBytes() {
        return cache().size();
    }

    /** Forgets every stored text; they are looked up again when wanted. Not on the main thread. */
    public static void clearStored() {
        synchronized (MEMORY) {
            MEMORY.clear();
        }
        cache().clear();
    }
}
