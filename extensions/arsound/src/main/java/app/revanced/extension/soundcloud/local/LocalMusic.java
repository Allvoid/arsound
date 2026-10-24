package app.revanced.extension.soundcloud.local;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.shared.Rx;

/**
 * Imported audio files and their playback through the regular SoundCloud player.
 * <p>
 * SoundCloud still contains a local file player: a {@code LocalTrackUrn} made from a file is
 * resolved to a track with metadata read from the file, and played with a file stream.
 * Imported files are copied into the app storage, because the player accepts file paths only.
 */
@SuppressWarnings("unused")
public final class LocalMusic {
    private static final String IMPORT_DIRECTORY = "arsound/imported";
    /** Unfinished copies and downloads, in the cache directory: they never show up as tracks. */
    private static final String PART_DIRECTORY = "arsound-part";
    /** A copy this old was left by a killed process (another process may be copying right now). */
    private static final long STALE_PART_MS = 6 * 60 * 60 * 1000L;
    private static final AtomicBoolean partsSwept = new AtomicBoolean();
    /** Choosing a free name and moving a finished copy onto it must not interleave between imports. */
    private static final Object IMPORT_LOCK = new Object();

    private static volatile Object playbackInitiator;

    private LocalMusic() {
    }

    /** Called from the constructor of {@code PlaybackInitiator}. */
    public static void setPlaybackInitiator(Object instance) {
        playbackInitiator = instance;
    }

    public static final class Track {
        public final File file;
        public final String title;
        public final String artist;
        public final long durationMs;

        Track(File file, String title, String artist, long durationMs) {
            this.file = file;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
        }
    }

    private static File directory(Context context) {
        File directory = new File(context.getFilesDir(), IMPORT_DIRECTORY);
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        return directory;
    }

    /** Imported files, newest first, without reading their metadata. */
    public static List<File> getFiles(Context context) {
        File[] files = directory(context).listFiles(File::isFile);
        List<File> result = new ArrayList<>();
        if (files == null) return result;
        Arrays.sort(files, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        result.addAll(Arrays.asList(files));
        return result;
    }

    /** Imported tracks, newest first. Reads metadata from the files, so call it off the main thread. */
    public static List<Track> getTracks(Context context) {
        File[] files = directory(context).listFiles(File::isFile);
        List<Track> tracks = new ArrayList<>();
        if (files == null) return tracks;

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) tracks.add(readTrack(file));
        return tracks;
    }

    public static Track readTrack(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String title = dot > 0 ? name.substring(0, dot) : name;
        String artist = "";
        long duration = 0;

        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            String metadataTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            String metadataArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            String metadataDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (metadataTitle != null && !metadataTitle.trim().isEmpty()) title = metadataTitle.trim();
            if (metadataArtist != null) artist = metadataArtist.trim();
            if (metadataDuration != null) duration = Long.parseLong(metadataDuration);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read metadata of " + file + ": " + ex);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return new Track(file, title, artist, duration);
    }

    /**
     * Copies picked documents into the import directory.
     *
     * @return The number of imported files.
     */
    public static int importFiles(Context context, List<Uri> uris) {
        return importFileList(context, uris).size();
    }

    /**
     * Copies picked documents into the import directory.
     *
     * @return The imported files.
     */
    public static List<File> importFileList(Context context, List<Uri> uris) {
        ContentResolver resolver = context.getContentResolver();
        List<File> imported = new java.util.ArrayList<>();
        for (Uri uri : uris) {
            String name = displayName(resolver, uri);
            // Copied aside and moved onto its name when complete: a process killed mid-copy must not leave
            // a truncated file in the import directory.
            File part = null;
            boolean moved = false;
            try {
                part = newPartFile(context);
                try (InputStream input = resolver.openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(part)) {
                    if (input == null) {
                        Logger.printInfo(() -> "Could not open " + uri);
                        continue;
                    }
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    output.getFD().sync();
                }
                imported.add(moveIntoImports(context, part, name));
                moved = true;
            } catch (Exception ex) {
                Logger.printException(() -> "Could not import " + uri, ex);
            } finally {
                //noinspection ResultOfMethodCallIgnored
                if (!moved && part != null) part.delete();
            }
        }
        return imported;
    }

    /**
     * A temporary file for a copy or a download that goes into the import directory: write it fully, then
     * move it with {@link File#renameTo} onto a name from {@link #newImportFile}, and delete it if that fails.
     * Leftovers of killed processes are removed here, once per process.
     */
    public static File newPartFile(Context context) throws IOException {
        File directory = new File(context.getCacheDir(), PART_DIRECTORY);
        //noinspection ResultOfMethodCallIgnored
        directory.mkdirs();
        if (partsSwept.compareAndSet(false, true)) {
            File[] parts = directory.listFiles((dir, fileName) -> fileName.endsWith(".part"));
            long limit = System.currentTimeMillis() - STALE_PART_MS;
            if (parts != null) {
                for (File stale : parts) {
                    if (stale.lastModified() < limit && !stale.delete()) {
                        Logger.printInfo(() -> "Could not delete the unfinished copy " + stale);
                    }
                }
            }
        }
        return File.createTempFile("import", ".part", directory);
    }

