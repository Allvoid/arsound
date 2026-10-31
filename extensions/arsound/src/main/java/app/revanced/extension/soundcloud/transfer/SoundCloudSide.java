package app.revanced.extension.soundcloud.transfer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.ClientProfiles;
import app.revanced.extension.soundcloud.local.SavedPlaylist;
import app.revanced.extension.soundcloud.local.SongChoice;
import app.revanced.extension.soundcloud.shared.Rx;

/** What a transfer does on SoundCloud: finding each track and making the playlists. Blocks: off the main thread. */
final class SoundCloudSide {
    /** SoundCloud keeps at most this many tracks in a playlist. */
    static final int PLAYLIST_LIMIT = 500;
    /** A title-only search is tried for titles at least this long: a short one finds too many other songs. */
    private static final int TITLE_ONLY_MIN = 5;
    private static final long[] RETRY_WAITS_MS = {2_000, 5_000};

    private SoundCloudSide() {
    }

    /** SoundCloud's playlists could not be made: the app has not set up its playlist service yet. */
    static final class NotReadyException extends IOException {
        NotReadyException() {
            super("No playlist operations yet");
        }
    }

    /** The SoundCloud upload of the track, or null if there is none. */
    static ClientProfiles.FoundTrack find(YandexTrack track) throws Exception {
        ClientProfiles.FoundTrack pick = SongChoice.pick(search(track.query()), track.artists, track.wantedTitle(),
                track.durationMs);
        if (pick != null || track.durationMs <= 0 || track.artists.isEmpty()
                || track.title.replaceAll("[^\\p{L}\\p{N}]", "").length() < TITLE_ONLY_MIN) {
            return pick;
        }
        // The uploader may write the artist another way ("Земфира" and "Zemfira"): the title alone, held to the length.
        return SongChoice.pick(search(track.title), track.artists, track.wantedTitle(), track.durationMs);
    }

    /** The search, asked again after a pause when it fails: a busy server must not lose the track. */
    private static List<ClientProfiles.FoundTrack> search(String query) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                return ClientProfiles.searchTracks(query);
            } catch (InterruptedIOException ex) {
                throw ex;
            } catch (Exception ex) {
                if (attempt >= RETRY_WAITS_MS.length || Thread.currentThread().isInterrupted()) throw ex;
                Thread.sleep(RETRY_WAITS_MS[attempt]);
            }
        }
    }

    /** The playlists a transfer made before it failed, for the message: the user should know they exist. */
    static final class PartlyMadeException extends IOException {
        final int made;
        final int parts;

        PartlyMadeException(int made, int parts, Exception cause) {
            super("Made " + made + " of " + parts + " playlists", cause);
            this.made = made;
            this.parts = parts;
        }
    }

    /**
     * Private playlists with the tracks, in their order: one per {@link #PLAYLIST_LIMIT} tracks, the second and later
     * ones numbered ("Title (2)"). Each playlist is remembered by {@link TransferStore} the moment it is made: a try
     * that failed half way leaves them in the account, and the next try of the same collection goes on from there
     * instead of making them again.
     *
     * @param key The collection's key, under which the playlists made are remembered.
     * @return The urns of the playlists.
     */
    static List<String> createPlaylists(String key, String title, List<String> trackUrns) throws Exception {
        Object operations = SavedPlaylist.getPlaylistOperations();
        if (operations == null) throw new NotReadyException();
        ClassLoader loader = operations.getClass().getClassLoader();
        Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
        List<String> made = new ArrayList<>(TransferStore.madePlaylists(key));
        int parts = (trackUrns.size() + PLAYLIST_LIMIT - 1) / PLAYLIST_LIMIT;
        if (made.size() > parts) made = new ArrayList<>(made.subList(0, parts));
        for (int part = made.size(); part < parts; part++) {
            try {
                String urn = createPart(operations, urnClass, part == 0 ? title : title + " (" + (part + 1) + ")",
                        trackUrns.subList(part * PLAYLIST_LIMIT, Math.min(trackUrns.size(), (part + 1) * PLAYLIST_LIMIT)));
                TransferStore.addMadePlaylist(key, urn);
                made.add(urn);
            } catch (Exception ex) {
                if (made.isEmpty()) throw ex;
                throw new PartlyMadeException(made.size(), parts, ex);
            }
        }
        return made;
    }

    /** One private playlist with these tracks; its urn. */
    private static String createPart(Object operations, Class<?> urnClass, String name, List<String> trackUrns) throws Exception {
        List<Object> urns = new ArrayList<>();
        for (String urn : trackUrns) {
            urns.add(urnClass.getMethod("forTrack", String.class).invoke(null, urn.substring(urn.lastIndexOf(':') + 1)));
        }
        // false: not public, the playlist is private.
        Object single = operations.getClass().getMethod("createNewPlaylist", String.class, boolean.class, List.class)
                .invoke(operations, name, false, urns);
        Object result = Rx.blockingFirst(single, 60, TimeUnit.SECONDS);
        if (result == null) {
            // No answer in time: the playlist may still be made by SoundCloud. The user is told to look.
            throw new UnknownOutcomeException(name);
        }
        if (!result.getClass().getName().endsWith("PlaylistCreationResult$Success")) {
            throw new IOException("SoundCloud did not make the playlist: " + result);
        }
        Object playlist = result.getClass().getMethod("getPlaylist").invoke(result);
        String urn = String.valueOf(playlist.getClass().getMethod("getUrn").invoke(playlist));
        Logger.printInfo(() -> "Transfer: made the playlist " + urn + " with " + urns.size() + " tracks");
        app.revanced.extension.soundcloud.shared.OwnPlaylists.remember(urn);
        return urn;
    }

    /** SoundCloud did not answer in time whether it made the playlist; it may have. */
    static final class UnknownOutcomeException extends IOException {
        final String name;

        UnknownOutcomeException(String name) {
            super("No answer about the playlist " + name);
            this.name = name;
        }
    }
}
