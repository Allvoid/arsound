package app.revanced.extension.soundcloud.local;

import android.content.Context;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * Imported files among "Your likes", as if the user liked them. On this phone only: nothing is written to the
 * SoundCloud database and nothing is sent to the server.
 * <p>
 * The likes screen reads the database with a join on the tracks table, which has no row for a file. So the lists
 * are read as usual and the files are added to what comes out of {@code RoomLikesReadStorage}: to the first
 * page of the list, to the play queue of tap, shuffle and play all, and to the number in the header. The methods
 * that sync with the server, make offline copies or count for other screens are left alone, so a file can never
 * reach them.
 * <p>
 * The methods of the first group are hooks: they take SoundCloud's objects as Object and return null to let the
 * original method run.
 */
@SuppressWarnings("unused")
public final class ImportedLikes {
    private static final String PAGED_LIKES = "pagedLiveLoadTrackLikes";
    private static final String PLAY_ITEMS = "loadLikedTrackPlayItems";

    private static final Object LOCK = new Object();
    private static final HiddenLikes HIDDEN = new HiddenLikes(new HiddenLikesPreferences());
    private static final LikesPaging PAGING = new LikesPaging();
    private static final LikePlacement PLACEMENT = new ImportedOnTop();
    /** Set while the original method runs for a hook, so the hook in it lets that call through. */
    private static final ThreadLocal<Boolean> CALLING_ORIGINAL = new ThreadLocal<>();
    private static final Map<String, Method> ORIGINALS = new ConcurrentHashMap<>();

    /** Cleared by {@link #refresh}. */
    private static LikedFiles liked;
    private static volatile LikeObjects objects;
    private static volatile Object trackRepository;

    private ImportedLikes() {
    }

    // region Hooks

    /**
     * {@code RoomLikesReadStorage.pagedLiveLoadTrackLikes(sort, query, offset, limit)}: a page of the likes.
     * The first page gets the imported files; the later ones are moved back by their number.
     */
    public static Object pagedLikes(Object storage, Object sort, String query, int offset, int limit) {
        if (!isActive() || isCallingOriginal()) return null;
        try {
            String list = sort + "|" + query;
            if (offset > 0) {
                int databaseOffset = PAGING.databaseOffset(list, offset);
                return databaseOffset == offset
                        ? null : callOriginal(storage, PAGED_LIKES, sort, query, databaseOffset, limit);
            }

            LikeObjects objects = objects(storage.getClass().getClassLoader());
            LikesFeed feed = new LikesFeed(storage.getClass().getClassLoader(), PLACEMENT,
                    () -> isActive() ? shownLikes(objects, query) : Collections.emptyList(),
                    count -> PAGING.firstPage(list, count));
            LikesFeeds.add(feed);
            return feed.attach(callOriginal(storage, PAGED_LIKES, sort, query, offset, limit));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add imported files to the likes", ex);
            return null;
        }
    }

