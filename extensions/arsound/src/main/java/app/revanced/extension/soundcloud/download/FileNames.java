package app.revanced.extension.soundcloud.download;

import android.webkit.MimeTypeMap;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** How the file of a downloaded track is named: "Artist - Title", cleaned for a file system. */
final class FileNames {
    /**
     * The longest name without extension, in bytes of UTF-8. A file name may have 255; the extension, a track id
     * for a name that is taken, and the "(1)" of the media store come after it.
     */
    private static final int MAX_BYTES = 160;
    /** What a file name cannot hold on the storage of a phone or on the computer the music is copied to. */
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern EDGE_DOTS = Pattern.compile("^\\.+|\\.+$");

    private FileNames() {
    }

    /**
     * @param artistIsUploader True if the artist is only the name of the account that uploaded the track, which
     *                         is often a label or a channel and says nothing about who made the music.
     * @return The name without extension, or null if the track has no title to name it by.
     */
    static String base(String title, String artist, boolean artistIsUploader) {
        if (title.isEmpty()) return null;
        // "Artist - Title" is a common way to write the title itself: the artist is not put in front of it twice.
        boolean titleHasArtist = !artist.isEmpty()
                && (title.regionMatches(true, 0, artist + " - ", 0, artist.length() + 3)
                || (artistIsUploader && title.contains(" - ")));
        String name = artist.isEmpty() || titleHasArtist ? title : artist + " - " + title;
        String cleaned = clean(name);
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String clean(String name) {
        String cleaned = ILLEGAL.matcher(name).replaceAll("_");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ").trim();
        // A leading dot hides the file, and a trailing one is dropped by some file systems.
        cleaned = EDGE_DOTS.matcher(cleaned).replaceAll("").trim();
        while (cleaned.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            cleaned = cleaned.substring(0, cleaned.offsetByCodePoints(cleaned.length(), -1));
        }
        return cleaned.trim();
    }

    /**
     * The type of a file for the storage that keeps it. It has to match the extension, otherwise the storage appends
     * an extension of its own ("Name.wav.mp3").
     */
    static String mimeTypeOf(String fileName) {
        if (fileName.endsWith(".m4a")) return "audio/mp4";
        if (fileName.endsWith(".aac")) return "audio/aac";
        if (fileName.endsWith(".opus")) return "audio/ogg";
        if (fileName.endsWith(".mp3")) return "audio/mpeg";
        int dot = fileName.lastIndexOf('.');
        String known = dot < 0 ? null : MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
        // A kind of file the system does not know is kept under the name it has.
        return known != null ? known : "application/octet-stream";
    }
}