    /**
     * Moves a finished part file into the import directory under a free name. Every import goes through here: the
     * name is chosen and taken in one step, as renameTo would silently replace a file another import just made.
     */
    public static File moveIntoImports(Context context, File part, String name) throws IOException {
        synchronized (IMPORT_LOCK) {
            File target = newImportFile(context, name);
            if (!part.renameTo(target)) throw new IOException("Could not move " + part + " to " + target);
            return target;
        }
    }

    /** A new file in the import directory, for a track downloaded from another source. */
    public static File newImportFile(Context context, String name) {
        return uniqueFile(directory(context), name.replaceAll("[\\\\/:*?\"<>|]", "_"));
    }

    /** Makes a file written into the import directory show up in the imported playlist. */
    public static void onFileAdded() {
        LocalAdditions.clearLocalTrackCache();
    }

    public static boolean delete(Track track) {
        LocalAdditions.clearLocalTrackCache();
        boolean deleted = track.file.delete();
        if (deleted) {
            LocalCovers.onDeleted(track.file);
            // The next file with this name gets the same path: it must not inherit the playlists it was
            // added to, or the removal from the Imported playlist.
            String entry = LocalAdditions.fileEntry(track.file);
            LocalAdditions.forgetEntry(entry);
            SavedPlaylist.unexclude(entry);
        }
        return deleted;
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null && !name.isEmpty()) return name.replaceAll("[\\\\/:*?\"<>|]", "_");
            }
        } catch (Exception ignored) {
        }
        return "track-" + System.currentTimeMillis() + ".mp3";
    }

    private static String cutToBytes(String text, int limit) {
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int point = text.codePointAt(end);
            int size = new String(Character.toChars(point)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + size > limit) break;
            bytes += size;
            end += Character.charCount(point);
        }
        return text.substring(0, end);
    }

    private static File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        // A file name is at most 255 bytes, and a long title in Cyrillic or hieroglyphs needs two or three per letter.
        base = cutToBytes(base, 200 - extension.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        file = new File(directory, base + extension);
        for (int i = 2; file.exists(); i++) file = new File(directory, base + " (" + i + ")" + extension);
        return file;
    }

    /**
     * Plays imported files in the SoundCloud player, starting with {@code startIndex}.
     *
     * @return False if the player is not ready yet.
     */
    public static boolean play(List<File> files, int startIndex, boolean shuffle) {
        List<String> entries = new ArrayList<>();
        for (File file : files) entries.add(LocalAdditions.fileEntry(file));
        return playEntries(entries, startIndex, shuffle);
    }

    /**
     * Plays SoundCloud tracks and imported files together.
     *
     * @param entries {@code soundcloud:tracks:ID} or {@code file:path} entries.
     * @return False if the player is not ready yet or nothing is playable.
     */
    public static boolean playEntries(List<String> entries, int startIndex, boolean shuffle) {
        Object initiator = playbackInitiator;
        if (initiator == null || entries.isEmpty()) return false;

        try {
            ClassLoader loader = initiator.getClass().getClassLoader();
            Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
            Class<?> itemClass = Class.forName("com.soundcloud.android.foundation.actions.models.PlayAllItem", false, loader);
            Class<?> contextClass = Class.forName("com.soundcloud.android.foundation.playqueue.PlaybackContext", false, loader);
            Class<?> linkClass = Class.forName("com.soundcloud.android.foundation.playqueue.PlaybackContext$Link", false, loader);
            Class<?> playAllClass = Class.forName("com.soundcloud.android.foundation.actions.models.PlayParams$PlayAll", false, loader);
            Class<?> singleClass = Class.forName("io.reactivex.rxjava3.core.Single", false, loader);
            Constructor<?> itemConstructor = itemClass.getConstructor(urnClass, boolean.class);

            List<String> ordered = new ArrayList<>(entries.size());
            if (shuffle) {
                ordered.addAll(entries);
                java.util.Collections.shuffle(ordered);
            } else {
                // Start at the tapped track and keep the rest in order after it.
                ordered.addAll(entries.subList(startIndex, entries.size()));
                ordered.addAll(entries.subList(0, startIndex));
            }

            List<Object> items = new ArrayList<>();
            for (Object urn : LocalAdditions.toUrns(loader, ordered)) items.add(itemConstructor.newInstance(urn, false));
            if (items.isEmpty()) return false;

            Object playables = findJust(singleClass).invoke(null, items);
            Object playbackContext = linkClass.getConstructor(String.class).newInstance("arsound:local");
            Object params = playAllClass.getConstructor(singleClass, contextClass, String.class)
                    .newInstance(playables, playbackContext, "arsound_local");

            Method playAll = initiator.getClass().getMethod("b", playAllClass);
            Object result = playAll.invoke(initiator, params);
            Rx.subscribeIgnoringErrors(result, error -> Logger.printException(() -> "Local playback failed", error));
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not play local tracks", ex);
            return false;
        }
    }

    /** {@code Single.just(Object)} is renamed by R8; it is the static factory returning SingleJust. */
    private static Method findJust(Class<?> singleClass) {
        for (Method method : singleClass.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())
                    && method.getReturnType().getSimpleName().equals("SingleJust")
                    && method.getParameterTypes().length == 1
                    && method.getParameterTypes()[0] == Object.class) {
                return method;
            }
        }
        throw new IllegalStateException("Single.just not found");
    }
}
