package app.revanced.extension.soundcloud.local;

import java.io.File;
import java.util.List;

/** An imported file. The path is kept as it was stored: it is compared with the path of the imported tracks as text. */
final class FileEntry extends Entry {
    private final String path;

    FileEntry(String path) {
        this.path = path;
    }

    File file() {
        return new File(path);
    }

    @Override
    String stored() {
        return "file:" + path;
    }

    @Override
    Object makeUrn(ClassLoader loader) throws Exception {
        File file = file();
        return file.isFile() ? Urns.forFile(loader, file) : null;
    }

    @Override
    String cachedTitle(TrackTitles.Snapshot titles) {
        return null;
    }

    @Override
    String trackIdToLoad() {
        return null;
    }

    @Override
    String label(List<LocalMusic.Track> imported, TrackTitles.Snapshot titles) {
        for (LocalMusic.Track track : imported) if (track.file.getPath().equals(path)) return track.title;
        return file().getName();
    }

    @Override
    boolean isComplete() {
        return LocalCovers.hasCover(file());
    }
}
