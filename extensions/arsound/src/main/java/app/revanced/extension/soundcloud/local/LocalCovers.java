package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Covers of imported tracks and of playlists on this device, kept apart from the audio files.
 * <p>
 * A cover is stored once, named by the hash of its bytes: all tracks of an album point to the same
 * file, and the same cover in memory is shared too. SoundCloud shows a picture embedded in a local
 * file; for files without one, the stored cover is given instead. A cover the user picked replaces
 * the embedded picture as well.
 */
@SuppressWarnings("unused")
public final class LocalCovers {
    private static final String PREFERENCES_NAME = "arsound_local_covers";
    private static final String DIRECTORY = "arsound/covers";
    /** Most bytes of covers kept in memory: the rest is read from the files again. */
    private static final int MEMORY_LIMIT_BYTES = 6 * 1024 * 1024;
    /** Longest side of a stored cover in pixels: the cells and the player show it far smaller. */
    private static final int MAX_SIDE = 1280;
    /** A picture that cannot be decoded is stored as it is only up to this size. */
    private static final int MAX_UNREADABLE_BYTES = 1024 * 1024;
    /** Marks a file whose cover the user picked: it replaces the picture embedded in the file. */
    private static final String USER_PREFIX = "user:";
    /** The cover a file had before the user picked one, given back when the picked one is removed. */
    private static final String PREVIOUS_PREFIX = "previous:";
    /** Keys of playlist covers: the prefix and the playlist urn. */
    private static final String PLAYLIST_PREFIX = "playlist:";
    /** Set once the covers saved before cutting to a square are cut. */
    private static final String SQUARED_KEY = "covers_squared";

    /** Cover hash to bytes, the least recently used dropped first; bounded by size, not by count. */
    private static final LruCache<String, byte[]> memory = new LruCache<String, byte[]>(MEMORY_LIMIT_BYTES) {
        @Override
        protected int sizeOf(String hash, byte[] bytes) {
            return bytes.length;
        }
    };

    private LocalCovers() {
    }

    private static SharedPreferences preferences() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static File directory() {
        Context context = Utils.getContext();
        if (context == null) return null;
        File directory = new File(context.getFilesDir(), DIRECTORY);
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        return directory;
    }

    private static boolean isUserCover(SharedPreferences preferences, File audio) {
        return preferences.getBoolean(USER_PREFIX + audio.getName(), false);
    }

    /**
     * Called where SoundCloud reads the picture embedded in a local file.
     *
     * @return The cover picked by the user, else the embedded picture, else the stored cover.
     */
    public static byte[] coverOr(byte[] embedded, File audio) {
        if (audio == null) return embedded;
        squareStoredCoversOnce();
        try {
            SharedPreferences preferences = preferences();
            if (preferences == null || (embedded != null && !isUserCover(preferences, audio))) return embedded;
            String hash = preferences.getString(audio.getName(), null);
            byte[] cover = hash == null ? null : read(hash);
            return cover != null ? cover : embedded;
        } catch (Exception | OutOfMemoryError ex) {
            Logger.printException(() -> "Could not read the cover of " + audio, ex);
            return embedded;
        }
    }

