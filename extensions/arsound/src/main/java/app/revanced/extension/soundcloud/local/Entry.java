package app.revanced.extension.soundcloud.local;

import java.io.File;
import java.util.List;

import app.revanced.extension.shared.Logger;

/**
 * One track of a playlist that exists only on this phone: either a SoundCloud track ({@link TrackEntry}) or an
 * imported file ({@link FileEntry}). What differs between the two lives in the two classes, so the code that
 * handles entries never asks which kind it has.
 * <p>
 * The stored text is kept in users' preferences and in batch reports, so its format does not change:
 * {@code soundcloud:tracks:123} and {@code file:/data/.../imported/name.mp3}.
 */
abstract class Entry {
    private static final String FILE_PREFIX = "file:";

    /** The entry for a stored text. */
    static Entry parse(String stored) {
        return stored.startsWith(FILE_PREFIX) ? new FileEntry(stored.substring(FILE_PREFIX.length())) : new TrackEntry(stored);
    }

    /** The entry of an imported file. */
    static Entry ofFile(File file) {
        return new FileEntry(file.getPath());
    }

    /** The text kept in the preferences and the batch report. */
    abstract String stored();

    /** The urn made from this entry, or null if the track cannot be shown (a missing file, no track id). */
    final Object toUrn(ClassLoader loader) {
        try {
            return makeUrn(loader);
        } catch (Exception ex) {
            Logger.printException(() -> "Invalid local addition " + stored(), ex);
            return null;
        }
    }

    abstract Object makeUrn(ClassLoader loader) throws Exception;

    /** The title cached for a SoundCloud track; a file has none, its tags are read instead. */
    abstract String cachedTitle(TrackTitles.Snapshot titles);

    /** The id of a SoundCloud track whose title can be loaded; null for a file. */
    abstract String trackIdToLoad();

    /** A name for the list of the playlist's local tracks. */
    abstract String label(List<LocalMusic.Track> imported, TrackTitles.Snapshot titles);

    /** Whether nothing more is needed for the entry in a batch run: a SoundCloud track is done, a file still needs its cover. */
    abstract boolean isComplete();
}
