package app.revanced.extension.soundcloud.download;

import android.content.Context;

import java.io.IOException;
import java.io.InputStream;

/**
 * What the recognition of lyrics may listen to, asked of the download code that knows how a track is offered. It
 * lives in this package only because it needs methods that are package-private here; it adds no way to save a track.
 * <p>
 * A track is listened to only when it is a whole track that SoundCloud serves to anyone: a progressive file, or an
 * HLS playlist that is open or locked by plain AES-128. Previews, tracks that need a subscription, unavailable tracks
 * and every protected stream (FairPlay, Widevine, SAMPLE-AES) are refused here, before a byte of audio is asked for.
 * Used by {@code lyrics.transcribe.TrackAudio}.
 */
public final class TranscriptionAudio {
    /** The track must not be listened to, or cannot be found out about. */
    public static final class Refused extends IOException {
        Refused(String reason) {
            super(reason);
        }
    }

    /** Where to get the audio of a streamed track. */
    public static final class Stream {
        public final String url;
        /** True when {@link #url} is an HLS playlist, false for one file. */
        public final boolean hls;

        Stream(String url, boolean hls) {
            this.url = url;
            this.hls = hls;
        }
    }

    private TranscriptionAudio() {
    }

    /**
     * The audio of a track the user downloaded with Arsound, or null if the track is not downloaded. The user's own
     * file on the phone: nothing is asked of the network.
     */
    public static InputStream openDownloaded(Context context, String trackId) throws IOException {
        DownloadIndex.Entry entry = DownloadTrackPatch.downloadedEntry(trackId);
        return entry == null ? null : entry.open(context);
    }

    /**
     * @throws Refused If the track is a preview, needs a subscription, is unavailable, or its stream is protected.
     * @throws Exception For a failure of the network or of SoundCloud's answer; the caller may try again later.
     */
    public static Stream resolve(String trackId) throws Exception {
        TrackSource source = DownloadTrackPatch.resolveSource(trackId);
        switch (source.status) {
            case READY:
                if (source.url == null) throw new Refused("no stream");
                return new Stream(source.url, false);
            case REQUIRES_HLS_PROCESSING:
                if (source.url == null) throw new Refused("no stream");
                // A protected playlist can come with this status too: the playlist itself is looked at.
                if (HlsDownloader.isDrmProtected(source.url)) throw new Refused("protected stream");
                return new Stream(source.url, true);
            default:
                throw new Refused(source.status.name());
        }
    }
}
