package app.revanced.extension.soundcloud.recommendations;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
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
    /** Looked up once: the class of the items is always the same. */
    private static volatile Field[] repostFields;

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

    private static boolean isRepost(Object item) {
        try {
            for (Field field : repostFields(item.getClass())) {
                if (field.get(item) != null) return true;
            }
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
        return false;
    }

    private static Field[] repostFields(Class<?> itemClass) {
        Field[] fields = repostFields;
        if (fields != null && fields.length > 0 && fields[0].getDeclaringClass() == itemClass) return fields;
        try {
            fields = new Field[REPOST_FIELDS.length];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = itemClass.getDeclaredField(REPOST_FIELDS[i]);
                fields[i].setAccessible(true);
            }
        } catch (NoSuchFieldException ex) {
            throw new IllegalStateException("The Feed item has no field for reposts: " + itemClass.getName(), ex);
        }
        repostFields = fields;
        return fields;
    }
}
