package app.revanced.extension.soundcloud.local;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The imported files that count as liked: all of them except the ones the user removed from the likes.
 * Newest first. No Android here, so it can be tested on the JVM.
 */
final class LikedFiles {
    private final List<File> files;
    private final Set<String> paths = new HashSet<>();

    /**
     * @param newestFirst Every imported file, newest first.
     * @param removed     True for a file the user removed from the likes.
     */
    LikedFiles(List<File> newestFirst, Predicate<File> removed) {
        List<File> kept = new ArrayList<>(newestFirst.size());
        for (File file : newestFirst) {
            if (removed.test(file)) continue;
            kept.add(file);
            paths.add(file.getPath());
        }
        files = Collections.unmodifiableList(kept);
    }

    List<File> all() {
        return files;
    }

    int size() {
        return files.size();
    }

    boolean contains(File file) {
        return paths.contains(file.getPath());
    }

    /**
     * The files for a search in the likes. Only the file name is compared, without the extension and
     * ignoring case: the tags would have to be read from every file.
     */
    List<File> matching(String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) return files;

        List<File> found = new ArrayList<>();
        for (File file : files) {
            if (baseName(file).toLowerCase(Locale.ROOT).contains(needle)) found.add(file);
        }
        return found;
    }

    private static String baseName(File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
