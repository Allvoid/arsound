package app.revanced.extension.soundcloud.debug;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import app.revanced.extension.shared.Utils;
import app.revanced.extension.shared.debug.PlaybackLog;
import timber.log.Timber;

/**
 * Personal build only: measures how long a track takes to start after a tap and says why.
 * <p>
 * SoundCloud logs every step of playback through Timber, but the release build plants no tree, so the
 * lines are thrown away. This tree catches the playback lines, writes them to {@link PlaybackLog} and
 * builds a summary for every start:
 * <ul>
 *   <li>action: the tap (next, previous, a track in a list) or the end of the previous track;</li>
 *   <li>player: SoundCloud gave the track to ExoPlayer, so everything before it is SoundCloud's own
 *       preparation (queue, metadata, stream link);</li>
 *   <li>sound: ExoPlayer is ready and plays, so the time after "player" is the stream download.</li>
 * </ul>
 * A pause in the middle of a track, waiting for data, is logged as a stall with its length.
 */
@SuppressWarnings("unused")
public final class PlaybackTimeline extends Timber.Tree {
    /** Starts slower than this are marked as slow in the log. */
    private static final long SLOW_MS = 1_000;

    private static final Set<String> TAGS = new HashSet<>(Arrays.asList(
            "ExoPlayerAdapter", "ExoPlayerPreloader", "PlaybackManager", "MediaService", "LocalPlayback",
            "StreamSelector", "StreamPlayer", "QueueManager", "PlayQueueManager", "PlaybackMediaProvider",
            "MediaController", "MediaControllerCallback", "CastPlayback"));

    private static final int WARN = 5;

    private static boolean planted;

    // The state of the start being measured. Guarded by the class lock: Timber is called from many threads.
    private static long actionAt;
    private static String action;
    private static long playerAt;
    private static String item;
    private static boolean waiting;
    private static int errors;
    private static String lastError;
    private static boolean playing;
    private static long stallAt;

    private PlaybackTimeline() {
    }

