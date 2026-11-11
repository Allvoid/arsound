package app.revanced.extension.soundcloud.local;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;

/**
 * SoundCloud's limit of likes: the warning before it and the notice after it.
 * <p>
 * SoundCloud limits the likes of an account per day. Past the limit the server takes new likes (and, with a transfer,
 * most of a long list) and does not keep them: the app takes the heart back a moment later, with no word, and the
 * account is barred from liking for about a day. A user cannot tell that from a bug. So:
 * <ul>
 * <li>every like given on SoundCloud (by a tap on a heart, or by a transfer: both go through
 * {@code LikeToggler.toggleTrackLike}) is counted in {@link LikeBudget}; near the limit a notice warns, once in half
 * a day;</li>
 * <li>a short while after a like, the app looks whether the track is still liked; one that is not, and was not
 * un-liked by the user, was refused: a notice says what happened, and that it passes by itself.</li>
 * </ul>
 * <p>
 * Cost: nothing runs on its own. A like adds a number to a short list, saved a few seconds later (one write for a
 * burst), and sets one timer; the check, one read of the likes the app already holds, runs once per burst of likes.
 * Nothing is asked of the server, and nothing is looked at while the user does not like.
 */
public final class LikeLimit {
    private static final String LOG_KEY = "like_budget_log";
    private static final String WARNED_KEY = "like_budget_warned_at";
    private static final String NOTICE_KEY = "like_blocked_notice_at";
    private static final String BLOCKED_KEY = "like_blocked_at";

    /** The likes are looked at this long after the first of a burst, so a transfer is looked at once, not at every like. */
    private static final long CHECK_AFTER_MS = 20_000;
    /** A like is looked at only when it is this old: SoundCloud takes a refused one back a few seconds after it. */
    private static final long MIN_AGE_MS = 12_000;
    private static final long SAVE_AFTER_MS = 4_000;
    /** The notice of a refusal is not repeated within this time. */
    private static final long NOTICE_GAP_MS = 3L * 60 * 60 * 1000;
    private static final int RECENT_MAX = 200;

    private static final Object LOCK = new Object();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    /** Likes given and not looked at yet, with their time. */
    private static final Map<String, Long> RECENT = new LinkedHashMap<>();
    private static LikeBudget budget;

    private LikeLimit() {
    }

    /**
     * Injection point, from {@code LikeHearts.toggle}: a like or an un-like of a SoundCloud track is about to be
     * written. Never throws: a failure here must not stop a like.
     */
    public static void onToggle(Object urn, boolean like) {
        try {
            String id = String.valueOf(urn);
            long now = System.currentTimeMillis();
            int count = -1;
            synchronized (LOCK) {
                if (!like) {
                    RECENT.remove(id);
                    return;
                }
                load();
                budget.add(now);
                RECENT.put(id, now);
                if (RECENT.size() > RECENT_MAX) {
                    Iterator<String> oldest = RECENT.keySet().iterator();
                    oldest.next();
                    oldest.remove();
                }
                if (budget.shouldWarn(now, Settings.getLong(WARNED_KEY, 0))) {
                    count = budget.count(now);
                    Settings.putLong(WARNED_KEY, now);
                }
            }
            if (!HANDLER.hasCallbacks(SAVE)) HANDLER.postDelayed(SAVE, SAVE_AFTER_MS);
            if (!HANDLER.hasCallbacks(CHECK)) HANDLER.postDelayed(CHECK, CHECK_AFTER_MS);
            if (count >= 0) {
                int shown = count;
                HANDLER.post(() -> LikeLimitBanner.show(LikeLimitBanner.Kind.NEAR, shown));
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Like limit: could not count a like", ex);
        }
    }

    /** The server answered a like with a refusal that means the limit (429, 403): no need to wait to look. */
    public static void onRefused() {
        refused();
    }

    private static final Runnable SAVE = () -> {
        synchronized (LOCK) {
            if (budget != null) Settings.putString(LOG_KEY, budget.serialize());
        }
    };

    private static final Runnable CHECK = () -> Utils.runOnBackgroundThread(LikeLimit::check);

    /** Looks whether the likes given a while ago are still likes. Off the main thread. */
    private static void check() {
        try {
            long now = System.currentTimeMillis();
            List<String> due = new ArrayList<>();
            boolean waiting;
            synchronized (LOCK) {
                for (Iterator<Map.Entry<String, Long>> it = RECENT.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<String, Long> entry = it.next();
                    if (now - entry.getValue() >= MIN_AGE_MS) {
                        due.add(entry.getKey());
                        it.remove();
                    }
                }
                waiting = !RECENT.isEmpty();
            }
            if (waiting) HANDLER.postDelayed(CHECK, CHECK_AFTER_MS);
            if (due.isEmpty()) return;
            Set<String> liked = SoundCloudLikes.alreadyLiked(due);
            // The likes cannot be read yet (the app is still starting): nothing is known, so nothing is said.
            if (liked == null) return;
            int lost = 0;
            for (String id : due) {
                if (!liked.contains(id)) lost++;
            }
            int looked = due.size();
            int notKept = lost;
            Logger.printInfo(() -> "Like limit: " + looked + " likes looked at, " + notKept + " not kept");
            if (lost > 0) refused();
            else confirmed();
        } catch (Throwable ex) {
            Logger.printException(() -> "Like limit: could not look at the likes", ex);
        }
    }

    /** A like was not kept by the server: the notice, at most once in a few hours. */
    private static void refused() {
        try {
            long now = System.currentTimeMillis();
            boolean show;
            synchronized (LOCK) {
                Settings.putLong(BLOCKED_KEY, now);
                show = LikeBudget.due(now, Settings.getLong(NOTICE_KEY, 0), NOTICE_GAP_MS);
                if (show) Settings.putLong(NOTICE_KEY, now);
            }
            Logger.printInfo(() -> "Like limit: a like was not kept by the server" + (show ? ", the notice is shown" : ""));
            if (show) HANDLER.post(() -> LikeLimitBanner.show(LikeLimitBanner.Kind.BLOCKED, 0));
        } catch (Throwable ex) {
            Logger.printException(() -> "Like limit: could not tell of a refused like", ex);
        }
    }

    /** A like was kept: the limit, if there was one, has passed. */
    private static void confirmed() {
        synchronized (LOCK) {
            if (Settings.getLong(BLOCKED_KEY, 0) != 0) {
                Settings.putLong(BLOCKED_KEY, 0);
                Settings.putLong(NOTICE_KEY, 0);
            }
        }
    }

    /** Reads the saved likes the first time they are needed. Under {@link #LOCK}. */
    private static void load() {
        if (budget == null) budget = new LikeBudget(Settings.getString(LOG_KEY, ""));
    }
}
