package app.revanced.extension.soundcloud.local;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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

    /** Imported tracks, newest first. Reads metadata from the files, so call it off the main thread. */
    public static List<Track> getTracks(Context context) {
        File[] files = directory(context).listFiles(File::isFile);
        List<Track> tracks = new ArrayList<>();
        if (files == null) return tracks;

        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) tracks.add(readTrack(file));
        return tracks;
    }

    private static Track readTrack(File file) {
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
        ContentResolver resolver = context.getContentResolver();
        int imported = 0;
        for (Uri uri : uris) {
            String name = displayName(resolver, uri);
            File target = uniqueFile(directory(context), name);
            try (InputStream input = resolver.openInputStream(uri);
                 OutputStream output = new FileOutputStream(target)) {
                if (input == null) continue;
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                imported++;
            } catch (Exception ex) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                Logger.printException(() -> "Could not import " + uri, ex);
            }
        }
        return imported;
    }

    public static boolean delete(Track track) {
        return track.file.delete();
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

    private static File uniqueFile(File directory, String name) {
        File file = new File(directory, name);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int i = 2; file.exists(); i++) file = new File(directory, base + " (" + i + ")" + extension);
        return file;
    }

    /**
     * Plays the tracks in the SoundCloud player, starting with {@code startIndex}.
     *
     * @return False if the player is not ready yet.
     */
    public static boolean play(List<File> files, int startIndex, boolean shuffle) {
        Object initiator = playbackInitiator;
        if (initiator == null || files.isEmpty()) return false;

        try {
            ClassLoader loader = initiator.getClass().getClassLoader();
            Class<?> localUrnClass = Class.forName("com.soundcloud.android.foundation.domain.LocalTrackUrn", false, loader);
            Class<?> urnClass = Class.forName("com.soundcloud.android.foundation.domain.Urn", false, loader);
            Class<?> itemClass = Class.forName("com.soundcloud.android.foundation.actions.models.PlayAllItem", false, loader);
            Class<?> contextClass = Class.forName("com.soundcloud.android.foundation.playqueue.PlaybackContext", false, loader);
            Class<?> linkClass = Class.forName("com.soundcloud.android.foundation.playqueue.PlaybackContext$Link", false, loader);
            Class<?> playAllClass = Class.forName("com.soundcloud.android.foundation.actions.models.PlayParams$PlayAll", false, loader);
            Class<?> singleClass = Class.forName("io.reactivex.rxjava3.core.Single", false, loader);

            Object companion = localUrnClass.getField("Companion").get(null);
            Method fromFile = companion.getClass().getMethod("fromFile", File.class);
            Constructor<?> itemConstructor = itemClass.getConstructor(urnClass, boolean.class);

            List<File> ordered = new ArrayList<>(files.size());
            if (shuffle) {
                ordered.addAll(files);
                java.util.Collections.shuffle(ordered);
            } else {
                // Start at the tapped track and keep the rest in order after it.
                ordered.addAll(files.subList(startIndex, files.size()));
                ordered.addAll(files.subList(0, startIndex));
            }

            List<Object> items = new ArrayList<>();
            for (File file : ordered) items.add(itemConstructor.newInstance(fromFile.invoke(companion, file), false));

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
