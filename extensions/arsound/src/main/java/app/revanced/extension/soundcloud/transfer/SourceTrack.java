package app.revanced.extension.soundcloud.transfer;

import java.util.List;

/**
 * A track as a source lists it: what is needed to find it on SoundCloud and to name it in the report. The id is the
 * key of its match in {@link TransferStore}: the bare number for Yandex Music, "vk:" and "yt:" for the others.
 */
final class SourceTrack {
    final String id;
    /** The main artist first; may be empty for a podcast episode or a removed track. */
    final List<String> artists;
    final String title;
    /** "Live", "Remix" and so on; null when the service names none. */
    final String version;
    /** 0 if unknown. */
    final long durationMs;

    SourceTrack(String id, List<String> artists, String title, String version, long durationMs) {
        this.id = id;
        this.artists = artists;
        this.title = title;
        this.version = version == null || version.trim().isEmpty() ? null : version.trim();
        this.durationMs = durationMs;
    }

    /** The title to match: with the version in brackets, so that "Song (Live)" does not take the studio take. */
    String wantedTitle() {
        return version == null ? title : title + " (" + version + ")";
    }

    String mainArtist() {
        return artists.isEmpty() ? "" : artists.get(0);
    }

    /** What the search on SoundCloud asks for: the main artist and the title, without the version. */
    String query() {
        return (mainArtist() + " " + title).trim();
    }

    /** "Artist, Guest — Title (Version)", for the list of tracks not found. */
    String label() {
        String names = String.join(", ", artists);
        return names.isEmpty() ? wantedTitle() : names + " — " + wantedTitle();
    }
}
