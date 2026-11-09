package app.revanced.extension.soundcloud.transfer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a VK link points at: a playlist or album ("vk.com/music/playlist/-147845620_2949_ab12…", the older
 * "audio?act=audio_playlist…" and "?z=audio_playlist…/hash"), or the music of a person or community
 * ("vk.com/audios123"). vk.com and vk.ru, with or without "m.". Pure text; no network and no Android.
 */
final class VkLink {
    static final String PREFIX = "vk";
    static final String PLAYLIST = "PLAYLIST";
    /** The music list of an owner: "My music" of a person, the audio of a community. */
    static final String AUDIOS = "AUDIOS";

    private static final Pattern URL = Pattern.compile(
            "(?i)(?<![a-z0-9.-])(?:https?://)?(?:m\\.|www\\.)?vk\\.(?:com|ru)(/[^\\s\"'<>]*)");
    private static final Pattern MUSIC_PAGE = Pattern.compile(
            "^/music/(?:playlist|album)/(-?\\d+)_(\\d+)(?:_([0-9a-f]+))?(?:[/?#]|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLAYLIST_IN_PATH = Pattern.compile(
            "audio_playlist(-?\\d+)_(\\d+)(?:(?:/|%2F)([0-9a-f]+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCESS_HASH = Pattern.compile("[?&]access_hash=([0-9a-f]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUDIOS_PAGE = Pattern.compile("^/audios(-?\\d+)(?:[/?#]|$)");

    private VkLink() {
    }

    /** The link in the text, or null if it holds none Arsound can read. */
    static SourceLink find(String text) {
        if (text == null) return null;
        Matcher url = URL.matcher(text);
        while (url.find()) {
            SourceLink link = ofPath(url.group(1));
            if (link != null) return link;
        }
        return null;
    }

    private static SourceLink ofPath(String path) {
        Matcher matcher = MUSIC_PAGE.matcher(path);
        if (matcher.find()) return playlist(matcher.group(1), matcher.group(2), hash(matcher.group(3), path));
        // A playlist opened over another page ("audios123?z=audio_playlist…") is the playlist, not the page.
        matcher = PLAYLIST_IN_PATH.matcher(path);
        if (matcher.find()) return playlist(matcher.group(1), matcher.group(2), hash(matcher.group(3), path));
        matcher = AUDIOS_PAGE.matcher(path);
        if (matcher.find()) return new SourceLink(VkSource.INSTANCE, PREFIX, AUDIOS, matcher.group(1), null, null);
        return null;
    }

    private static String hash(String inPath, String path) {
        if (inPath != null) return inPath.toLowerCase(java.util.Locale.ROOT);
        Matcher matcher = ACCESS_HASH.matcher(path);
        return matcher.find() ? matcher.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    static SourceLink playlist(String owner, String id, String accessKey) {
        return new SourceLink(VkSource.INSTANCE, PREFIX, PLAYLIST, owner, id, accessKey);
    }

    static SourceLink audios(String owner) {
        return new SourceLink(VkSource.INSTANCE, PREFIX, AUDIOS, owner, null, null);
    }

    /** Null for a key that does not name a whole list: a damaged file must not reach the API with a part missing. */
    static SourceLink fromKey(String key) {
        SourceLink link = SourceLink.fromKey(VkSource.INSTANCE, PREFIX, key);
        if (link == null || link.owner == null) return null;
        if (PLAYLIST.equals(link.kind)) return link.id == null ? null : link;
        return AUDIOS.equals(link.kind) ? link : null;
    }
}
