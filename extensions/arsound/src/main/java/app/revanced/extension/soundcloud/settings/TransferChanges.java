package app.revanced.extension.soundcloud.settings;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What importing a {@link TransferBackup} does to this phone: the values to write and the keys to remove, per preferences
 * file. Worked out from the file and the current values by {@link SettingsTransfer#plan}, written by {@code TransferFiles}.
 */
final class TransferChanges {
    /** File to key to value: written as it is. */
    final Map<String, Map<String, Object>> writes = new LinkedHashMap<>();
    /** File to the keys that are on this phone but not in the file, so the file's state wins: removed. */
    final Map<String, Set<String>> removals = new LinkedHashMap<>();
    /** Values left out because this phone already keeps that key as another type; reading it back would crash. */
    int typeConflicts;

    /** Values that will be written. */
    int writeCount() {
        int count = 0;
        for (Map<String, Object> values : writes.values()) count += values.size();
        return count;
    }

    /** Keys that will be removed (back to their defaults). */
    int removeCount() {
        int count = 0;
        for (Set<String> keys : removals.values()) count += keys.size();
        return count;
    }

    Map<String, Object> writesOf(String file) {
        return writes.computeIfAbsent(file, name -> new LinkedHashMap<>());
    }

    Set<String> removalsOf(String file) {
        return removals.computeIfAbsent(file, name -> new TreeSet<>());
    }
}
