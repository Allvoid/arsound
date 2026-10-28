package app.revanced.extension.soundcloud.local;

import java.io.File;

/**
 * What a batch run found for one line of the list: the line it writes to the report, and the playlist entry the
 * track gets (null if there is none). The report lines are tab separated:
 * <pre>
 * OK   key  title  source  value       found: the SoundCloud urn or the path of the file
 * FAIL key  error                      the search or the download failed
 * MISS key                             nothing found
 * </pre>
 * A later run reads the {@code OK} lines to skip what is done.
 */
final class BatchOutcome {
    private static final String OK = "OK";
    private static final String FAIL = "FAIL";
    private static final String MISS = "MISS";
    private static final String KEPT_TITLE = "kept";

    private final String line;
    private final String status;
    private final String entry;

    private BatchOutcome(String status, String line, String entry) {
        this.status = status;
        this.line = line;
        this.entry = entry;
    }

    /** A SoundCloud track. */
    static BatchOutcome soundCloudTrack(String key, String foundTitle, String trackId) {
        String entry = TrackEntry.PREFIX + trackId;
        return new BatchOutcome(OK, OK + "\t" + key + "\t" + foundTitle + "\tsoundcloud\t" + entry, entry);
    }

    /** A file downloaded from another source. */
    static BatchOutcome downloaded(String key, String foundTitle, String sourceUrl, File file) {
        return new BatchOutcome(OK, OK + "\t" + key + "\t" + foundTitle + "\t" + sourceUrl + "\t" + file.getPath(), Entry.ofFile(file).stored());
    }

    /** A file imported earlier. */
    static BatchOutcome imported(String key, String foundTitle, File file) {
        return downloaded(key, foundTitle, "imported", file);
    }

    static BatchOutcome notFound(String key) {
        return new BatchOutcome(MISS, MISS + "\t" + key, null);
    }

    static BatchOutcome failed(String key, Throwable reason) {
        return new BatchOutcome(FAIL, FAIL + "\t" + key + "\t" + reason, null);
    }

    /** The entry of an earlier run keeps its place when this run could not reach the network. */
    static BatchOutcome kept(String key, String earlierEntry) {
        return new BatchOutcome(OK, OK + "\t" + key + "\t" + KEPT_TITLE + "\tearlier\t" + earlierEntry, earlierEntry);
    }

    String reportLine() {
        return line;
    }

    /** The entry the track has in the playlist, or null. */
    String entry() {
        return entry;
    }

    boolean isFailure() {
        return FAIL.equals(status);
    }

    /** The title in a report line of an earlier run that is a kept entry, not a title found by a search. */
    static boolean isKeptTitle(String reportedTitle) {
        return KEPT_TITLE.equals(reportedTitle);
    }

    /** True for an {@code OK} line of the report. */
    static boolean isDoneLine(String[] parts) {
        return parts[0].equals(OK) && parts.length >= 5;
    }
}
