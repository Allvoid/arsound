package app.revanced.extension.soundcloud.local;

/** A playlist or album of the library, as the merge dialog lists it. */
final class MergeCandidate {
    final String urn;
    final String title;
    /** The number of tracks the library shows for it: the ones on SoundCloud and the ones added on this phone. */
    final int count;
    /** The number of tracks SoundCloud lists in it; -1 if the playlist is not known to the library. */
    final int serverCount;
    /** What {@link LocalCovers#playlistArtwork} gave: a file, SoundCloud's link with the size open, or nothing. */
    final String cover;
    final boolean editable;
    final boolean album;
    /** A station or a playlist SoundCloud makes for the user: not a playlist to take tracks from. */
    final boolean generated;

    MergeCandidate(String urn, String title, int count, int serverCount, String cover, boolean editable,
                   boolean album, boolean generated) {
        this.urn = urn;
        this.title = title;
        this.count = count;
        this.serverCount = serverCount;
        this.cover = cover;
        this.editable = editable;
        this.album = album;
        this.generated = generated;
    }
}
