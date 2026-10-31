package app.revanced.extension.soundcloud.lyrics;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
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
    static final class Request implements LyricsHttp.Watch {
        private volatile boolean cancelled;
        /** The connection of the request on the network now, if any. */
        private volatile HttpURLConnection connection;

        void cancel() {
            cancelled = true;
            final HttpURLConnection busy = connection;
            if (busy == null) return;
            // Cuts a wait on the network, so the worker is free for the next track at once. Off the main thread:
            // closing a connection there may be refused.
            Thread cut = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        busy.disconnect();
                    } catch (Throwable ignored) {
                        // Already finished or closed; nothing is left to stop.
                    }
                }
            }, "Arsound lyrics cancel");
            cut.setDaemon(true);
            cut.start();
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void connecting(HttpURLConnection next) {
            // Set before the flag is read by the caller, and cancel() sets the flag before it reads this: one of the
            // two always sees the other.
            connection = next;
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
                    // Words the network recognised are on the phone: they stand in for the failed search too.
                    result = orRecognised(info, LyricsResult.error());
                }
                final LyricsResult answer = result;
                // LRCLIB never answers with recognised words: such an answer stands in for "no lyrics" or "no connection".
                // It is not kept in memory, so the next load asks LRCLIB again when it can (the text found there is better).
                if (answer.lyrics == null || answer.lyrics.source != Lyrics.Source.NEURAL) remember(info, answer);
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
        if (stored != null) return orRecognised(info, stored);
        if (query.durationMs > LyricsFinder.MAX_TRACK_MS || query.variants.isEmpty()) {
            return orRecognised(info, LyricsResult.missing());
        }
        if (!isOnline()) return orRecognised(info, LyricsResult.offline());

        // Wait out a quick skip; stop at once if the track is not wanted any more.
        Pause.cancellable(DEBOUNCE_MS, request);
        LyricsHttp http = new LyricsHttp(new LyricsHttp.Gate() {
            @Override
            public void check() throws IOException {
                RegionGuard.throwIfBlockedAnyHost();
            }
        }, request);
        LyricsResult result = find(info, query, http, request);
        Logger.printInfo(() -> "Lyrics: " + info.urn + " -> " + result.status
                + (result.lyrics == null ? "" : (result.lyrics.isTimed() ? ", synced" : ", plain") + ", " + result.lyrics.source));
        cache().write(info.urn, query.signature, result);
        return orRecognised(info, result);
    }

    /**
     * LRCLIB first: its texts are written for lyrics apps and timed for the track. Then NetEase, Kugou and lyrics.ovh
     * when LRCLIB has nothing or cannot be reached (it is often busy). When none answers, LRCLIB's failure is thrown:
     * nothing is stored then.
     */
    private static final long LRCLIB_RETRY_MS = 1_500;

    private static LyricsResult find(TrackInfo info, TrackQuery query, LyricsHttp http, Request request) throws IOException {
        LyricsResult result = null;
        IOException failure = null;
        try {
            Logger.printInfo(() -> "Lyrics: asking LRCLIB for " + info.urn);
            result = new LyricsFinder(new LrclibClient(http), request).find(query);
            if (result.status != LyricsResult.Status.MISSING) return result;
        } catch (IOException ex) {
            if (request.isCancelled()) throw ex;
            failure = ex;
        }
        // The other places, one after another, until one has the words. One that cannot be reached is skipped. Asked
        // while LRCLIB, which is often busy (503), has time to recover.
        LyricsResult other = askOthers(info, query, http, request);
        if (other != null) return other;
        if (failure == null) return result;
        // Nobody has the track and LRCLIB did not answer: once more, before the lookup is called failed.
        Pause.cancellable(LRCLIB_RETRY_MS, request);
        try {
            Logger.printInfo(() -> "Lyrics: asking LRCLIB again for " + info.urn);
            result = new LyricsFinder(new LrclibClient(http), request).find(query);
            return result;
        } catch (IOException ex) {
            if (request.isCancelled()) throw ex;
            throw failure;
        }
    }

    /** One place that has lyrics, asked for the track. */
    private interface Place {
        LyricsResult find() throws IOException;
    }

    /** The answer of the first of the other places that has the track, or null when none has it. */
    private static LyricsResult askOthers(final TrackInfo info, final TrackQuery query, final LyricsHttp http,
                                          final Request request) throws IOException {
        String[] names = {"NetEase", "Kugou", "lyrics.ovh"};
        Place[] places = {
                new Place() {
                    @Override
                    public LyricsResult find() throws IOException {
                        return new NeteaseFinder(new NeteaseClient(http), request).find(query);
                    }
                },
                new Place() {
                    @Override
                    public LyricsResult find() throws IOException {
                        return new KugouFinder(new KugouClient(http), request).find(query);
                    }
                },
                new Place() {
                    @Override
                    public LyricsResult find() throws IOException {
                        return new OvhFinder(http, request).find(query);
                    }
                },
        };
        for (int i = 0; i < places.length; i++) {
            final String name = names[i];
            try {
                Logger.printInfo(() -> "Lyrics: asking " + name + " for " + info.urn);
                LyricsResult answer = places[i].find();
                if (answer.status != LyricsResult.Status.MISSING) return answer;
            } catch (IOException ex) {
                if (request.isCancelled()) throw ex;
                Logger.printInfo(() -> "Lyrics: " + name + " failed: " + ex.getMessage());
            }
        }
        return null;
    }

    /**
     * What the neural network heard, in place of "no lyrics" (or "no connection": the recognised text is on the phone).
     * It is never written to the cache above: LRCLIB is asked again when "no lyrics" runs out, and a text found there
     * is better than this one.
     */
    private static LyricsResult orRecognised(TrackInfo info, LyricsResult result) {
        if (result.status != LyricsResult.Status.MISSING && result.status != LyricsResult.Status.OFFLINE
                && result.status != LyricsResult.Status.ERROR) {
            return result;
        }
        Lyrics recognised = AiTranscripts.lookUp(info);
        return recognised == null ? result : LyricsResult.found(recognised);
    }

    /** The words the neural network just recognised, so a page that asks for the track gets them at once. */
    static void rememberRecognised(TrackInfo info, Lyrics lyrics) {
        remember(info, LyricsResult.found(lyrics));
    }

    /** Forgets the recognised texts kept in memory (they were removed from the phone, or the recognition was switched off). */
    public static void forgetRecognised() {
        synchronized (MEMORY) {
            java.util.Iterator<LyricsResult> it = MEMORY.values().iterator();
            while (it.hasNext()) {
                LyricsResult result = it.next();
                if (result.lyrics != null && result.lyrics.source == Lyrics.Source.NEURAL) it.remove();
            }
        }
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