    /** Injection point. Called at the start of the application. */
    public static void plant() {
        if (planted) return;
        planted = true;
        try {
            Class<?> timber = Class.forName("timber.log.Timber");
            Object forest = null;
            for (Field field : timber.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && field.getType().getName().equals("timber.log.Timber$Forest")) {
                    field.setAccessible(true);
                    forest = field.get(null);
                    break;
                }
            }
            if (forest == null) throw new IllegalStateException("No Timber.Forest");

            for (Method method : forest.getClass().getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == Timber.Tree.class) {
                    method.setAccessible(true);
                    method.invoke(forest, new PlaybackTimeline());
                    PlaybackLog.append("timeline", "---- app started, playback log on ----");
                    return;
                }
            }
            throw new IllegalStateException("No Timber.Forest.plant");
        } catch (Exception ex) {
            PlaybackLog.append("timeline", "Could not plant the Timber tree: " + ex);
        }
    }

    @Override
    public void i(String tag, int priority, String message, Throwable throwable) {
        try {
            boolean wanted = tag != null && TAGS.contains(tag);
            if (!wanted && priority < WARN) return;

            PlaybackLog.append((tag == null ? "?" : tag) + "/" + level(priority), message);
            if (wanted) track(tag, priority, message);
        } catch (Exception ignored) {
            // Logging must never break playback.
        }
    }

    private static synchronized void track(String tag, int priority, String message) {
        long now = SystemClock.elapsedRealtime();

        if (priority >= WARN && waiting) {
            errors++;
            lastError = message.length() > 200 ? message.substring(0, 200) : message;
        }

        if (tag.equals("PlaybackManager")) {
            String name = actionName(message);
            if (name != null) {
                if (waiting) {
                    PlaybackLog.append("timeline", "!! previous start (" + action + ") abandoned after "
                            + (now - actionAt) + " ms without sound, new action " + name);
                }
                startWaiting(now, name);
                PlaybackLog.append("timeline", ">> ACTION " + name + " | " + network());
            }
            return;
        }

        if (!tag.equals("ExoPlayerAdapter")) return;

        if (message.startsWith("play(") && !message.startsWith("play()")) {
            if (!waiting) startWaiting(now, "no tap (auto)");
            playerAt = now;
            item = message;
            PlaybackLog.append("timeline", ">> PLAYER got track " + (now - actionAt) + " ms after " + action);
            return;
        }

        if (!message.startsWith("onPlayerStateChanged(")) return;
        boolean playWhenReady = message.startsWith("onPlayerStateChanged(true");
        boolean ready = message.contains("STATE_READY");
        boolean buffering = message.contains("STATE_BUFFERING");

        if (waiting && ready && playWhenReady) {
            summarize(now);
            waiting = false;
            playing = true;
            return;
        }

        if (!waiting && playing && buffering && playWhenReady && stallAt == 0) {
            stallAt = now;
            PlaybackLog.append("timeline", "!! STALL: waiting for data mid-track | " + network());
        } else if (stallAt != 0 && ready) {
            PlaybackLog.append("timeline", "!! STALL ended after " + (now - stallAt) + " ms | " + network());
            stallAt = 0;
        }
        if (!playWhenReady || message.contains("STATE_IDLE") || message.contains("STATE_ENDED")) {
            playing = false;
            stallAt = 0;
        }
    }

    private static void startWaiting(long now, String name) {
        actionAt = now;
        action = name;
        playerAt = 0;
        item = null;
        waiting = true;
        errors = 0;
        lastError = null;
        playing = false;
        stallAt = 0;
    }

    private static void summarize(long now) {
        long total = now - actionAt;
        long prepare = playerAt == 0 ? total : playerAt - actionAt;
        long download = playerAt == 0 ? 0 : now - playerAt;
        String network = network();

        StringBuilder line = new StringBuilder(total >= SLOW_MS ? "== SLOW START " : "== START ");
        line.append(total).append(" ms after ").append(action)
                .append(" | SoundCloud prep ").append(prepare).append(" ms, stream to sound ").append(download).append(" ms")
                .append(" | ").append(network);
        if (item != null) line.append(" | ").append(item);
        if (errors > 0) line.append(" | errors ").append(errors).append(", last: ").append(lastError);
        if (total >= SLOW_MS) line.append(" | CAUSE: ").append(cause(prepare, download, network));
        PlaybackLog.append("timeline", line.toString());
    }

    private static String cause(long prepare, long download, String network) {
        if (errors > 0) return "playback errors and retries before the sound (see the lines above)";
        if (network.startsWith("no network")) return "no network at the moment of the start";
        if (network.contains("validated=false")) return "network connected but without working internet";
        boolean preloaded = item != null && item.contains("preloaded: true");
        if (prepare >= download) {
            return "SoundCloud prepared the track slowly before giving it to the player "
                    + "(queue, track metadata or stream link request)";
        }
        return preloaded
                ? "stream start downloaded slowly even though the track was preloaded"
                : "stream start downloaded slowly, the track was not preloaded";
    }

    private static String actionName(String message) {
        String text = message.trim();
        if (text.startsWith("onSkipToNext")) return "next";
        if (text.startsWith("onSkipToPrevious")) return "previous";
        if (text.startsWith("onSkipToQueueItem")) return "queue item";
        if (text.startsWith("onPlayFromMediaId")) return "track tapped";
        if (text.startsWith("onPlayFromSearch")) return "play from search";
        if (text.startsWith("onPlayFromPosition")) return "play from position";
        if (text.equals("onPlay()")) return "play";
        if (text.startsWith("onSeekTo")) return "seek";
        if (text.startsWith("onCompletion")) return "track ended (auto next)";
        return null;
    }

    private static String network() {
        try {
            Context context = Utils.getContext();
            if (context == null) return "network unknown";
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network active = manager.getActiveNetwork();
            NetworkCapabilities caps = active == null ? null : manager.getNetworkCapabilities(active);
            if (caps == null) return "no network";

            StringBuilder text = new StringBuilder();
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) text.append("wifi");
            else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) text.append("cellular");
            else text.append("other");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) text.append("+vpn");
            text.append(" validated=").append(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            text.append(" down=").append(caps.getLinkDownstreamBandwidthKbps()).append("kbps");
            return text.toString();
        } catch (Exception ex) {
            return "network unknown";
        }
    }

    private static String level(int priority) {
        switch (priority) {
            case 2: return "V";
            case 3: return "D";
            case 4: return "I";
            case 5: return "W";
            case 6: return "E";
            default: return String.valueOf(priority);
        }
    }
}
