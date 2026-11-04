package app.revanced.extension.soundcloud.recommendations;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Getters;

/**
 * SoundCloud's play queue as autoplay sees it: which tracks stay in it when recommendations are added, and the
 * recommendations that make way for new ones.
 * <p>
 * With {@code Features.AlignRecommendations} SoundCloud asks for recommendations for the last track of the user's own
 * part of the queue, and every refill first removed all recommendations from the queue, the played ones and the
 * playing one too, then added the answer. The queue manager keeps the place of the playing track as a number and does
 * not move it, so the places stayed right only while the answer was the same list each time. Arsound's duplicate
 * filter (and the server now and then) answered with another list, and the number then pointed at another track: the
 * player, the mini player and the notification showed a track that was not playing, and swiping or the end of the
 * track jumped to a wrong one. Here only the recommendations after the playing track go, so the number stays right,
 * and {@code DuplicateFilter.filterAutoplay} leaves out the tracks that are in the queue already.
 * <p>
 * The manager's methods are obfuscated letters: {@code k()} is the queue, {@code i()} the place of the playing track
 * (checked by the patch, see PlayModePatch.kt). The queue's own methods keep their names.
 */
@SuppressWarnings("unused")
public final class AutoplayQueue {
    private static final String AUTO_PLAY = "AUTO_PLAY";
    /** How far back and ahead of the playing track the queue is read: a queue of all likes can be long. */
    private static final int WINDOW = 500;

    private static volatile WeakReference<Object> queueManager = new WeakReference<>(null);
    /** Whether SoundCloud replaces the recommendations that wait instead of appending to them. */
    private static volatile boolean replacesRecommendations;

    private AutoplayQueue() {
    }

    /** Injection point. End of the {@code PlayQueueManager} constructor: there is one manager in the app. */
    public static void setQueueManager(Object manager) {
        queueManager = new WeakReference<>(manager);
    }

    /**
     * Injection point. Replaces {@code PlayQueue.removeRecommendations()} in the manager's method that adds
     * recommendations: removes the recommendations after the playing track, keeps it and the ones played before it.
     */
    public static void removeUpcomingRecommendations(Object manager) {
        replacesRecommendations = true;
        try {
            Object queue = queueOf(manager);
            int position = positionOf(manager);
            List<?> entries = (List<?>) queue.getClass().getMethod("getEntries").invoke(queue);
            int removed = 0;
            // SoundCloud changes the entries under the same lock.
            synchronized (entries) {
                for (int index = entries.size() - 1; index > position; index--) {
                    Object entry = entries.get(index);
                    // The getters looked up once per class, not per entry while the queue's lock is held.
                    Object item = Getters.get(entry, "getPlayQueueItem");
                    if (isRecommendation(item)) {
                        entries.remove(index);
                        removed++;
                    }
                }
            }
            int count = removed;
            Logger.printDebug(() -> "Autoplay: " + count + " waiting recommendations make way, position " + position + " kept");
        } catch (Exception ex) {
            // Nothing removed: the new recommendations are appended, the playing track keeps its place.
            Logger.printException(() -> "Autoplay: could not remove the waiting recommendations", ex);
        }
    }

    /**
     * The urns of the tracks that stay in the queue when autoplay adds recommendations: the played ones, the playing
     * one, and the ones after it, except recommendations SoundCloud is about to replace. Empty when there is no queue.
     */
    static List<String> stayingUrns() {
        Object manager = queueManager.get();
        if (manager == null) return Collections.emptyList();
        try {
            Object queue = queueOf(manager);
            int position = positionOf(manager);
            // A copy, taken under SoundCloud's lock.
            List<?> items = (List<?>) queue.getClass().getMethod("items").invoke(queue);
            int from = Math.max(0, position - WINDOW);
            int to = Math.min(items.size(), Math.max(position, 0) + WINDOW);
            List<String> urns = new ArrayList<>(to - from);
            for (int index = from; index < to; index++) {
                Object item = items.get(index);
                if (index > position && replacesRecommendations && isRecommendation(item)) continue;
                Object urn = item.getClass().getMethod("getUrn").invoke(item);
                if (urn != null) urns.add(TrackUrn.normalize(urn));
            }
            return urns;
        } catch (Exception ex) {
            Logger.printException(() -> "Autoplay: could not read the play queue", ex);
            return Collections.emptyList();
        }
    }

