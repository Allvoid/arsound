package app.revanced.extension.soundcloud.player;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;

/**
 * SoundCloud's play queue and the manager that holds it, reached by reflection. The one place that knows the names of
 * the manager's methods: the class is obfuscated, so they are short letters, and the patch checks that each is still
 * there (PlayModePatch.kt, the queue manager methods and {@code reflectedMethods}). The names of the queue's own
 * methods are not obfuscated.
 * <p>
 * Everything here runs on the main thread: SoundCloud sets play queues only there.
 */
final class PlayQueueApi {
    private static final String PLAY_QUEUE = "com.soundcloud.android.foundation.playqueue.PlayQueue";
    private static final String SHUFFLED_QUEUE = PLAY_QUEUE + "$Shuffled";
    private static final String PLAY_QUEUE_ITEM = "com.soundcloud.android.foundation.playqueue.PlayQueueItem";
    private static final String REPEAT_MODE = "com.soundcloud.android.foundation.domain.playqueue.RepeatMode";

    /** The names the repeat setting of the queue has. */
    static final String REPEAT_NONE = "REPEAT_NONE";
    static final String REPEAT_ALL = "REPEAT_ALL";
    static final String REPEAT_ONE = "REPEAT_ONE";

    private PlayQueueApi() {
    }

    // region The manager

    /** The queue the manager plays now. */
    static Object queue(Object manager) throws Exception {
        return manager.getClass().getMethod("k").invoke(manager);
    }

    /** The place of the playing item in the queue. */
    static int position(Object manager) throws Exception {
        return (int) manager.getClass().getMethod("i").invoke(manager);
    }

    /** Sets what is repeated (one of the {@code REPEAT_} names), keeping the playing track. */
    static void setRepeat(Object manager, String repeatName) throws Exception {
        Class<?> repeatModes = Class.forName(REPEAT_MODE);
        Method setRepeat = manager.getClass().getMethod("E", repeatModes, boolean.class);
        setRepeat.invoke(manager, repeatMode(repeatModes, repeatName), true);
    }

    /** Makes the queue the one the manager plays. */
    static void setQueue(Object manager, Object queue) throws Exception {
        manager.getClass().getMethod("C", Class.forName(PLAY_QUEUE)).invoke(manager, queue);
    }

    /** Goes back from the shuffled queue to the one from before the shuffle. */
    static void unshuffle(Object manager) throws Exception {
        manager.getClass().getMethod("F").invoke(manager);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object repeatMode(Class<?> repeatModes, String name) {
        return Enum.valueOf((Class) repeatModes, name);
    }

    // endregion

    // region A queue

    static int size(Object queue) throws Exception {
        return (int) queue.getClass().getMethod("size").invoke(queue);
    }

    static boolean isShuffled(Object queue) throws Exception {
        return Class.forName(SHUFFLED_QUEUE).isInstance(queue);
    }

    /** The name of what the queue repeats: one of the {@code REPEAT_} names. */
    static String repeatName(Object queue) throws Exception {
        return String.valueOf(queue.getClass().getMethod("getRepeatMode").invoke(queue));
    }

    /** A copy of the queue with the tracks from a place on shuffled; the ones before it stay as they are. */
    static Object shuffledFrom(Object queue, int from) throws Exception {
        return queue.getClass().getMethod("shuffle", int.class).invoke(queue, from);
    }

    /**
     * Tracks added after shuffling (autoplay recommendations, "Play next") are only in the shuffled queue.
     * SoundCloud's unshuffle goes back to the queue from before the shuffle and throws if the playing track is not
     * in it, so the mode stayed on "Shuffle" once autoplay had started. The added tracks are appended to that
     * queue first, in the order they play now.
     */
    static void keepAddedItems(Object shuffled) throws Exception {
        Object original = shuffled.getClass().getMethod("getOriginalQueue").invoke(shuffled);
        // The original queue read once into a set: asking it about each track copied the whole queue every time.
        // Played items are told apart by identity, as SoundCloud's own indexOfUnique does; the rest by equals.
        List<?> originalItems = (List<?>) original.getClass().getMethod("items").invoke(original);
        java.util.Set<Object> byIdentity = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.Set<Object> byValue = new java.util.HashSet<>();
        Class<?> playable = playableItemClass(original.getClass().getClassLoader());
        for (Object item : originalItems) {
            if (playable != null && playable.isInstance(item)) byIdentity.add(item);
            else byValue.add(item);
        }
        List<Object> added = new ArrayList<>();
        for (Object item : (List<?>) shuffled.getClass().getMethod("items").invoke(shuffled)) {
            boolean known = playable != null && playable.isInstance(item) ? byIdentity.contains(item) : byValue.contains(item);
            if (!known) added.add(item);
        }
        if (added.isEmpty()) return;
        original.getClass().getMethod("addAllPlayQueueItems", List.class, String.class)
                .invoke(original, added, "reco");
        Logger.printDebug(() -> "Play mode: kept " + added.size() + " tracks added after shuffling");
    }

    /** SoundCloud's playable queue item, which its queue tells apart by identity; null if it cannot be found. */
    private static Class<?> playableItemClass(ClassLoader loader) {
        try {
            return Class.forName(PLAY_QUEUE_ITEM + "$Playable", false, loader);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    // endregion
}
