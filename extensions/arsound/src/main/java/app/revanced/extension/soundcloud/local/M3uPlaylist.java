package app.revanced.extension.soundcloud.local;

import java.util.List;

/**
 * The text of an M3U8 playlist (UTF-8, extended M3U): one {@code #EXTINF} line with the length and the name, then the
 * place of the track. An imported file is named by its file name, so the list works next to a copy of the music
 * folder; a SoundCloud track by its address {@code https://api.soundcloud.com/tracks/ID}, which keeps its number, so
 * Arsound on the desktop and other SoundCloud clients can find it again. No Android here, for the tests.
 */
final class M3uPlaylist {
    /** One track of the list. */
    static final class Track {
        /** Seconds; -1 when not known. */
        final long seconds;
        /** "Artist - Title", or what is known. */
        final String name;
        /** A file name or an address. */
        final String place;

        Track(long seconds, String name, String place) {
            this.seconds = seconds;
            this.name = name;
            this.place = place;
        }
    }

    static final String SOUNDCLOUD_TRACK_URL = "https://api.soundcloud.com/tracks/";

    private M3uPlaylist() {
    }

    /** "Artist - Title", or the title alone when the artist is not known. */
    static String name(String artist, String title) {
        String a = oneLine(artist), t = oneLine(title);
        if (a.isEmpty()) return t;
        if (t.isEmpty()) return a;
        return a + " - " + t;
    }

    /** The whole text, with {@code title} as its {@code #PLAYLIST} name. */
    static String text(String title, List<Track> tracks) {
        StringBuilder out = new StringBuilder("#EXTM3U\n");
        String name = oneLine(title);
        if (!name.isEmpty()) out.append("#PLAYLIST:").append(name).append('\n');
        for (Track track : tracks) {
            String place = oneLine(track.place);
            if (place.isEmpty()) continue;
            out.append("#EXTINF:").append(track.seconds >= 0 ? track.seconds : -1).append(',')
                    .append(oneLine(track.name)).append('\n')
                    .append(place).append('\n');
        }
        return out.toString();
    }

    /** A file name a playlist can be saved under: no characters file systems refuse, and the extension. */
    static String fileName(String title) {
        String base = oneLine(title).replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isEmpty()) base = "playlist";
        if (base.length() > 80) base = base.substring(0, 80).trim();
        return base + ".m3u8";
    }

    /** A line break inside a tag would start a new line of the list. */
    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
