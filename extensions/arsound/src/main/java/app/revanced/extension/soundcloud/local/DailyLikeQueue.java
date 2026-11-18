package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.AtomicFiles;

/**
 * The likes of transferred tracks, {@link LikeQueueState#PER_DAY} a day. SoundCloud takes about fifty likes a day and,
 * when more are given, bars the account from liking for a day, so a transfer that was asked to like everything puts
 * the tracks in this queue instead of liking them at once: today's fifty are given, the rest wait for the next days.
 * The queue is kept in a file after every like, so a restart or a phone that ended the app goes on where it stopped.
 * <p>
 * Who gives the likes: the transfer, for today's fifty; after that {@link #tick} on its own, when the app has made
 * SoundCloud's like service ({@link SoundCloudLikes#setToggler}), and from a timer that fires after midnight and every
 * hour while the app lives and the queue waits. SoundCloud's like service lives in the app's process, so the likes go
 * only while the app has been started that day; a phone on which the app was not opened gives them on its next start.
 * <p>
 * While the day's likes are all given, a heart tapped by hand shows a short notice ({@link #onManualLike}): the limit of
 * the account is used up by the transfer, and a like of the user's own may not be kept.
 */
public final class DailyLikeQueue {
    private static final String FILE = "arsound-transfer/like-queue.txt";
    private static final long LIKE_PAUSE_MS = 1_000;
    private static final long FAILURE_PAUSE_MS = 3_000;
    /** This many failed likes in a row end the run: the server does not take likes now. */
    private static final int FAILURES_IN_ROW = 6;
    private static final long RETRY_MS = 60L * 60 * 1000;
    private static final long NOTICE_GAP_MS = 60_000;

    private static final Object LOCK = new Object();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    /** The tracks being liked by the queue right now, so a like of the user's own is told from them. */
    private static final Set<String> OWN = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static LikeQueueState state;
    private static boolean timerSet;
    private static long noticeAt;

    private DailyLikeQueue() {
    }

    // region Queue

    /**
     * Queues the tracks of a transfer, last to first. Tracks already liked are left out.
     *
     * @return How many were queued.
     */
    public static int enqueue(List<String> inPlaylistOrder, Set<String> alreadyLiked) {
        int count;
        synchronized (LOCK) {
            LikeQueueState current = load();
            count = current.add(inPlaylistOrder, alreadyLiked == null ? new HashSet<>() : alreadyLiked);
            save();
        }
        schedule();
        Logger.printInfo(() -> "Like queue: " + inPlaylistOrder.size() + " tracks offered, " + count + " queued");
        return count;
    }

    /** Whether tracks wait for their likes. */
    public static boolean hasQueue() {
        synchronized (LOCK) {
            return !load().isEmpty();
        }
    }

    /** The queue is dropped: the user does not want the rest of the likes. */
    public static void clear() {
        synchronized (LOCK) {
            load().clear();
            save();
        }
    }

    /**
     * One line about the queue for the screen and the notification, or null when none waits:
     * "Liked 120 of 430, the next 50 tomorrow".
     */
    public static String progressLine() {
        synchronized (LOCK) {
            LikeQueueState current = load();
            if (current.isEmpty()) return null;
            String day = today();
            String head = text("Пролайкано ", "Liked ") + current.done() + text(" из ", " of ") + current.total();
            int left = current.remaining();
            if (current.dayUsed(day)) {
                int next = Math.min(LikeQueueState.PER_DAY, left);
                return head + text(", следующие " + next + " — завтра", ", the next " + next + " tomorrow");
            }
            int now = Math.min(current.allowance(day), left);
            return head + text(", сегодня ещё " + now, ", " + now + " more today");
        }
    }

    // endregion

    // region Giving the likes

