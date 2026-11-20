package app.revanced.extension.soundcloud.local;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The queue of likes that {@link DailyLikeQueue} gives {@link #PER_DAY} in any 24 hours: the tracks still to like, the
 * moments of the likes given lately, and how far the whole queue has come.
 * <p>
 * RULE (design decision 20.11): the limit is a rolling window of {@link #WINDOW_MS}, not a calendar day, so a midnight,
 * a change of time zone or of the clock cannot open a second fifty; and it is counted conservatively: a like is counted
 * before it is asked for, a like whose result is not known (no answer, no network, the process died) stays counted, and
 * a like the user gave by hand in SoundCloud counts too. Pure: no Android, so it is tested on the JVM;
 * the text form is what is kept on the phone, one line each, so a restart picks the queue up where it stopped.
 * <p>
 * The tracks are queued from the last of a playlist to the first: the newest like is on top of "Your likes", so the
 * likes then read there in the order of the playlist, however many days they take.
 */
final class LikeQueueState {
    /** SoundCloud takes about this many likes a day; more bars the account from liking for a day. */
    static final int PER_DAY = 50;
    /** The window the limit is counted over. */
    static final long WINDOW_MS = 24L * 60 * 60 * 1000;
    private static final String HEADER = "arsound-like-queue 1";
    /** Longest queue kept: far more than any transfer gives (a playlist holds 500 tracks). */
    private static final int MAX_QUEUE = 20_000;

    private final Deque<String> pending = new ArrayDeque<>();
    /** The tracks that were queued in all, those liked or skipped and those still waiting. */
    private int total;
    private int done;
    /** When the likes of the window were given (counted), UTC milliseconds; only the last 24 hours matter. */
    private final List<Long> given = new ArrayList<>();

    /** An empty queue. */
    LikeQueueState() {
    }

    /** Reads the kept text; a damaged one gives an empty queue. */
    static LikeQueueState parse(String saved) {
        return parse(saved, TimeZone.getDefault());
    }

    /**
     * @param zone Where the old calendar day of a file made before the rolling window began is read (once).
     */
    static LikeQueueState parse(String saved, TimeZone zone) {
        String oldDay = "";
        int oldToday = 0;
        LikeQueueState state = new LikeQueueState();
        if (saved == null) return state;
        try {
            String[] lines = saved.split("\n");
            if (lines.length == 0 || !HEADER.equals(lines[0].trim())) return state;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                int equals = line.indexOf('=');
                if (equals > 0 && !line.startsWith("soundcloud:")) {
                    String key = line.substring(0, equals), value = line.substring(equals + 1);
                    if (key.equals("total")) state.total = Integer.parseInt(value);
                    else if (key.equals("done")) state.done = Integer.parseInt(value);
                    else if (key.equals("day")) oldDay = value;
                    else if (key.equals("today")) oldToday = Integer.parseInt(value);
                    else if (key.equals("given")) {
                        for (String stamp : value.split(",")) {
                            if (!stamp.trim().isEmpty()) state.given.add(Long.parseLong(stamp.trim()));
                        }
                    }
                } else if (line.startsWith("soundcloud:tracks:") && state.pending.size() < MAX_QUEUE) {
                    state.pending.add(line);
                }
            }
        } catch (RuntimeException ex) {
            return new LikeQueueState();
        }
        state.total = Math.max(state.total, state.done + state.pending.size());
        // A file of the calendar-day kind: the day's likes become that many moments at the start of that day, once.
        if (state.given.isEmpty() && oldToday > 0) {
            long start = dayStart(oldDay, zone);
            if (start >= 0) for (int i = 0; i < Math.min(oldToday, PER_DAY); i++) state.given.add(start);
        }
        return state;
    }

    /** The text to keep. */
    String serialize() {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        out.append("total=").append(total).append('\n');
        out.append("done=").append(done).append('\n');
        out.append("given=");
        for (int i = 0; i < given.size(); i++) out.append(i == 0 ? "" : ",").append(given.get(i));
        out.append('\n');
        for (String urn : pending) out.append(urn).append('\n');
        return out.toString();
    }

    /**
     * Queues the tracks of a playlist that are not liked yet and not queued already, last to first (see the class
     * comment). When the queue was empty, its count starts anew.
     *
     * @param inPlaylistOrder The tracks as the playlist has them, first to last.
     * @param alreadyLiked    Those of them that are liked.
     * @return How many were queued.
     */
    int add(List<String> inPlaylistOrder, Set<String> alreadyLiked) {
        if (pending.isEmpty()) {
            total = 0;
            done = 0;
        }
        Set<String> queued = new HashSet<>(pending);
        List<String> fresh = new ArrayList<>();
        for (String urn : inPlaylistOrder) {
            if (alreadyLiked.contains(urn) || !queued.add(urn)) continue;
            fresh.add(urn);
        }
        int count = 0;
        for (int i = fresh.size() - 1; i >= 0 && pending.size() < MAX_QUEUE; i--) {
            pending.addLast(fresh.get(i));
            count++;
        }
        total += count;
        return count;
    }

    /**
     * Puts the moments right: one in the future (the clock went back) counts as now, so it leaves the window 24 hours from
     * now and not later; those that left the window are dropped.
     */
    private void tidy(long now) {
        for (int i = 0; i < given.size(); i++) if (given.get(i) > now) given.set(i, now);
        given.removeIf(at -> at <= now - WINDOW_MS);
    }

    /** How many likes the window of the 24 hours before {@code now} holds. */
    int countIn(long now) {
        tidy(now);
        return given.size();
    }

    /** How many more likes the window lets through now. */
    int allowance(long now) {
        return Math.max(0, PER_DAY - countIn(now));
    }

    /** Counts a like at {@code now}: before it is asked for, or for one the user gave by hand. */
    void count(long now) {
        tidy(now);
        given.add(now);
    }

    /** Takes back a like counted at {@code at}: the server said no to the track itself, so nothing was given. */
    void giveBack(long at) {
        given.remove(Long.valueOf(at));
    }

    /** The moment the window lets the next like through: now when it does already, else when the oldest needed one leaves. */
    long nextSlotAt(long now) {
        tidy(now);
        if (given.size() < PER_DAY) return now;
        List<Long> sorted = new ArrayList<>(given);
        java.util.Collections.sort(sorted);
        return sorted.get(sorted.size() - PER_DAY) + WINDOW_MS;
    }

    /** True while tracks wait and the window is full. */
    boolean waitingForSlot(long now) {
        return !pending.isEmpty() && allowance(now) == 0;
    }

    /**
     * Whether a failed like stays counted: yes unless the failure is the track's own (the like did not happen). A result
     * that is not known may be a like the server took.
     */
    static boolean countsAsGiven(Throwable failure) {
        return failure == null || !isTrackFailure(failure);
    }

    /** The queue may be given likes only while the account it belongs to is still the one signed in. */
    static boolean ownerStillSame(String expected, String now) {
        return expected != null && expected.equals(now);
    }

    /** The start of a calendar day {@code 2026-11-18} in the zone, UTC milliseconds; -1 when the text is not a day. */
    static long dayStart(String dayKey, TimeZone zone) {
        try {
            String[] parts = dayKey.split("-");
            if (parts.length != 3) return -1;
            Calendar calendar = Calendar.getInstance(zone, Locale.ROOT);
            calendar.clear();
            calendar.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]), 0, 0, 0);
            return calendar.getTimeInMillis();
        } catch (RuntimeException ex) {
            return -1;
        }
    }

    /** The track to like next, or null when the queue is empty. */
    String peek() {
        return pending.peekFirst();
    }

    /**
     * The track {@code urn} was liked. It leaves the queue only while it is still the first one: a queue that was stopped
     * and filled again meanwhile keeps its tracks. The like was counted when it was asked for ({@link #count}).
     */
    void liked(String urn) {
        if (urn.equals(pending.peekFirst())) {
            pending.pollFirst();
            done++;
        }
    }

    /**
     * The track {@code urn} needs no like (it is liked already, or cannot be liked): it counts as done, for no like of the
     * day. Only while it is still the first one.
     */
    void skipped(String urn) {
        if (urn.equals(pending.peekFirst())) {
            pending.pollFirst();
            done++;
        }
    }

    /** The track {@code urn} could not be liked now: it goes to the back, to be tried again after the others. Only while it is still the first one. */
    void postponed(String urn) {
        if (!urn.equals(pending.peekFirst())) return;
        String first = pending.pollFirst();
        pending.addLast(first);
    }

    /**
     * Whether a failed like says something about the track itself (gone from SoundCloud, not likeable) rather than about
     * the moment: no like service yet, no answer in time, no network. Only the first kind may drop a track from the queue.
     */
    static boolean isTrackFailure(Throwable failure) {
        // Also what the failure was caused by: SoundCloud may wrap the network's exception in its own.
        for (int depth = 0; failure != null && depth < 6; depth++, failure = failure.getCause()) {
            if (failure instanceof java.util.concurrent.TimeoutException
                    || failure instanceof IllegalStateException
                    || failure instanceof java.io.IOException) return false;
        }
        return true;
    }

    /**
     * Whether the file is still needed: tracks wait, or this day's likes are counted. A queue that was stopped keeps its
     * day's count, or a new queue the same day would give another {@link #PER_DAY} likes and risk the account's block.
     */
    boolean worthKeeping(long now) {
        return !pending.isEmpty() || total > 0 || countIn(now) > 0;
    }

    void clear() {
        pending.clear();
        total = 0;
        done = 0;
    }

    boolean isEmpty() {
        return pending.isEmpty();
    }

    int remaining() {
        return pending.size();
    }

    int total() {
        return total;
    }

    int done() {
        return done;
    }

    /** An independent copy, so a screen can read it while the queue is changed. */
    LikeQueueState copy() {
        LikeQueueState copy = new LikeQueueState();
        copy.pending.addAll(pending);
        copy.total = total;
        copy.done = done;
        copy.given.addAll(given);
        return copy;
    }

    /**
     * The part of a file name that tells the queues of accounts apart. Letters, digits and "-" stay as they are, every
     * other character becomes "_" and its code in hex and "_", so two different names never give the same key.
     */
    static String keyOf(String owner) {
        StringBuilder key = new StringBuilder();
        for (char c : owner.toCharArray()) {
            boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
            if (plain) key.append(c);
            else key.append('_').append(Integer.toHexString(c)).append('_');
        }
        return key.toString();
    }
}