    /**
     * Track lists load artwork by URL only; the embedded picture reaches just the player screen. A
     * local track with a stored cover gets the address of that one file, shared by the whole album.
     *
     * @param track The {@code Track} made from a local file.
     */
    public static void addCoverUrl(Object track) {
        squareStoredCoversOnce();
        try {
            Object urn = track.getClass().getMethod("getTrackUrn").invoke(track);
            File audio = (File) urn.getClass().getMethod("getFile").invoke(urn);
            SharedPreferences preferences = preferences();
            File directory = directory();
            String hash = preferences == null || directory == null ? null : preferences.getString(audio.getName(), null);
            if (hash == null) return;
            File cover = new File(directory, hash + ".jpg");
            if (!cover.isFile()) return;
            java.lang.reflect.Field field = track.getClass().getDeclaredField("imageUrlTemplate");
            field.setAccessible(true);
            if (field.get(track) == null || isUserCover(preferences, audio)) {
                field.set(track, android.net.Uri.fromFile(cover).toString());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not set the cover address", ex);
        }
    }

    private static synchronized byte[] read(String hash) throws Exception {
        byte[] bytes = memory.get(hash);
        if (bytes != null) return bytes;
        File directory = directory();
        if (directory == null) return null;
        File file = new File(directory, hash + ".jpg");
        if (!file.isFile()) return null;
        bytes = Files.readAllBytes(file.toPath());
        memory.put(hash, bytes);
        return bytes;
    }

    /** Downloads a picture. Blocks. */
    private static byte[] download(String imageUrl) throws Exception {
        app.revanced.extension.soundcloud.network.RegionGuard.throwIfBlocked(new URL(imageUrl).getHost());
        HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        try (InputStream input = connection.getInputStream()) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            return output.toByteArray();
        }
    }

    /** Downloads a cover for an imported file. Blocks: call it off the main thread. */
    public static void save(File audio, String imageUrl) {
        if (audio == null || imageUrl == null || imageUrl.isEmpty()) return;
        try {
            byte[] bytes = square(download(imageUrl));
            String hash = storeAndRecord(bytes, (preferences, stored) -> {
                // A cover picked by the user stays; the found one is given back if the user removes it.
                String key = isUserCover(preferences, audio) ? PREVIOUS_PREFIX + audio.getName() : audio.getName();
                preferences.edit().putString(key, stored).apply();
            });
            if (hash == null) return;
            Logger.printInfo(() -> "Cover saved for " + audio.getName() + ": " + hash + ", " + bytes.length + " bytes");
            LocalAdditions.clearLocalTrackCache();
        } catch (Exception | OutOfMemoryError ex) {
            Logger.printException(() -> "Could not save the cover of " + audio, ex);
        }
    }

    /**
     * Sets a picture picked by the user as the cover of an imported file, in lists and in the player.
     * Blocks: call it off the main thread.
     */
    public static boolean setUserCover(File audio, byte[] image) {
        try {
            String hash = storeAndRecord(square(image), (preferences, stored) -> {
                SharedPreferences.Editor editor = preferences.edit();
                String previous = preferences.getString(audio.getName(), null);
                if (previous != null && !isUserCover(preferences, audio)) editor.putString(PREVIOUS_PREFIX + audio.getName(), previous);
                editor.putString(audio.getName(), stored).putBoolean(USER_PREFIX + audio.getName(), true).commit();
            });
            if (hash == null) return false;
            removeUnused();
            LocalAdditions.clearLocalTrackCache();
            Logger.printInfo(() -> "User cover set for " + audio.getName() + ": " + hash);
            return true;
        } catch (Exception | OutOfMemoryError ex) {
            Logger.printException(() -> "Could not set the cover of " + audio, ex);
            return false;
        }
    }

    public static boolean hasUserCover(File audio) {
        SharedPreferences preferences = preferences();
        return preferences != null && isUserCover(preferences, audio);
    }