    /**
     * {@code RoomLikesReadStorage.loadLikedTrackPlayItems(sort)}: the play queue of tap, shuffle and play all,
     * in the order of the screen.
     */
    public static Object playItems(Object storage, Object sort) {
        if (!isActive() || isCallingOriginal() || likedFiles().size() == 0) return null;
        try {
            LikeObjects objects = objects(storage.getClass().getClassLoader());
            return Rx.mapSingle(callOriginal(storage, PLAY_ITEMS, sort), rows -> {
                try {
                    // All files, not only the loaded ones: the queue plays them from the file.
                    List<Object> files = new ArrayList<>();
                    for (File file : likedFiles().all()) {
                        Object urn = objects.urn(file);
                        if (urn != null) files.add(objects.playItem(urn));
                    }
                    return PLACEMENT.place(files, new ArrayList<Object>((List<?>) rows));
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not queue imported files", ex);
                    return rows;
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not queue imported files", ex);
            return null;
        }
    }

    /** The number of likes in the header of the screen, in {@code TrackLikesPresenter}: counts the files shown. */
    public static Object withImportedCount(Object count) {
        try {
            LikeObjects objects = objects(count.getClass().getClassLoader());
            return Rx.mapObservable(count, total -> {
                if (!isActive()) return total;
                return (Integer) total + shownLikes(objects, "").size();
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not count imported files in the likes", ex);
            return count;
        }
    }

    /**
     * The tracks whose "liked by" users are asked from the server, in {@code RelatedLikersService}: the list without
     * imported files, which SoundCloud does not know. Always, whether the option is on or not.
     */
    public static List<?> withoutLocalTracks(List<?> urns) {
        boolean anyLocal = false;
        for (Object urn : urns) anyLocal |= LikeObjects.isLocal(urn);
        if (!anyLocal) return urns;

        List<Object> remote = new ArrayList<>(urns.size());
        for (Object urn : urns) {
            if (!LikeObjects.isLocal(urn)) remote.add(urn);
        }
        return remote;
    }

    /** Called from the constructor of {@code LocalFileAwareTrackRepository}: it loads the tracks of the files. */
    public static void setTrackRepository(Object repository) {
        trackRepository = repository;
    }

    // endregion

    /** True while the option "Imported tracks in Your likes" is on. */
    static boolean isActive() {
        return Settings.isImportedInLikesEnabled();
    }

    /** The imported files that are among the likes now. */
    static LikedFiles likedFiles() {
        synchronized (LOCK) {
            if (liked == null) {
                Context context = Utils.getContext();
                // Not kept while the app has no context yet.
                if (context == null) return new LikedFiles(Collections.emptyList(), file -> false);
                liked = new LikedFiles(LocalMusic.getFiles(context), HIDDEN::isHidden);
            }
            return liked;
        }
    }

    /**
     * The user liked or un-liked an imported file.
     *
     * @return True if that changed the likes.
     */
    static boolean setLiked(File file, boolean like) {
        boolean changed = like ? HIDDEN.show(file) : HIDDEN.hide(file);
        if (changed) refresh();
        return changed;
    }

    /** Files were imported, deleted or finished loading, or the option changed: the lists on screen are redrawn. */
    public static void refresh() {
        synchronized (LOCK) {
            liked = null;
        }
        LikesFeeds.redrawSoon();
    }

    /** An imported file was deleted. */
    public static void onFileDeleted(File file) {
        // Forgotten, so a new file that gets the same name is not hidden from the likes.
        HIDDEN.show(file);
        refresh();
    }

    /**
     * The likes of the imported files that can be drawn right now. A file whose track is not loaded yet would be
     * dropped by the list, so it is left out here, and loaded in the background: the list is redrawn once it is
     * ready. That keeps the number of rows the first page shows equal to the number counted for paging.
     */
    private static List<Object> shownLikes(LikeObjects objects, String query) {
        List<Object> likes = new ArrayList<>();
        List<Object> waiting = new ArrayList<>();
        for (File file : likedFiles().matching(query)) {
            Object urn = objects.urn(file);
            if (urn == null) continue;
            if (!LocalTrackLoader.isLoaded(urn)) {
                waiting.add(urn);
                continue;
            }
            try {
                likes.add(objects.like(urn, file));
            } catch (ReflectiveOperationException ex) {
                Logger.printException(() -> "Could not make a like of " + file, ex);
            }
        }
        Object repository = trackRepository;
        if (!waiting.isEmpty() && repository != null) LocalTrackLoader.queueAll(repository, waiting);
        return likes;
    }

    private static LikeObjects objects(ClassLoader loader) throws ReflectiveOperationException {
        LikeObjects current = objects;
        if (current == null) objects = current = new LikeObjects(loader);
        return current;
    }

    private static boolean isCallingOriginal() {
        return CALLING_ORIGINAL.get() != null;
    }

    /** Runs the method that a hook replaced; the hook in it lets this call through. */
    private static Object callOriginal(Object storage, String name, Object... arguments) throws Exception {
        Method method = ORIGINALS.get(name);
        if (method == null) {
            for (Method candidate : storage.getClass().getMethods()) {
                if (candidate.getName().equals(name) && candidate.getParameterTypes().length == arguments.length) {
                    method = candidate;
                    ORIGINALS.put(name, method);
                    break;
                }
            }
            if (method == null) throw new NoSuchMethodException(name);
        }
        CALLING_ORIGINAL.set(true);
        try {
            return method.invoke(storage, arguments);
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause();
            throw cause instanceof Exception ? (Exception) cause : ex;
        } finally {
            CALLING_ORIGINAL.remove();
        }
    }
}
