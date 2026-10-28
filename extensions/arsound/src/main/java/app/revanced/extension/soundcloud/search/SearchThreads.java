package app.revanced.extension.soundcloud.search;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The threads the Arsound search works on. */
final class SearchThreads {
    static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final ExecutorService NETWORK = Executors.newFixedThreadPool(3);
    /** Album and artist searches, run next to the track search without taking its threads. */
    static final ExecutorService EXTRA = Executors.newFixedThreadPool(2);
    /** Downloads have their own threads: a long queue of them must not hold back search, pages and previews. */
    static final ExecutorService DOWNLOADS = Executors.newFixedThreadPool(2);

    private SearchThreads() {
    }
}
