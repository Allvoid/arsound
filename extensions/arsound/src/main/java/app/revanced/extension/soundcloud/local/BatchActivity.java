package app.revanced.extension.soundcloud.local;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.arsound.shaded.newpipe.extractor.stream.AudioStream;
import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.search.OtherSource;

/**
 * Downloads a list of tracks through the Arsound search, for filling a playlist from a computer:
 * <pre>
 * adb push list.txt /sdcard/Android/data/PACKAGE/files/batch/list.txt
 * adb shell am start -n PACKAGE/app.revanced.extension.soundcloud.local.BatchActivity --es file list.txt --es playlist "Title"
 * </pre>
 * Each line of the list is {@code artist<TAB>title}. Only a result by the same artist with a matching
 * title is downloaded. Progress goes to {@code list.txt.report}; lines already reported as done are
 * skipped, so a run can be repeated. With a playlist title, the playlist gets the files in list order
 * (the opened playlist with that title); a track missing from the search is taken from the imported files.
 * <p>
 * The list is read only from the app's own external folder, which other apps cannot write to.
 */
@SuppressWarnings("unused")
public final class BatchActivity extends Activity {
    private static volatile boolean running;
    private static final int TRACK_LIMIT_MINUTES = 3;
    private static java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        String name = getIntent().getStringExtra("file");
        String playlistTitle = getIntent().getStringExtra("playlist");
        Context context = getApplicationContext();
        // Started over adb, the app may have no screen yet: the stores of playlists and covers need the context.
        if (Utils.getContext() == null) Utils.setContext(context);
        File folder = context.getExternalFilesDir("batch");
        if (name == null || folder == null || name.contains("/") || running) return;
        File list = new File(folder, name);
        if (!list.isFile()) return;
        running = true;
        Toast.makeText(context, "Arsound: batch " + name, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                run(context, list, new File(folder, name + ".report"), playlistTitle);
            } catch (Throwable ex) {
                Logger.printException(() -> "Batch failed", ex);
                try (Writer report = new OutputStreamWriter(new FileOutputStream(new File(folder, name + ".report"), true), StandardCharsets.UTF_8)) {
                    report.write("ERROR\t" + android.util.Log.getStackTraceString(ex) + "\n");
                } catch (Exception ignored) {
                }
            } finally {
                running = false;
            }
        }).start();
    }

    private static void run(Context context, File list, File reportFile, String playlistTitle) throws Exception {
        String playlistUrn = null;
        if (playlistTitle != null) {
            for (String[] playlist : LocalAdditions.readRecentPlaylists()) {
                if (playlist[1].equalsIgnoreCase(playlistTitle)) {
                    playlistUrn = playlist[0];
                    break;
                }
            }
            if (playlistUrn == null) throw new IllegalStateException("Open the playlist once: " + playlistTitle);
        }

        // Lines done in an earlier run, with the file they got.
        Map<String, File> done = new HashMap<>();
        if (reportFile.isFile()) {
            for (String line : readLines(reportFile)) {
                String[] parts = line.split("\t");
                if (parts[0].equals("OK") && parts.length >= 5) done.put(parts[1], new File(parts[4]));
            }
        }
        List<String> ordered = new ArrayList<>();

        try (Writer report = new OutputStreamWriter(new FileOutputStream(reportFile, true), StandardCharsets.UTF_8)) {
            for (String line : readLines(list)) {
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;
                String key = line.trim().replace('\t', ' ');
                File earlier = done.get(key);
                // A file without a cover goes through the search again to get one; it is not downloaded twice.
                if (earlier != null && earlier.isFile() && LocalCovers.hasCover(earlier)) {
                    ordered.add(LocalAdditions.fileEntry(earlier));
                    continue;
                }
                String artist = parts[0].trim();
                String title = parts[1].trim();
                String result;
                Logger.printInfo(() -> "Batch: " + key);
                File[] file = new File[1];
                try {
                    // A DNS lookup on a broken network ignores connection timeouts and can hang for good:
                    // one track gets a hard limit, then the batch goes on.
                    java.util.concurrent.Future<String> task = worker.submit(() -> downloadOne(context, artist, title, file));
                    try {
                        result = task.get(TRACK_LIMIT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
                    } catch (java.util.concurrent.TimeoutException ex) {
                        task.cancel(true);
                        worker.shutdownNow();
                        worker = java.util.concurrent.Executors.newSingleThreadExecutor();
                        file[0] = null;
                        throw new java.io.IOException("No answer in " + TRACK_LIMIT_MINUTES + " minutes");
                    } catch (java.util.concurrent.ExecutionException ex) {
                        throw ex.getCause();
                    }
                } catch (Throwable ex) {
                    Logger.printInfo(() -> "Batch: could not get " + key + ": " + ex);
                    result = "FAIL\t" + key + "\t" + ex;
                }
                // A file of an earlier run keeps its place even if this run could not reach the network.
                if (file[0] == null && earlier != null && earlier.isFile()) {
                    file[0] = earlier;
                    if (!result.startsWith("OK")) result = "OK\t" + key + "\tkept\tearlier\t" + earlier.getPath();
                }
                if (file[0] != null) ordered.add(LocalAdditions.fileEntry(file[0]));
                report.write(result + "\n");
                report.flush();
            }
            report.write("END\n");
        }
        if (playlistUrn != null && !SavedPlaylist.isSavedPlaylist(playlistUrn)) {
            // The playlist follows the list: its tracks first in list order, entries added by hand after them.
            List<String> entries = new ArrayList<>(ordered);
            for (String entry : LocalAdditions.getEntries(playlistUrn)) if (!entries.contains(entry)) entries.add(entry);
            LocalAdditions.setEntries(playlistUrn, entries);
            TrackOrder.clear(playlistUrn);
        }
        String urn = playlistUrn;
        Utils.runOnMainThread(() -> {
            if (urn != null) LocalAdditions.notifyPlaylistChanged(urn);
            String saved = SavedPlaylist.getUrn();
            if (saved != null) LocalAdditions.notifyPlaylistChanged(saved);
            Toast.makeText(context, "Arsound: batch done", Toast.LENGTH_SHORT).show();
        });
    }

    /** Downloads one track, or finds it among the imported files; the file goes to {@code result[0]}. */
    private static String downloadOne(Context context, String artist, String title, File[] result) throws Exception {
        String key = artist + " " + title;
        OtherSource.Track match = null;
        for (OtherSource.Track track : OtherSource.search(artist + " " + title)) {
            if (normalize(track.artist).contains(normalize(artist)) && titleMatches(track.title, title)) {
                match = track;
                break;
            }
        }
        if (match == null) {
            // Not on YouTube Music: a file imported earlier (for example a track removed from SoundCloud) will do.
            String wanted = normalize(title.replaceAll("\\s*\\([^)]*\\)\\s*$", ""));
            // The title from the file tags (the file name may be a SoundCloud id). Only a title holding the
            // whole wanted one: a short title must not match a longer one.
            for (LocalMusic.Track existing : LocalMusic.getTracks(context)) {
                if (!wanted.isEmpty() && normalize(existing.title).contains(wanted)) {
                    result[0] = existing.file;
                    return "OK\t" + key + "\t" + existing.title + "\timported\t" + existing.file.getPath();
                }
            }
            return "MISS\t" + key;
        }

        // A track downloaded earlier is reused instead of downloading a copy.
        File file = null;
        String prefix = (match.artist + " - " + match.title).replaceAll("[\\\\/:*?\"<>|]", "_") + ".";
        for (File existing : LocalMusic.getFiles(context)) {
            if (existing.getName().startsWith(prefix)) file = existing;
        }
        for (int attempt = 1; file == null; attempt++) {
            AudioStream stream = OtherSource.bestAudio(match.url);
            file = LocalMusic.newImportFile(context, match.artist + " - " + match.title + "." + OtherSource.extensionOf(stream));
            try (OutputStream output = new FileOutputStream(file)) {
                OtherSource.download(stream.getContent(), output, value -> {
                });
                break;
            } catch (Exception ex) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                file = null;
                if (!(ex instanceof OtherSource.RefusedException) || attempt >= 3) throw ex;
            }
        }
        if (!LocalCovers.hasCover(file)) LocalCovers.save(file, match.coverUrl);
        LocalMusic.onFileAdded();
        result[0] = file;
        return "OK\t" + key + "\t" + match.title + "\t" + match.url + "\t" + file.getPath();
    }

    /** The title without the translation in brackets, compared by letters and digits only. */
    private static boolean titleMatches(String found, String wanted) {
        String a = normalize(found);
        String b = normalize(wanted.replaceAll("\\s*\\([^)]*\\)\\s*$", ""));
        return !b.isEmpty() && (a.contains(b) || b.contains(a) && !a.isEmpty());
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("[^\\p{L}\\p{N}]", "");
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
