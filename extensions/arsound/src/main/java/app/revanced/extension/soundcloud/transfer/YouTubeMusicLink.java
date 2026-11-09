package app.revanced.extension.soundcloud.transfer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a YouTube Music or YouTube link points at: a playlist or an album ("…/playlist?list=PL…", "OLAK5uy_…", a
 * "watch?v=…&list=…" link of a playing playlist too). "list=LM" is the liked music of the signed-in account. Pure
 * text; no network and no Android.
 */
final class YouTubeMusicLink {
    static final String PREFIX = "ytm";
    static final String PLAYLIST = "PLAYLIST";
    /** The liked music of the signed-in account. */
    static final String LIKES = "LIKES";

    private static final Pattern URL = Pattern.compile(
            "(?i)(?<![a-z0-9.-])(?:https?://)?(?:music\\.|www\\.|m\\.)?(?:youtube\\.com|youtu\\.be)(/[^\\s\"'<>]*)");
    private static final Pattern LIST = Pattern.compile("[?&]list=([A-Za-z0-9_-]{2,})");
    /** "Mixes" made for one listener on the fly ("RD…" of a track): they have no fixed list of tracks to read. */
    private static final Pattern MIX = Pattern.compile("^RD(?!CLAK)");

    private YouTubeMusicLink() {
    }

    /** The link in the text, or null if it holds none Arsound can read. */
    static SourceLink find(String text) {
        if (text == null) return null;
        Matcher url = URL.matcher(text);
        while (url.find()) {
            Matcher list = LIST.matcher(url.group(1));
            if (!list.find()) continue;
            String id = list.group(1);
            if ("LM".equals(id)) return likes();
            if (MIX.matcher(id).find()) continue;
            return playlist(id);
        }
        return null;
    }

    static SourceLink playlist(String id) {
        return new SourceLink(YouTubeMusicSource.INSTANCE, PREFIX, PLAYLIST, null, id, null);
    }

    static SourceLink likes() {
        return new SourceLink(YouTubeMusicSource.INSTANCE, PREFIX, LIKES, null, null, null);
    }

    /** Null for a key that does not name a list: a damaged file must not reach YouTube with a part missing. */
    static SourceLink fromKey(String key) {
        SourceLink link = SourceLink.fromKey(YouTubeMusicSource.INSTANCE, PREFIX, key);
        if (link == null) return null;
        if (PLAYLIST.equals(link.kind)) return link.id == null ? null : link;
        return LIKES.equals(link.kind) ? link : null;
    }
}
