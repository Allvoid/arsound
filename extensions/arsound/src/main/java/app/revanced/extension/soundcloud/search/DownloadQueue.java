package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

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

    /** Downloads queued or running at once at most: a long album waits for its turn in this queue, not in the pool. */
    private static final int MAX_QUEUED = 20;
    /** About what a track of an album takes, for the question before "Download all". */
    private static final long TRACK_BYTES_ESTIMATE = 8L * 1024 * 1024;
    /** The track URLs downloaded and the files they became; kept on the phone, so a restart does not download again. */
    private static final String PREFERENCES_NAME = "arsound_search_downloads";

    /** Track URL to download percent, while downloading. */
    private static final Map<String, Integer> downloading = new HashMap<>();
    /** Track URL to its imported file, read from the preferences on first use. */
    private static Map<String, String> downloaded;
    /** The tasks of the downloads queued or running, to stop them. */
    private static final Map<String, Future<?>> tasks = new HashMap<>();
    /** The tracks of a "Download all" waiting for room in the queue. */
    private static final java.util.ArrayDeque<OtherSource.Track> waiting = new java.util.ArrayDeque<>();
    /** A batch is running: its tracks are told in one message at the end, not one each. */
    private static int batchDone, batchFailed;
    private static boolean inBatch;

    private DownloadQueue() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    /** The downloads of earlier runs whose files are still there. */
    private static Map<String, String> downloaded() {
        if (downloaded != null) return downloaded;
        downloaded = new HashMap<>();
        Context context = app.revanced.extension.shared.Utils.getContext();
        if (context == null) return downloaded;
        for (Map.Entry<String, ?> entry : preferences(context).getAll().entrySet()) {
            if (entry.getValue() instanceof String) downloaded.put(entry.getKey(), (String) entry.getValue());
        }
        return downloaded;
    }

    /** The percent done of a track that is downloading, or null. */
    static Integer progress(String url) {
        return downloading.get(url);
    }

    /** Downloaded in this or an earlier run, its file still in the imported music. */
    static boolean isDownloaded(String url) {
        String path = downloaded().get(url);
        if (path == null) return false;
        if (new File(path).isFile()) return true;
        // Deleted from the imported music since: it can be downloaded again.
        downloaded.remove(url);
        Context context = app.revanced.extension.shared.Utils.getContext();
        if (context != null) preferences(context).edit().remove(url).apply();
        return false;
    }

    /** @param context The application context. */
    static void start(Context context, OtherSource.Track track) {
        String url = track.url;
        if (downloading.containsKey(url) || isDownloaded(url)) return;
        downloading.put(url, 0);
        SearchScreen.refreshRow(url);
        tasks.put(url, SearchThreads.DOWNLOADS.submit(() -> download(context, track)));
    }

    /**
     * "Download all": asks first, with the number of tracks and about the room they take, and refuses when the phone
     * has not that much free. The tracks then go into the queue a few at a time; the row offers to stop them.
     */
    static void askToStartAll(Context screen, List<OtherSource.Track> tracks) {
        if (!waiting.isEmpty() || inBatch) {
            new app.revanced.extension.soundcloud.shared.ArsoundDialog(screen)
                    .setTitle(text("Остановить загрузки?", "Stop the downloads?"))
                    .setMessage(text("Скачанные треки останутся, остальные не будут скачиваться.",
                            "The tracks downloaded stay, the rest are not downloaded."))
                    .setPositiveButton(text("Остановить", "Stop"), (dialog, which) -> stopAll())
                    .setNegativeButton(text("Продолжить", "Keep going"), null)
                    .show();
            return;
        }
        Context context = screen.getApplicationContext();
        List<OtherSource.Track> left = new ArrayList<>();
        for (OtherSource.Track track : tracks) {
            if (!downloading.containsKey(track.url) && !isDownloaded(track.url)) left.add(track);
        }
        if (left.isEmpty()) {
            Toasts.showLong(context, text("Все треки уже скачаны", "All the tracks are downloaded already"));
            return;
        }
        long needed = left.size() * TRACK_BYTES_ESTIMATE;
        long free = context.getFilesDir().getUsableSpace();
        long megabytes = needed / (1024 * 1024);
        if (free < needed + 200L * 1024 * 1024) {
            Toasts.showLong(context, text("Мало места: нужно около " + megabytes + " МБ", "Not enough space: about "
                    + megabytes + " MB needed"));
            return;
        }
        new app.revanced.extension.soundcloud.shared.ArsoundDialog(screen)
                .setTitle(text("Скачать " + left.size() + " треков?", "Download " + left.size() + " tracks?"))
                .setMessage(text("Около " + megabytes + " МБ, в плейлист «Импортированные». Остановить можно этой же строкой.",
                        "About " + megabytes + " MB, to the imported music. The same row stops them."))
                .setPositiveButton(text("Скачать", "Download"), (dialog, which) -> {
                    inBatch = true;
                    batchDone = 0;
                    batchFailed = 0;
                    waiting.addAll(left);
                    fillQueue(context);
                })
                .setNegativeButton(text("Отмена", "Cancel"), null)
                .show();
    }

    /** Moves waiting tracks of a batch into the queue while it has room. */
    private static void fillQueue(Context context) {
        while (!waiting.isEmpty() && downloading.size() < MAX_QUEUED) start(context, waiting.poll());
        if (inBatch && waiting.isEmpty() && downloading.isEmpty()) {
            inBatch = false;
            Toasts.showLong(context, text("Скачано: " + batchDone + (batchFailed > 0 ? ", не вышло: " + batchFailed : "")
                            + " — в плейлисте «Импортированные»",
                    "Downloaded: " + batchDone + (batchFailed > 0 ? ", failed: " + batchFailed : "") + " — in the imported music"));
        }
    }

    /** Stops the batch: the waiting tracks are dropped, the queued ones given up. */
    static void stopAll() {
        waiting.clear();
        for (Map.Entry<String, Future<?>> task : new ArrayList<>(tasks.entrySet())) {
            task.getValue().cancel(true);
            downloading.remove(task.getKey());
            SearchScreen.refreshRow(task.getKey());
        }
        tasks.clear();
        inBatch = false;
    }

    /** A download ended, done or not: its place in the queue goes to the next waiting track. */
    private static void ended(Context context, String url, boolean done) {
        tasks.remove(url);
        downloading.remove(url);
        if (inBatch) {
            if (done) batchDone++;
            else batchFailed++;
        }
        SearchScreen.refreshRow(url);
        fillQueue(context);
    }

    private static void download(Context context, OtherSource.Track track) {
        String url = track.url;
        // The audio is written to a part file that the library does not list, and moved into the
        // imported music when whole: a killed download must not leave a cut track there.
        File part = null;
        File file = null;
        boolean finished = false;
        boolean reported = false;
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
            String path = file.getPath();
            preferences(context).edit().putString(url, path).apply();
            SearchThreads.MAIN.post(() -> {
                downloaded().put(url, path);
                boolean single = !inBatch;
                ended(context, url, true);
                if (single) {
                    Toasts.showLong(context, text("Скачано: «" + track.title + "» — в плейлисте «Импортированные»",
                            "Downloaded \"" + track.title + "\" to the imported music"));
                }
            });
        } catch (Exception ex) {
            reported = true;
            // Given up by "stop": nothing to tell.
            if (Thread.currentThread().isInterrupted()) return;
            Logger.printException(() -> "Could not download " + url, ex);
            SearchThreads.MAIN.post(() -> {
                ended(context, url, false);
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
                // An Error skipped the catch: the row must not stay "downloading" for ever.
                if (!reported) SearchThreads.MAIN.post(() -> ended(context, url, false));
            }
        }
    }
}
