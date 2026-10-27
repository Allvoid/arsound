package app.revanced.extension.soundcloud.lyrics;

/** The track a player page shows, as far as the lyrics care: who and what it is, and how long. */
final class TrackInfo {
    final String urn;
    final String title;
    final String uploader;
    final long durationMs;
    private TrackQuery query;

    TrackInfo(String urn, String title, String uploader, long durationMs) {
        this.urn = urn;
        this.title = title == null ? "" : title;
        this.uploader = uploader == null ? "" : uploader;
        this.durationMs = durationMs;
    }

    /** What to ask LRCLIB; made once, the cleaning of a title is not free. */
    synchronized TrackQuery query() {
        if (query == null) query = TrackQuery.of(title, uploader, durationMs);
        return query;
    }

    boolean sameTrack(String otherUrn, String otherTitle, String otherUploader, long otherDurationMs) {
        return durationMs == otherDurationMs && urn.equals(otherUrn)
                && title.equals(otherTitle == null ? "" : otherTitle)
                && uploader.equals(otherUploader == null ? "" : otherUploader);
    }

    boolean sameTrack(TrackInfo other) {
        return other != null && sameTrack(other.urn, other.title, other.uploader, other.durationMs);
    }
}
