package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.local.LocalCovers;
import app.revanced.extension.soundcloud.local.LocalMusic;
import app.revanced.extension.soundcloud.shared.Toasts;

/**
 * Downloads of Arsound search results into the imported music. What is downloading and what is done survives leaving
 * and opening the search tab again. Called and changed on the main thread.
 */
final class DownloadQueue {
    private static final int ATTEMPTS = 3;

    /** Track URL to download percent, while downloading. */
    private static final Map<String, Integer> downloading = new HashMap<>();
    private static final Set<String> downloaded = new HashSet<>();

    private DownloadQueue() {
    }

    /** The percent done of a track that is downloading, or null. */
    static Integer progress(String url) {
        return downloading.get(url);
    }

    static boolean isDownloaded(String url) {
        return downloaded.contains(url);
    }

    /** @param context The application context. */
    static void start(Context context, OtherSource.Track track) {
        String url = track.url;
        if (downloading.containsKey(url) || downloaded.contains(url)) return;
        downloading.put(url, 0);
        SearchScreen.refreshRow(url);
        SearchThreads.DOWNLOADS.execute(() -> download(context, track));
    }

    private static void download(Context context, OtherSource.Track track) {
        String url = track.url;
        // The audio is written to a part file that the library does not list, and moved into the
        // imported music when whole: a killed download must not leave a cut track there.
        File part = null;
        File file = null;
        boolean finished = false;
        try {
            for (int attempt = 1; ; attempt++) {
                AudioStream stream = OtherSource.bestAudio(url);
                String name = (track.artist.isEmpty() ? "" : track.artist + " - ") + track.title
                        + "." + OtherSource.extensionOf(stream);
                part = LocalMusic.newPartFile(context);
                int[] shown = {-1};
                try (OutputStream output = new FileOutputStream(part)) {
                    OtherSource.download(stream.getContent(), output, value -> {
                        if (value == shown[0]) return;
                        shown[0] = value;
                        SearchThreads.MAIN.post(() -> {
                            if (downloading.containsKey(url)) downloading.put(url, value);
                            SearchScreen.refreshRow(url);
                        });
                    });
                } catch (OtherSource.RefusedException ex) {
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                    if (attempt >= ATTEMPTS) throw ex;
                    continue;
                }
                file = LocalMusic.moveIntoImports(context, part, name);
                break;
            }
            LocalCovers.save(file, track.coverUrl);
            LocalMusic.onFileAdded();
            finished = true;
            SearchThreads.MAIN.post(() -> {
                downloading.remove(url);
                downloaded.add(url);
                SearchScreen.refreshRow(url);
                Toasts.showLong(context, text("Скачано: «" + track.title + "» — в плейлисте «Импортированные»",
                        "Downloaded \"" + track.title + "\" to the imported music"));
            });
        } catch (Exception ex) {
            Logger.printException(() -> "Could not download " + url, ex);
            SearchThreads.MAIN.post(() -> {
                downloading.remove(url);
                SearchScreen.refreshRow(url);
                SearchFailure.of(ex).report(context,
                        text("YouTube не отдал файл: похоже, VPN шлёт запросы с разных адресов.",
                                "YouTube refused the file: the VPN seems to use different addresses."),
                        text("Не получилось скачать «" + track.title + "».", "Could not download \"" + track.title + "\"."));
            });
        } finally {
            // Also for an Error, which the catch above does not take.
            if (!finished) {
                if (part != null) //noinspection ResultOfMethodCallIgnored
                    part.delete();
                if (file != null) //noinspection ResultOfMethodCallIgnored
                    file.delete();
            }
        }
    }
}
