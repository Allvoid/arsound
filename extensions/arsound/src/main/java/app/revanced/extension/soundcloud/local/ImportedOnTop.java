package app.revanced.extension.soundcloud.local;

import java.util.ArrayList;
import java.util.List;

/**
 * Imported files before everything else, newest first. Right for "recently added", where an import counts as the
 * latest like; the other sort orders show them there too, for now.
 */
final class ImportedOnTop implements LikePlacement {
    @Override
    public <T> List<T> place(List<T> imported, List<T> stored) {
        List<T> result = new ArrayList<>(imported.size() + stored.size());
        result.addAll(imported);
        result.addAll(stored);
        return result;
    }
}
