package app.revanced.extension.soundcloud.transfer;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a Yandex Music link points at: a playlist (by its owner and number, or by its id), the likes of a person or an
 * album. The link may sit inside the text the app's "Share" gives ("Слушаю … https://music.yandex.ru/…"). Pure text;
 * no network and no Android.
 */
public final class YandexLink {
    public enum Kind {PLAYLIST, LIKES, ALBUM}

    private static final Pattern URL = Pattern.compile(
            "(?i)(?:https?://)?music\\.yandex\\.[a-z]{2,3}(?:\\.[a-z]{2})?(/[^\\s\"'<>]*)");
    private static final Pattern USER_PLAYLIST = Pattern.compile("^/users/([^/?#]+)/playlists/(\\d+)");
    private static final Pattern USER_LIKES = Pattern.compile("^/users/([^/?#]+)/(?:tracks|likes)(?:[/?#]|$)");
    /** Newer links: "/playlists/lk.<uuid>" for a person's playlist, other prefixes for Yandex's own. */
    private static final Pattern PLAYLIST_ID = Pattern.compile("^/playlists?/((?:[a-z]{2}\\.)?[0-9a-f-]{16,})(?:[/?#]|$)");
    private static final Pattern ALBUM = Pattern.compile("^/album/(\\d+)(?:[/?#]|$)");

    public final Kind kind;
    /** The login or number of the owner, for a playlist by number and for likes; null otherwise. */
    public final String owner;
    /** The number of the playlist, its id, or the album's number; null for likes. */
    public final String id;

    YandexLink(Kind kind, String owner, String id) {
        this.kind = kind;
        this.owner = owner;
        this.id = id;
    }

    /** The link in the text, or null if it holds none Arsound can read. */
    public static YandexLink find(String text) {
        if (text == null) return null;
        Matcher url = URL.matcher(text);
        while (url.find()) {
            YandexLink link = ofPath(url.group(1));
            if (link != null) return link;
        }
        return null;
    }

    private static YandexLink ofPath(String path) {
        Matcher matcher = USER_PLAYLIST.matcher(path);
        if (matcher.find()) return new YandexLink(Kind.PLAYLIST, decode(matcher.group(1)), matcher.group(2));
        matcher = USER_LIKES.matcher(path);
        if (matcher.find()) return new YandexLink(Kind.LIKES, decode(matcher.group(1)), null);
        matcher = PLAYLIST_ID.matcher(path.toLowerCase(java.util.Locale.ROOT));
        if (matcher.find()) return new YandexLink(Kind.PLAYLIST, null, matcher.group(1));
        matcher = ALBUM.matcher(path);
        if (matcher.find()) return new YandexLink(Kind.ALBUM, null, matcher.group(1));
        return null;
    }

    private static String decode(String part) {
        try {
            return URLDecoder.decode(part, "UTF-8");
        } catch (UnsupportedEncodingException | IllegalArgumentException ex) {
            return part;
        }
    }

    /** The link of a {@link #key}, or null for a key that is not one. */
    public static YandexLink fromKey(String key) {
        if (key == null) return null;
        String[] parts = key.split(":", 3);
        if (parts.length != 3) return null;
        try {
            Kind kind = Kind.valueOf(parts[0]);
            return new YandexLink(kind, parts[1].isEmpty() ? null : parts[1], parts[2].isEmpty() ? null : parts[2]);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** One key per collection, to tell a collection transferred before. */
    public String key() {
        return kind + ":" + (owner == null ? "" : owner) + ":" + (id == null ? "" : id);
    }

    @Override
    public String toString() {
        return key();
    }
}
