package app.revanced.extension.soundcloud.transfer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.download.ClientProfiles;
import app.revanced.extension.soundcloud.local.SavedPlaylist;
import app.revanced.extension.soundcloud.local.SoundCloudLikes;
import app.revanced.extension.soundcloud.local.SongChoice;
import app.revanced.extension.soundcloud.shared.Rx;

/** What a transfer does on SoundCloud: finding each track and making the playlists. Blocks: off the main thread. */
final class SoundCloudSide {
    /** SoundCloud keeps at most this many tracks in a playlist. */
    static final int PLAYLIST_LIMIT = 500;
    /** A title-only search is tried for titles at least this long: a short one finds too many other songs. */
    private static final int TITLE_ONLY_MIN = 5;
    private static final long[] RETRY_WAITS_MS = {2_000, 5_000};
    /** Likes go one at a time, and each is a request of its own: a pause, so a long list does not flood the server. */
    private static final long LIKE_PAUSE_MS = 250;
    /** A longer pause after a like that failed, which is mostly the server asking to slow down. */
    private static final long LIKE_FAILURE_PAUSE_MS = 3_000;
    /** This many failed likes in a row stop the likes: the server does not take them now. */
    private static final int LIKE_FAILURES = 8;
    /** The server is asked this many times how many likes it has, this long apart, after the likes. */
    private static final int VERIFY_ATTEMPTS = 4;
    private static final long VERIFY_PAUSE_MS = 15_000;

    private SoundCloudSide() {
    }

    /** SoundCloud's playlists or likes could not be used: the app has not set up that service yet. */
    static final class NotReadyException extends IOException {
        NotReadyException() {
            super("The SoundCloud service is not set up yet");
        }
    }

    /** The SoundCloud upload of the track, or null if there is none. */
    static ClientProfiles.FoundTrack find(SourceTrack track) throws Exception {
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

    /**
     * Likes the tracks that are not liked yet, see {@link LikePlan}. A like that fails is skipped: the rest of the list
     * still gets theirs.
     *
     * @param progress Told how many of the tracks are liked so far.
     * @return How many of the tracks the server has in the likes now (what the phone says, if the server cannot be asked).
     * @throws LikesStoppedException If SoundCloud stopped taking likes.
     */
    static int like(List<String> trackUrns, IntConsumer progress) throws Exception {
        if (!SoundCloudLikes.isReady()) throw new NotReadyException();
        Set<String> alreadyInLikes = SoundCloudLikes.alreadyLiked(trackUrns);
        // Not liked blindly: a like again moves a track up the likes.
        if (alreadyInLikes == null) throw new NotReadyException();
        List<String> plan = LikePlan.of(trackUrns, alreadyInLikes);
        int liked = trackUrns.size() - plan.size();
        int alreadyLiked = liked;
        Logger.printInfo(() -> "Transfer: liking " + plan.size() + " tracks, " + alreadyLiked + " are liked already");
        progress.accept(liked);
        int failuresInRow = 0;
        for (String urn : plan) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            Throwable failure = SoundCloudLikes.like(urn);
            if (failure == null) {
                failuresInRow = 0;
                progress.accept(++liked);
                Thread.sleep(LIKE_PAUSE_MS);
                continue;
            }
            Logger.printInfo(() -> "Transfer: could not like " + urn + ": " + failure);
            if (++failuresInRow >= LIKE_FAILURES) throw new LikesStoppedException(liked, trackUrns.size());
            Thread.sleep(LIKE_FAILURE_PAUSE_MS);
        }
        return verified(trackUrns, liked, progress);
    }

    /**
     * How many of the tracks the server has in the likes. The phone says a like is done when it wrote it down, but
     * the server may not take it (its own limits): then the like is gone with the next sync of the likes, and the
     * count of "liked" the transfer would show is wrong. The server is asked after the likes, again after a pause
     * while the number still grows, and the last answer is the result.
     *
     * @param given How many the phone says are liked; the answer when the server cannot be asked.
     */
    private static int verified(List<String> trackUrns, int given, IntConsumer progress) throws InterruptedException {
        int best = -1;
        int unchanged = 0;
        for (int attempt = 0; attempt < VERIFY_ATTEMPTS; attempt++) {
            if (attempt > 0) Thread.sleep(VERIFY_PAUSE_MS);
            List<String> ids = app.revanced.extension.soundcloud.download.DownloadPlaylistPatch.serverLikedTrackIds();
            if (ids == null) break;
            Set<String> onServer = new java.util.HashSet<>(ids);
            int count = 0;
            for (String urn : trackUrns) {
                if (onServer.contains(urn.substring(urn.lastIndexOf(':') + 1))) count++;
            }
            unchanged = count == best ? unchanged + 1 : 0;
            best = count;
            progress.accept(count);
            if (count >= trackUrns.size() || unchanged >= 1) break;
        }
        if (best < 0) return given;
        final int serverHas = best;
        Logger.printInfo(() -> "Transfer: the phone says " + given + " of " + trackUrns.size()
                + " are liked, the server has " + serverHas);
        return best;
    }

    /** SoundCloud stopped taking likes; the playlists are made, and the likes given so far stay. */
    static final class LikesStoppedException extends IOException {
        final int liked;
        final int total;

        LikesStoppedException(int liked, int total) {
            super("Liked " + liked + " of " + total);
            this.liked = liked;
            this.total = total;
        }
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
