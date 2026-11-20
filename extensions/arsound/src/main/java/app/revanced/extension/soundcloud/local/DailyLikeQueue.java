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
        Moment at = moment();
        long now = at.wall;
        String head = text("Пролайкано ", "Liked ") + current.done() + text(" из ", " of ") + current.total();
        int left = current.remaining();
        if (current.waitingForSlot(at) && current.clockLooksWrong(at)) {
            // The likes were counted at a moment ahead of the phone's clock (it was ahead and was set back): conservative.
            String when = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                    .format(new java.util.Date(current.nextSlotAt(at)));
            return head + text(", часы телефона шли вперёд; следующий лайк после " + when,
                    ", the phone's clock was ahead; the next like after " + when);
        }
        if (current.waitingForSlot(at)) {
            long hours = Math.max(1, (current.nextSlotAt(at) - now + 3_600_000 - 1) / 3_600_000);
            return head + text(", следующие — через " + hours + " ч", ", the next in " + hours + " h");
        }
        int allowed = Math.min(current.allowance(at), left);
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
            liftAndSave();
            int failures = 0;
            String owner = null;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String urn;
                synchronized (LOCK) {
                    LikeQueueState current = load();
                    if (current == null || current.isEmpty() || current.allowance(moment()) == 0) break;
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
                Moment stamp = moment();
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
                // No failure gives the count back (see LikeQueueState.givesCountBack): the like stays counted.
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
            return current.countAndKeep(moment(), DailyLikeQueue::save);
        }
    }

    /** Called when SoundCloud's like service is ready (and by the timer): gives what today allows, off the main thread. */
    public static void tick() {
        registerTimeReceiver();
        QueueWork.tick(Utils::runOnBackgroundThread, () -> {
            liftAndSave();
            return hasQueue();
        }, DailyLikeQueue::schedule, () -> {
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
        Moment at = moment();
        long now = at.wall;
        LikeQueueState current = view();
        long slot = current == null ? now : current.nextSlotAt(at);
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
                    counting.count(moment());
                    save();
                }
            });
            LikeQueueState current = view();
            if (current == null || !current.waitingForSlot(moment()) || now - noticeAt < NOTICE_GAP_MS) return;
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
        LikeQueueState read = saved == null ? new LikeQueueState()
                : LikeQueueState.tryParse(saved, TimeZone.getDefault(), System.currentTimeMillis());
        boolean salvaged = read == null;
        if (read == null) {
            // The file is there but is not a queue (cut by a power loss, damaged): nothing is known of the likes given
            // lately. The file is kept as it was, beside it, and the window counts as full for 24 hours; it is not read as
            // an empty queue, which the next save would write over it and open another fifty.
            Logger.printInfo(() -> "Like queue: the kept queue cannot be read, kept as .bad, no likes for 24 hours");
            copyAside(file);
            // The tracks still waiting are saved from the text; the window is full for a day all the same.
            read = LikeQueueState.salvage(saved, moment());
        }
        state = read;
        stateOwner = owner;
        // The salvaged state is written at once, once per unreadable file: otherwise every start finds the file unreadable
        // again, and the day of waiting begins anew from each start.
        if (salvaged) save();
        return state;
    }

    /** How many copies of unreadable queue files are kept beside it: .bad1 is the newest. */
    private static final int BAD_COPIES = 3;

    /** A copy of a file that cannot be read, next to it as {@code .bad1}; the earlier copies move on, the last three stay. */
    private static void copyAside(File file) {
        try {
            // The same garbage again (a start that found it before the save below went through) is not copied over the
            // earlier copies.
            File newest = new File(file.getPath() + ".bad1");
            if (newest.isFile()) {
                String earlier;
                String now;
                try (FileInputStream a = new FileInputStream(newest); FileInputStream b = new FileInputStream(file)) {
                    earlier = new String(readAll(a), StandardCharsets.UTF_8);
                    now = new String(readAll(b), StandardCharsets.UTF_8);
                }
                if (!LikeQueueState.needsCopy(earlier, now)) return;
            }
            File last = new File(file.getPath() + ".bad" + BAD_COPIES);
            //noinspection ResultOfMethodCallIgnored
            last.delete();
            for (int i = BAD_COPIES - 1; i >= 1; i--) {
                File from = new File(file.getPath() + ".bad" + i);
                //noinspection ResultOfMethodCallIgnored
                if (from.exists()) from.renameTo(new File(file.getPath() + ".bad" + (i + 1)));
            }
            try (FileInputStream in = new FileInputStream(file);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(new File(file.getPath() + ".bad1"))) {
                out.write(readAll(in));
            }
        } catch (IOException ex) {
            Logger.printInfo(() -> "Like queue: could not copy the unreadable queue aside: " + ex);
        }
    }

    /**
     * The moment now, as the window needs it: wall clock, elapsed time and which start of the phone. The start is told by
     * {@code Settings.Global.BOOT_COUNT} (API 24, no permission); empty when it cannot be read.
     */
    private static Moment moment() {
        String boot = "";
        try {
            Context context = Utils.getContext();
            if (context != null) {
                int count = android.provider.Settings.Global.getInt(context.getContentResolver(),
                        android.provider.Settings.Global.BOOT_COUNT, -1);
                if (count >= 0) boot = String.valueOf(count);
            }
        } catch (RuntimeException ex) {
            // Not readable: nothing is taken for the same start.
        }
        return new Moment(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), boot);
    }

    /**
     * A look at the queue under the lock that puts its stamps right for the clock as it is now, and writes the file when any
     * changed (see {@link LikeQueueState#refreshAndKeep}). Off the main thread.
     */
    static void liftAndSave() {
        synchronized (LOCK) {
            LikeQueueState current = load();
            if (current != null) current.refreshAndKeep(moment(), DailyLikeQueue::save);
        }
    }

    private static final AtomicBoolean TIME_RECEIVER = new AtomicBoolean();

    /**
     * A change of the clock (the network set it right, the user did) lifts the stamps at once: the phone may start again
     * before any other look at the queue. Registered once, as soon as the queue is looked at.
     */
    private static void registerTimeReceiver() {
        Context context = Utils.getContext();
        if (context == null || !TIME_RECEIVER.compareAndSet(false, true)) return;
        try {
            context.getApplicationContext().registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context received, android.content.Intent intent) {
                    Utils.runOnBackgroundThread(DailyLikeQueue::liftAndSave);
                }
            }, new android.content.IntentFilter(android.content.Intent.ACTION_TIME_CHANGED));
        } catch (RuntimeException ex) {
            TIME_RECEIVER.set(false);
            Logger.printInfo(() -> "Like queue: could not watch the clock: " + ex);
        }
    }

    /** Whether the user is offered the repair of a clock that was ahead (see {@link LikeQueueState#repairOffered}). */
    public static boolean clockRepairOffered() {
        LikeQueueState current = view();
        return current != null && current.repairOffered(moment());
    }

    /**
     * The repair, after the user confirmed: the stamps ahead of the clock are moved to now, so the block that reaches into
     * the future is lifted and they count 24 hours from now. Never fewer likes are counted.
     *
     * @return False when it could not be written.
     */
    public static boolean repairClock() {
        synchronized (LOCK) {
            LikeQueueState current = load();
            Moment now = moment();
            // The offer is looked at again here: it may have gone since the screen showed it.
            if (current == null || !current.repairOffered(now)) return false;
            current.repairFuture(now);
            return save();
        }
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
        // A clock set right raises the wall moments recorded under a wrong one (see LikeQueueState.lift).
        state.lift(moment());
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
