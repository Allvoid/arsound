package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.SystemClock;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The mark of the quality of an imported track ({@link QualityLabel}) in the line under its title, in every list
 * SoundCloud builds that line for with {@code MetaLabelsKt.e}.
 * <p>
 * The line is built on the main thread for every row, so a file is never read there: a file with no known mark sends a
 * look over all files to the background, and the marks are kept in the preferences with the size and time of the file.
 */
@SuppressWarnings("unused")
public final class QualityMarks {
    private static final String PREFERENCES = "arsound_quality";
    /** Android's key of the bit depth in the format of a track of a file. */
    private static final String BITS_PER_SAMPLE = "bits-per-sample";
    /** A file that keeps having no mark does not start a look at every row. */
    private static final long LOOK_EVERY_MS = 30_000;

    /** Path of a file to its mark, empty for a file with none. */
    private static final Map<String, String> MARKS = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOOKING = new AtomicBoolean();
    /** The track of the line being built: {@code MetaLabelsKt.e} has many returns, and this runs at each. */
    private static final ThreadLocal<Object> BUILDING = new ThreadLocal<>();

    private static volatile long lookedAt;
    private static volatile Field typeField;

    private QualityMarks() {
    }

    /** Injection point. The start of {@code MetaLabelsKt.e}, with the track the line is for. */
    public static void onLineStart(Object trackItem) {
        BUILDING.set(trackItem);
    }

    /**
     * Injection point. Every return of {@code MetaLabelsKt.e}.
     *
     * @param line The {@code MetaLabel.ViewState} made just now.
     * @return The same object, with the mark as its text for an imported file.
     */
    public static Object withMark(Object line) {
        Object trackItem = BUILDING.get();
        BUILDING.remove();
        if (line == null || trackItem == null) return line;
        try {
            File file = LikeObjects.fileOf(Urns.of(trackItem));
            if (file == null) return line;
            String mark = MARKS.get(file.getPath());
            if (mark == null) lookSoon();
            else if (!mark.isEmpty()) typeField(line).set(line, mark);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not add the quality mark", ex);
        }
        return line;
    }

    /** The mark of an imported file, empty when none is known yet. */
    public static String of(File file) {
        String mark = MARKS.get(file.getPath());
        return mark == null ? "" : mark;
    }

    /** Reads the marks of all files now. Blocks: off the main thread. */
    public static void readAll() {
        if (LOOKING.compareAndSet(false, true)) lookAndRelease();
    }

    /** The first field of the state, {@code type}: a text the line shows before the rest. */
    private static Field typeField(Object line) throws NoSuchFieldException {
        Field field = typeField;
        if (field == null) {
            field = line.getClass().getDeclaredField("a");
            field.setAccessible(true);
            typeField = field;
        }
        return field;
    }

    private static void lookSoon() {
        if (lookedAt != 0 && SystemClock.uptimeMillis() - lookedAt < LOOK_EVERY_MS) return;
        if (!LOOKING.compareAndSet(false, true)) return;
        Utils.runOnBackgroundThread(QualityMarks::lookAndRelease);
    }

    private static void lookAndRelease() {
        try {
            look();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the quality of the imported files", ex);
        } finally {
            lookedAt = SystemClock.uptimeMillis();
            LOOKING.set(false);
        }
    }

    /** Gives every imported file its mark: from the preferences, or from the file when it is new or changed. */
    private static void look() {
        Context context = Utils.getContext();
        SharedPreferences preferences = LocalPrefs.open(PREFERENCES);
        if (context == null || preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        Set<String> names = new HashSet<>();
        boolean changed = false;
        for (File file : LocalMusic.getFiles(context)) {
            names.add(file.getName());
            String stamp = file.length() + "|" + file.lastModified() + "|";
            String kept = preferences.getString(file.getName(), "");
            String mark;
            if (kept.startsWith(stamp)) {
                mark = kept.substring(stamp.length());
            } else {
                mark = read(file);
                editor.putString(file.getName(), stamp + mark);
                changed = true;
            }
            MARKS.put(file.getPath(), mark);
        }
        for (String name : preferences.getAll().keySet()) {
            if (!names.contains(name)) editor.remove(name);
        }
        editor.apply();
        // The rows on screen were built without the marks.
        if (changed) ImportedLikes.refresh();
    }

    private static String read(File file) {
        String codec = null;
        int sampleRate = 0, bits = 0, bitRate = 0;
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getPath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                codec = mime;
                if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                if (format.containsKey(MediaFormat.KEY_BIT_RATE)) bitRate = format.getInteger(MediaFormat.KEY_BIT_RATE);
                if (format.containsKey(BITS_PER_SAMPLE)) bits = format.getInteger(BITS_PER_SAMPLE);
                break;
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read the format of " + file + ": " + ex);
        } finally {
            extractor.release();
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            if (bitRate == 0) bitRate = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (bits == 0) bits = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE));
                if (sampleRate == 0) sampleRate = number(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE));
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not read the tags of " + file + ": " + ex);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
        return QualityLabel.of(file.getName(), codec, sampleRate, bits, bitRate);
    }

    private static int number(String text) {
        try {
            return text == null ? 0 : Integer.parseInt(text.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