    /** The place of the playing track in the queue, -1 if it cannot be read. */
    static int position() {
        Object manager = queueManager.get();
        if (manager == null) return -1;
        try {
            return positionOf(manager);
        } catch (Exception ex) {
            return -1;
        }
    }

    /** The class loader of SoundCloud's classes, once the queue manager exists; null before. */
    static ClassLoader classLoader() {
        Object manager = queueManager.get();
        return manager == null ? null : manager.getClass().getClassLoader();
    }

    /** How many items wait after the playing one; -1 if the queue cannot be read. */
    static int upcomingCount() {
        Object manager = queueManager.get();
        if (manager == null) return -1;
        try {
            Object queue = queueOf(manager);
            return (int) queue.getClass().getMethod("size").invoke(queue) - positionOf(manager) - 1;
        } catch (Exception ex) {
            Logger.printException(() -> "Autoplay: could not count the waiting items", ex);
            return -1;
        }
    }

    /**
     * Adds tracks at the end of the queue, as the menu item "Play last" does. Main thread only: SoundCloud changes
     * play queues there.
     *
     * @param urns {@code TrackUrn} objects.
     */
    static boolean playLast(String context, List<Object> urns) {
        Object manager = queueManager.get();
        if (manager == null || urns.isEmpty()) return false;
        try {
            Class<?> trackUrn = Class.forName("com.soundcloud.android.foundation.domain.TrackUrn", false,
                    manager.getClass().getClassLoader());
            Object array = java.lang.reflect.Array.newInstance(trackUrn, urns.size());
            for (int i = 0; i < urns.size(); i++) java.lang.reflect.Array.set(array, i, urns.get(i));
            manager.getClass().getMethod("o", String.class, array.getClass()).invoke(manager, context, array);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Autoplay: could not add tracks to the queue", ex);
            return false;
        }
    }

    /**
     * Takes the tracks put into the queue by hand ("Play next", "Play last", and the batches the wave adds) out of the
     * part that waits. A new queue keeps every such item that waited in the old one and puts it right after the first
     * track (SoundCloud's {@code PlayQueueFactory.reapplyAnyItemsExplicitlyAdded}), ahead of the rest of the new list.
     * Only the places after the playing one are touched, so the playing track keeps its number. Main thread only.
     *
     * @return How many items were taken out.
     */
    static int removeAdded() {
        Object manager = queueManager.get();
        if (manager == null) return 0;
        try {
            Object queue = queueOf(manager);
            int position = positionOf(manager);
            List<?> entries = (List<?>) queue.getClass().getMethod("getEntries").invoke(queue);
            int removed = 0;
            synchronized (entries) {
                for (int index = entries.size() - 1; index > position; index--) {
                    if (!isAddedByUser(Getters.get(entries.get(index), "getPlayQueueItem"))) continue;
                    entries.remove(index);
                    removed++;
                }
            }
            return removed;
        } catch (Exception ex) {
            Logger.printException(() -> "Autoplay: could not take the added tracks out of the queue", ex);
            return 0;
        }
    }

    /** A {@code PlayQueueItem} that was put into the queue by hand ("Play next", "Play last"): its context is {@code Explicit}. */
    private static boolean isAddedByUser(Object item) throws Exception {
        Object context = Getters.get(item, "getPlaybackContext");
        return context != null && "Explicit".equals(context.getClass().getSimpleName());
    }

    private static Object queueOf(Object manager) throws Exception {
        return manager.getClass().getMethod("k").invoke(manager);
    }

    private static int positionOf(Object manager) throws Exception {
        return (int) manager.getClass().getMethod("i").invoke(manager);
    }

    /** A {@code PlayQueueItem} that autoplay added, as SoundCloud's {@code PlayQueueItemKt.isRecommendation} decides. */
    private static boolean isRecommendation(Object item) throws Exception {
        Object context = Getters.get(item, "getPlaybackContext");
        if (context == null) return false;
        Object kind = Getters.get(context, "getKind");
        return AUTO_PLAY.equals(String.valueOf(kind));
    }
}
