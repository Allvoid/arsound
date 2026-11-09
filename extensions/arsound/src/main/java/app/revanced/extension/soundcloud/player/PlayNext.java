package app.revanced.extension.soundcloud.player;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Getters;

/**
 * Where "Play next" puts a track. The tracks put next play in the order they were tapped, right after the playing one, and then
 * the queue goes on as it was: a second "Play next" goes after the first, not before it.
 * <p>
 * SoundCloud's own rule ({@code PlayQueueManager.p}) counts the items after the playing one that were added by hand (their context
 * is {@code Explicit}) and puts the new track after them. Two things broke it in Arsound:
 * <ul>
 * <li>"My Wave" adds its batches the same way, so in a wave every track ahead is "added by hand", and the new track went after
 * the whole wave;</li>
 * <li>when every item ahead is "added by hand" SoundCloud's count ends at -1, and the track went one place too early: in front of
 * the playing track. The playing track's number was then the new track's, the player showed it while the old one played, and at
 * its end the old one played again.</li>
 * </ul>
 * Here only the tracks the person put next (and ads) are counted, and the place is never before the one after the playing track.
 */
@SuppressWarnings("unused")
public final class PlayNext {
    /** The start page of the tracks the wave adds (see {@code AutoplayQueue.WAVE_PAGE}). */
    private static final String WAVE_PAGE = "arsound:wave";

    /** What an item after the playing one is, for the rule above. */
    enum Kind {
        /** An ad: stays where it is, the tracks put next go after it, as SoundCloud does. */
        AD,
        /** A track the person put next (or added with "Play last", which is at the end). */
        PUT_NEXT,
        /** Anything else: the queue as it was. */
        QUEUE
    }

    private PlayNext() {
    }

    /**
     * The place for the next "Play next".
     *
     * @param playing The place of the playing track.
     * @param after   What the items after it are, in order (the first is at {@code playing + 1}); may be shorter than the queue,
     *                it is read only up to the first {@link Kind#QUEUE}.
     */
    static int position(int playing, List<Kind> after) {
        int place = Math.max(0, playing + 1);
        for (Kind kind : after) {
            if (kind == Kind.QUEUE) break;
            place++;
        }
        return place;
    }

    /**
     * Injection point: replaces the call of {@code PlayQueueManager.n(int, TrackUrn[], String)} at the end of
     * {@code PlayQueueManager.p}, the "Play next" of every menu. Inserts the tracks at {@link #placeOf}; at SoundCloud's own place
     * when the queue cannot be read.
     *
     * @param manager    The {@code PlayQueueManager}.
     * @param soundCloud The place SoundCloud worked out.
     * @param urns       The {@code TrackUrn[]} to insert.
     * @param page       The start page the menu passed.
     * @return What {@code n} returns.
     */
    public static Object insert(Object manager, int soundCloud, Object urns, String page) throws Exception {
        int place = soundCloud;
        try {
            place = placeOf(manager);
        } catch (Throwable ex) {
            Logger.printException(() -> "Play next: could not read the queue, SoundCloud's place is used", ex);
        }
        if (place != soundCloud) {
            int chosen = place;
            Logger.printInfo(() -> "Play next: place " + chosen + " instead of SoundCloud's " + soundCloud);
        }
        try {
            return manager.getClass().getMethod("n", int.class, urns.getClass(), String.class).invoke(manager, place, urns, page);
        } catch (java.lang.reflect.InvocationTargetException ex) {
            // SoundCloud's own failure, as it would have come without the reflection.
            Throwable cause = ex.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw ex;
        }
    }

    /** The place for the next "Play next" in the manager's queue now. {@code i()} is the playing place, {@code k()} the queue. */
    static int placeOf(Object manager) throws Exception {
        int playing = (int) manager.getClass().getMethod("i").invoke(manager);
        Object queue = manager.getClass().getMethod("k").invoke(manager);
        List<?> items = (List<?>) queue.getClass().getMethod("items").invoke(queue);
        List<Kind> after = new ArrayList<>();
        for (int index = playing + 1; index < items.size(); index++) {
            Kind kind = kindOf(items.get(index));
            after.add(kind);
            if (kind == Kind.QUEUE) break;
        }
        return Math.min(position(playing, after), items.size());
    }

    private static Kind kindOf(Object item) throws Exception {
        if (item == null) return Kind.QUEUE;
        if (item.getClass().getSimpleName().equals("Ad")) return Kind.AD;
        Object context = Getters.get(item, "getPlaybackContext");
        if (context == null || !"Explicit".equals(context.getClass().getSimpleName())) return Kind.QUEUE;
        return WAVE_PAGE.equals(Getters.get(context, "getStartPage")) ? Kind.QUEUE : Kind.PUT_NEXT;
    }
}
