package app.revanced.extension.soundcloud.recommendations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * Keeps one copy of a song in a batch of "My Wave": a track whose song (the same title and length, {@link SongRef#isSameSongAs})
 * is in the queue already, or came earlier in the batch, is left out. A remix or another speed is another song here. A
 * track whose title or length is not known cannot be compared and stays. No Android work, so tests can run it.
 */
final class WaveDedupe {
    /** The songs that are there already: the queue, then every track the pass kept. */
    private final List<SongRef> seen = new ArrayList<>();
    private int dropped;

    /** @param present The songs already in the queue (null entries are ignored). */
    WaveDedupe(Collection<SongRef> present) {
        if (present != null) {
            for (SongRef song : present) remember(song);
        }
    }

    /** The songs of the given tracks that are known; the others have nothing to compare. */
    static List<SongRef> songsOf(Collection<String> urns, Function<String, SongRef> songOf) {
        List<SongRef> songs = new ArrayList<>();
        if (urns == null) return songs;
        for (String urn : urns) {
            SongRef song = songOf.apply(urn);
            if (song != null) songs.add(song);
        }
        return songs;
    }

    /**
     * The tracks that stay, in their order, at most {@code limit}.
     *
     * @param songOf The song of a track urn, or null when it is not known.
     */
    List<String> filter(List<String> urns, Function<String, SongRef> songOf, int limit) {
        List<String> kept = new ArrayList<>();
        for (String urn : urns) {
            if (kept.size() >= limit) break;
            if (admit(songOf.apply(urn))) kept.add(urn);
        }
        return kept;
    }

    /** Whether a track of this song stays; a song that stays is remembered, so its copies do not. */
    boolean admit(SongRef song) {
        if (song == null) return true;
        if (song.isCopyOfAny(seen)) {
            dropped++;
            return false;
        }
        remember(song);
        return true;
    }

    /** How many tracks the pass has left out. */
    int dropped() {
        return dropped;
    }

    private void remember(SongRef song) {
        if (song != null && song.title != null && !song.title.isEmpty() && song.durationMs > 0) seen.add(song);
    }
}
