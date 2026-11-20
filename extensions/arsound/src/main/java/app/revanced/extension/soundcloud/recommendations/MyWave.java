package app.revanced.extension.soundcloud.recommendations;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.local.LocalAdditions;
import app.revanced.extension.soundcloud.local.LocalMusic;

/**
 * "My Wave": a queue that follows the person's taste and does not end. It starts from the likes and what was heard to a
 * good length; when only a few tracks are left in the queue, the next batch is asked from the sources the person ticked
 * and added at the end, so the music goes on without a pause. A track queued once is never queued again in this wave;
 * a disliked one never comes, a skipped one rests a few days ({@link WaveLedger}, {@link WaveBuilder}, {@link WaveMixer}).
 * <p>
 * The wave is on while the playing track is one it queued: when the person plays something else the wave lets go.
 */
public final class MyWave {
    /**
     * The wave asks for more when this many tracks or fewer wait in the queue: a batch takes some ten seconds to find,
     * so it is asked for well before the last track.
     */
    private static final int REFILL_AT = 6;
    /** The first tracks, from the fastest source; every source joins with the batch asked for right after. */
    private static final int QUICK_BATCH = 8;
    /** Tracks picked ahead while the tab is open are played if the tap comes within this long. */
    private static final long PREPARED_FRESH_MS = 15 * 60 * 1000L;
    private static final int NEXT_BATCH = 12;
    /** The tracks that follow the seed of "My Wave by this track"; the desktop station queues about as many. */
    private static final int STATION_BATCH = 24;
    /** The tracks of this wave, for the "never again" rule and to see whether the playing one is still the wave's. */
    private static final Set<String> QUEUED = new LinkedHashSet<>();
    private static volatile boolean active;
    /** The wave's track that plays now, or null before the first one starts. */
    private static volatile String current;
    /** Whether the last wave was read from the phone ({@link WaveSession}); once, before the first look at the queue. */
    private static boolean restored;
    /** A track that is not the wave's does not end it before this time: the wave is starting. */
    private static volatile long holdUntil;
    /** How long after the start the player may still report the track it played before. */
    private static final long START_HOLD_MS = 3_000;
    /** A wave is being started: its first tracks are asked for. */
    private static volatile boolean building;
    /**
     * A batch is being asked for and will go to the end of the queue. Apart from {@link #building}, so a tap on "by this
     * track" is not refused while a batch of the wave that is playing is on its way.
     */
    private static volatile boolean topUp;
    /** Counts the waves started; a batch asked for an earlier wave is dropped when it comes back. */
    private static volatile int generation;
    /** Whether the wave being started was started from a track menu ({@link #startFromTrack}), not from the tab. */
    private static volatile boolean fromTrack;
    /** Whether a wave was playing when the start in progress began: a failed "by this track" gives it back. */
    private static volatile boolean activeBeforeStart;
    /**
     * Batches built in a row that brought nothing, and when the next one may be tried. Without the wait every change
     * of track asked every source again while they had nothing to give (no network, everything offered already heard).
     */
    private static int emptyBatches;
    private static volatile long nextBatchAt;
    private static final long EMPTY_BATCH_WAIT_MS = 30_000;
    private static final long EMPTY_BATCH_WAIT_MAX_MS = 10 * 60_000;
    private static volatile boolean preparing;
    /** What the last start ran into, for the tab; null when it went well. */
    private static volatile String problem;
    /** The first tracks picked ahead ({@link #prepare}), and when; guarded by {@link #QUEUED}. */
    private static List<String> prepared;
    private static long preparedAt;
    /** When tracks were last picked ahead; a try that found nothing waits {@link #PREPARE_RETRY_MS} before the next. */
    private static volatile long preparedTriedAt;
    private static final long PREPARE_RETRY_MS = 60 * 1000L;

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

