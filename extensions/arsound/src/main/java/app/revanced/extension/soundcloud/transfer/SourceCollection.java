package app.revanced.extension.soundcloud.transfer;

import java.util.List;

/** A collection to transfer, as a source read it: its name and its tracks in their order. */
final class SourceCollection {
    final String title;
    final List<SourceTrack> tracks;

    SourceCollection(String title, List<SourceTrack> tracks) {
        this.title = title;
        this.tracks = tracks;
    }
}
