package app.revanced.extension.soundcloud.local;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which tracks a merge adds to the target playlist, in the order chosen. Pure rules, no SoundCloud and no Android:
 * a track the target has already (or an earlier playlist brought) is skipped, a track that cannot go to SoundCloud
 * is added on this phone only, and a playlist holds {@link #maxTracks} tracks on SoundCloud at most.
 * <p>
 * The plan is made in two steps: {@link #offer} for every track of every playlist taken from, and {@link #recheck}
 * once the track list of the target is read again right before it is saved.
 */
final class MergePlan {
    /** A track to add. */
    static final class Incoming {
        /** What the caller needs to add the track: SoundCloud's urn object. */
        final Object urn;
        /** The urn text of a SoundCloud track, the file entry of an imported file. */
        final String entry;
        /** It goes to the track list of the playlist on SoundCloud; otherwise it is added on this phone only. */
        final boolean server;
        /** Left out at the last moment: the playlist has it by now, or it no longer fits. */
        boolean skipped;

        Incoming(Object urn, String entry, boolean server) {
            this.urn = urn;
            this.entry = entry;
            this.server = server;
        }
    }

    private final boolean editable;
    private final int maxTracks;
    private final Set<String> present = new HashSet<>();
    private final List<Incoming> incoming = new ArrayList<>();
    private final Set<String> notFitted = new HashSet<>();
    private int serverTracks;
    private int duplicates;

    /**
     * @param alreadyThere The entries the target holds: its tracks on SoundCloud and the ones added on this phone.
     * @param serverTracks How many tracks the target has on SoundCloud.
     * @param editable     Whether the target is the user's own playlist, which SoundCloud accepts new tracks for.
     */
    MergePlan(Collection<String> alreadyThere, int serverTracks, boolean editable, int maxTracks) {
        this.present.addAll(alreadyThere);
        this.serverTracks = serverTracks;
        this.editable = editable;
        this.maxTracks = maxTracks;
    }

    /**
     * Considers one track of a playlist taken from.
     *
     * @param file True for an imported file, which cannot be on SoundCloud.
     */
    void offer(Object urn, String entry, boolean file) {
        if (present.contains(entry)) {
            duplicates++;
        } else if (file || !editable) {
            // A file cannot be on SoundCloud, nor can anything on a playlist that is not the user's.
            present.add(entry);
            incoming.add(new Incoming(urn, entry, false));
        } else if (serverTracks >= maxTracks) {
            notFitted.add(entry);
        } else {
            present.add(entry);
            incoming.add(new Incoming(urn, entry, true));
            serverTracks++;
        }
    }

    /**
     * Looks at the track list of the target as it is now, right before it is saved: a track that came to it while the
     * playlists were read must not be added twice, and the tracks that no longer fit are left out.
     *
     * @param latestEntries The entries of the target's tracks on SoundCloud.
     */
    void recheck(Collection<String> latestEntries) {
        Set<String> inList = new HashSet<>(latestEntries);
        int size = latestEntries.size();
        for (Incoming item : incoming) {
            if (!item.server) continue;
            if (inList.contains(item.entry)) {
                item.skipped = true;
                duplicates++;
            } else if (size >= maxTracks) {
                item.skipped = true;
                notFitted.add(item.entry);
            } else {
                size++;
            }
        }
    }

    boolean isEmpty() {
        return incoming.isEmpty();
    }

    List<Incoming> incoming() {
        return incoming;
    }

    /** Tracks skipped because they were in the target already. */
    int duplicates() {
        return duplicates;
    }

    /** Tracks that did not fit in the playlist. */
    int overLimit() {
        return notFitted.size();
    }

    boolean hasServerTracks() {
        for (Incoming item : incoming) if (item.server) return true;
        return false;
    }

    /** How many tracks are sent to SoundCloud. */
    int sentCount() {
        int sent = 0;
        for (Incoming item : incoming) if (item.server && !item.skipped) sent++;
        return sent;
    }

    /** The entries that are added on this phone only. */
    List<String> phoneEntries() {
        List<String> entries = new ArrayList<>();
        for (Incoming item : incoming) if (!item.server) entries.add(item.entry);
        return entries;
    }

    /** Whether a file comes before a track of SoundCloud in the order chosen: the screen would put it after it. */
    boolean filesBeforeTracks() {
        boolean file = false;
        for (Incoming item : incoming) {
            if (!item.server) file = true;
            else if (file) return true;
        }
        return false;
    }
}
