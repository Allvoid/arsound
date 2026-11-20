package app.revanced.extension.soundcloud.local;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.accounts.Account;
import android.accounts.AccountManager;
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
import java.util.function.Consumer;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.shared.AtomicFiles;

/**
 * The likes of transferred tracks, {@link LikeQueueState#PER_DAY} a day. SoundCloud takes about fifty likes a day and,
 * when more are given, bars the account from liking for a day, so a transfer that was asked to like everything puts
 * the tracks in this queue instead of liking them at once: today's fifty are given, the rest wait for the next days.
 * The queue is kept in a file after every like, so a restart or a phone that ended the app goes on where it stopped.
 * Each account has a queue of its own: it waits for the account it was made for, and is given only while that account
 * is the one signed in.
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
    /** The queue kept before each account had its own: it becomes the queue of the account signed in first. */
    private static final String LEGACY_FILE = "arsound-transfer/like-queue.txt";
    /** The queue of an account: its name, made into a file name by {@link LikeQueueState#keyOf}. */
    private static final String FILE_PREFIX = "arsound-transfer/like-queue-";
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
    /** The queue of the account in {@link #stateOwner}, read from its file; null until it is read. Under {@link #LOCK}. */
    private static LikeQueueState state;
    /** The account {@link #state} belongs to. Under {@link #LOCK}. */
    private static String stateOwner;
    /** The queue as the screens last saw it, with its account: set under {@link #LOCK}, read without it. */
    private static volatile Shown shown;
    /** The account the screens read the queue of, see {@link #view}. */
    private static final TimedCache<String> VIEW_OWNER = new TimedCache<>(1_000);
    /** Told (on the main thread) when the first read of the queue ended, for a screen that found nothing before. */
    private static final QueueWork.ListenerSlot VIEW_LISTENER = new QueueWork.ListenerSlot();
    private static final QueueWork.OffMainLoad<LikeQueueState> VIEW_LOAD = new QueueWork.OffMainLoad<>(
            Utils::runOnBackgroundThread, () -> Utils.runOnMainThread(VIEW_LISTENER::run));
    private static final AtomicBoolean TIMER_SET = new AtomicBoolean();
    private static volatile long noticeAt;

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
            if (current == null) {
                Logger.printInfo(() -> "Like queue: nobody is signed in, the likes are not queued");
                return 0;
            }
            count = current.add(inPlaylistOrder, alreadyLiked == null ? new HashSet<>() : alreadyLiked);
            save();
        }
        schedule();
        Logger.printInfo(() -> "Like queue: " + inPlaylistOrder.size() + " tracks offered, " + count + " queued");
        return count;
    }

    /** Whether tracks of the account signed in wait for their likes. */
    public static boolean hasQueue() {
        LikeQueueState current = view();
        return current != null && !current.isEmpty();
    }

    /** The queue of the account signed in is dropped: the user does not want the rest of its likes. */
    public static void clear() {
        synchronized (LOCK) {
            LikeQueueState current = load();
            if (current == null) return;
            current.clear();
            save();
        }
    }

    /**
     * One line about the queue for the screen and the notification, or null when none waits:
     * "Liked 120 of 430, the next 50 tomorrow".
     */
    public static String progressLine() {
        LikeQueueState current = view();
        if (current == null || current.isEmpty()) return null;
        String day = today();
        String head = text("Пролайкано ", "Liked ") + current.done() + text(" из ", " of ") + current.total();
        int left = current.remaining();
        if (current.dayUsed(day)) {
            int next = Math.min(LikeQueueState.PER_DAY, left);
            return head + text(", следующие " + next + " — завтра", ", the next " + next + " tomorrow");
        }
        int now = Math.min(current.allowanceOf(day), left);
        return head + text(", сегодня ещё " + now, ", " + now + " more today");
    }

    // endregion

    // region Giving the likes

    /**
     * Likes what today still allows, one at a time with a pause. Blocks: off the main thread. One run at a time: a call
     * made while another runs returns 0. Only the queue of the account signed in is given: when another account is
     * signed in, the run stops and that queue waits.
     *
     * @param onLiked Told the urn of each track this run has liked; may be null.
     * @return How many likes this run gave.
     * @throws InterruptedException If the thread was interrupted: the queue keeps what is left.
     */
    public static int runBatch(Consumer<String> onLiked) throws InterruptedException {
        if (!SoundCloudLikes.isReady() || !RUNNING.compareAndSet(false, true)) return 0;
        int given = 0;
        try {
            int failures = 0;
            Set<String> failedOnce = new HashSet<>();
            String owner = null;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String urn;
                synchronized (LOCK) {
                    LikeQueueState current = load();
                    if (current == null || current.isEmpty() || current.allowance(today()) == 0) break;
                    urn = current.peek();
                    owner = stateOwner;
                }
                Set<String> liked = SoundCloudLikes.alreadyLiked(Collections.singletonList(urn));
                if (liked == null) break;
                if (liked.contains(urn)) {
                    synchronized (LOCK) {
                        LikeQueueState current = queueOf(owner);
                        if (current == null) break;
                        current.skipped(urn);
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
                        LikeQueueState current = queueOf(owner);
                        if (current != null) {
                            current.liked(urn, today());
                            save();
                        }
                    }
                    if (onLiked != null) onLiked.accept(urn);
                    Thread.sleep(LIKE_PAUSE_MS);
                    continue;
                }
                Logger.printInfo(() -> "Like queue: could not like " + urn + ": " + failure);
                // Not the track's fault (no like service yet, no answer, no network): the run ends and the track stays
                // queued, or a short queue would give up its tracks one after the other within the run.
                if (!LikeQueueState.isTrackFailure(failure)) break;
                synchronized (LOCK) {
                    LikeQueueState current = queueOf(owner);
                    if (current == null) break;
                    // A track that fails twice in one run is not liked at all: gone from SoundCloud, or not likeable.
                    if (!failedOnce.add(urn)) current.skipped(urn);
                    else current.postponed(urn);
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
        QueueWork.tick(Utils::runOnBackgroundThread, DailyLikeQueue::hasQueue, DailyLikeQueue::schedule, () -> {
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
        if (!TIMER_SET.compareAndSet(false, true)) return;
        long now = System.currentTimeMillis();
        TimeZone zone = TimeZone.getDefault();
        long midnight = LikeQueueState.nextDayStart(now, zone) + 60_000 + (long) (Math.random() * 120_000);
        long wait = Math.min(Math.max(1_000, midnight - now), RETRY_MS);
        HANDLER.postDelayed(() -> {
            TIMER_SET.set(false);
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
            LikeQueueState current = view();
            if (current == null || !current.dayUsed(today()) || now - noticeAt < NOTICE_GAP_MS) return;
            noticeAt = now;
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

    /**
     * Under {@link #LOCK}. The queue of the account signed in, read from its file the first time it is asked for. Null
     * when nobody is signed in or there is no context yet: nothing is cached then, so an empty queue is never taken for
     * the kept one.
     */
    private static LikeQueueState load() {
        String owner = currentOwner();
        if (owner == null) return null;
        if (state != null && owner.equals(stateOwner)) return state;
        File file = file(owner);
        if (file == null) return null;
        File legacy = legacyFile();
        // A queue kept before the queues were told apart is the queue of the account signed in, once.
        if (!file.isFile() && legacy != null && legacy.isFile() && !legacy.renameTo(file)) {
            Logger.printInfo(() -> "Like queue: could not take over the kept queue");
        }
        String saved = null;
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                saved = new String(readAll(in), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                // Not an empty queue to cache: the next change would overwrite the file that is there.
                Logger.printInfo(() -> "Like queue: could not read the kept queue: " + ex);
                return null;
            }
        }
        state = LikeQueueState.parse(saved);
        stateOwner = owner;
        return state;
    }

    /** Under {@link #LOCK}: the queue of {@code owner} if it is still the account signed in, else null. */
    private static LikeQueueState queueOf(String owner) {
        LikeQueueState current = load();
        return current != null && owner.equals(stateOwner) ? current : null;
    }

    /**
     * Under {@link #LOCK}. Keeps the queue of its account in its file and shows a copy of it to the screens. Called after
     * every change of the queue.
     */
    private static void save() {
        if (state == null) return;
        publish();
        File file = file(stateOwner);
        if (file == null) return;
        try {
            File folder = file.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return;
            if (!state.worthKeeping(today())) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return;
            }
            AtomicFiles.write(new File(file.getPath() + ".part"), file, state.serialize().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            Logger.printException(() -> "Like queue: could not keep the queue", ex);
        }
    }

    /** Under {@link #LOCK}. Copies the queue for the screens, see {@link #view}. */
    private static void publish() {
        shown = new Shown(stateOwner, state.copy());
    }

    /**
     * The queue of the account signed in, as the screens read it. It is read from the copy kept after the last change, so
     * a screen does not wait for the lock or the file while a like is written. The first read of an account reads its
     * file, once. Null when nobody is signed in.
     */
    private static LikeQueueState view() {
        // The owner is asked from the account manager (a binder call): kept for a moment, as the screens ask often.
        String owner = VIEW_OWNER.get(android.os.SystemClock.uptimeMillis(), DailyLikeQueue::currentOwner);
        if (owner == null) return null;
        Shown seen = shown;
        if (seen != null && owner.equals(seen.owner)) return seen.queue;
        // The file is read once per account, and not on the main thread: the screen is told when it is there.
        return VIEW_LOAD.read(Looper.myLooper() == Looper.getMainLooper(),
                () -> {
                    Shown now = shown;
                    return now != null && owner.equals(now.owner) ? now.queue : null;
                },
                () -> {
                    // The account may have changed within the kept second: the load asks for the owner afresh.
                    VIEW_OWNER.invalidate();
                    synchronized (LOCK) {
                        if (load() == null) return null;
                        publish();
                        return shown.queue;
                    }
                });
    }

    /** A screen asks to be told when the queue was read in the background. */
    public static void setOnViewLoaded(Runnable listener) {
        VIEW_LISTENER.set(listener);
    }

    /** A screen that goes stops its own listener; one that another screen set meanwhile stays. */
    public static void clearOnViewLoaded(Runnable listener) {
        VIEW_LISTENER.clear(listener);
    }

    /**
     * The name of the SoundCloud account signed in on this phone ("username-user id"), or null when nobody is signed in
     * or there is no context. Found as AccountSwitch finds it.
     */
    private static String currentOwner() {
        Context context = Utils.getContext();
        if (context == null) return null;
        try {
            int id = AppResources.identifier(context, "account_type", "string");
            String type = id != 0 ? context.getString(id) : context.getPackageName() + ".account";
            Account[] accounts = AccountManager.get(context).getAccountsByType(type);
            return accounts.length == 0 ? null : accounts[0].name;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Like queue: could not read the account", ex);
            return null;
        }
    }

    /** The file of the queue of this account, or null without a context. */
    private static File file(String owner) {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), FILE_PREFIX + LikeQueueState.keyOf(owner) + ".txt");
    }

    /** The file kept before the queues were told apart by account, or null without a context. */
    private static File legacyFile() {
        Context context = Utils.getContext();
        return context == null ? null : new File(context.getFilesDir(), LEGACY_FILE);
    }

    /** The queue of one account as the screens read it. */
    private static final class Shown {
        final String owner;
        final LikeQueueState queue;

        Shown(String owner, LikeQueueState queue) {
            this.owner = owner;
            this.queue = queue;
        }
    }

    private static byte[] readAll(FileInputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int read; (read = in.read(buffer)) > 0; ) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
