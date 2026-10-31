package app.revanced.extension.soundcloud.offline;

import java.lang.reflect.Method;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.Rx;
import app.revanced.extension.soundcloud.shared.Reflect;

/**
 * Makes playlist and album screens show what is stored on the device immediately.
 * <p>
 * SoundCloud blocked the screen on the network in three places:
 * <ol>
 *     <li>for playlists of other users it waited for the server copy of the playlist;</li>
 *     <li>if the playlist was last synced more than 24 hours ago, the track list waited for a full sync;</li>
 *     <li>if metadata of even one track was missing, the whole track list waited for the server.</li>
 * </ol>
 * All three now show local data first and let the network update the screen through the database.
 */
@SuppressWarnings("unused")
public final class OfflineFirstPatch {
    private OfflineFirstPatch() {
    }

    public static boolean isEnabled() {
        return Settings.isOfflineFirstEnabled();
    }

    /**
     * Point 1.
     *
     * @param repository A {@code PlaylistWithTracksRepository}.
     * @param urn        The playlist {@code Urn}.
     */
    public static void syncInBackground(Object repository, Object urn) {
        Logger.printInfo(() -> "Showing stored playlist first: " + urn);
        Utils.runOnBackgroundThread(() -> {
            try {
                ClassLoader loader = repository.getClass().getClassLoader();
                Class<?> urnClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.Urn");
                Class<?> playlistUrnClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.PlaylistUrn");
                Class<?> strategyClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.repository.LoadStrategy");
                Class<?> repositoryClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.playlists.PlaylistWithTracksRepository");

                Object playlistUrn = Reflect.type(loader, "com.soundcloud.android.foundation.domain.UrnKt")
                        .getMethod("toPlaylist", urnClass).invoke(null, urn);
                Object synced = strategyClass.getField("SYNCED").get(null);
                Object observable = repositoryClass.getMethod("playlistWithTracks", playlistUrnClass, strategyClass)
                        .invoke(repository, playlistUrn, synced);

                // Errors must be consumed, otherwise RxJava passes them to its global handler, which crashes the app.
                Rx.subscribeIgnoringErrors(observable,
                        error -> Logger.printInfo(() -> "Background playlist refresh failed: " + error));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not start background playlist refresh", ex);
            }
        });
    }

    /**
     * Point 2. Starts the playlist sync without waiting for it.
     *
     * @param sync The {@code Completable} of the sync the track list would wait for.
     * @return True if the sync was started in the background and the caller must not wait.
     */
    public static boolean detachPlaylistSync(Object sync) {
        if (!isEnabled()) return false;
        Logger.printInfo(() -> "Playlist sync moved to background");
        Rx.subscribeIgnoringErrors(sync, error -> Logger.printInfo(() -> "Background playlist sync failed: " + error));
        return true;
    }

    /**
     * Point 3. Emits the tracks found in the database right away, then the result that includes
     * tracks loaded from the server.
     *
     * @param repository   The {@code TrackRepository}.
     * @param urns         The list of {@code TrackUrn}.
     * @param syncIfMissing The original {@code tracks(urns, SYNC_MISSING)} observable.
     */
    public static Object localTracksFirst(Object repository, Object urns, Object syncIfMissing) {
        return localFirst(repository, "com.soundcloud.android.foundation.domain.tracks.TrackRepository", "tracks",
                Iterable.class, urns, syncIfMissing);
    }

    /**
     * Point 3 for profiles, such as the ones on the Following screen. Replaces the call
     * {@code UserRepository.users(urns, strategy)}.
     *
     * @return The stored profiles right away, then the result with profiles loaded from the server.
     */
    public static Object localUsersFirst(Object repository, Object urns, Object strategy) throws Exception {
        ClassLoader loader = repository.getClass().getClassLoader();
        Class<?> strategyClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.repository.LoadStrategy");
        Class<?> repositoryClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.users.UserRepository");
        Object requested = repositoryClass.getMethod("users", java.util.Set.class, strategyClass).invoke(repository, urns, strategy);
        return localFirst(repository, repositoryClass.getName(), "users", java.util.Set.class, urns, requested);
    }

    /** Emits {@code repository.method(urns, LOCAL_ONLY)} until {@code syncIfMissing} answers. */
    private static Object localFirst(Object repository, String repositoryName, String methodName, Class<?> urnsType,
                                     Object urns, Object syncIfMissing) {
        if (!isEnabled()) return syncIfMissing;
        try {
            ClassLoader loader = repository.getClass().getClassLoader();
            Class<?> strategyClass = Reflect.type(loader, "com.soundcloud.android.foundation.domain.repository.LoadStrategy");
            Class<?> repositoryClass = Reflect.type(loader, repositoryName);
            Method method = repositoryClass.getMethod(methodName, urnsType, strategyClass);
            Object local = method.invoke(repository, urns, strategyClass.getField("LOCAL_ONLY").get(null));
            // A failing server after the stored data was shown keeps the stored data on screen.
            return Rx.localUntilRemoteKeepingLocal(local, syncIfMissing);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not show stored " + methodName + " first", ex);
            return syncIfMissing;
        }
    }
}
