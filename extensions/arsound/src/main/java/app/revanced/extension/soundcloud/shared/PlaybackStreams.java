package app.revanced.extension.soundcloud.shared;

/** Questions about the streams SoundCloud's player plays. */
public final class PlaybackStreams {
    private PlaybackStreams() {
    }

    /** True if the stream is a local file (downloaded by Arsound or imported), not a network one. */
    public static boolean isFileStream(Object stream) {
        return stream != null && stream.getClass().getName().endsWith("Stream$FileStream");
    }
}
