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
 * The queue of likes that {@link DailyLikeQueue} gives {@link #PER_DAY} a day: the tracks still to like, how many were
 * given on the day it last worked, and how far the whole queue has come. Pure: no Android, so it is tested on the JVM;
 * the text form is what is kept on the phone, one line each, so a restart picks the queue up where it stopped.
 * <p>
 * The tracks are queued from the last of a playlist to the first: the newest like is on top of "Your likes", so the
 * likes then read there in the order of the playlist, however many days they take.
 */
final class LikeQueueState {
    /** SoundCloud takes about this many likes a day; more bars the account from liking for a day. */
    static final int PER_DAY = 50;
    private static final String HEADER = "arsound-like-queue 1";
    /** Longest queue kept: far more than any transfer gives (a playlist holds 500 tracks). */
    private static final int MAX_QUEUE = 20_000;

    private final Deque<String> pending = new ArrayDeque<>();
    /** The tracks that were queued in all, those liked or skipped and those still waiting. */
    private int total;
    private int done;
    /** The calendar day the count {@link #today} belongs to, as {@code 2026-11-18}; empty before the first like. */
    private String day = "";
    private int today;

    /** An empty queue. */
    LikeQueueState() {
    }

    /** Reads the kept text; a damaged one gives an empty queue. */
    static LikeQueueState parse(String saved) {
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
                    else if (key.equals("day")) state.day = value;
                    else if (key.equals("today")) state.today = Integer.parseInt(value);
                } else if (line.startsWith("soundcloud:tracks:") && state.pending.size() < MAX_QUEUE) {
                    state.pending.add(line);
                }
            }
        } catch (RuntimeException ex) {
            return new LikeQueueState();
        }
        state.total = Math.max(state.total, state.done + state.pending.size());
        return state;
    }

    /** The text to keep. */
    String serialize() {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        out.append("total=").append(total).append('\n');
        out.append("done=").append(done).append('\n');
        out.append("day=").append(day).append('\n');
        out.append("today=").append(today).append('\n');
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

    /** A new day starts the count of the day again. */
    void rollDay(String dayKey) {
        if (!dayKey.equals(day)) {
            day = dayKey;
            today = 0;
        }
    }

    /** How many more likes this day may give. */
    int allowance(String dayKey) {
        rollDay(dayKey);
        return Math.max(0, PER_DAY - today);
    }

    /** The track to like next, or null when the queue is empty. */
    String peek() {
        return pending.peekFirst();
    }

    /** The first track was liked, on this day. */
    void liked(String dayKey) {
        rollDay(dayKey);
        if (pending.pollFirst() != null) {
            done++;
            today++;
        }
    }

    /** The first track needs no like (it is liked already, or cannot be liked): it counts as done, for no like of the day. */
    void skipped() {
        if (pending.pollFirst() != null) done++;
    }

    /** The first track could not be liked now: it goes to the back, to be tried again after the others. */
    void postponed() {
        String urn = pending.pollFirst();
        if (urn != null) pending.addLast(urn);
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

    /** The likes given on this day. */
    int givenOn(String dayKey) {
        return dayKey.equals(day) ? today : 0;
    }

    /** True while the queue waits and this day's likes are all given. */
    boolean dayUsed(String dayKey) {
        return !pending.isEmpty() && allowanceOf(dayKey) == 0;
    }

    private int allowanceOf(String dayKey) {
        return dayKey.equals(day) ? Math.max(0, PER_DAY - today) : PER_DAY;
    }

    /** The calendar day of a moment in the given zone, as {@code 2026-11-18}. */
    static String dayKey(long millis, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone, Locale.ROOT);
        calendar.setTimeInMillis(millis);
        return String.format(Locale.ROOT, "%04d-%02d-%02d", calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }

    /** The moment the next calendar day begins in the zone, a little after midnight. */
    static long nextDayStart(long millis, TimeZone zone) {
        Calendar calendar = Calendar.getInstance(zone, Locale.ROOT);
        calendar.setTimeInMillis(millis);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        return calendar.getTimeInMillis();
    }
}
