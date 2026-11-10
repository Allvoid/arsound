package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.widget.Toast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.download.DownloadPlaylistPatch;
import app.revanced.extension.soundcloud.local.SoundCloudLikes;

/**
 * "Like all in the list" of the "My Wave" tab: likes the tracks the list holds at the moment of the tap, one at a time
 * with a pause, from the last to the first, as the transfer from other services does. Tracks already liked are left
 * alone. One run at a time; the work is off the main thread and survives leaving the page. The end says how many of
 * the tracks are in the likes, asked from the server, and says so when SoundCloud refused.
 */
final class WaveLikes {
    private static final long PAUSE_MS = 250;
    private static final long FAILURE_PAUSE_MS = 3_000;
    /** SoundCloud refused this many likes in a row: it stopped taking them. */
    private static final int FAILURES_IN_ROW = 5;

    private static volatile boolean running;
    private static volatile int total;
    private static volatile int finished;

    private WaveLikes() {
    }

    static boolean isRunning() {
        return running;
    }

    /** How far the run is, 0 to 1, or -1 when none runs. */
    static float progress() {
        return running ? WaveBatch.fraction(finished, total) : -1f;
    }

    /** @return False when a run is already going on. */
    static synchronized boolean start(Context context, List<String> urns) {
        if (running) return false;
        Context app = context.getApplicationContext();
        total = urns.size();
        finished = 0;
        running = true;
        Utils.runOnBackgroundThread(() -> {
            try {
                run(app, urns);
            } catch (Exception ex) {
                Logger.printException(() -> "My Wave: liking the list failed", ex);
                toast(app, text("Лайки не поставились", "The likes were not given"));
            } finally {
                running = false;
            }
        });
        return true;
    }

    private static void run(Context context, List<String> urns) throws Exception {
        if (!SoundCloudLikes.isReady()) {
            toast(context, text("SoundCloud ещё не готов: откройте библиотеку и попробуйте снова",
                    "SoundCloud is not ready yet: open the library and try again"));
            return;
        }
        Set<String> already = SoundCloudLikes.alreadyLiked(urns);
        if (already == null) {
            toast(context, text("Лайки ещё не прочитаны: откройте библиотеку и попробуйте снова",
                    "The likes are not read yet: open the library and try again"));
            return;
        }
        List<String> plan = WaveBatch.likePlan(urns, already);
        finished = urns.size() - plan.size();
        int liked = finished;
        int failures = 0;
        boolean stopped = false;
        Logger.printInfo(() -> "My Wave: liking " + plan.size() + " of " + urns.size() + " tracks");
        for (String urn : plan) {
            Throwable failure = SoundCloudLikes.like(urn);
            if (failure == null) {
                failures = 0;
                liked++;
                finished++;
                Thread.sleep(PAUSE_MS);
                continue;
            }
            Logger.printInfo(() -> "My Wave: could not like " + urn + ": " + failure);
            finished++;
            if (++failures >= FAILURES_IN_ROW) {
                stopped = true;
                break;
            }
            Thread.sleep(FAILURE_PAUSE_MS);
        }
        int inLikes = onServer(urns, liked);
        String summary = WaveBatch.likedSummary(inLikes, urns.size(), stopped);
        Logger.printInfo(() -> "My Wave: " + summary);
        toast(context, summary);
    }

    /** How many of the tracks the server has in the likes; the phone's own count when the server cannot be asked. */
    private static int onServer(List<String> urns, int given) {
        List<String> ids = DownloadPlaylistPatch.serverLikedTrackIds();
        if (ids == null) return given;
        Set<String> have = new HashSet<>(ids);
        int count = 0;
        for (String urn : urns) if (have.contains(WaveBatch.trackId(urn))) count++;
        return count;
    }

    private static void toast(Context context, String message) {
        Utils.runOnMainThread(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
}