    /**
     * Likes what today still allows, one at a time with a pause. Blocks: off the main thread. One run at a time: a call
     * made while another runs returns 0.
     *
     * @param progress Told how many likes this run has given so far; may be null.
     * @return How many likes this run gave.
     * @throws InterruptedException If the thread was interrupted: the queue keeps what is left.
     */
    public static int runBatch(IntConsumer progress) throws InterruptedException {
        if (!SoundCloudLikes.isReady() || !RUNNING.compareAndSet(false, true)) return 0;
        int given = 0;
        try {
            int failures = 0;
            Set<String> failedOnce = new HashSet<>();
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String urn;
                synchronized (LOCK) {
                    LikeQueueState current = load();
                    if (current.isEmpty() || current.allowance(today()) == 0) break;
                    urn = current.peek();
                }
                Set<String> liked = SoundCloudLikes.alreadyLiked(Collections.singletonList(urn));
                if (liked == null) break;
                if (liked.contains(urn)) {
                    synchronized (LOCK) {
                        load().skipped();
                        save();
                    }
                    continue;
                }
                OWN.add(urn);
                Throwable failure;
                try {
                    failure = SoundCloudLikes.like(urn);
                } finally {
                    // A moment of grace: the hook that tells the likes apart runs a little after the call.
                    HANDLER.postDelayed(() -> OWN.remove(urn), 15_000);
                }
                if (failure == null) {
                    failures = 0;
                    given++;
                    synchronized (LOCK) {
                        load().liked(today());
                        save();
                    }
                    if (progress != null) progress.accept(given);
                    Thread.sleep(LIKE_PAUSE_MS);
                    continue;
                }
                Logger.printInfo(() -> "Like queue: could not like " + urn + ": " + failure);
                synchronized (LOCK) {
                    // A track that fails twice in one run is not liked at all: gone from SoundCloud, or not likeable.
                    if (!failedOnce.add(urn)) load().skipped();
                    else load().postponed();
                    save();
                }
                if (++failures >= FAILURES_IN_ROW) break;
                Thread.sleep(FAILURE_PAUSE_MS);
            }
        } catch (InterruptedException ex) {
            throw ex;
        } catch (Exception ex) {
            Logger.printException(() -> "Like queue: the run stopped", ex);
        } finally {
            RUNNING.set(false);
        }
        int total = given;
        Logger.printInfo(() -> "Like queue: this run gave " + total + " likes");
        return given;
    }

    /** Called when SoundCloud's like service is ready (and by the timer): gives what today allows, off the main thread. */
    public static void tick() {
        if (!hasQueue()) return;
        schedule();
        Utils.runOnBackgroundThread(() -> {
            try {
                runBatch(null);
            } catch (InterruptedException ignored) {
                // The queue keeps what is left.
            }
        });
    }

    /** The like service of the app was made ({@link SoundCloudLikes#setToggler}): a day that is due gets its likes. */
    static void onLikeServiceReady() {
        // Not at once: the app is starting, and the likes of the library come first.
        HANDLER.postDelayed(DailyLikeQueue::tick, 20_000);
    }

    /** The timer: after midnight, and every hour while likes wait (a run may stop on a refusal). */
    private static void schedule() {
        synchronized (LOCK) {
            if (timerSet) return;
            timerSet = true;
        }
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        long midnight = LikeQueueState.nextDayStart(now, zone) + 60_000 + (long) (Math.random() * 120_000);
        long wait = Math.min(Math.max(1_000, midnight - now), RETRY_MS);
        HANDLER.postDelayed(() -> {
            synchronized (LOCK) {
                timerSet = false;
            }
            tick();
        }, wait);
    }

    // endregion

    // region Likes by hand

    /**
     * Whether the track is being liked by the queue now (as opposed to a heart the user tapped).
     */
    static boolean isOwnLike(String urn) {
        return OWN.contains(urn);
    }

    /**
     * Injection point of {@link LikeLimit#onToggle}: a like was given by hand. While the queue waits and today's likes
     * are all given, a short notice says why the like may not be kept. Never throws.
     */
    static void onManualLike(String urn) {
        try {
            if (isOwnLike(urn)) return;
            long now = System.currentTimeMillis();
            synchronized (LOCK) {
                if (!load().dayUsed(today()) || now - noticeAt < NOTICE_GAP_MS) return;
                noticeAt = now;
            }
            Context context = Utils.getContext();
            if (context == null) return;
            Utils.runOnMainThread(() -> Toast.makeText(context,
                    text("Дневной лимит лайков занят переносом треков: ваш лайк может не сохраниться. Перенесённые треки "
                                    + "лайкаются по " + LikeQueueState.PER_DAY + " в день.",
                            "The daily like limit is used by the track transfer: your like may not be kept. Transferred "
                                    + "tracks are liked " + LikeQueueState.PER_DAY + " a day."),
                    Toast.LENGTH_LONG).show());
        } catch (Throwable ex) {
            Logger.printException(() -> "Like queue: could not tell of the limit", ex);
        }
    }

    // endregion

    private static String today() {
        return LikeQueueState.dayKey(System.currentTimeMillis(), TimeZone.getDefault());
    }

    /** Under {@link #LOCK}. */
    private static LikeQueueState load() {
        if (state != null) return state;
        File file = file();
        String saved = null;
        if (file != null && file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                saved = new String(readAll(in), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                Logger.printInfo(() -> "Like queue: could not read the kept queue: " + ex);
            }
        }
        state = LikeQueueState.parse(saved);
        return state;
    }

    /** Under {@link #LOCK}. */
    private static void save() {
        File file = file();
        if (file == null || state == null) return;
        try {
            File folder = file.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return;
            if (state.isEmpty() && state.total() == 0) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return;
            }
            AtomicFiles.write(new File(file.getPath() + ".part"), file, state.serialize().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            Logger.printException(() -> "Like queue: could not keep the queue", ex);
        }
    }

    private static File file() {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), FILE);
    }

    private static byte[] readAll(FileInputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
