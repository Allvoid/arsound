package app.revanced.extension.soundcloud.local;

import java.util.ArrayList;
import java.util.List;

/**
 * How many likes the account has given in the last day, for the warning before SoundCloud's limit and for the notice
 * after it. SoundCloud limits the likes of an account per day and, when it is passed, takes new likes back for about a
 * day; it does not say the number. Two accounts seen so far were taken for about a hundred in one go.
 * <p>
 * Pure: times in, answers out; {@link LikeLimit} keeps it between starts and shows the notices. A like costs one
 * number in a short list, nothing is asked of the system.
 */
final class LikeBudget {
    static final long DAY_MS = 24L * 60 * 60 * 1000;
    /** Where the warning starts: well under the hundred where the limit was met. */
    static final int WARN_AT = 60;
    /** The most likes kept: past it nothing new is learnt, and the saved text stays small. */
    private static final int KEEP = 400;

    private final List<Long> times = new ArrayList<>();

    LikeBudget(String saved) {
        if (saved == null || saved.isEmpty()) return;
        for (String part : saved.split(",")) {
            try {
                times.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // A damaged part is dropped: the count is a hint, never a promise.
            }
        }
    }

    /** One like given at this time. */
    void add(long now) {
        prune(now);
        times.add(now);
        if (times.size() > KEEP) times.subList(0, times.size() - KEEP).clear();
    }

    /** The likes of the last day. */
    int count(long now) {
        prune(now);
        return times.size();
    }

    /**
     * Whether to warn now: the count has come near the limit, and the warning was not given in the last half-day (a
     * warning that comes at every like is worse than none).
     */
    boolean shouldWarn(long now, long lastWarnedAt) {
        return count(now) >= WARN_AT && due(now, lastWarnedAt, DAY_MS / 2);
    }

    /** True when {@code gap} has passed since {@code last}; a clock that went back counts as passed. */
    static boolean due(long now, long last, long gap) {
        return last <= 0 || now < last || now - last >= gap;
    }

    String serialize() {
        StringBuilder out = new StringBuilder();
        for (long time : times) {
            if (out.length() > 0) out.append(',');
            out.append(time);
        }
        return out.toString();
    }

    private void prune(long now) {
        int stale = 0;
        while (stale < times.size() && now - times.get(stale) >= DAY_MS) stale++;
        if (stale > 0) times.subList(0, stale).clear();
    }
}
