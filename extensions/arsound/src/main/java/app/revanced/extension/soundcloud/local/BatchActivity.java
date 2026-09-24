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
import app.revanced.extension.soundcloud.download.ClientProfiles;
import app.revanced.extension.soundcloud.search.OtherSource;

/**
 * Downloads a list of tracks through the Arsound search, for filling a playlist from a computer:
 * <pre>
 * adb push list.txt /sdcard/Android/data/PACKAGE/files/batch/list.txt
 * adb shell am start -n PACKAGE/app.revanced.extension.soundcloud.local.BatchActivity --es file list.txt --es playlist "Title"
 * </pre>
 * Each line of the list is {@code artist<TAB>title}. Only a result by the same artist with a matching
 * title is downloaded. Progress goes to {@code list.txt.report}; lines already reported as done are
 * skipped, so a run can be repeated; {@code --ez refresh true} searches them again, keeping the earlier
 * result if the new search fails. With a playlist title, the playlist gets the files in list order
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
        // Searches every line again (SoundCloud first) instead of keeping what earlier runs found.
        boolean refresh = getIntent().getBooleanExtra("refresh", false);
        Context context = getApplicationContext();
        // Started over adb, the app may have no screen yet: the stores of playlists and covers need the context.
        if (Utils.getContext() == null) Utils.setContext(context);
        File folder = context.getExternalFilesDir("batch");
        if (name == null || folder == null || name.contains("/") || running) return;
        File list = new File(folder, name);
        if (!list.isFile()) return;
        running = true;
        // Android cuts the network of an app in the background: the app comes to the screen first.
        android.content.Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) startActivity(launch);
        Toast.makeText(context, "Arsound: batch " + name, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                run(context, list, new File(folder, name + ".report"), playlistTitle, refresh);
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

    private static void run(Context context, File list, File reportFile, String playlistTitle, boolean refresh)
            throws Exception {
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

        // Lines done in an earlier run, with the playlist entry they got: a SoundCloud track or a file.
        Map<String, String> done = new HashMap<>();
        // Every entry any run put into the playlist: the ones this run does not keep are dropped from it.
        java.util.Set<String> batchEntries = new java.util.HashSet<>();
        if (reportFile.isFile()) {
            for (String line : readLines(reportFile)) {
                String[] parts = line.split("\t");
                if (parts[0].equals("OK") && parts.length >= 5) {
                    done.put(parts[1], parts[4]);
                    String entry = entryOf(parts[4]);
                    if (entry != null) batchEntries.add(entry);
                }
            }
        }
        List<String> ordered = new ArrayList<>();

        try (Writer report = new OutputStreamWriter(new FileOutputStream(reportFile, true), StandardCharsets.UTF_8)) {
            for (String line : readLines(list)) {
                String[] parts = line.split("\t");
                if (parts.length < 2) continue;
                String key = line.trim().replace('\t', ' ');
                String earlier = entryOf(done.get(key));
                // A file without a cover goes through the search again to get one; it is not downloaded twice.
                if (earlier != null && !refresh && isComplete(earlier)) {
                    ordered.add(earlier);
                    continue;
                }
                String artist = parts[0].trim();
                String title = parts[1].trim();
                String result;
                Logger.printInfo(() -> "Batch: " + key);
                String[] entry = new String[1];
                try {
                    // A DNS lookup on a broken network ignores connection timeouts and can hang for good:
                    // one track gets a hard limit, then the batch goes on.
                    java.util.concurrent.Future<String> task = worker.submit(() -> findOne(context, artist, title, entry));
                    try {
                        result = task.get(TRACK_LIMIT_MINUTES, java.util.concurrent.TimeUnit.MINUTES);
                    } catch (java.util.concurrent.TimeoutException ex) {
                        task.cancel(true);
                        worker.shutdownNow();
                        worker = java.util.concurrent.Executors.newSingleThreadExecutor();
                        entry[0] = null;
                        throw new java.io.IOException("No answer in " + TRACK_LIMIT_MINUTES + " minutes");
                    } catch (java.util.concurrent.ExecutionException ex) {
                        throw ex.getCause();
                    }
                } catch (Throwable ex) {
                    Logger.printInfo(() -> "Batch: could not get " + key + ": " + ex);
                    result = "FAIL\t" + key + "\t" + ex;
                }
                // An entry of an earlier run keeps its place even if this run could not reach the network.
                // A search that found nothing drops it: the earlier match may have been a wrong one.
                if (entry[0] == null && earlier != null && result.startsWith("FAIL")) {
                    entry[0] = earlier;
                    if (!result.startsWith("OK")) result = "OK\t" + key + "\tkept\tearlier\t" + earlier;
                }
                if (entry[0] != null) ordered.add(entry[0]);
                report.write(result + "\n");
                report.flush();
            }
            report.write("END\n");
        }
        if (playlistUrn != null && !SavedPlaylist.isSavedPlaylist(playlistUrn)) {
            // The playlist follows the list: its tracks first in list order, entries added by hand after them.
            // Entries of earlier runs that this run replaced (a file by a SoundCloud track) are dropped.
            List<String> entries = new ArrayList<>(ordered);
            for (String old : LocalAdditions.getEntries(playlistUrn)) {
                if (!entries.contains(old) && !batchEntries.contains(old)) entries.add(old);
            }
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

    /** The playlist entry of a report value: a SoundCloud urn as is, a file path as a file entry. */
    private static String entryOf(String value) {
        if (value == null) return null;
        if (value.startsWith("soundcloud:")) return value;
        File file = new File(value);
        return file.isFile() ? LocalAdditions.fileEntry(file) : null;
    }

    /** A SoundCloud track needs nothing more; a file still needs its cover. */
    private static boolean isComplete(String entry) {
        if (entry.startsWith("soundcloud:")) return true;
        return LocalCovers.hasCover(new File(entry.substring("file:".length())));
    }

    /**
     * Finds one track: the original on SoundCloud first (often uncensored, and more tracks are there),
     * then YouTube Music, then the imported files. The playlist entry goes to {@code entry[0]}.
     */
    private static String findOne(Context context, String artist, String title, String[] entry) throws Exception {
        String key = artist + " " + title;
        ClientProfiles.FoundTrack snippet = null;
        try {
            for (ClientProfiles.FoundTrack track : ClientProfiles.searchTracks(artist + " " + title)) {
                boolean byArtist = normalize(track.user).contains(normalize(artist))
                        || normalize(track.title).contains(normalize(artist));
                if (!byArtist || !track.isFull() || !holdsTitle(stripArtist(track.title, artist), title)
                        || isVariant(track.title, title)) continue;
                if (isSnippet(track.title, title)) {
                    if (snippet == null) snippet = track;
                    continue;
                }
                entry[0] = "soundcloud:tracks:" + track.id;
                return "OK\t" + key + "\t" + track.title + "\tsoundcloud\t" + entry[0];
            }
        } catch (Exception ex) {
            // YouTube Music is still worth asking.
            Logger.printInfo(() -> "Batch: SoundCloud search failed for " + key + ": " + ex);
        }
        File[] file = new File[1];
        String result = downloadOne(context, artist, title, file);
        if (file[0] != null) {
            entry[0] = LocalAdditions.fileEntry(file[0]);
            return result;
        }
        // A snippet only when the full track is nowhere: better than nothing for unreleased songs.
        if (snippet != null) {
            entry[0] = "soundcloud:tracks:" + snippet.id;
            return "OK\t" + key + "\t" + snippet.title + "\tsoundcloud\t" + entry[0];
        }
        return result;
    }

    private static final String[] VARIANTS = {"speed up", "sped up", "speedup", "slowed", "reverb", "remix",
            "nightcore", "cover", "karaoke", "instrumental", "минус", "ускор", "замедл", "ремикс", "кавер"};

    private static final String[] SNIPPETS = {"snippet", "сниппет", "отрывок", "preview", "teaser", "тизер"};

    /** A short piece of a track, unless the wanted title names it. */
    private static boolean isSnippet(String found, String wanted) {
        String lowerFound = found.toLowerCase(Locale.ROOT);
        String lowerWanted = wanted.toLowerCase(Locale.ROOT);
        for (String word : SNIPPETS) {
            if (lowerFound.contains(word) && !lowerWanted.contains(word)) return true;
        }
        return false;
    }

    /** A sped-up, slowed, remixed or covered version, unless the wanted title names it. */
    private static boolean isVariant(String found, String wanted) {
        String lowerFound = found.toLowerCase(Locale.ROOT);
        String lowerWanted = wanted.toLowerCase(Locale.ROOT);
        for (String variant : VARIANTS) {
            if (lowerFound.contains(variant) && !lowerWanted.contains(variant)) return true;
        }
        return false;
    }

    /** "CUPSIZE - title" and "title" name the same track. */
    private static String stripArtist(String found, String artist) {
        String lower = found.toLowerCase(Locale.ROOT);
        String prefix = artist.toLowerCase(Locale.ROOT);
        if (lower.startsWith(prefix)) return found.substring(prefix.length()).replaceFirst("^\\s*[-–—:]\\s*", "");
        return found;
    }

    /** The found title holds the whole wanted one: "вата" must not stand for "сахарная вата". */
    private static boolean holdsTitle(String found, String wanted) {
        String b = normalize(wanted.replaceAll("\\s*\\([^)]*\\)\\s*$", ""));
        return !b.isEmpty() && normalize(found).contains(b);
    }

    /** Downloads one track, or finds it among the imported files; the file goes to {@code result[0]}. */
    private static String downloadOne(Context context, String artist, String title, File[] result) throws Exception {
        String key = artist + " " + title;
        OtherSource.Track match = null;
        for (OtherSource.Track track : OtherSource.search(artist + " " + title)) {
            if (normalize(track.artist).contains(normalize(artist)) && holdsTitle(track.title, title) && !isVariant(track.title, title)) {
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