    /** Removes the cover picked by the user: the file shows its own picture or the saved cover again. */
    public static void removeUserCover(File audio) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        String previous = preferences.getString(PREVIOUS_PREFIX + audio.getName(), null);
        SharedPreferences.Editor editor = preferences.edit()
                .remove(USER_PREFIX + audio.getName())
                .remove(PREVIOUS_PREFIX + audio.getName());
        if (previous != null) {
            editor.putString(audio.getName(), previous);
        } else {
            editor.remove(audio.getName());
        }
        editor.commit();
        removeUnused();
        LocalAdditions.clearLocalTrackCache();
        Logger.printInfo(() -> "User cover removed for " + audio.getName());
    }

    /** Notes where a stored cover is used. */
    private interface Reference {
        void record(SharedPreferences preferences, String hash);
    }

    /**
     * Stores a cover and notes where it is used in one step under the lock of {@link #removeUnused()}:
     * a cover written but not noted yet would be taken for unused and deleted.
     *
     * @return The hash, or null without a context.
     */
    private static synchronized String storeAndRecord(byte[] bytes, Reference reference) throws Exception {
        String hash = store(bytes);
        SharedPreferences preferences = preferences();
        if (hash == null || preferences == null) return null;
        reference.record(preferences, hash);
        return hash;
    }

    /**
     * Writes a cover once, named by its hash. Call it under the lock of {@link #removeUnused()}.
     *
     * @return The hash, or null without a context.
     */
    private static String store(byte[] bytes) throws Exception {
        String hash = hash(bytes);
        File directory = directory();
        if (directory == null) return null;
        File file = new File(directory, hash + ".jpg");
        // The same cover of another track of the album is already there, unless it was cut short.
        if (file.isFile() && file.length() == bytes.length) return hash;
        // Written aside and moved, so a file under the final name is never half written. A part file
        // left by a killed process is removed with the unused covers.
        File part = new File(directory, hash + ".tmp");
        boolean moved = false;
        try {
            try (FileOutputStream output = new FileOutputStream(part)) {
                output.write(bytes);
            }
            moved = part.renameTo(file);
            if (!moved) throw new java.io.IOException("Could not move the cover to " + file);
        } finally {
            if (!moved) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
            }
        }
        return hash;
    }

    // region Playlist covers

    /** Playlist urn to the address of its cover file, read once from the preferences. */
    private static volatile Map<String, String> playlistCovers;

    private static Map<String, String> playlistCovers() {
        Map<String, String> covers = playlistCovers;
        if (covers != null) return covers;
        covers = new java.util.concurrent.ConcurrentHashMap<>();
        SharedPreferences preferences = preferences();
        File directory = directory();
        if (preferences == null || directory == null) return covers;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(PLAYLIST_PREFIX)) continue;
            File file = new File(directory, entry.getValue() + ".jpg");
            if (file.isFile()) {
                covers.put(entry.getKey().substring(PLAYLIST_PREFIX.length()), android.net.Uri.fromFile(file).toString());
            }
        }
        playlistCovers = covers;
        return covers;
    }

    private static java.lang.reflect.Field artworkField;
    private static java.lang.reflect.Field urnField;

    /**
     * Injection point: replaces {@code Playlist.getArtworkImageUrl()}, which has no spare register
     * for the original value. The header of the playlist screen and the cards of the library load
     * the playlist picture by this address.
     *
     * @return The cover set on this device, otherwise SoundCloud's artwork.
     */
    public static String playlistArtwork(Object playlist) {
        try {
            Map<String, String> covers = playlistCovers();
            String urn = playlistUrn(playlist);
            String cover = covers.isEmpty() ? null : covers.get(urn);
            if (cover != null) return cover;
            String remote = remoteArtwork(playlist, urn);
            String copy = offlineCopy(urn, remote);
            return copy != null ? copy : remote;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not give the playlist cover", ex);
            return null;
        }
    }

    private static String playlistUrn(Object playlist) throws Exception {
        if (artworkField == null) {
            java.lang.reflect.Field urn = playlist.getClass().getDeclaredField("urn");
            urn.setAccessible(true);
            urnField = urn;
            java.lang.reflect.Field artwork = playlist.getClass().getDeclaredField("artworkImageUrl");
            artwork.setAccessible(true);
            artworkField = artwork;
        }
        return String.valueOf(urnField.get(playlist));
    }

    /** SoundCloud's artwork of the playlist, otherwise the cover of its first track added on the phone. */
    private static String remoteArtwork(Object playlist, String urn) throws Exception {
        String artwork = (String) artworkField.get(playlist);
        return artwork != null && !artwork.isEmpty() ? artwork : firstTrackCover(urn);
    }

    /** Marks the cover of a playlist taken from its first track added on the phone: entry, then address. */
    private static final String FIRST_TRACK_PREFIX = "first:";
    private static final Set<String> firstTrackRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Playlists whose first track is being compared with the remembered one in the background. */
    private static final Set<String> firstTrackChecks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Playlists asked for again while their check runs: it may have read the list before a change. */
    private static final Set<String> firstTrackRechecks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** When the first track of each playlist was last compared, on the elapsed-time clock. */
    private static final Map<String, Long> firstTrackCheckedAt = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long FIRST_TRACK_CHECK_MS = 3_000;

    /**
     * SoundCloud gives a playlist the artwork of its first track, but only for tracks on the server.
     * A playlist made of tracks added on the phone gets the cover of its first such track instead.
     * The address is found once in the background and remembered.
     * <p>
     * This runs whenever a screen binds the playlist, often on the main thread, so it only reads the
     * remembered address. The list of tracks, which can mean listing the import folder, is read in
     * the background and the screen is told if the cover changes.
     *
     * @return The remembered address, or null until it is found.
     */
    private static String firstTrackCover(String playlistUrn) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return null;
        String address = rememberedAddress(preferences.getString(FIRST_TRACK_PREFIX + playlistUrn, null));
        checkFirstTrack(preferences, playlistUrn);
        return address;
    }

    /** @return The address in a remembered value (entry, then address), or null if none or its file is gone. */
    private static String rememberedAddress(String saved) {
        int separator = saved == null ? -1 : saved.indexOf('\n');
        String address = separator > 0 ? saved.substring(separator + 1) : null;
        // A cover file of an imported track goes away with its album.
        boolean present = address != null && (!address.startsWith("file:")
                || new File(android.net.Uri.parse(address).getPath()).isFile());
        return present ? address : null;
    }

    /** Compares the first track of a playlist with the remembered one in the background, one check at a time. */
    private static void checkFirstTrack(SharedPreferences preferences, String playlistUrn) {
        // Every bind of a cell asks, many times a second while a list scrolls: a fresh check is enough.
        // A change of the tracks drops the freshness, see onPlaylistChanged.
        long now = android.os.SystemClock.elapsedRealtime();
        Long checkedAt = firstTrackCheckedAt.get(playlistUrn);
        if (checkedAt != null && now - checkedAt < FIRST_TRACK_CHECK_MS) return;
        if (!firstTrackChecks.add(playlistUrn)) return;
        firstTrackCheckedAt.put(playlistUrn, now);
        Utils.runOnBackgroundThread(() -> {
            try {
                updateFirstTrackCover(preferences, playlistUrn);
            } catch (Exception | OutOfMemoryError ex) {
                Logger.printException(() -> "Could not check the first track cover of " + playlistUrn, ex);
            } finally {
                firstTrackChecks.remove(playlistUrn);
                // The tracks changed while this check read them: compared again with the new list.
                if (firstTrackRechecks.remove(playlistUrn)) {
                    firstTrackCheckedAt.remove(playlistUrn);
                    checkFirstTrack(preferences, playlistUrn);
                }
            }
        });
    }

    /** Called when the tracks of a playlist changed: its first track is compared again at the next bind. */
    static void onPlaylistChanged(String playlistUrn) {
        firstTrackCheckedAt.remove(playlistUrn);
        if (firstTrackChecks.contains(playlistUrn)) firstTrackRechecks.add(playlistUrn);
    }

    private static void updateFirstTrackCover(SharedPreferences preferences, String playlistUrn) {
        java.util.List<String> entries = LocalAdditions.getShownEntries(playlistUrn);
        String key = FIRST_TRACK_PREFIX + playlistUrn;
        String saved = preferences.getString(key, null);
        if (entries.isEmpty()) {
            // The playlist lost its tracks: the cover of the first one goes too, and is looked for again if tracks come back.
            if (saved != null) {
                preferences.edit().remove(key).apply();
                firstTrackRequests.removeIf(request -> request.startsWith(playlistUrn + '\n'));
                Utils.runOnMainThread(() -> LocalAdditions.notifyPlaylistChanged(playlistUrn));
            }
            return;
        }
        String first = entries.get(0);
        if (rememberedAddress(saved) != null && saved.substring(0, saved.indexOf('\n')).equals(first)) return;
        if (firstTrackRequests.add(playlistUrn + '\n' + first)) findFirstTrackCover(preferences, playlistUrn, first);
    }

    private static void findFirstTrackCover(SharedPreferences preferences, String playlistUrn, String entry) {
        try {
            String address = null;
            if (entry.startsWith("file:")) {
                String hash = preferences.getString(new File(entry.substring(5)).getName(), null);
                File directory = directory();
                if (hash != null && directory != null && new File(directory, hash + ".jpg").isFile()) {
                    address = android.net.Uri.fromFile(new File(directory, hash + ".jpg")).toString();
                }
            } else {
                String id = app.revanced.extension.soundcloud.download.DownloadTrackPatch.parseTrackId(entry);
                String[] response = id == null ? null
                        : app.revanced.extension.soundcloud.download.DownloadTrackPatch.apiGet("https://api-v2.soundcloud.com/tracks/" + id);
                if (response != null && response[1] != null) {
                    org.json.JSONObject track = new org.json.JSONObject(response[1]);
                    String url = track.optString("artwork_url", "");
                    if (url.isEmpty() || "null".equals(url)) {
                        org.json.JSONObject user = track.optJSONObject("user");
                        url = user == null ? "" : user.optString("avatar_url", "");
                    }
                    // SoundCloud loads artwork by a template with the size left open.
                    if (!url.isEmpty() && !"null".equals(url)) address = url.replace("-large.", "-{size}.");
                }
            }
            if (address == null) {
                Logger.printInfo(() -> "No cover for the first track of " + playlistUrn);
                return;
            }
            String found = address;
            preferences.edit().putString(FIRST_TRACK_PREFIX + playlistUrn, entry + '\n' + address).apply();
            Logger.printInfo(() -> "Playlist " + playlistUrn + " takes the cover of its first track: " + found);
            Utils.runOnMainThread(() -> LocalAdditions.notifyPlaylistChanged(playlistUrn));
        } catch (java.io.IOException ex) {
            // Without a network the cover is looked for again at the next start.
            Logger.printInfo(() -> "No first track cover of " + playlistUrn + " without a network: " + ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not find the first track cover of " + playlistUrn, ex);
        }
    }

    public static boolean hasPlaylistCover(String playlistUrn) {
        return playlistCovers().containsKey(playlistUrn);
    }

    /** @return The address of the playlist cover set on this device, or null. */
    public static String playlistCoverUrl(String playlistUrn) {
        return playlistCovers().get(playlistUrn);
    }

    /** Sets the cover of a playlist on this device, or removes it for a null image. Blocks. */
    public static boolean setPlaylistCover(String playlistUrn, byte[] image) {
        try {
            SharedPreferences preferences = preferences();
            File directory = directory();
            if (preferences == null || directory == null) return false;
            if (image == null) {
                preferences.edit().remove(PLAYLIST_PREFIX + playlistUrn).commit();
                playlistCovers().remove(playlistUrn);
            } else {
                String hash = storeAndRecord(square(image), (prefs, stored) ->
                        prefs.edit().putString(PLAYLIST_PREFIX + playlistUrn, stored).commit());
                if (hash == null) return false;
                playlistCovers().put(playlistUrn, android.net.Uri.fromFile(new File(directory, hash + ".jpg")).toString());
            }
            removeUnused();
            Logger.printInfo(() -> "Playlist cover " + (image == null ? "removed" : "set") + " for " + playlistUrn);
            return true;
        } catch (Exception | OutOfMemoryError ex) {
            Logger.printException(() -> "Could not set the playlist cover", ex);
            return false;
        }
    }

    // endregion

    // region Offline copies of playlist covers

    /**
     * Library playlists show SoundCloud's artwork by its link, and the image cache does not keep it:
     * without a network the library had empty squares. A copy of each cover is kept here and given
     * instead of the link. Keys: the prefix and the playlist urn, the value is the cover hash.
     */
    private static final String OFFLINE_PREFIX = "offline:";
    /** The link each copy was made from: a playlist with another cover gets a new copy. */
    private static final String OFFLINE_SOURCE_PREFIX = "offline-source:";
    /** The size put into the link template: the playlist header shows the cover large. */
    private static final String OFFLINE_SIZE = "t500x500";
    /** Failed downloads in a row after which the network counts as gone. */
    private static final int OFFLINE_FAILURES_TO_STOP = 3;
    /** A copy that failed is tried again when the playlist is shown, at most this often. */
    private static final long OFFLINE_RETRY_MS = 60_000;

    /** Playlist urn to the link of the copy and the address of its file, read once from the preferences. */
    private static volatile Map<String, String[]> offlineCopies;
    /**
     * Library playlists still without an up-to-date copy, with the time of the last try. Android cuts
     * the network of an app in the background, so the copies missed at the start are made when the
     * library is on screen.
     */
    private static final Map<String, Long> offlineAttempts = new java.util.concurrent.ConcurrentHashMap<>();

    private static Map<String, String[]> offlineCopies() {
        Map<String, String[]> copies = offlineCopies;
        if (copies != null) return copies;
        copies = new java.util.concurrent.ConcurrentHashMap<>();
        SharedPreferences preferences = preferences();
        File directory = directory();
        if (preferences == null || directory == null) return copies;
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (!entry.getKey().startsWith(OFFLINE_PREFIX)) continue;
            String urn = entry.getKey().substring(OFFLINE_PREFIX.length());
            File file = new File(directory, entry.getValue() + ".jpg");
            String source = preferences.getString(OFFLINE_SOURCE_PREFIX + urn, null);
            if (file.isFile() && source != null) {
                copies.put(urn, new String[]{source, android.net.Uri.fromFile(file).toString()});
            }
        }
        offlineCopies = copies;
        return copies;
    }

    /**
     * @param remote The link SoundCloud would load.
     * @return The address of the stored copy, or null to load the link.
     */
    private static String offlineCopy(String urn, String remote) {
        // The cover of an imported file is on the phone already, and without artwork there is nothing to keep.
        if (remote == null || !remote.startsWith("http")) return null;
        String[] copy = offlineCopies().get(urn);
        // A library playlist whose copy is missing, or one that got another cover: the old copy stays
        // until the new one is downloaded.
        boolean stale = copy == null ? offlineAttempts.containsKey(urn) : !remote.equals(copy[0]);
        if (stale) tryOfflineCopy(urn, remote, copy != null);
        return copy == null ? null : copy[1];
    }

    private static void tryOfflineCopy(String urn, String remote, boolean redraw) {
        long now = System.currentTimeMillis();
        Long last = offlineAttempts.get(urn);
        if (last != null && now - last < OFFLINE_RETRY_MS) return;
        offlineAttempts.put(urn, now);
        Utils.runOnBackgroundThread(() -> {
            if (!saveOfflineCopy(urn, remote)) return;
            offlineAttempts.remove(urn);
            removeUnused();
            // The old copy is on screen; without one, the link was shown and nothing changes.
            if (redraw) Utils.runOnMainThread(() -> LocalAdditions.notifyPlaylistChanged(urn));
        });
    }

    /** Downloads the cover of a playlist and keeps it as its copy. Blocks. @return True if saved. */
    private static boolean saveOfflineCopy(String urn, String remote) {
        try {
            String hash = storeAndRecord(download(remote.replace("{size}", OFFLINE_SIZE)), (preferences, stored) ->
                    preferences.edit()
                            .putString(OFFLINE_PREFIX + urn, stored)
                            .putString(OFFLINE_SOURCE_PREFIX + urn, remote)
                            .commit());
            File directory = directory();
            if (hash == null || directory == null) return false;
            offlineCopies().put(urn, new String[]{remote, android.net.Uri.fromFile(new File(directory, hash + ".jpg")).toString()});
            return true;
        } catch (java.io.IOException ex) {
            // No network or SoundCloud is switched off: tried again later.
            Logger.printInfo(() -> "No copy of the cover of " + urn + " yet: " + ex);
            return false;
        } catch (Exception | OutOfMemoryError ex) {
            Logger.printException(() -> "Could not keep the cover of " + urn, ex);
            return false;
        }
    }

    /**
     * Keeps a copy of the cover of every library playlist: downloads the missing and changed ones and
     * removes the copies of playlists no longer in the library. Blocks: call it off the main thread.
     *
     * @param playlists The {@code Playlist}s of the library.
     */
    public static void keepOfflineCopies(java.util.List<Object> playlists) {
        if (playlists.isEmpty()) return;
        Map<String, String> wanted = new LinkedHashMap<>();
        Set<String> library = new HashSet<>();
        for (Object playlist : playlists) {
            try {
                String urn = playlistUrn(playlist);
                library.add(urn);
                String remote = remoteArtwork(playlist, urn);
                String[] copy = offlineCopies().get(urn);
                if (remote != null && remote.startsWith("http") && (copy == null || !remote.equals(copy[0]))) {
                    wanted.put(urn, remote);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Could not read a library playlist cover", ex);
                return;
            }
        }

        int saved = 0, failures = 0;
        for (Map.Entry<String, String> entry : wanted.entrySet()) {
            String urn = entry.getKey();
            if (failures >= OFFLINE_FAILURES_TO_STOP) {
                // Not tried: tried as soon as the playlist is shown.
                offlineAttempts.put(urn, 0L);
            } else if (saveOfflineCopy(urn, entry.getValue())) {
                offlineAttempts.remove(urn);
                saved++;
                failures = 0;
            } else {
                offlineAttempts.put(urn, System.currentTimeMillis());
                failures++;
            }
        }

        SharedPreferences preferences = preferences();
        int removed = 0;
        if (preferences != null) {
            SharedPreferences.Editor editor = preferences.edit();
            for (String urn : offlineCopies().keySet()) {
                if (library.contains(urn)) continue;
                editor.remove(OFFLINE_PREFIX + urn).remove(OFFLINE_SOURCE_PREFIX + urn);
                offlineCopies().remove(urn);
                removed++;
            }
            editor.commit();
        }
        offlineAttempts.keySet().retainAll(library);
        if (saved > 0 || removed > 0) removeUnused();
        int total = library.size(), done = saved, gone = removed, missing = wanted.size() - saved;
        Logger.printInfo(() -> "Playlist covers kept for offline: " + total + " in library, downloaded " + done
                + ", not downloaded " + missing + ", removed " + gone);
    }

    // endregion

    // region Squaring old covers

    private static volatile boolean squaringChecked;

    /**
     * Covers saved before they were cut to a square keep the black bars of a video frame. They are
     * cut once, in the background; the tracks then load their cover address again.
     */
    private static void squareStoredCoversOnce() {
        if (squaringChecked) return;
        squaringChecked = true;
        SharedPreferences preferences = preferences();
        if (preferences == null || preferences.getBoolean(SQUARED_KEY, false)) return;
        Utils.runOnBackgroundThread(LocalCovers::squareStoredCovers);
    }

    private static synchronized void squareStoredCovers() {
        SharedPreferences preferences = preferences();
        File directory = directory();
        if (preferences == null || directory == null) return;
        // Old hash to the hash of the square copy.
        Map<String, String> replaced = new HashMap<>();
        File[] covers = directory.listFiles();
        if (covers != null) {
            for (File cover : covers) {
                // A part file of a killed process is not a cover.
                if (!cover.getName().endsWith(".jpg")) continue;
                try {
                    byte[] image = Files.readAllBytes(cover.toPath());
                    byte[] bytes = square(image);
                    if (bytes == image) continue;
                    String hash = store(bytes);
                    if (hash != null) replaced.put(cover.getName().replace(".jpg", ""), hash);
                } catch (Exception | OutOfMemoryError ex) {
                    Logger.printException(() -> "Could not cut the cover " + cover, ex);
                }
            }
        }
        SharedPreferences.Editor editor = preferences.edit();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String hash = replaced.get(String.valueOf(entry.getValue()));
            if (hash != null) editor.putString(entry.getKey(), hash);
        }
        editor.putBoolean(SQUARED_KEY, true).commit();
        for (String hash : replaced.keySet()) memory.remove(hash);
        playlistCovers = null;
        offlineCopies = null;
        removeUnused();
        Logger.printInfo(() -> "Stored covers cut to a square: " + replaced.size());
        if (!replaced.isEmpty()) LocalAdditions.clearLocalTrackCache();
    }

    // endregion

    /**
     * Covers are shown in square cells. A video thumbnail is a 16:9 frame with the art in the middle
     * and black bars around it, so it is cut to the centre square. A big picture, like a photo from
     * the gallery, is made smaller while it is decoded: it never takes its full size in memory.
     *
     * @return The same array if the picture is square and small already, or cannot be read and is small.
     * @throws java.io.IOException If the picture cannot be read and is big.
     */
    private static byte[] square(byte[] image) throws java.io.IOException {
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
        int width = bounds.outWidth, height = bounds.outHeight;
        if (width > 0 && width == height && width <= MAX_SIDE) return image;
        byte[] cut = width > 0 && height > 0 ? cutSquare(image, width, height) : null;
        if (cut != null) return cut;
        if (image.length > MAX_UNREADABLE_BYTES) throw new java.io.IOException("The picture cannot be read: " + image.length + " bytes");
        return image;
    }

    /** @return The centre square of the picture, at most {@link #MAX_SIDE} wide, or null if it cannot be decoded. */
    private static byte[] cutSquare(byte[] image, int width, int height) {
        android.graphics.Bitmap bitmap = null;
        try {
            // Decoded in steps of two that keep the short side (the square) at or above the wanted size; the rest
            // is scaled below.
            int sample = 1;
            while (Math.min(width, height) / (sample * 2) >= MAX_SIDE) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            bitmap = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length, options);
            if (bitmap == null) return null;
            int side = Math.min(bitmap.getWidth(), bitmap.getHeight());
            android.graphics.Bitmap centre = android.graphics.Bitmap.createBitmap(bitmap,
                    (bitmap.getWidth() - side) / 2, (bitmap.getHeight() - side) / 2, side, side);
            if (centre != bitmap) {
                bitmap.recycle();
                bitmap = centre;
            }
            if (side > MAX_SIDE) {
                android.graphics.Bitmap scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, MAX_SIDE, MAX_SIDE, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            // A transparent picture would get black corners as a JPEG.
            boolean done = bitmap.hasAlpha()
                    ? bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                    : bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output);
            return done ? output.toByteArray() : null;
        } catch (OutOfMemoryError error) {
            Logger.printException(() -> "Not enough memory to cut a picture of " + width + "x" + height, error);
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    public static boolean hasCover(File audio) {
        SharedPreferences preferences = preferences();
        return preferences != null && preferences.contains(audio.getName());
    }

    /** Forgets the cover of a deleted file and removes covers nothing uses any more. */
    public static void onDeleted(File audio) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        // Applied, not committed: this can run on the main thread, and removeUnused reads the preferences in memory.
        preferences.edit().remove(audio.getName()).remove(USER_PREFIX + audio.getName())
                .remove(PREVIOUS_PREFIX + audio.getName()).apply();
        removeUnused();
    }

    /** Removes the cover files no track or playlist uses. */
    private static synchronized void removeUnused() {
        SharedPreferences preferences = preferences();
        File directory = directory();
        if (preferences == null || directory == null) return;
        Set<String> used = new HashSet<>();
        for (Object hash : preferences.getAll().values()) used.add(String.valueOf(hash));
        File[] covers = directory.listFiles();
        if (covers == null) return;
        for (File cover : covers) {
            String hash = cover.getName().replace(".jpg", "");
            if (used.contains(hash)) continue;
            memory.remove(hash);
            //noinspection ResultOfMethodCallIgnored
            cover.delete();
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }
}
