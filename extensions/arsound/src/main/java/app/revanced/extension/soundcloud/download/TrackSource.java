package app.revanced.extension.soundcloud.download;

/**
 * Where the audio of a track comes from, and why it cannot be downloaded when it cannot.
 * <p>
 * SoundCloud offers a track either as one progressive file or as an HLS playlist of encrypted
 * segments. The progressive file is downloaded by Android's download manager; HLS is assembled by
 * {@link HlsDownloader}.
 */
public final class TrackSource {
    public enum Status {
        /** A progressive stream is available: the download manager saves it. */
        READY,
        /** Only an HLS playlist is available: the segments are downloaded and packed into one file. */
        REQUIRES_HLS_PROCESSING,
        /** No stream at all in the standard response: other client profiles are asked. */
        REQUIRES_PROFILE_SEARCH,
        /** The track is a preview (SNIP), so there is no full audio to save. */
        AUDIO_ONLY_PREVIEW,
        /** The track needs a subscription (SUB, GO_PLUS), so it is left alone. */
        SUBSCRIPTION_REQUIRED,
        /** The only streams left are locked by FairPlay or Widevine, whose key comes from a licence server. */
        DRM_PROTECTED,
        /** Blocked in this region, private, or removed. */
        UNAVAILABLE,
    }

    public final Status status;
    /** The progressive file URL, or the HLS playlist URL. Null unless the status is READY or REQUIRES_HLS_PROCESSING. */
    public final String url;
    /** The mime type of the HLS segments, used to choose the file extension. */
    public final String mimeType;
    private final long resolvedAt = System.currentTimeMillis();

    private TrackSource(Status status, String url, String mimeType) {
        this.status = status;
        this.url = url;
        this.mimeType = mimeType;
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

    public boolean isDownloadable() {
        return url != null;
    }

    /** Stream links expire, so a link from a dialog left open for long is resolved again. */
    public boolean isFresh() {
        return System.currentTimeMillis() - resolvedAt < 5 * 60_000;
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
            case REQUIRES_PROFILE_SEARCH:
                return DownloadTrackPatch.text("нет потока", "no stream");
            default:
                return DownloadTrackPatch.text("недоступен", "not available");
        }
    }
}
