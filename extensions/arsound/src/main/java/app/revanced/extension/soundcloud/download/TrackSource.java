package app.revanced.extension.soundcloud.download;

import org.json.JSONObject;

/**
 * Where the audio of a track comes from, and why it cannot be downloaded when it cannot.
 * <p>
 * SoundCloud offers a track either as one progressive file or as an HLS playlist of encrypted
 * segments. The progressive file is downloaded by Android's download manager, or by {@link ProgressiveDownloader}
 * into a folder the download manager cannot write to; HLS is assembled by {@link HlsDownloader}.
 * <p>
 * DRM-protected tracks (FairPlay/Widevine) are not saved; they get the status {@link Status#DRM_PROTECTED}.
 */
public final class TrackSource {
    public enum Status {
        /** A progressive stream is available: one file, saved by the download manager or by the app itself. */
        READY,
        /** Only an HLS playlist is available: the segments are downloaded and packed into one file. */
        REQUIRES_HLS_PROCESSING,
        /** No stream at all in the standard response: other client profiles are asked. */
        REQUIRES_PROFILE_SEARCH,
        /** The track is a preview (SNIP), so there is no full audio to save. */
        AUDIO_ONLY_PREVIEW,
        /** The track needs a subscription (SUB, GO_PLUS), so it is left alone. */
        SUBSCRIPTION_REQUIRED,
        /** The only streams left are locked by FairPlay or Widevine, whose key comes from a licence server. Such a track is not saved. */
        DRM_PROTECTED,
        /** Blocked in the region SoundCloud decided the account or the connection is in (policy BLOCK). */
        REGION_BLOCKED,
        /** Private, removed, or not served for another reason. */
        UNAVAILABLE,
    }

    public final Status status;
    /** The progressive file URL, or the HLS playlist URL. Null unless the status is READY or REQUIRES_HLS_PROCESSING. */
    public final String url;
    /** The mime type of the HLS segments, used to choose the file extension. */
    public final String mimeType;
    /** The title of the track, or null if the source was made without the track's data. */
    final String title;
    /** The artist of the track, or null as the title. */
    final String artist;
    /** The file name without extension, "Artist - Title"; null if the track's data is unknown, which leaves the id. */
    final String fileBase;
    private final long resolvedAt;

    private TrackSource(Status status, String url, String mimeType) {
        this(status, url, mimeType, null, null, null, System.currentTimeMillis());
    }

    private TrackSource(Status status, String url, String mimeType, String title, String artist, String fileBase,
                        long resolvedAt) {
        this.status = status;
        this.url = url;
        this.mimeType = mimeType;
        this.title = title;
        this.artist = artist;
        this.fileBase = fileBase;
        this.resolvedAt = resolvedAt;
    }

    /**
     * The same source with the title and artist of the track, which name the saved file and tag it in the media
     * store. A source that cannot be downloaded, or a missing track, is returned as it is.
     *
     * @param track The track as the API returned it.
     */
    TrackSource withTrack(JSONObject track) {
        if (track == null || !isDownloadable()) return this;
        String title = text(track, "title");
        // The artist the label or the author wrote down is better than the account that uploaded the track.
        JSONObject publisher = track.optJSONObject("publisher_metadata");
        String artist = publisher == null ? "" : text(publisher, "artist");
        boolean uploader = artist.isEmpty();
        if (uploader) {
            JSONObject user = track.optJSONObject("user");
            artist = user == null ? "" : text(user, "username");
        }
        return new TrackSource(status, url, mimeType, title, artist, FileNames.base(title, artist, uploader), resolvedAt);
    }

    /** A JSON null reads as the text "null" with optString, so it is asked for first. */
    private static String text(JSONObject object, String name) {
        return object.isNull(name) ? "" : object.optString(name).trim();
    }

    static TrackSource progressive(String url) {
        return new TrackSource(Status.READY, url, "audio/mpeg");
    }

    static TrackSource hls(String url, String mimeType) {
        return new TrackSource(Status.REQUIRES_HLS_PROCESSING, url, mimeType);
    }

    static TrackSource unavailable(Status status) {
        return new TrackSource(status, null, null);
    }

    static TrackSource drmHls(String url, String mimeType) {
        return new TrackSource(Status.REQUIRES_HLS_PROCESSING, url, mimeType);
    }

    public boolean isDownloadable() {
        return url != null;
    }

    /** Stream links expire, so a link from a dialog left open for long is resolved again. */
    public boolean isFresh() {
        return isFresh(5 * 60_000);
    }

    public boolean isFresh(long maxAgeMs) {
        return System.currentTimeMillis() - resolvedAt < maxAgeMs;
    }

    /** Why the track cannot be downloaded, for the playlist check dialog. */
    public String reason() {
        switch (status) {
            case AUDIO_ONLY_PREVIEW:
                return DownloadTrackPatch.text("отрывок", "preview");
            case SUBSCRIPTION_REQUIRED:
                return DownloadTrackPatch.text("по подписке", "subscription");
            case DRM_PROTECTED:
                return DownloadTrackPatch.text("защищён DRM", "DRM protected");
            case REGION_BLOCKED:
                return DownloadTrackPatch.text("закрыт в регионе", "blocked in your region");
            case REQUIRES_PROFILE_SEARCH:
                return DownloadTrackPatch.text("нет потока", "no stream");
            default:
                return DownloadTrackPatch.text("недоступен", "not available");
        }
    }
}
