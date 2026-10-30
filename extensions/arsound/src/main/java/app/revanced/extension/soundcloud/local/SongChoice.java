package app.revanced.extension.soundcloud.local;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.revanced.extension.soundcloud.download.ClientProfiles;

/**
 * Picking the SoundCloud upload of a song another service knows by its artists, title and length (a transfer from
 * Yandex Music). Pure text rules over search results; no network and no Android.
 * <p>
 * The artist's own full upload with the title comes first, the one of the same length before the others. When the
 * uploader is not the artist (a label, a name written in Latin letters), a full track with the title and the same
 * length to a few seconds is taken instead: the length tells one recording from another well enough.
 */
public final class SongChoice {
    /** A result this close to the wanted length is the same recording. */
    static final long SAME_LENGTH_MS = 3_000;
    /** An upload by the artist that differs by more than this (and {@link #OTHER_LENGTH_SHARE}) is another version. */
    static final long OTHER_LENGTH_MS = 20_000;
    static final double OTHER_LENGTH_SHARE = 0.15;
    /** Words of a DJ's take on a song that {@link TrackMatcher#isVariant} does not know: "Bad Times (X Edit Mix)". */
    private static final Pattern OTHER_MIX = Pattern.compile("(?iu)\\b(mix|edit|rmx|bootleg|mashup|flip|vip|extended)\\b");

    private SongChoice() {
    }

    /**
     * @param artists    The artists of the song, the main one first.
     * @param title      The title, with the version in brackets if the service names one ("Song (Live)").
     * @param durationMs The length, 0 if unknown.
     * @return The upload to take, or null if the results have none.
     */
    public static ClientProfiles.FoundTrack pick(List<ClientProfiles.FoundTrack> results, List<String> artists,
                                                 String title, long durationMs) {
        ClientProfiles.FoundTrack byArtist = null;
        ClientProfiles.FoundTrack sameLength = null;
        for (ClientProfiles.FoundTrack track : results) {
            if (!track.isFull() || TrackMatcher.isSnippet(track.title, title) || namesOtherMix(track.title, title)) continue;
            if (isByAnyArtist(track, artists, title)) {
                if (isSameLength(track, durationMs)) return track;
                if (byArtist == null && !isOtherLength(track, durationMs)) byArtist = track;
            } else if (sameLength == null && isSameLength(track, durationMs) && holdsTitle(track, artists, title)) {
                sameLength = track;
            }
        }
        return byArtist != null ? byArtist : sameLength;
    }

    private static boolean isByAnyArtist(ClientProfiles.FoundTrack track, List<String> artists, String title) {
        for (String artist : artists) {
            if (!artist.isEmpty() && TrackMatcher.isWantedTrack(track.user, track.title, artist, title)) return true;
        }
        return false;
    }

    /** The title alone, for an upload by someone else: the artist's name may still lead it. */
    private static boolean holdsTitle(ClientProfiles.FoundTrack track, List<String> artists, String title) {
        if (TrackMatcher.isVariant(track.title, title)) return false;
        String found = artists.isEmpty() ? track.title : TrackMatcher.stripArtist(track.title, artists.get(0));
        return TrackMatcher.holdsTitle(found, title);
    }

    /** The found title names a mix or an edit the wanted one does not. */
    static boolean namesOtherMix(String found, String wanted) {
        String lowerWanted = wanted.toLowerCase(Locale.ROOT);
        Matcher word = OTHER_MIX.matcher(found);
        while (word.find()) {
            if (!lowerWanted.contains(word.group(1).toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    static boolean isSameLength(ClientProfiles.FoundTrack track, long durationMs) {
        return durationMs > 0 && track.duration > 0 && Math.abs(track.duration - durationMs) <= SAME_LENGTH_MS;
    }

    /** Both lengths known and far apart: an extended mix or a live take, not the song. */
    static boolean isOtherLength(ClientProfiles.FoundTrack track, long durationMs) {
        if (durationMs <= 0 || track.duration <= 0) return false;
        long difference = Math.abs(track.duration - durationMs);
        return difference > OTHER_LENGTH_MS && difference > durationMs * OTHER_LENGTH_SHARE;
    }
}
