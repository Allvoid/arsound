package app.revanced.extension.soundcloud.local;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which tracks of an edited playlist go to this phone only, because the server keeps a limited number of tracks in a
 * playlist. Pure rules, no SoundCloud and no Android, see {@link PlaylistLimit} for what is done with the answer.
 * <p>
 * Only an edit that makes the playlist grow past the limit moves tracks, and only the tracks that this edit added. An
 * old playlist can hold more than the limit on the server; removing a track from it, or putting its tracks in a new
 * order, must send the whole list as it is and not cut the tail off.
 */
final class PlaylistOverflow {
    private PlaylistOverflow() {
    }

    /**
     * The tracks of a list about to be saved that do not fit on the server.
     * <p>
     * A track is "added by this edit" if the server list had not got it before. The tracks the server had stay, so
     * a playlist that is already at or over the limit takes no new track, while one with room for some takes that
     * many of the new tracks, the first ones in the order of the saved list. The new tracks may stand anywhere in the
     * saved list: SoundCloud's "Add to playlist" puts them last, its "Add music" screen puts them first.
     * <p>
     * Tracks are told apart by their text ({@code soundcloud:tracks:123}), as everywhere else in the local lists, not
     * by the class of the urn object.
     *
     * @param saved The whole list the edit saves, in its order.
     * @param before The tracks the server list had right before the edit, or null if that could not be read. Then
     *               nothing tells the new tracks from the old ones, and the tracks past position {@code limit} are taken:
     *               the server keeps no more than that, and they stay in the playlist on this phone.
     * @param limit How many tracks the server keeps in a playlist.
     * @return The tracks to keep on this phone only, in the order of {@code saved}; empty if the edit does not make
     * the playlist grow past the limit. The arguments are not changed.
     */
    static <T> List<T> beyondLimit(Collection<T> saved, Collection<?> before, int limit) {
        List<T> overflow = new ArrayList<>();
        // Cannot be over the limit: also what a removal or a new order of a short playlist ends in.
        if (saved.size() <= limit) return overflow;
        if (before == null) {
            int index = 0;
            for (T track : saved) {
                if (index++ >= limit) overflow.add(track);
            }
            return overflow;
        }
        Set<String> had = new HashSet<>();
        for (Object track : before) had.add(String.valueOf(track));
        int kept = 0;
        for (T track : saved) {
            if (had.contains(String.valueOf(track))) kept++;
        }
        int room = Math.max(0, limit - kept);
        for (T track : saved) {
            if (had.contains(String.valueOf(track))) continue;
            if (room > 0) room--;
            else overflow.add(track);
        }
        return overflow;
    }
}
