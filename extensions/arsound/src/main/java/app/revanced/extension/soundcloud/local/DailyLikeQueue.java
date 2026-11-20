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
 * The likes of transferred tracks, {@link LikeQueueState#PER_DAY} in any 24 hours. SoundCloud takes about fifty likes a day
 * and, when more are given, bars the account from liking for a day, so a transfer that was asked to like everything puts
 * the tracks in this queue instead of liking them at once: fifty are given, the rest wait until the window lets them through.
 * The queue never likes more than fifty in any 24 hours, counting every like of the account made by hand
 * ({@link #onManualLike}) and those of My Wave's and the transfer's "Like all" ({@link #likeCounted}); a like is counted
 * before it is asked for, and one whose result is not known stays counted. Those two features have limits of their own and
 * are NOT held to fifty in 24 hours: they only make the queue wait.
 * The queue is kept in a file after every like, so a restart or a phone that ended the app goes on where it stopped.
 * Each account has a queue of its own: it waits for the account it was made for, and is given only while that account
 * is the one signed in.
 * <p>
 * Who gives the likes: the transfer, for the first fifty; after that {@link #tick} on its own, when the app has made
 * SoundCloud's like service ({@link SoundCloudLikes#setToggler}), and from a timer that fires when the window lets the next
 * like through and every hour while the app lives and the queue waits. SoundCloud's like service lives in the app's process,
 * so the likes go only while the app runs; a phone on which the app was not opened gives them on its next start.
 * <p>
 * While the window is full, a heart tapped by hand shows a short notice ({@link #onManualLike}): the limit of the account is
 * used up by the transfer, and a like of the user's own may not be kept.
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
    public static boolean clear() {
        synchronized (LOCK) {
            LikeQueueState current = load();
            // Not read (the file could not be opened): nothing was stopped, and the caller says so.
            if (current == null) return false;
            current.clear();
            save();
            return true;
        }
    }

    /**
     * One line about the queue for the screen and the notification, or null when none waits:
     * "Liked 120 of 430, the next in 5 h".
     */
    public static String progressLine() {
        LikeQueueState current = view();
        if (current == null || current.isEmpty()) return null;
        long now = System.currentTimeMillis();
        String head = text("Пролайкано ", "Liked ") + current.done() + text(" из ", " of ") + current.total();
        int left = current.remaining();
        if (current.waitingForSlot(now)) {
            long hours = Math.max(1, (current.nextSlotAt(now) - now + 3_600_000 - 1) / 3_600_000);
            return head + text(", следующие — через " + hours + " ч", ", the next in " + hours + " h");
        }
        int allowed = Math.min(current.allowance(now), left);
        return head + text(", сейчас можно ещё " + allowed, ", " + allowed + " more now");
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
            String owner = null;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String urn;
                synchronized (LOCK) {
                    LikeQueueState current = load();
                    if (current == null || current.isEmpty() || current.allowance(System.currentTimeMillis()) == 0) break;
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
                // Counted before the like is asked for, and the account is looked at once more: a like that reaches the server
                // and is not known to have, a process that dies meanwhile, an account switched under the run all stay
                // inside the limit.
                long stamp = System.currentTimeMillis();
                LikeQueueState.Counted counted;
                synchronized (LOCK) {
                    LikeQueueState current = queueOf(owner);
                    if (current == null || !LikeQueueState.ownerStillSame(owner, currentOwner())) break;
                    // The limit is tested in the same locked step as the count: a heart by hand or a like of My Wave counted
                    // since the allowance was read must not make this one the 51st. And no like when the count did not
                    // reach the disk: a process that dies after it would have forgotten it.
                    counted = current.countIfAllowed(stamp, DailyLikeQueue::save);
                }
                if (counted == LikeQueueState.Counted.LIMIT) break;
                if (counted == LikeQueueState.Counted.NOT_WRITTEN) {
                    Logger.printInfo(() -> "Like queue: the count could not be written, no like is given");
                    break;
                }
                Throwable failure = likeMarkedOwn(urn);
                if (failure == null) {
                    failures = 0;
                    given++;
                    synchronized (LOCK) {
                        LikeQueueState current = queueOf(owner);
                        if (current != null) {
                            current.liked(urn);
                            save();
                        }
                    }
                    if (onLiked != null) onLiked.accept(urn);
                    Thread.sleep(LIKE_PAUSE_MS);
                    continue;
                }
                Logger.printInfo(() -> "Like queue: could not like " + urn + ": " + failure);
                if (LikeQueueState.givesCountBack(failure)) {
                    synchronized (LOCK) {
                        LikeQueueState current = queueOf(owner);
                        if (current != null) {
                            current.giveBack(stamp);
                            save();
                        }
                    }
                }
                // Not the track's fault (no like service yet, no answer, no network): the run ends and the track stays
                // queued, or a short queue would give up its tracks one after the other within the run.
                if (!LikeQueueState.isTrackFailure(failure)) break;
                synchronized (LOCK) {
                    LikeQueueState current = queueOf(owner);
                    if (current == null) break;
                    // Nothing says what the failure was (see givesCountBack): the like stays counted and the track goes to
                    // the back, to be tried again later; it is never dropped from the queue because of a failure.
                    current.postponed(urn);
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

    /** Likes the track as the queue's own like (a heart tapped by hand is told from it); the count is written already. */
    private static Throwable likeMarkedOwn(String urn) throws Exception {
        OWN.add(urn);
        try {
            return SoundCloudLikes.like(urn);
        } finally {
            // A moment of grace: the hook that tells the likes apart runs a little after the call.
            HANDLER.postDelayed(() -> OWN.remove(urn), 15_000);
        }
    }

    /**
     * The one entry for likes of Arsound's own features other than the queue (My Wave, the transfer's own fifty): the like
     * is counted in the window of the account first and written to the disk, then given. Those features keep their own
     * limits; counting them is what keeps the queue from adding to them past 50 in any 24 hours. Blocks: off the main thread.
     *
     * @return Null when it went through; what went wrong otherwise (also when the count could not be written: no like then).
     */
    public static Throwable likeCounted(String urn) throws Exception {
        if (!countOne()) return new IllegalStateException("The like could not be counted");
        return likeMarkedOwn(urn);
    }

    /** Counts one like now in the window of the account signed in; false when that could not be written. */
    static boolean countOne() {
        synchronized (LOCK) {
            LikeQueueState current = load();
            if (current == null) return false;
            return current.countAndKeep(System.currentTimeMillis(), DailyLikeQueue::save);
        }
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

    /** The timer: when the window lets the next like through, and every hour while likes wait (a run may stop on a refusal). */
    private static void schedule() {
        if (!TIMER_SET.compareAndSet(false, true)) return;
        long now = System.currentTimeMillis();
        LikeQueueState current = view();
        long slot = current == null ? now : current.nextSlotAt(now);
        long wait = slot <= now ? RETRY_MS : Math.min(Math.max(1_000, slot - now + 60_000 + (long) (Math.random() * 120_000)), RETRY_MS);
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
            // A heart by hand counts in the window too, or the limit would be passed by it and the queue together.
            Utils.runOnBackgroundThread(() -> {
                synchronized (LOCK) {
                    LikeQueueState counting = load();
                    if (counting == null) return;
                    counting.count(System.currentTimeMillis());
                    save();
                }
            });
            LikeQueueState current = view();
            if (current == null || !current.waitingForSlot(now) || now - noticeAt < NOTICE_GAP_MS) return;
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
    private static boolean save() {
        if (state == null) return false;
        publish();
        File file = file(stateOwner);
        if (file == null) return false;
        try {
            File folder = file.getParentFile();
            if (folder != null && !folder.isDirectory() && !folder.mkdirs()) return false;
            if (!state.worthKeeping(System.currentTimeMillis())) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                return true;
            }
            AtomicFiles.write(new File(file.getPath() + ".part"), file, state.serialize().getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException ex) {
            Logger.printException(() -> "Like queue: could not keep the queue", ex);
            return false;
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
