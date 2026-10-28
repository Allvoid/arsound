package app.revanced.extension.soundcloud.download;

import android.content.Context;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.shared.Logger;

/**
 * Gives a track back its file when the app lost the record of it: after a reinstall, a change of the package or of
 * the signature (the files then belong to the old install for Android), or when the stored data was wiped.
 * Files made by earlier versions carry the track id in their name ("soundcloud-ID.m4a"), so they need no network.
 * A track that has a usable file is left alone; of several copies of one track one is picked: a copy the app made
 * itself (it can remove it), then the one without a " (n)", then the lowest n.
 * <p>
 * Needs the access to music to see the files of an earlier install. The entry points are on {@link DownloadIndex}.
 */
final class DownloadAdoption {
    /** Files this small are not a track, whatever their name: a failed download leaves a few bytes. */
    private static final long MIN_ADOPT_BYTES = 10 * 1024;
    private static final Object ADOPT_LOCK = new Object();

    private DownloadAdoption() {
    }

    /**
     * Call off the main thread.
     *
     * @param manual True if the user asked for it: then the tracks the user deleted are found again too, as the user
     *               asked for the files. Otherwise they are not, or deleting a track whose copies are left would bring
     *               it back at the next start.
     * @return How many tracks were found.
     */
    static int adopt(Context context, boolean manual) {
        if (context == null) return 0;
        synchronized (ADOPT_LOCK) {
            DownloadIndex.refreshNow();
            MediaSnapshot listed = DownloadIndex.snapshot();
            if (listed == null) return 0;

            Map<String, List<DownloadLocation.Row>> byTrack = candidatesByTrack(listed, manual);

            String own = context.getPackageName();
            Map<String, DownloadLocation.Row> found = new HashMap<>();
            for (Map.Entry<String, List<DownloadLocation.Row>> entry : byTrack.entrySet()) {
                String trackId = entry.getKey();
                // A track on its way has its own record; a track with a usable file needs none.
                if (DownloadProgress.isAssembling(trackId) || DownloadTrackPatch.isDownloadPending(trackId)) continue;
                DownloadIndex.Ref ref = DownloadTrackPatch.fileRefOf(trackId);
                if (ref != null && DownloadIndex.find(ref, true) != null) continue;
                List<DownloadLocation.Row> rows = entry.getValue();
                Collections.sort(rows, preferredCopy(own));
                found.put(trackId, rows.get(0));
            }
            if (found.isEmpty()) {
                Logger.printInfo(() -> "Download index: no tracks to take over among " + byTrack.size() + " named by id");
                return 0;
            }
            DownloadTrackPatch.rememberAdopted(found);
            Logger.printInfo(() -> "Download index: took over the files of " + found.size() + " tracks");
            DownloadProgress.onFilesChanged();
            return found.size();
        }
    }

    /** The files named after a track by an earlier version, big enough to be music, grouped by the track. */
    private static Map<String, List<DownloadLocation.Row>> candidatesByTrack(MediaSnapshot listed, boolean manual) {
        Set<String> deleted = DownloadTrackPatch.deletedTrackIds();
        Map<String, List<DownloadLocation.Row>> byTrack = new HashMap<>();
        for (DownloadLocation.Row row : listed.byName.values()) {
            String trackId = EarlierDownloadNames.trackIdOf(row.name);
            if (trackId == null || row.size < MIN_ADOPT_BYTES) continue;
            if (!manual && deleted.contains(trackId)) continue;
            List<DownloadLocation.Row> rows = byTrack.get(trackId);
            if (rows == null) byTrack.put(trackId, rows = new ArrayList<>());
            rows.add(row);
        }
        return byTrack;
    }

    private static Comparator<DownloadLocation.Row> preferredCopy(String ownPackage) {
        return (left, right) -> {
            boolean leftOwn = ownPackage.equals(left.owner);
            if (leftOwn != ownPackage.equals(right.owner)) return leftOwn ? -1 : 1;
            int byCopy = EarlierDownloadNames.copyNumber(left.name) - EarlierDownloadNames.copyNumber(right.name);
            return byCopy != 0 ? byCopy : left.name.length() - right.name.length();
        };
    }
}
