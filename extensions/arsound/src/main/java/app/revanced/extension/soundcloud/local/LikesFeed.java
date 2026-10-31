package app.revanced.extension.soundcloud.local;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * The first page of "Your likes" as one live list: the rows of the database with the imported files placed among
 * them. It follows the database as SoundCloud's own list does, and {@link #refresh} redraws it when the imported
 * files change, which the database knows nothing about.
 */
final class LikesFeed {
    private final Object relay;
    private final LikePlacement placement;
    private final Supplier<List<Object>> imported;
    private final IntConsumer onFirstDraw;

    private boolean drawn;
    /** The latest rows of the database, kept apart so a redraw does not add the imported files twice. */
    private List<Object> stored;

    /**
     * @param imported    The likes of the imported files as they are now.
     * @param onFirstDraw Told how many imported files the list showed when it was first drawn.
     */
    LikesFeed(ClassLoader loader, LikePlacement placement, Supplier<List<Object>> imported, IntConsumer onFirstDraw)
            throws Exception {
        this.relay = Rx.newRelay(loader);
        this.placement = placement;
        this.imported = imported;
        this.onFirstDraw = onFirstDraw;
    }

    /** @return The Observable of the lists: the database rows with the imported files, and every redraw. */
    Object attach(Object database) throws Exception {
        Object withImported = Rx.mapObservable(database, rows -> {
            synchronized (this) {
                stored = new ArrayList<>((List<?>) rows);
                return compose();
            }
        });
        return Rx.merge(withImported, relay);
    }

    /** Draws the list again with the imported files as they are now. */
    void refresh() {
        List<Object> list;
        synchronized (this) {
            if (stored == null) return;
            list = compose();
        }
        // Outside the lock, and on the main thread: the timer of the redraw runs on a thread of its own, and the screen's
        // chain is not to be fed from it.
        app.revanced.extension.shared.Utils.runOnMainThread(() -> {
            try {
                Rx.relayAccept(relay, list);
            } catch (Exception ex) {
                Logger.printException(() -> "Could not redraw the likes", ex);
            }
        });
    }

    private List<Object> compose() {
        List<Object> files = imported.get();
        if (!drawn) {
            drawn = true;
            onFirstDraw.accept(files.size());
        }
        return placement.place(files, stored);
    }
}
