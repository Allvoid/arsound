package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadataRetriever;
import android.os.SystemClock;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * The tags the search and the order of "Imported" need: title, artist, album, year, track number and bit rate of each
 * imported file. Read from the file once, in the background, and kept in the preferences with the size and time of the
 * file, as the quality marks are ({@link QualityMarks}); a new or changed file is read at the next look. The search
 * and the sort read only these, never the files.
 */
final class FileTags {
    private static final String PREFERENCES = "arsound_file_tags";
    /** Between the fields of a kept record: a character no tag holds. */
    private static final char SEPARATOR = '\u001F';
    /** A file that keeps having no tags does not start a look at every list. */
    private static final long LOOK_EVERY_MS = 30_000;

    /** The tags of one file. */
    static final class Tags {
        final String title, artist, album;
        final int year, track, bitRate;

        Tags(String title, String artist, String album, int year, int track, int bitRate) {
            this.title = clean(title);
            this.artist = clean(artist);
            this.album = clean(album);
            this.year = year;
            this.track = track;
            this.bitRate = bitRate;
        }

        String format() {
            return title + SEPARATOR + artist + SEPARATOR + album + SEPARATOR + year + SEPARATOR + track + SEPARATOR
                    + bitRate;
        }

        /** @return Null for a record of another shape (an older version, damage). */
        static Tags parse(String text) {
            String[] parts = text.split(String.valueOf(SEPARATOR), -1);
            if (parts.length != 6) return null;
            try {
                return new Tags(parts[0], parts[1], parts[2], Integer.parseInt(parts[3]), Integer.parseInt(parts[4]),
                        Integer.parseInt(parts[5]));
            } catch (NumberFormatException ex) {
                return null;
            }
        }

        private static String clean(String text) {
            return text == null ? "" : text.replace(SEPARATOR, ' ').trim();
        }
    }

    /** Path of a file to its tags. */
    private static final Map<String, Tags> TAGS = new ConcurrentHashMap<>();
    private static final AtomicBoolean LOOKING = new AtomicBoolean();
    private static volatile long lookedAt;

    private FileTags() {
    }

    /** The tags of an imported file, or null while they are not read: then a look goes to the background. */
    static Tags of(File file) {
        Tags tags = TAGS.get(file.getPath());
        if (tags == null) lookSoon();
        return tags;
    }

    /** Reads the tags of all files now. Blocks: off the main thread. */
    static void readAll() {
        if (LOOKING.compareAndSet(false, true)) lookAndRelease();
    }

    private static void lookSoon() {
        if (lookedAt != 0 && SystemClock.uptimeMillis() - lookedAt < LOOK_EVERY_MS) return;
        if (!LOOKING.compareAndSet(false, true)) return;
        Utils.runOnBackgroundThread(FileTags::lookAndRelease);
    }

    private static void lookAndRelease() {
        try {
            look();
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the tags of the imported files", ex);
        } finally {
            lookedAt = SystemClock.uptimeMillis();
            LOOKING.set(false);
        }
    }

    private static void look() {
        Context context = Utils.getContext();
        SharedPreferences preferences = LocalPrefs.open(PREFERENCES);
        if (context == null || preferences == null) return;
        SharedPreferences.Editor editor = preferences.edit();
        Set<String> names = new HashSet<>();
        int read = 0;
        for (File file : LocalMusic.getFiles(context)) {
            names.add(file.getName());
            String stamp = file.length() + "|" + file.lastModified() + "|";
            String kept = preferences.getString(file.getName(), "");
            Tags tags = kept.startsWith(stamp) ? Tags.parse(kept.substring(stamp.length())) : null;
            if (tags == null) {
                tags = read(file);
                editor.putString(file.getName(), stamp + tags.format());
                read++;
            }
            TAGS.put(file.getPath(), tags);
        }
        for (String name : preferences.getAll().keySet()) {
            if (!names.contains(name)) editor.remove(name);
        }
        editor.apply();
        if (read > 0) {
            int count = read;
            Logger.printInfo(() -> "File tags: read " + count + " files");
            // A search or an order chosen before the tags were known is put right.
            ImportedView.onTagsRead();
        }
    }

    private static Tags read(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            String year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR);
            if (year == null) year = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE);
            return new Tags(
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    year(year),
                    leadingNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                    leadingNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)));
        } catch (Exception ex) {
            Logger.printInfo(() -> "File tags: could not read " + file + ": " + ex);
            return new Tags("", "", "", 0, 0, 0);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** The year of a tag: "2019", "2019-05-01", "20190501T000000.000Z"; 0 when there is none. */
    static int year(String text) {
        if (text == null) return 0;
        String trimmed = text.trim();
        if (trimmed.length() < 4) return 0;
        int year = leadingNumber(trimmed.substring(0, 4));
        return year >= 1000 && year <= 2999 ? year : 0;
    }

    /** The number a tag starts with: "3/12" is 3; 0 when it starts with none. */
    static int leadingNumber(String text) {
        if (text == null) return 0;
        String trimmed = text.trim();
        int end = 0;
        while (end < trimmed.length() && end < 9 && Character.isDigit(trimmed.charAt(end))) end++;
        if (end == 0) return 0;
        return Integer.parseInt(trimmed.substring(0, end));
    }

    /** Whether a file is lossless: by its quality mark when it is known, by its extension otherwise. */
    static boolean isLossless(File file) {
        String mark = QualityMarks.of(file);
        if (!mark.isEmpty()) return mark.startsWith("FLAC") || mark.startsWith("ALAC") || mark.startsWith("WAV")
                || mark.startsWith("AIF");
        String name = file.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".flac") || name.endsWith(".wav") || name.endsWith(".aiff") || name.endsWith(".aif");
    }
}
