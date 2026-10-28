package app.revanced.extension.soundcloud.local;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Rx;

import android.os.Looper;

/**
 * SoundCloud's track repository drops the urns of imported files from track lists. This puts the tracks of the
 * files back at their positions, using {@link LocalTrackLoader} for the files whose tags are not read yet.
 */
final class LocalFileTracks {
    private static final long LOCAL_TRACK_BUDGET_MS = 1_500;
    private static final String RESPONSE_PREFIX = "com.soundcloud.android.foundation.domain.repository.ListResponse$Success$";

    private LocalFileTracks() {
    }

    /**
     * Called at the start of {@code LocalFileAwareTrackRepository.tracks()}: if the list has local files, it requests
     * the other tracks as usual and inserts the tracks of the files at their positions.
     *
     * @param repository The {@code LocalFileAwareTrackRepository}.
     * @param urns       The requested {@code Iterable<TrackUrn>}.
     * @param strategy   The {@code LoadStrategy}.
     * @return The {@code Observable<ListResponse<Track>>}, or null to run the original method.
     */
    static Object withLocalFiles(Object repository, Object urns, Object strategy) {
        try {
            List<Object> requested = new ArrayList<>();
            List<Object> remote = new ArrayList<>();
            for (Object urn : (Iterable<?>) urns) {
                requested.add(urn);
                if (!Urns.isLocalFile(urn)) remote.add(urn);
            }
            if (remote.size() == requested.size()) return null;
            Logger.printInfo(() -> "Adding " + (requested.size() - remote.size()) + " local file tracks to a track list");

            ClassLoader loader = repository.getClass().getClassLoader();
            Method track = LocalTrackLoader.trackMethod(repository);
            Method tracks = repository.getClass().getMethod("tracks", Iterable.class, LocalTrackLoader.strategyClass(loader));
            Object response = tracks.invoke(repository, remote, strategy);

            return Rx.mapObservable(response, value -> {
                try {
                    // Waiting here froze the app: this can run on the main thread, and every missing file
                    // track waited up to 5 s in turn. Now: cached items, a short total budget off the main
                    // thread only, and anything still missing is loaded in the background for the next update.
                    boolean mainThread = Looper.myLooper() == Looper.getMainLooper();
                    long deadline = System.currentTimeMillis() + LOCAL_TRACK_BUDGET_MS;
                    return merge(loader, value, requested, urn -> {
                        Object cached = LocalTrackLoader.cached(urn);
                        if (cached != null) return cached;
                        long left = deadline - System.currentTimeMillis();
                        if (mainThread || left <= 0) {
                            LocalTrackLoader.queue(repository, track, urn, strategy);
                            return null;
                        }
                        Object item = LocalTrackLoader.loadWithin(repository, track, urn, strategy, left);
                        // Not ready within the budget, or another file is being read: it continues in the background.
                        if (item == null) LocalTrackLoader.queue(repository, track, urn, strategy);
                        return item;
                    });
                } catch (Exception ex) {
                    Logger.printException(() -> "Could not merge local file tracks", ex);
                    return value;
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add local file tracks", ex);
            return null;
        }
    }

    /** Rebuilds a {@code ListResponse} with the tracks of the requested urns in the requested order. */
    private static Object merge(ClassLoader loader, Object response, List<Object> requested,
                                Function<Object, Object> loadLocal) throws Exception {
        Class<?> total = Class.forName(RESPONSE_PREFIX + "Total", false, loader);
        Class<?> partial = Class.forName(RESPONSE_PREFIX + "Partial", false, loader);

        List<?> found;
        if (total.isInstance(response)) found = (List<?>) total.getMethod("getItems").invoke(response);
        else if (partial.isInstance(response)) found = (List<?>) partial.getMethod("getFound").invoke(response);
        else return response;

        Map<Object, Object> byUrn = new HashMap<>();
        for (Object item : found) byUrn.put(item.getClass().getMethod("getTrackUrn").invoke(item), item);

        List<Object> merged = new ArrayList<>();
        for (Object urn : requested) {
            Object item = byUrn.get(urn);
            if (item == null && Urns.isLocalFile(urn)) item = loadLocal.apply(urn);
            if (item != null) merged.add(item);
        }

        if (total.isInstance(response)) return total.getConstructor(List.class).newInstance(merged);
        Object missing = partial.getMethod("getMissing").invoke(response);
        Object exception = partial.getMethod("getException").invoke(response);
        return partial.getConstructors()[0].getParameterTypes().length == 3
                ? partial.getConstructor(List.class, List.class,
                        Class.forName("com.soundcloud.android.foundation.domain.repository.RepositoryException", false, loader))
                .newInstance(merged, missing, exception)
                : response;
    }
}
