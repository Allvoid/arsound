package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;

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
    private static final int MEMORY_LIMIT = 24;
    /** Marks a file whose cover the user picked: it replaces the picture embedded in the file. */
    private static final String USER_PREFIX = "user:";
    /** The cover a file had before the user picked one, given back when the picked one is removed. */
    private static final String PREVIOUS_PREFIX = "previous:";
    /** Keys of playlist covers: the prefix and the playlist urn. */
    private static final String PLAYLIST_PREFIX = "playlist:";
    /** Set once the covers saved before cutting to a square are cut. */
    private static final String SQUARED_KEY = "covers_squared";

    /** Cover hash to bytes, the most recently used last. */
    private static final Map<String, byte[]> memory = new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > MEMORY_LIMIT;
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
        } catch (Exception ex) {
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

    /** Downloads a cover for an imported file. Blocks: call it off the main thread. */
    public static void save(File audio, String imageUrl) {
        if (audio == null || imageUrl == null || imageUrl.isEmpty()) return;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(imageUrl).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            byte[] bytes;
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                bytes = square(output.toByteArray());
            }
            String hash = store(bytes);
            SharedPreferences preferences = preferences();
            if (hash == null || preferences == null) return;
            // A cover picked by the user stays; the found one is given back if the user removes it.
            String key = isUserCover(preferences, audio) ? PREVIOUS_PREFIX + audio.getName() : audio.getName();
            preferences.edit().putString(key, hash).apply();
            Logger.printInfo(() -> "Cover saved for " + audio.getName() + ": " + hash + ", " + bytes.length + " bytes");
            LocalAdditions.clearLocalTrackCache();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save the cover of " + audio, ex);
        }
    }

    /**
     * Sets a picture picked by the user as the cover of an imported file, in lists and in the player.
     * Blocks: call it off the main thread.
     */
    public static boolean setUserCover(File audio, byte[] image) {
        try {
            String hash = store(square(image));
            SharedPreferences preferences = preferences();
            if (hash == null || preferences == null) return false;
            SharedPreferences.Editor editor = preferences.edit();
            String previous = preferences.getString(audio.getName(), null);
            if (previous != null && !isUserCover(preferences, audio)) editor.putString(PREVIOUS_PREFIX + audio.getName(), previous);
            editor.putString(audio.getName(), hash).putBoolean(USER_PREFIX + audio.getName(), true).commit();
            removeUnused();
            LocalAdditions.clearLocalTrackCache();
            Logger.printInfo(() -> "User cover set for " + audio.getName() + ": " + hash);
            return true;
        } catch (Exception ex) {
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

    /** Writes a cover once, named by its hash. @return The hash, or null without a context. */
    private static String store(byte[] bytes) throws Exception {
        String hash = hash(bytes);
        File directory = directory();
        if (directory == null) return null;
        File file = new File(directory, hash + ".jpg");
        // The same cover of another track of the album is already there.
        if (!file.isFile()) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes);
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
            if (artworkField == null) {
                java.lang.reflect.Field urn = playlist.getClass().getDeclaredField("urn");
                urn.setAccessible(true);
                urnField = urn;
                java.lang.reflect.Field artwork = playlist.getClass().getDeclaredField("artworkImageUrl");
                artwork.setAccessible(true);
                artworkField = artwork;
            }
            Map<String, String> covers = playlistCovers();
            String urn = String.valueOf(urnField.get(playlist));
            String cover = covers.isEmpty() ? null : covers.get(urn);
            if (cover != null) return cover;
            String artwork = (String) artworkField.get(playlist);
            return artwork != null && !artwork.isEmpty() ? artwork : firstTrackCover(urn);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not give the playlist cover", ex);
            return null;
        }
    }

    /** Marks the cover of a playlist taken from its first track added on the phone: entry, then address. */
    private static final String FIRST_TRACK_PREFIX = "first:";
    private static final Set<String> firstTrackRequests = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * SoundCloud gives a playlist the artwork of its first track, but only for tracks on the server.
     * A playlist made of tracks added on the phone gets the cover of its first such track instead.
     * The address is found once in the background and remembered.
     *
     * @return The remembered address, or null until it is found.
     */
    private static String firstTrackCover(String playlistUrn) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return null;
        java.util.List<String> entries = LocalAdditions.getEntries(playlistUrn);
        if (entries.isEmpty()) return null;
        String first = entries.get(0);
        String saved = preferences.getString(FIRST_TRACK_PREFIX + playlistUrn, null);
        int separator = saved == null ? -1 : saved.indexOf('\n');
        String address = separator > 0 ? saved.substring(separator + 1) : null;
        // A cover file of an imported track goes away with its album.
        boolean present = address != null && (!address.startsWith("file:")
                || new File(android.net.Uri.parse(address).getPath()).isFile());
        if (present && saved.substring(0, separator).equals(first)) return address;
        if (firstTrackRequests.add(playlistUrn + '\n' + first)) {
            Utils.runOnBackgroundThread(() -> findFirstTrackCover(preferences, playlistUrn, first));
        }
        return present ? address : null;
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
                String hash = store(square(image));
                if (hash == null) return false;
                preferences.edit().putString(PLAYLIST_PREFIX + playlistUrn, hash).commit();
                playlistCovers().put(playlistUrn, android.net.Uri.fromFile(new File(directory, hash + ".jpg")).toString());
            }
            removeUnused();
            Logger.printInfo(() -> "Playlist cover " + (image == null ? "removed" : "set") + " for " + playlistUrn);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not set the playlist cover", ex);
            return false;
        }
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
                try {
                    byte[] image = Files.readAllBytes(cover.toPath());
                    byte[] bytes = square(image);
                    if (bytes == image) continue;
                    String hash = store(bytes);
                    if (hash != null) replaced.put(cover.getName().replace(".jpg", ""), hash);
                } catch (Exception ex) {
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
        memory.keySet().removeAll(replaced.keySet());
        playlistCovers = null;
        removeUnused();
        Logger.printInfo(() -> "Stored covers cut to a square: " + replaced.size());
        if (!replaced.isEmpty()) LocalAdditions.clearLocalTrackCache();
    }

    // endregion

    /**
     * Covers are shown in square cells. A video thumbnail is a 16:9 frame with the art in the middle
     * and black bars around it, so it is cut to the centre square.
     *
     * @return The same array if the picture is square already or cannot be read.
     */
    private static byte[] square(byte[] image) {
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(image, 0, image.length);
        if (bitmap == null || bitmap.getWidth() == bitmap.getHeight()) return image;
        int side = Math.min(bitmap.getWidth(), bitmap.getHeight());
        android.graphics.Bitmap cut = android.graphics.Bitmap.createBitmap(bitmap,
                (bitmap.getWidth() - side) / 2, (bitmap.getHeight() - side) / 2, side, side);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        cut.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output);
        return output.toByteArray();
    }

    public static boolean hasCover(File audio) {
        SharedPreferences preferences = preferences();
        return preferences != null && preferences.contains(audio.getName());
    }

    /** Forgets the cover of a deleted file and removes covers nothing uses any more. */
    public static void onDeleted(File audio) {
        SharedPreferences preferences = preferences();
        if (preferences == null) return;
        preferences.edit().remove(audio.getName()).remove(USER_PREFIX + audio.getName())
                .remove(PREVIOUS_PREFIX + audio.getName()).commit();
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
