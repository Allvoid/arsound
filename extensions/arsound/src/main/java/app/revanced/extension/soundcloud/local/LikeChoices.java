package app.revanced.extension.soundcloud.local;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which imported files are liked, as the user and the import decided. A file is {@code liked} (its heart is filled and it
 * is among "Your likes"), or {@code unliked} (the user took the like away), or neither (imported while "like what I
 * import" was off, and not touched since). {@code known} holds every file seen so far: a file that is not in it is new,
 * and gets the import's like if the setting is on at that moment. Files already known are never changed by the setting:
 * turning it on or off does not touch what the user chose; {@link #likeUndecided} is the one call that likes the files
 * nobody decided about, and only the settings screen makes it, after asking. Kept by path, on this phone only.
 * No Android here, so it can be tested on the JVM.
 */
final class LikeChoices {
    /** The three sets as they are kept between runs; the sets may not be changed by the one who gets them. */
    static final class Snapshot {
        final Set<String> known, liked, unliked;

        Snapshot(Set<String> known, Set<String> liked, Set<String> unliked) {
            this.known = known;
            this.liked = liked;
            this.unliked = unliked;
        }
    }

    /** Where the sets are kept between runs. */
    interface Storage {
        /** @return The saved sets, or null if nothing was saved by this version yet (the app was just updated). */
        Snapshot load();

        /** @return The files hidden from the likes by the older version of this feature, for the first start after the update. */
        Set<String> legacyHidden();

        void save(Snapshot snapshot);
    }

    private final Storage storage;
    private Set<String> known, liked, unliked;
    private boolean loaded;

    LikeChoices(Storage storage) {
        this.storage = storage;
    }

    /**
     * Looks at the files in the import folder. The first time after the update, every file that was among the likes
     * stays liked and the ones the user had removed stay removed; later, every file not seen before is new.
     *
     * @param likeNew Whether "like what I import" is on now.
     */
    synchronized void sync(List<File> files, boolean likeNew) {
        boolean changed = load(files);
        for (File file : files) {
            String path = file.getPath();
            if (!known.add(path)) continue;
            changed = true;
            if (likeNew) liked.add(path);
        }
        if (changed) save();
    }

    /** Reads the saved sets, or makes them from the older version's list of hidden files. True if they were made now. */
    private boolean load(List<File> files) {
        if (loaded) return false;
        Snapshot saved = storage.load();
        loaded = true;
        if (saved != null) {
            known = new HashSet<>(saved.known);
            liked = new HashSet<>(saved.liked);
            unliked = new HashSet<>(saved.unliked);
            return false;
        }
        known = new HashSet<>();
        liked = new HashSet<>();
        unliked = new HashSet<>();
        Set<String> hidden = storage.legacyHidden();
        for (File file : files) {
            String path = file.getPath();
            known.add(path);
            (hidden != null && hidden.contains(path) ? unliked : liked).add(path);
        }
        return true;
    }

    synchronized boolean isLiked(File file) {
        return liked != null && liked.contains(file.getPath());
    }

    /** @return True if the file was not liked until now. */
    synchronized boolean like(File file) {
        if (!loaded) return false;
        String path = file.getPath();
        boolean changed = liked.add(path);
        changed |= unliked.remove(path);
        changed |= known.add(path);
        if (changed) save();
        return changed;
    }

    /** @return True if the file was liked until now. */
    synchronized boolean unlike(File file) {
        if (!loaded) return false;
        String path = file.getPath();
        boolean wasLiked = liked.remove(path);
        boolean changed = wasLiked | unliked.add(path);
        changed |= known.add(path);
        if (changed) save();
        return wasLiked;
    }

    /** Forgets a deleted file, so a new file that gets the same name is new, not decided about. */
    synchronized void forget(File file) {
        if (!loaded) return;
        String path = file.getPath();
        boolean changed = known.remove(path);
        changed |= liked.remove(path);
        changed |= unliked.remove(path);
        if (changed) save();
    }

    /** How many of the files nobody decided about: not liked, and not taken away by the user. */
    synchronized int undecidedCount(List<File> files) {
        int count = 0;
        for (File file : files) if (isUndecided(file.getPath())) count++;
        return count;
    }

    /** Likes the files nobody decided about; the ones the user took the like away from stay as they are. Returns how many. */
    synchronized int likeUndecided(List<File> files) {
        if (!loaded) return 0;
        int count = 0;
        for (File file : files) {
            String path = file.getPath();
            if (!isUndecided(path)) continue;
            liked.add(path);
            known.add(path);
            count++;
        }
        if (count > 0) save();
        return count;
    }

    private boolean isUndecided(String path) {
        return loaded && !liked.contains(path) && !unliked.contains(path);
    }

    private void save() {
        storage.save(new Snapshot(new HashSet<>(known), new HashSet<>(liked), new HashSet<>(unliked)));
    }
}
