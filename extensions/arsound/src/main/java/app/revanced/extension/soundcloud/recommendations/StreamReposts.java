package app.revanced.extension.soundcloud.recommendations;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * "Hide reposts in Feed": the items of the Feed tab that someone else reposted are left out of what the Feed stores,
 * so it shows only the posts of the artists themselves. SoundCloud syncs the Feed in pages ({@code SoundStreamSyncer}); each
 * page, an {@code ApiCollection} of {@code ApiStreamItem}, goes through {@link #hideReposts} right before it is stored
 * (called from {@code recommendations/StreamRepostsPatch.kt}). An item is a repost when it holds a track or a playlist
 * repost; promoted items and own posts are not touched. Pulling the Feed down syncs it from scratch, so a switch
 * takes effect with the next refresh.
 */
@SuppressWarnings("unused")
public final class StreamReposts {
    /** The fields of {@code ApiStreamItem} that hold a repost, read by name (the class is not shrunk). */
    private static final String[] REPOST_FIELDS = {"apiTrackRepost", "apiPlaylistRepost"};
    /** The repost fields found for each item class, looked up once per class (a subclass of the item has its own). */
    private static final Map<Class<?>, Field[]> REPOST_FIELDS_BY_CLASS = new ConcurrentHashMap<>();
    private static final Field[] NO_FIELDS = new Field[0];

    private StreamReposts() {
    }

    /**
     * Injection point. Called with the page of the Feed about to be stored.
     *
     * @param collection An {@code ApiCollection} of {@code ApiStreamItem}.
     * @return The same page, or a copy of it without the reposts (the next-page links are kept); the page as it is if
     * the switch is off, nothing is a repost, or its shape is not the expected one.
     */
    public static Object hideReposts(Object collection) {
        if (collection == null || !Settings.isHideFeedRepostsEnabled()) return collection;
        try {
            List<?> items = (List<?>) collection.getClass().getMethod("getCollection").invoke(collection);
            List<?> kept = without(items, StreamReposts::isRepost);
            if (kept == items) return collection;
            Logger.printInfo(() -> "Feed page checked: " + items.size() + " items, reposts hidden: " + (items.size() - kept.size()));
            return collection.getClass().getMethod("copyWithItems", List.class).invoke(collection, kept);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not hide the reposts of the Feed", ex);
            return collection;
        }
    }

    /**
     * The items for which {@code isRepost} is false, in their order.
     *
     * @return The same list object when nothing is left out, so the caller can tell.
     */
    static <T> List<T> without(List<T> items, Predicate<T> isRepost) {
        List<T> kept = null;
        for (int i = 0; i < items.size(); i++) {
            T item = items.get(i);
            boolean repost = item != null && isRepost.test(item);
            if (repost && kept == null) kept = new ArrayList<>(items.subList(0, i));
            if (!repost && kept != null) kept.add(item);
        }
        return kept == null ? items : kept;
    }

    /** Whether the item holds a repost. An item that cannot be read is not one: the rest of its page is still hidden. */
    private static boolean isRepost(Object item) {
        try {
            for (Field field : repostFields(item.getClass())) {
                if (field.get(item) != null) return true;
            }
        } catch (IllegalAccessException ex) {
            return false;
        }
        return false;
    }

    private static Field[] repostFields(Class<?> itemClass) {
        return REPOST_FIELDS_BY_CLASS.computeIfAbsent(itemClass, StreamReposts::findRepostFields);
    }

    /**
     * The repost fields the class has, its superclasses included. A class without any is logged once and its items are
     * not reposts; a missing field of a class that has the others only leaves that one out.
     */
    private static Field[] findRepostFields(Class<?> itemClass) {
        List<Field> found = new ArrayList<>(REPOST_FIELDS.length);
        for (String name : REPOST_FIELDS) {
            Field field = findField(itemClass, name);
            if (field != null) found.add(field);
        }
        if (found.isEmpty()) {
            Logger.printInfo(() -> "Feed item without repost fields, its items are not checked: " + itemClass.getName());
            return NO_FIELDS;
        }
        return found.toArray(new Field[0]);
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Not declared here: the class it extends may have it.
            }
        }
        return null;
    }
}
