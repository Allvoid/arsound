package app.revanced.extension.soundcloud.download;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The extra copies of downloaded tracks: what is left by downloading the same tracks again and again before the app
 * could tell they were already there. The entry points are on {@link DownloadIndex}.
 */
final class DuplicateFiles {
    private DuplicateFiles() {
    }

    /**
     * Files named like a download by an earlier version of a track that is downloaded from another file, or that the
     * user deleted. Call off the main thread.
     */
    static DownloadIndex.Duplicates find() {
        DownloadIndex.refreshIfStale();
        MediaSnapshot listed = DownloadIndex.snapshot();
        List<DownloadLocation.Row> extra = new ArrayList<>();
        if (listed == null) return new DownloadIndex.Duplicates(extra);

        Set<String> downloaded = DownloadTrackPatch.getDownloadedTracks();
        Set<String> deleted = DownloadTrackPatch.deletedTrackIds();
        // The files a track keeps (and the one a download again will replace), the tracks that keep one, and the
        // tracks whose files are changing. A track that keeps none has its copies taken over by adoption, not removed,
        // unless the user deleted the track: then the copies it left are all extra.
        Set<String> kept = new HashSet<>();
        // The names alone as well: the same folder reached two ways (the media store and a picked folder) lists one
        // file twice, and that is not a copy to remove.
        Set<String> keptNames = new HashSet<>();
        Set<String> keeping = new HashSet<>();
        Set<String> busy = new HashSet<>();
        for (String trackId : downloaded) {
            if (DownloadProgress.isAssembling(trackId) || DownloadTrackPatch.isDownloadPending(trackId)) {
                busy.add(trackId);
                continue;
            }
            for (DownloadIndex.Ref ref : new DownloadIndex.Ref[]{DownloadTrackPatch.fileRefOf(trackId), DownloadTrackPatch.replacedRefOf(trackId)}) {
                DownloadIndex.Entry entry = ref == null ? null : DownloadIndex.find(ref, false);
                if (entry == null) continue;
                kept.add(MediaSnapshot.key(ref.locationId, entry.name));
                keptNames.add(entry.name);
                keeping.add(trackId);
            }
        }
        for (DownloadLocation.Row row : listed.byName.values()) {
            String trackId = EarlierDownloadNames.trackIdOf(row.name);
            if (trackId == null) continue;
            if (busy.contains(trackId)) continue;
            if (!keeping.contains(trackId)) {
                // The leftovers of a track the user deleted, which a copy of an earlier install can be.
                if (deleted.contains(trackId) && !downloaded.contains(trackId)) extra.add(row);
                continue;
            }
            if (!kept.contains(MediaSnapshot.key(row.locationId, row.name)) && !keptNames.contains(row.name)) extra.add(row);
        }
        return new DownloadIndex.Duplicates(extra);
    }

    /**
     * Removes the extra copies the app is allowed to remove (its own).
     *
     * @param needsConsent Gets the rows of copies the system has to confirm (made by an earlier install).
     * @return How many files were removed.
     */
    static int remove(Context context, DownloadIndex.Duplicates duplicates, List<Uri> needsConsent) {
        int removed = 0;
        for (DownloadLocation.Row row : duplicates.rows) {
            if (DownloadLocation.deleteRow(context, row.uri)) {
                removed++;
            } else if (MediaStore.AUTHORITY.equals(row.uri.getAuthority())) {
                // Only the media store can ask the user about a file of another install; a picked folder cannot.
                needsConsent.add(row.uri);
            }
        }
        DownloadIndex.refreshAsync();
        return removed;
    }

    /**
     * Removes the other files named like the download of a track ("soundcloud-ID (1).m4a") that the app is allowed to
     * remove, once the user deleted the track: the copies that downloading again and again left behind would otherwise
     * be the track's files again at the next look. A copy of an earlier install cannot be removed without the user's
     * consent; {@link #find} offers those.
     */
    static void deleteCopiesOf(Context context, String trackId) {
        MediaSnapshot listed = DownloadIndex.snapshot();
        if (listed == null) return;
        String own = context.getPackageName();
        for (DownloadLocation.Row row : new ArrayList<>(listed.byUri.values())) {
            if (!trackId.equals(EarlierDownloadNames.trackIdOf(row.name))) continue;
            if (row.owner != null && !own.equals(row.owner) && MediaStore.AUTHORITY.equals(row.uri.getAuthority())) continue;
            if (DownloadLocation.deleteRow(context, row.uri)) listed.remove(row);
        }
    }
}
