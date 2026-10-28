package app.revanced.extension.soundcloud.shared;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SoundCloud's addresses of things: {@code soundcloud:tracks:123}, {@code soundcloud:playlists:456}. */
public final class Urns {
    public static final String TRACK_PREFIX = "soundcloud:tracks:";
    public static final String PLAYLIST_PREFIX = "soundcloud:playlists:";

    private static final Pattern TRACK = Pattern.compile("^soundcloud:tracks:(\\d+)$");
    private static final Pattern PLAYLIST = Pattern.compile("^soundcloud:playlists:(\\d+)$");

    private Urns() {
    }

    /** The id of a track urn; null if the text is anything else. */
    public static String trackId(String urn) {
        return idOf(TRACK, urn);
    }

    /** The id of a playlist urn; null if the text is anything else. */
    public static String playlistId(String urn) {
        return idOf(PLAYLIST, urn);
    }

    public static String track(String id) {
        return TRACK_PREFIX + id;
    }

    public static String playlist(String id) {
        return PLAYLIST_PREFIX + id;
    }

    private static String idOf(Pattern pattern, String urn) {
        if (urn == null) return null;
        Matcher matcher = pattern.matcher(urn);
        return matcher.find() ? matcher.group(1) : null;
    }
}