    /** All the wave's tracks in their order. */
    public static List<String> queue() {
        restore();
        synchronized (QUEUED) {
            return new ArrayList<>(QUEUED);
        }
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
                // The restored tracks are known again by their title and length, so the song checks see them as before.
                for (Map.Entry<String, SongRef> song : WaveSession.songs().entrySet()) {
                    KnownTracks.remember(song.getKey(), song.getValue());
                }
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

    /** Adds "My Wave by this track" to the track menu. */
    public static void addTrackMenuRow(android.app.Dialog dialog, Object trackUrn) {
        WaveTrackRow.add(dialog, trackUrn);
    }

    /** Starts the wave: builds the first batch off the main thread and plays it. */
    public static void start(Context context) {
        begin(null);
    }

    /**
     * "My Wave by this track": starts the wave from one track. That track plays first, then the tracks the sources the
     * person ticked offer for it (like the desktop station by a track); the wave then goes on from there as usual.
     * Main thread; the work is done in the background.
     *
     * @param urn The track, as {@code soundcloud:tracks:ID} (a "sounds" urn is read as a track).
     */
    public static void startFromTrack(Object urn) {
        if (!TrackUrn.isTrack(urn)) return;
        if (building) {
            Utils.showToastShort(text("Волна ещё собирается, подождите секунду", "The wave is still being built, wait a second"));
            return;
        }
        Utils.showToastShort(text("Включаю «Мою волну» по этому треку…", "Starting My Wave by this track…"));
        begin(TrackUrn.normalize(urn));
    }

    /** @param seed The track the wave starts from, or null for the wave of the person's taste. */
    private static void begin(String seed) {
        if (building) return;
        fromTrack = seed != null;
        activeBeforeStart = active;
        building = true;
        active = true;
        holdUntil = Long.MAX_VALUE;
        generation++;
        problem = null;
        // The list of the last wave stays until the new one has tracks: a start that finds nothing keeps it on the tab.
        synchronized (QUEUED) {
            restored = true;
        }
        List<String> ready = seed == null ? takePrepared() : null;
        if (ready != null) {
            // Picked while the tab was open: the music starts at once.
            play(ready);
            return;
        }
        Utils.runOnBackgroundThread(() -> {
            try {
                List<String> entries = seed == null ? new WaveBuilder(new ArrayList<>()).buildQuick(QUICK_BATCH)
                        : stationEntries(seed);
                Utils.runOnMainThread(() -> play(entries));
            } catch (Exception ex) {
                Logger.printException(() -> "My Wave: could not start", ex);
                Utils.runOnMainThread(() -> fail(text("Волна не включилась: проверьте интернет",
                        "The wave did not start: check the connection")));
            }
        });
    }

    /** The track itself and then what the sources offer for it; empty when nothing similar was found. */
    private static List<String> stationEntries(String seed) throws Exception {
        List<String> similar = new WaveBuilder(new ArrayList<>()).buildStation(seed, STATION_BATCH);
        List<String> entries = new ArrayList<>();
        if (similar.isEmpty()) return entries;
        entries.add(seed);
        entries.addAll(similar);
        return entries;
    }

    /** Plays the first tracks, then at once looks for the rest with every source, while those play. */
    private static void play(List<String> entries) {
        if (entries.isEmpty()) {
            fail(fromTrack
                    ? text("К этому треку ничего похожего не нашлось: проверьте интернет и источники волны",
                    "Nothing similar to this track was found: check the connection and the wave's sources")
                    : text("Пока нечего предложить: полайкайте пару треков или включите источник",
                    "Nothing to offer yet: like a few tracks or tick a source"));
            return;
        }
        if (!LocalMusic.canPlay()) {
            fail(notReady());
            return;
        }
        takeOutAddedTracks();
        List<String> previous;
        String previousCurrent;
        synchronized (QUEUED) {
            previous = new ArrayList<>(QUEUED);
            previousCurrent = current;
            QUEUED.clear();
            QUEUED.addAll(entries);
            current = null;
        }
        persist();
        if (!LocalMusic.playTracks(entries)) {
            synchronized (QUEUED) {
                QUEUED.clear();
                QUEUED.addAll(previous);
                current = previousCurrent;
            }
            persist();
            fail(notReady());
            return;
        }
        holdUntil = System.currentTimeMillis() + START_HOLD_MS;
        building = false;
        extend(entries.get(0));
        Utils.runOnMainThreadDelayed(() -> logQueue("after start"), 2500);
    }

    /** What the player's queue holds against the wave's own list; for the log the user sends. */
    private static void logQueue(String when) {
        List<String> inQueue = AutoplayQueue.stayingUrns();
        // A set: every track of the queue is looked up in it, on the main thread.
        Set<String> own;
        synchronized (QUEUED) {
            own = new java.util.HashSet<>(QUEUED);
        }
        StringBuilder marks = new StringBuilder();
        for (String urn : inQueue) marks.append(own.contains(urn) ? 'W' : '.');
        int size = inQueue.size();
        int ownCount = own.size();
        int at = AutoplayQueue.position();
        Logger.printInfo(() -> "My Wave: " + when + ", the queue holds " + size + " tracks, the wave has " + ownCount
                + ", playing at " + at + ", the wave's places: " + marks);
    }

    /**
     * A new queue keeps what was put into the old one by hand ("added by you": the person's own picks and the batches an
     * earlier wave added) right after its first track, ahead of the new wave, so a swipe landed on those. A new wave
     * starts from a clean queue: they are taken out first.
     */
    private static void takeOutAddedTracks() {
        int removed = AutoplayQueue.removeAdded();
        Logger.printInfo(() -> "My Wave: " + removed + " tracks added to the queue earlier taken out");
    }

    private static String notReady() {
        return text("Плеер ещё не готов, попробуйте через секунду", "The player is not ready yet, try again in a second");
    }

    /**
     * Whether the wave is on after a start failed. A start from a track menu that found nothing leaves the wave that was
     * playing alone: its queue goes on playing and refilling. The start from the tab had no wave to leave.
     */
    static boolean activeAfterFailedStart(boolean fromTrack, boolean activeBeforeStart) {
        return fromTrack && activeBeforeStart;
    }

    private static void fail(String message) {
        active = activeAfterFailedStart(fromTrack, activeBeforeStart);
        building = false;
        holdUntil = 0;
        problem = message;
        // A wave started from a track menu has no tab open to show the problem on.
        if (fromTrack) Utils.showToastLong(message);
    }

    /** What went wrong at the last start, for the tab to show; null when it went well. */
    public static String problem() {
        return problem;
    }

    /**
     * Picks the first tracks of a wave ahead, off the main thread, while the tab is open, so a tap plays at once. Does
     * nothing while a wave plays or when tracks picked a short while ago are still waiting.
     */
    public static void prepare() {
        if (active || building || preparing) return;
        if (System.currentTimeMillis() - preparedTriedAt < PREPARE_RETRY_MS) return;
        synchronized (QUEUED) {
            if (prepared != null && System.currentTimeMillis() - preparedAt < PREPARED_FRESH_MS) return;
        }
        preparing = true;
        preparedTriedAt = System.currentTimeMillis();
        Utils.runOnBackgroundThread(() -> {
            try {
                List<String> entries = new WaveBuilder(new ArrayList<>()).buildQuick(QUICK_BATCH);
                if (entries.isEmpty()) return;
                synchronized (QUEUED) {
                    prepared = entries;
                    preparedAt = System.currentTimeMillis();
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "My Wave: nothing picked ahead: " + ex);
            } finally {
                preparing = false;
            }
        });
    }

    /** The tracks picked ahead, once, while they are fresh; null otherwise. */
    private static List<String> takePrepared() {
        synchronized (QUEUED) {
            List<String> ready = prepared != null && System.currentTimeMillis() - preparedAt < PREPARED_FRESH_MS ? prepared : null;
            prepared = null;
            if (ready == null) return null;
            // A track disliked or skipped since it was picked is left out.
            List<String> kept = new ArrayList<>();
            Set<String> disliked = TrackDislikes.getAll().keySet();
            for (String urn : ready) if (!disliked.contains(urn) && !WaveMemory.ledger().isResting(urn, System.currentTimeMillis())) kept.add(urn);
            return kept.isEmpty() ? null : kept;
        }
    }

    /** Asks every source for the next batch right away and puts it at the end of the queue. */
    private static void extend(String playing) {
        if (!active || building || topUp) return;
        buildNextBatch(playing);
    }

    /**
     * Builds the next batch in the background and puts it at the end of the queue. The caller checked {@link #building}
     * and {@link #topUp}.
     */
    private static void buildNextBatch(String playing) {
        topUp = true;
        int started = generation;
        Utils.runOnBackgroundThread(() -> {
            List<String> entries = null;
            try {
                Set<String> queued;
                synchronized (QUEUED) {
                    queued = new LinkedHashSet<>(QUEUED);
                }
                entries = new WaveBuilder(queued).build(playing, NEXT_BATCH);
                noteBatch(!entries.isEmpty());
            } catch (Exception ex) {
                noteBatch(false);
                Logger.printException(() -> "My Wave: could not build the next batch", ex);
            }
            List<String> batch = entries;
            Utils.runOnMainThread(() -> {
                topUp = false;
                if (batch != null) add(batch, started);
            });
        });
    }

    /** After a batch that brought nothing the next one waits: half a minute, then twice as long each time, up to ten minutes. */
    private static synchronized void noteBatch(boolean broughtTracks) {
        if (broughtTracks) {
            emptyBatches = 0;
            nextBatchAt = 0;
            return;
        }
        long wait = Math.min(EMPTY_BATCH_WAIT_MAX_MS, EMPTY_BATCH_WAIT_MS << Math.min(emptyBatches, 5));
        emptyBatches++;
        nextBatchAt = android.os.SystemClock.elapsedRealtime() + wait;
    }

    /** Called by {@code PlaybackEvents} when a new track starts. Adds the next batch if few tracks wait. */
    public static void onTrackChanged(String urn) {
        if (!active || urn == null) return;
        String key = TrackUrn.normalize(urn);
        synchronized (QUEUED) {
            // The person started something else: the wave lets go, a batch being built included (add looks at active).
            if (!QUEUED.contains(key)) {
                if (System.currentTimeMillis() >= holdUntil) active = false;
                return;
            }
            current = key;
        }
        persist();
        // The queue is read on the main thread: the hook runs on the player's thread.
        Utils.runOnMainThread(() -> refillIfLow(key));
    }

    private static void refillIfLow(String playing) {
        if (!active || building || topUp) return;
        if (android.os.SystemClock.elapsedRealtime() < nextBatchAt) return;
        int waiting = AutoplayQueue.upcomingCount();
        // When the player's queue cannot be read, the wave counts its own tracks after the playing one.
        if (waiting < 0) waiting = ownUpcoming(playing);
        if (waiting > REFILL_AT) return;
        buildNextBatch(playing);
    }

    /** How many of the wave's tracks come after the given one, by the wave's own list. */
    private static int ownUpcoming(String playing) {
        synchronized (QUEUED) {
            List<String> all = new ArrayList<>(QUEUED);
            int at = all.indexOf(playing);
            return at < 0 ? 0 : all.size() - at - 1;
        }
    }

    /**
     * Plays the wave from one of its tracks, the ones after it following, as a tap in a playlist does. A wave that is
     * over (the last one, kept on the phone) goes on from there: it is on again and looks for more as it plays.
     */
    public static void playFrom(String urn) {
        restore();
        List<String> from;
        synchronized (QUEUED) {
            List<String> all = new ArrayList<>(QUEUED);
            int at = all.indexOf(urn);
            if (at < 0) return;
            from = new ArrayList<>(all.subList(at, all.size()));
        }
        if (building) return;
        if (!LocalMusic.canPlay()) {
            problem = notReady();
            return;
        }
        boolean wasActive = active;
        active = true;
        problem = null;
        takeOutAddedTracks();
        if (!LocalMusic.playTracks(from)) {
            active = wasActive;
            problem = notReady();
            return;
        }
        current = urn;
        persist();
        if (from.size() - 1 <= REFILL_AT) extend(urn);
    }

    /** Adds a batch at the end of the queue. */
    private static void add(List<String> entries, int wave) {
        // A batch asked for before a new wave started belongs to the old one: it is dropped.
        if (!active || entries.isEmpty() || wave != generation) return;
        ClassLoader loader = AutoplayQueue.classLoader();
        if (loader == null) return;
        List<Object> urns = LocalAdditions.toUrns(loader, entries);
        if (AutoplayQueue.playLast(AutoplayQueue.WAVE_PAGE, urns)) {
            synchronized (QUEUED) {
                QUEUED.addAll(entries);
            }
            persist();
            Logger.printInfo(() -> "My Wave: " + entries.size() + " tracks added");
            logQueue("after adding");
        }
    }

}
