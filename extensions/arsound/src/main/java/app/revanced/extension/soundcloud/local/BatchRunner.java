package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * One run of the batch download (see {@link BatchActivity}): reads the list and the report of earlier runs, finds
 * each line, writes the report, and fills the playlist in the order of the list.
 */
final class BatchRunner {
    private static final int TRACK_LIMIT_MINUTES = 3;
    private static ExecutorService worker = Executors.newSingleThreadExecutor();

    private final Context context;
    private final File list;
    private final File reportFile;
    private final String playlistTitle;
    private final boolean refresh;

    /** Lines done in an earlier run, with the playlist entry they got: a SoundCloud track or a file. */
    private final Map<String, String> done = new HashMap<>();
    /** The title each earlier line was matched to: a match an improved check rejects is searched again. */
    private final Map<String, String> doneTitles = new HashMap<>();
    /** Every entry any run put into the playlist: the ones this run does not keep are dropped from it. */
    private final Set<String> batchEntries = new HashSet<>();

    BatchRunner(Context context, File list, File reportFile, String playlistTitle, boolean refresh) {
        this.context = context;
        this.list = list;
        this.reportFile = reportFile;
        this.playlistTitle = playlistTitle;
        this.refresh = refresh;
    }

    void run() throws Exception {
        String playlistUrn = playlistTitle == null ? null : findOpenedPlaylist(playlistTitle);
        readEarlierReport();
        List<String> ordered = new ArrayList<>();

        try (Writer report = new OutputStreamWriter(new FileOutputStream(reportFile, true), StandardCharsets.UTF_8)) {
            for (String line : readLines(list)) {
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;
                BatchOutcome outcome = handleLine(line, parts, ordered);
                if (outcome == null) continue;
                report.write(outcome.reportLine() + "\n");
                report.flush();
            }
            report.write("END\n");
        }
        if (playlistUrn != null && !SavedPlaylist.isSavedPlaylist(playlistUrn)) fillPlaylist(playlistUrn, ordered);
        finish(playlistUrn);
    }

    /** The urn of the opened playlist with this title: the playlist has to be opened once so the app knows it. */
    private static String findOpenedPlaylist(String title) {
        for (String[] playlist : LocalAdditions.readRecentPlaylists()) {
            if (playlist[1].equalsIgnoreCase(title)) return playlist[0];
        }
        throw new IllegalStateException("Open the playlist once: " + title);
    }

    private void readEarlierReport() throws Exception {
        if (!reportFile.isFile()) return;
        for (String line : readLines(reportFile)) {
            String[] parts = line.split("\t");
            if (!BatchOutcome.isDoneLine(parts)) continue;
            done.put(parts[1], parts[4]);
            doneTitles.put(parts[1], parts[2]);
            String entry = earlierEntry(parts[4]);
            if (entry != null) batchEntries.add(entry);
        }
    }

    /**
     * Finds one line of the list.
     *
     * @return What goes to the report, or null if the line was done by an earlier run and nothing is written.
     */
    private BatchOutcome handleLine(String line, String[] parts, List<String> ordered) {
        String key = line.trim().replace('\t', ' ');
        String earlier = earlierEntry(done.get(key));
        String earlierTitle = doneTitles.get(key);
        String artist = parts[0].trim();
        String title = parts[1].trim();
        if (earlierTitle != null && !BatchOutcome.isKeptTitle(earlierTitle) && !TrackMatcher.namesVersion(earlierTitle, title)) {
            earlier = null;
        }
        // A file without a cover goes through the search again to get one; it is not downloaded twice.
        if (earlier != null && !refresh && Entry.parse(earlier).isComplete()) {
            ordered.add(earlier);
            return null;
        }
        Logger.printInfo(() -> "Batch: " + key);
        BatchOutcome outcome = findWithinLimit(key, artist, title);
        // An entry of an earlier run keeps its place even if this run could not reach the network.
        // A search that found nothing drops it: the earlier match may have been a wrong one.
        if (outcome.entry() == null && earlier != null && outcome.isFailure()) outcome = BatchOutcome.kept(key, earlier);
        if (outcome.entry() != null) ordered.add(outcome.entry());
        return outcome;
    }

    /**
     * A DNS lookup on a broken network ignores connection timeouts and can hang for good: one track gets a hard limit,
     * then the batch goes on.
     */
    private BatchOutcome findWithinLimit(String key, String artist, String title) {
        try {
            Future<BatchOutcome> task = worker.submit(() -> TrackFinder.findOne(context, artist, title));
            try {
                return task.get(TRACK_LIMIT_MINUTES, TimeUnit.MINUTES);
            } catch (TimeoutException ex) {
                task.cancel(true);
                worker.shutdownNow();
                worker = Executors.newSingleThreadExecutor();
                throw new IOException("No answer in " + TRACK_LIMIT_MINUTES + " minutes");
            } catch (ExecutionException ex) {
                throw ex.getCause();
            }
        } catch (Throwable ex) {
            Logger.printInfo(() -> "Batch: could not get " + key + ": " + ex);
            return BatchOutcome.failed(key, ex);
        }
    }

    /**
     * The playlist follows the list: its tracks first in list order, entries added by hand after them.
     * Entries of earlier runs that this run replaced (a file by a SoundCloud track) are dropped.
     */
    private void fillPlaylist(String playlistUrn, List<String> ordered) {
        List<String> entries = new ArrayList<>(ordered);
        for (String old : LocalAdditions.getEntries(playlistUrn)) {
            if (!entries.contains(old) && !batchEntries.contains(old)) entries.add(old);
        }
        LocalAdditions.setEntries(playlistUrn, entries);
        TrackOrder.clear(playlistUrn);
    }

    private void finish(String playlistUrn) {
        Utils.runOnMainThread(() -> {
            if (playlistUrn != null) LocalAdditions.notifyPlaylistChanged(playlistUrn);
            String saved = SavedPlaylist.getUrn();
            if (saved != null) LocalAdditions.notifyPlaylistChanged(saved);
            Toast.makeText(context, "Arsound: batch done", Toast.LENGTH_SHORT).show();
        });
    }

    /** The playlist entry of a report value: a SoundCloud urn as is, a file path as a file entry. */
    private static String earlierEntry(String value) {
        if (value == null) return null;
        if (value.startsWith("soundcloud:")) return value;
        File file = new File(value);
        return file.isFile() ? Entry.ofFile(file).stored() : null;
    }

    private static List<String> readLines(File file) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) if (!line.trim().isEmpty()) lines.add(line);
        }
        return lines;
    }
}
