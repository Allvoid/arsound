package app.revanced.extension.soundcloud.local;

import java.util.List;

import app.revanced.extension.soundcloud.download.DownloadTrackPatch;
import app.revanced.extension.soundcloud.shared.Lang;

/** A SoundCloud track added to a playlist on this phone. */
final class TrackEntry extends Entry {
    /** Starts the stored text of a SoundCloud track entry, and the urn text of a track. */
    static final String PREFIX = "soundcloud:tracks:";

    private final String stored;

    TrackEntry(String stored) {
        this.stored = stored;
    }

    @Override
    String stored() {
        return stored;
    }

    @Override
    Object makeUrn(ClassLoader loader) throws Exception {
        String id = DownloadTrackPatch.parseTrackId(stored);
        return id == null ? null : Urns.forTrack(loader, id);
    }

    @Override
    String cachedTitle(TrackTitles.Snapshot titles) {
        return titles.get(stored);
    }

    @Override
    String trackIdToLoad() {
        return DownloadTrackPatch.parseTrackId(stored);
    }

    @Override
    String label(List<LocalMusic.Track> imported, TrackTitles.Snapshot titles) {
        String title = cachedTitle(titles);
        return title != null ? title : Lang.text("Трек SoundCloud ", "SoundCloud track ") + DownloadTrackPatch.parseTrackId(stored);
    }

    @Override
    boolean isComplete() {
        return true;
    }
}
