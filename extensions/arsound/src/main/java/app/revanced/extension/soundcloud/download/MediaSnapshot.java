package app.revanced.extension.soundcloud.download;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What the media store lists in the known locations. The maps are changed in place when a file is removed. */
final class MediaSnapshot {
    final Map<String, DownloadLocation.Row> byName = new ConcurrentHashMap<>();
    final Map<String, DownloadLocation.Row> byUri = new ConcurrentHashMap<>();

    /** The key of a file by name: the same name in two locations is two files. */
    static String key(String locationId, String name) {
        return locationId + '\u0000' + name;
    }

    void add(DownloadLocation.Row row) {
        byName.put(key(row.locationId, row.name), row);
        byUri.put(row.uri.toString(), row);
    }

    void remove(DownloadLocation.Row row) {
        byName.remove(key(row.locationId, row.name));
        byUri.remove(row.uri.toString());
    }

    boolean sameAs(MediaSnapshot other) {
        if (byUri.size() != other.byUri.size()) return false;
        for (Map.Entry<String, DownloadLocation.Row> entry : byUri.entrySet()) {
            DownloadLocation.Row row = other.byUri.get(entry.getKey());
            if (row == null || !row.sameAs(entry.getValue())) return false;
        }
        return true;
    }
}
