package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;

/**
 * "My Wave": a queue that follows the person's taste and does not end. It starts from the likes and what was heard to a
 * good length; when only a few tracks are left in the queue, the next batch is asked from the sources the person ticked
 * and added at the end, so the music goes on without a pause. A track queued once is never queued again in this wave,
 * and neither are the tracks skipped in the first seconds or disliked ({@link WaveBuilder}, {@link WaveMixer}).
 * <p>
 * The wave is on while the playing track is one it queued: when the person plays something else the wave lets go.
 */
public final class MyWave {
    /** The wave asks for more when this many tracks or fewer wait in the queue. */
    private static final int REFILL_AT = 4;
    private static final int FIRST_BATCH = 15;
    private static final int NEXT_BATCH = 12;
    /** The tracks of this wave, for the "never again" rule and to see whether the playing one is still the wave's. */
    private static final Set<String> QUEUED = new LinkedHashSet<>();
    private static volatile boolean active;
    /** The wave's track that plays now, or null before the first one starts. */
    private static volatile String current;
    /** Whether the last wave was read from the phone ({@link WaveSession}); once, before the first look at the queue. */
    private static boolean restored;
    private static volatile boolean building;

    private MyWave() {
    }

    /** How many tracks the wave has queued since it was started. */
    public static int addedCount() {
        restore();
        synchronized (QUEUED) {
            return QUEUED.size();
        }
    }

    /**
     * A window of the wave's queue in its order: up to {@code before} tracks that played before the one playing now, that
     * one, and the next ones, {@code total} in all. From the start of the wave while none of its tracks played yet.
     */
    public static List<String> around(int before, int total) {
        restore();
        synchronized (QUEUED) {
            List<String> all = new ArrayList<>(QUEUED);
            int from = windowStart(current == null ? -1 : all.indexOf(current), all.size(), before, total);
            return new ArrayList<>(all.subList(from, Math.min(all.size(), from + total)));
        }
    }

    /**
     * Where a window of {@code total} places starts in a list of {@code size}, to show {@code before} places ahead of
     * {@code at} (-1: none) and as many after it as fit; it slides back from the end so it stays full.
     */
    static int windowStart(int at, int size, int before, int total) {
        return Math.max(0, Math.min(at - before, size - total));
    }

    /** The wave's track that plays now, or null. */
    public static String current() {
        return active ? current : null;
    }

    /** Whether the tracks known are those of a wave that is over (the last one, kept on the phone). */
    public static boolean isPast() {
        return !active && addedCount() > 0;
    }

    /** Puts the last wave, kept on the phone, back into the list of the wave's tracks; the wave stays off. */
    private static void restore() {
        synchronized (QUEUED) {
            if (restored) return;
            restored = true;
            if (active || !QUEUED.isEmpty()) return;
            try {
                QUEUED.addAll(WaveSession.queue());
                current = WaveSession.current();
            } catch (Exception ex) {
                Logger.printInfo(() -> "My Wave: the last wave was not put back: " + ex);
            }
        }
    }

    /** Keeps the wave's tracks and the one playing on the phone, for the tab after a restart. */
    private static void persist() {
        List<String> queue;
        synchronized (QUEUED) {
            queue = new ArrayList<>(QUEUED);
        }
        WaveSession.save(queue, current);
    }

    public static boolean isActive() {
        return active;
    }

    /** Starts the wave: builds the first batch off the main thread and plays it. */
    public static void start(Context context) {
        if (building) return;
        building = true;
        active = true;
        current = null;
        synchronized (QUEUED) {
            restored = true;
            QUEUED.clear();
        }
        toast(context, text("Включаю волну…", "Starting the wave…"));
        Utils.runOnBackgroundThread(() -> {
            try {
                List<String> entries = new WaveBuilder(new ArrayList<>()).build(null, FIRST_BATCH);
                Utils.runOnMainThread(() -> {
                    if (entries.isEmpty()) {
                        active = false;
                        toast(context, text("Пока нечего предложить: полайкайте пару треков или включите источник в настройках",
                                "Nothing to offer yet: like a few tracks or tick a source in the settings"));
                        return;
                    }
                    synchronized (QUEUED) {
                        QUEUED.addAll(entries);
                    }
                    persist();
                    if (!LocalMusic.playTracks(entries)) {
                        active = false;
                        toast(context, text("Плеер ещё не готов", "The player is not ready yet"));
                    }
                });
            } catch (Exception ex) {
                active = false;
                Logger.printException(() -> "My Wave: could not start", ex);
                Utils.runOnMainThread(() -> toast(context, text("Волна не включилась: проверьте интернет",
                        "The wave did not start: check the connection")));
            } finally {
                building = false;
            }
        });
    }

    public static void stop(Context context) {
        active = false;
        toast(context, text("Волна остановлена", "The wave is stopped"));
    }

    /** Called by {@code PlaybackEvents} when a new track starts. Adds the next batch if few tracks wait. */
    public static void onTrackChanged(String urn) {
        if (!active || urn == null) return;
        String key = TrackUrn.normalize(urn);
        synchronized (QUEUED) {
            // The person started something else: the wave lets go.
            if (!QUEUED.contains(key)) {
                if (!building) active = false;
                return;
            }
            current = key;
        }
        persist();
        // The queue is read on the main thread: the hook runs on the player's thread.
        Utils.runOnMainThread(() -> refillIfLow(key));
    }

    private static void refillIfLow(String playing) {
        if (!active || building) return;
        int waiting = AutoplayQueue.upcomingCount();
        if (waiting < 0 || waiting > REFILL_AT) return;
        building = true;
        Utils.runOnBackgroundThread(() -> {
            try {
                Set<String> queued;
                synchronized (QUEUED) {
                    queued = new LinkedHashSet<>(QUEUED);
                }
                List<String> entries = new WaveBuilder(queued).build(playing, NEXT_BATCH);
                Utils.runOnMainThread(() -> add(entries));
            } catch (Exception ex) {
                Logger.printException(() -> "My Wave: could not build the next batch", ex);
            } finally {
                building = false;
            }
        });
    }

    /** Adds a batch at the end of the queue. */
    private static void add(List<String> entries) {
        if (!active || entries.isEmpty()) return;
        ClassLoader loader = AutoplayQueue.classLoader();
        if (loader == null) return;
        List<Object> urns = LocalAdditions.toUrns(loader, entries);
        if (AutoplayQueue.playLast("arsound:wave", urns)) {
            synchronized (QUEUED) {
                QUEUED.addAll(entries);
            }
            persist();
            Logger.printInfo(() -> "My Wave: " + entries.size() + " tracks added");
        }
    }

    private static void toast(Context context, String message) {
        if (context != null) Toast.makeText(context.getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }
}
