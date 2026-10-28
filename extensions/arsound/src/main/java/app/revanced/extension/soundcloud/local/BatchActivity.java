package app.revanced.extension.soundcloud.local;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.ClientProfiles;

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
public final class BatchActivity extends LocalActivity {
    private static volatile boolean running;

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
        Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (launch != null) startActivity(launch);
        Toast.makeText(context, "Arsound: batch " + name, Toast.LENGTH_SHORT).show();
        File reportFile = new File(folder, name + ".report");
        new Thread(() -> {
            try {
                new BatchRunner(context, list, reportFile, playlistTitle, refresh).run();
            } catch (Throwable ex) {
                Logger.printException(() -> "Batch failed", ex);
                writeError(reportFile, ex);
            } finally {
                running = false;
            }
        }).start();
    }

    private static void writeError(File reportFile, Throwable ex) {
        try (Writer report = new OutputStreamWriter(new FileOutputStream(reportFile, true), StandardCharsets.UTF_8)) {
            report.write("ERROR\t" + Log.getStackTraceString(ex) + "\n");
        } catch (Exception ignored) {
        }
    }

    /**
     * The full original of a track on SoundCloud: by the artist, with the wanted title, not a snippet or
     * another version. Null if the search has none. Blocks: call it off the main thread.
     */
    public static ClientProfiles.FoundTrack findOnSoundCloud(String artist, String title) throws Exception {
        return TrackFinder.findOnSoundCloud(artist, title);
    }
}
