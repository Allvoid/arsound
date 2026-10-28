package app.revanced.extension.soundcloud.local;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import app.revanced.extension.shared.Logger;

/** The likes lists that are on a screen right now, redrawn together when the imported files change. */
final class LikesFeeds {
    /** Imports in a row, and loads finishing one after another, cause one redraw. */
    private static final long REDRAW_DELAY_MS = 300;

    /** Weak: a list nobody listens to any more is dropped with its screen. */
    private static final Set<LikesFeed> LIVE = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Debounced REDRAW = new Debounced(REDRAW_DELAY_MS, LikesFeeds::redrawAll);

    private LikesFeeds() {
    }

    static void add(LikesFeed feed) {
        synchronized (LIVE) {
            LIVE.add(feed);
        }
    }

    static void redrawSoon() {
        REDRAW.request();
    }

    private static void redrawAll() {
        List<LikesFeed> feeds;
        synchronized (LIVE) {
            feeds = new ArrayList<>(LIVE);
        }
        for (LikesFeed feed : feeds) {
            try {
                feed.refresh();
            } catch (Throwable ex) {
                // Runs on the timer thread, where an error would close the app.
                Logger.printException(() -> "Could not redraw a likes list", ex);
            }
        }
    }
}
