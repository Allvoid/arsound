package app.revanced.extension.soundcloud.local;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Imported files the user removed from "Your likes" by un-liking them. The file stays and keeps playing from the
 * "Imported" playlist; liking it again brings it back. Kept by path, in the settings of this phone only.
 * No Android here, so it can be tested on the JVM.
 */
final class HiddenLikes {
    /** Where the paths are kept between runs. */
    interface Storage {
        /** @return The paths, or null while the storage is not available yet. */
        Set<String> load();

        void save(Set<String> paths);
    }

    private final Storage storage;
    private Set<String> paths;

    HiddenLikes(Storage storage) {
        this.storage = storage;
    }

    /** Read once; an unavailable storage is asked again next time instead of being taken for an empty one. */
    private Set<String> paths() {
        if (paths != null) return paths;
        Set<String> loaded = storage.load();
        if (loaded == null) return new HashSet<>();
        paths = new HashSet<>(loaded);
        return paths;
    }

    synchronized boolean isHidden(File file) {
        return paths().contains(file.getPath());
    }

    /** @return True if the file was among the likes until now. */
    synchronized boolean hide(File file) {
        Set<String> current = paths();
        if (!current.add(file.getPath())) return false;
        storage.save(new HashSet<>(current));
        return true;
    }

    /**
     * Brings the file back, or forgets it when it is deleted.
     *
     * @return True if it was hidden until now.
     */
    synchronized boolean show(File file) {
        Set<String> current = paths();
        if (!current.remove(file.getPath())) return false;
        storage.save(new HashSet<>(current));
        return true;
    }
}
