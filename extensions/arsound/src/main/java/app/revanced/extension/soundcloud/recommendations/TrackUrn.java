package app.revanced.extension.soundcloud.recommendations;

/** SoundCloud calls a track "sounds" in some places and "tracks" in others; the filters use "tracks". */
final class TrackUrn {
    private static final String SOUNDS = "soundcloud:sounds:";
    static final String TRACKS = "soundcloud:tracks:";

    private TrackUrn() {
    }

    /** @param urn A track urn of either kind, or any object that prints as one. */
    static String normalize(Object urn) {
        return String.valueOf(urn).replace(SOUNDS, TRACKS);
    }

    /**
     * Whether the urn is of a SoundCloud track, of either kind. Asked before the urn was normalized, "sounds" was
     * turned away as "not a track".
     */
    static boolean isTrack(Object urn) {
        return urn != null && normalize(urn).contains(":tracks:");
    }
}
