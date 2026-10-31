package app.revanced.extension.soundcloud.download;

import static app.revanced.extension.soundcloud.download.DownloadTrackPatch.text;

import android.content.Context;
import android.net.Uri;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.offline.NativeOffline;

/**
 * Removes the downloads of tracks, whichever way they were made: a file of Arsound, or a protected track that lives in
 * SoundCloud's own offline library. A file left by an earlier install belongs to that install for Android, which
 * removes it only after the user allows it: one question for all such files ({@link DeleteConsentActivity}).
 */
final class DeleteDownloads {
    private DeleteDownloads() {
    }

    /** Whether the track has a download to remove: a file, a track being packed, or a copy in SoundCloud's library. */
    static boolean has(String trackId) {
        return DownloadTrackPatch.isDownloaded(trackId) || DownloadProgress.isAssembling(trackId)
                || NativeOffline.isInLibrary(trackId);
    }

    /**
     * Removes the downloads. Call off the main thread.
     *
     * @param done Called with how many tracks are removed and how many stay (the user declined, or the folder is out).
     */
    static void run(Context context, List<String> trackIds, BiConsumer<Integer, Integer> done) {
        Context app = context.getApplicationContext();
        int deleted = 0;
        List<String> kept = new ArrayList<>();
        List<Uri> files = new ArrayList<>();
        for (String id : trackIds) {
            if (NativeOffline.isInLibrary(id)) NativeOffline.cancel(app, id);
            if (DownloadTrackPatch.deleteDownload(app, id)) {
                deleted++;
                continue;
            }
            // The file stays: it belongs to an earlier install, so only Android's own confirmation removes it.
            kept.add(id);
            DownloadIndex.Entry file = DownloadIndex.known(id);
            Uri row = file == null ? null : file.rowUri(app);
            // Only the media store can ask the user about a file; a file of a picked folder is not removed that way.
            if (row != null && MediaStore.AUTHORITY.equals(row.getAuthority())) files.add(row);
        }
        DownloadProgress.onDownloadsDeleted();
        if (kept.isEmpty()) {
            done.accept(deleted, 0);
            return;
        }
        int removed = deleted;
        Utils.runOnMainThread(() -> {
            boolean asked = DeleteConsentActivity.ask(context, files, granted -> Utils.runOnBackgroundThread(() -> {
                int more = 0;
                if (granted) {
                    for (String id : kept) if (DownloadTrackPatch.deleteDownload(app, id)) more++;
                }
                DownloadIndex.refreshAsync();
                DownloadProgress.onDownloadsDeleted();
                done.accept(removed + more, kept.size() - more);
            }));
            if (!asked) Utils.runOnBackgroundThread(() -> done.accept(removed, kept.size()));
        });
    }

    /** What to say about the tracks that stayed. */
    static String stayedMessage(int removed, int stayed) {
        return text("Удалено: " + removed + ", осталось: " + stayed + ". Эти файлы остались от прежней установки Arsound: "
                        + "разрешите удаление в окне Android или удалите их в файловом менеджере.",
                "Deleted " + removed + ", " + stayed + " stayed. These files are left by an earlier install of Arsound: "
                        + "allow the removal in Android's window, or delete them in a file manager.");
    }
}
