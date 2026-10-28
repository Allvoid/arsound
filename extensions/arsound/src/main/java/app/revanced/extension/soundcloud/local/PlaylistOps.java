package app.revanced.extension.soundcloud.local;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.soundcloud.shared.Rx;

/**
 * SoundCloud's {@code DefaultPlaylistOperations}, called by the names of its methods that stay when the app is
 * shrunk. Each call throws what the reflection throws, as the callers handle failures in their own way.
 */
final class PlaylistOps {
    private static final long READ_TIMEOUT_SECONDS = 20;

    private final Object operations;
    private final ClassLoader loader;

    private PlaylistOps(Object operations) {
        this.operations = operations;
        this.loader = operations.getClass().getClassLoader();
    }

    /** @return The operations, or null before the app has made them. */
    static PlaylistOps current() {
        Object operations = SavedPlaylist.playlistOperations();
        return operations == null ? null : new PlaylistOps(operations);
    }

    /** SoundCloud's own object, for code that works with it directly. */
    Object raw() {
        return operations;
    }

    /** The {@code Urn} of a playlist from its text, e.g. {@code soundcloud:playlists:123}. */
    Object playlistUrn(String playlistUrnText) throws Exception {
        return Urns.forPlaylist(loader, Urns.idOf(playlistUrnText));
    }

    /** {@code Single<List<Urn>>} of the tracks a playlist has on SoundCloud, from the database. */
    Object trackUrns(Object playlistUrn) throws Exception {
        return method("playlistTrackUrns").invoke(operations, playlistUrn);
    }

    /** The same list, blocking. Null if the list is not there. */
    List<Object> serverTracks(Object playlistUrn) throws Exception {
        Object stored = Rx.blockingFirst(trackUrns(playlistUrn), READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return stored instanceof List ? new ArrayList<>((List<?>) stored) : null;
    }

    /** {@code Single<List<Urn>>} of the tracks of a playlist as they play. */
    Object playbackUrns(Object playlistUrn) throws Exception {
        return method("trackUrnsForPlayback").invoke(operations, playlistUrn);
    }

    /** Saves the whole track list of a playlist; SoundCloud then sends it to the server. Returns the {@code Completable}. */
    Object editTracks(Object playlistUrn, Set<Object> urns) throws Exception {
        return operations.getClass().getMethod("editPlaylistTracks", Urns.urnClass(loader), Set.class)
                .invoke(operations, playlistUrn, urns);
    }

    /** Asks SoundCloud to send the changes of a playlist, as its own "Add to playlist" sheet does. */
    void sync(Object playlistUrn) throws Exception {
        method("syncPlaylist").invoke(operations, playlistUrn);
    }

    /** Tells the library that these playlists changed. */
    void notifyUpdated(Object playlistUrn) throws Exception {
        operations.getClass().getMethod("notifyPlaylistsUpdated", Set.class, Set.class)
                .invoke(operations, Collections.singleton(playlistUrn), Collections.emptySet());
    }

    private Method method(String name) throws Exception {
        return operations.getClass().getMethod(name, Urns.urnClass(loader));
    }
}
