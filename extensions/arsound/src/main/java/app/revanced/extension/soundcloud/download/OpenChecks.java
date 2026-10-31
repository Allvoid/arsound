package app.revanced.extension.soundcloud.download;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;

/**
 * Whether the player can open the row of a file: a row of a file that is gone, or of an app that did not share it,
 * cannot. The answers are remembered for a short while, because playback asks again and again while a queue loads.
 */
final class OpenChecks {
    /** How long the answer of opening a file stays good. */
    static final long GOOD_FOR_MS = 10_000;

    /** When each row was last tried and what came of it: {time, 1 if it opened}. */
    private static final Map<String, long[]> openChecks = new ConcurrentHashMap<>();
    /** The rows that are being opened in the background right now, so a list that asks again does not queue the same check. */
    private static final Set<String> openChecking = ConcurrentHashMap.newKeySet();
    /** The rows of a location the listing could not reach, noted as unopenable until the location lists again. */
    private static final Set<String> unreachableRows = ConcurrentHashMap.newKeySet();

    private OpenChecks() {
    }

    /**
     * Whether the player can open the row. The main thread is never made to wait for the system: a row not asked about
     * before is taken for good there, and opened in the background to find out. A bad one is then found at the next try,
     * and the lists and the player go on streaming the track. Once the answer is there it holds on the main thread also
     * when it is old, until the background has a newer one: a file found missing does not become "yes" again only
     * because time has passed.
     */
    static boolean canOpen(Context context, Uri uri) {
        if (context == null) return false;
        String key = uri.toString();
        long now = SystemClock.elapsedRealtime();
        long[] known = openChecks.get(key);
        if (known != null && now - known[0] < GOOD_FOR_MS) return known[1] == 1;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Before the check starts: the check must not be overwritten by the guess when it is quick.
            if (known == null) openChecks.put(key, new long[]{now, 1});
            if (openChecking.add(key)) {
                Utils.runOnBackgroundThread(() -> {
                    try {
                        opens(context, uri);
                    } finally {
                        openChecking.remove(key);
                    }
                });
            }
            return known == null || known[1] == 1;
        }
        return opens(context, uri);
    }

    private static boolean opens(Context context, Uri uri) {
        boolean ok;
        try (AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            ok = descriptor != null;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Download index: cannot open " + uri + ": " + ex);
            ok = false;
            // The listing may be out of date: the file can have been removed in a file manager. Not more often than a
            // listing is made anyway: each failed file of a list asked for a full listing of the folder.
            DownloadIndex.refreshAsyncIfStale();
        }
        openChecks.put(uri.toString(), new long[]{SystemClock.elapsedRealtime(), ok ? 1 : 0});
        return ok;
    }

    /** The files cannot be reached (a card that is out): the first tap on one of them already streams the track. */
    static void noteUnreachable(String uri, long now) {
        unreachableRows.add(uri);
        openChecks.put(uri, new long[]{now, 0});
    }

    /** The location is back: a file that was taken for unreachable is asked about again. */
    static void noteReachable(String uri) {
        if (!unreachableRows.isEmpty() && unreachableRows.remove(uri)) {
            openChecks.remove(uri);
        }
    }
}
