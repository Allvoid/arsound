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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Covers of imported tracks, kept apart from the audio files.
 * <p>
 * A cover is stored once, named by the hash of its bytes: all tracks of an album point to the same
 * file, and the same cover in memory is shared too. SoundCloud shows a picture embedded in a local
 * file; for files without one, the stored cover is given instead.
 */
@SuppressWarnings("unused")
public final class LocalCovers {
    private static final String PREFERENCES_NAME = "arsound_local_covers";
    private static final String DIRECTORY = "arsound/covers";
    private static final int MEMORY_LIMIT = 24;

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

    /**
     * Called where SoundCloud reads the picture embedded in a local file.
     *
     * @return The embedded picture, or the stored cover if the file has none.
     */
    public static byte[] coverOr(byte[] embedded, File audio) {
        if (embedded != null || audio == null) return embedded;
        try {
            SharedPreferences preferences = preferences();
            String hash = preferences == null ? null : preferences.getString(audio.getName(), null);
            return hash == null ? null : read(hash);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the cover of " + audio, ex);
            return null;
        }
    }

    /**
     * Track lists load artwork by URL only; the embedded picture reaches just the player screen. A
     * local track with a stored cover gets the address of that one file, shared by the whole album.
     *
     * @param track The {@code Track} made from a local file.
     */
    public static void addCoverUrl(Object track) {
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
            if (field.get(track) == null) field.set(track, android.net.Uri.fromFile(cover).toString());
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
            String hash = hash(bytes);
            File directory = directory();
            SharedPreferences preferences = preferences();
            if (directory == null || preferences == null) return;
            File file = new File(directory, hash + ".jpg");
            // The same cover of another track of the album is already there.
            if (!file.isFile()) {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(bytes);
                }
            }
            preferences.edit().putString(audio.getName(), hash).apply();
            Logger.printInfo(() -> "Cover saved for " + audio.getName() + ": " + hash + ", " + bytes.length + " bytes");
            LocalAdditions.clearLocalTrackCache();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save the cover of " + audio, ex);
        }
    }

    /**
     * Covers are shown in square cells. A video thumbnail is a 16:9 frame with the art in the middle
     * and black bars around it, so it is cut to the centre square.
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

    /** Forgets the cover of a deleted file and removes covers no file uses any more. */
    public static void onDeleted(File audio) {
        SharedPreferences preferences = preferences();
        File directory = directory();
        if (preferences == null || directory == null) return;
        preferences.edit().remove(audio.getName()).commit();
        Set<String> used = new HashSet<>();
        for (Object hash : preferences.getAll().values()) used.add(String.valueOf(hash));
        File[] covers = directory.listFiles();
        if (covers == null) return;
        for (File cover : covers) {
            //noinspection ResultOfMethodCallIgnored
            if (!used.contains(cover.getName().replace(".jpg", ""))) cover.delete();
        }
    }

    private static String hash(byte[] bytes) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(bytes)) hex.append(String.format(Locale.ROOT, "%02x", b));
        return hex.toString();
    }
}
