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
    /** The last line of a file written whole. */
    private static final String END = "end";
    /** Longest queue kept: far more than any transfer gives (a playlist holds 500 tracks). */
    private static final int MAX_QUEUE = 20_000;

    private final Deque<String> pending = new ArrayDeque<>();
    /** The tracks that were queued in all, those liked or skipped and those still waiting. */
    private int total;
    private int done;
    /** The likes of the window, counted when they were asked for, oldest first. */
    private final List<Stamp> given = new ArrayList<>();

    /**
     * One counted like: its moment on the wall clock (never earlier than the one before it), and the elapsed time and the
     * start of the phone when it was counted. Within that start its age is the elapsed time, whatever the wall clock was
     * set to meanwhile; at any other start (and for a stamp of an older file, which has none) the wall clock is all there is.
     */
    static final class Stamp {
        final long wall;
        final long elapsed;
        final String boot;
        /** Whether {@link #elapsed} was read (a stamp of an older file has none). */
        final boolean known;

        Stamp(long wall, long elapsed, String boot, boolean known) {
            this.wall = wall;
            this.elapsed = elapsed;
            this.boot = boot == null ? "" : boot;
            this.known = known;
        }

        /**
         * Whether it was counted on this start of the phone. When the start cannot be told (no boot count), it is taken for
         * the same start while the elapsed time has not gone back: the age is then the elapsed difference, which can only
         * over-count after a restart nobody noticed (the safe way), and a forward jump of the wall clock opens nothing.
         */
        boolean sameStart(Moment now) {
            if (!boot.isEmpty()) return boot.equals(now.boot);
            return known && now.elapsedKnown && now.boot.isEmpty() && now.elapsed >= elapsed;
        }

        /** How long ago it was counted: the elapsed time within one start, else the wall clock's, never below 0. */
        long ageAt(Moment now) {
            return sameStart(now) ? Math.max(0, now.elapsed - elapsed) : Math.max(0, now.wall - wall);
        }

        /** {@code wall} alone, or {@code wall/elapsed/boot}. */
        String format() {
            if (boot.isEmpty()) return known ? wall + "/" + elapsed : String.valueOf(wall);
            return wall + "/" + elapsed + "/" + boot;
        }

        static Stamp parse(String text) {
            String[] parts = text.trim().split("/");
            if (parts.length == 3) return new Stamp(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2], true);
            if (parts.length == 2) return new Stamp(Long.parseLong(parts[0]), Long.parseLong(parts[1]), "", true);
            return new Stamp(Long.parseLong(parts[0]), 0, "", false);
        }
    }

    /** An empty queue. */
    LikeQueueState() {
    }

    /** Reads the kept text; a damaged one gives an empty queue. */
    static LikeQueueState parse(String saved) {
        return parse(saved, TimeZone.getDefault(), System.currentTimeMillis());
    }

    /**
     * @param zone Where the old calendar day of a file made before the rolling window began is read (once).
     * @param now  The moment of the migration of such a file: its likes are counted as given now (the hour they were given
     *             is not known, and a day's likes given late in the evening are hours old only).
     */
    static LikeQueueState parse(String saved, TimeZone zone, long now) {
        LikeQueueState read = tryParse(saved, zone, now);
        return read == null ? new LikeQueueState() : read;
    }

    /**
     * Reads the kept text strictly.
     *
     * @return The queue (an empty one for no text at all: no file yet); null when the text is there but is not a queue file
     * (no header: a file cut to nothing, another file; or a number that is not one).
     */
    static LikeQueueState tryParse(String saved, TimeZone zone, long now) {
        String oldDay = "";
        int oldToday = 0;
        boolean sawEnd = false, sawGiven = false, sawDay = false;
        LikeQueueState state = new LikeQueueState();
        if (saved == null) return state;
        try {
            String[] lines = saved.split("\n");
            if (lines.length == 0 || !HEADER.equals(lines[0].trim())) return null;
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                if (line.equals(END)) {
                    sawEnd = true;
                    continue;
                }
                int equals = line.indexOf('=');
                if (equals > 0 && !line.startsWith("soundcloud:")) {
                    String key = line.substring(0, equals), value = line.substring(equals + 1);
                    if (key.equals("total")) state.total = Integer.parseInt(value);
                    else if (key.equals("done")) state.done = Integer.parseInt(value);
                    else if (key.equals("day")) {
                        oldDay = value;
                        sawDay = true;
                    }
                    else if (key.equals("today")) oldToday = Integer.parseInt(value);
                    else if (key.equals("given")) {
                        sawGiven = true;
                        for (String stamp : value.split(",")) {
                            if (!stamp.trim().isEmpty()) state.given.add(Stamp.parse(stamp));
                        }
                    }
                } else if (line.startsWith("soundcloud:tracks:") && state.pending.size() < MAX_QUEUE) {
                    state.pending.add(line);
                }
            }
        } catch (RuntimeException ex) {
            return null;
        }
        // A file of the rolling kind must end with its end line; only a file of the calendar-day kind (it has its day line
        // and no stamps line) is read without one.
        if (!sawEnd && !(sawDay && !sawGiven)) return null;
        state.total = Math.max(state.total, state.done + state.pending.size());
        // A file of the calendar-day kind: the day's likes become that many moments at the start of that day, once.
        if (state.given.isEmpty() && oldToday > 0) {
            // A day long past has nothing in the window: only today's and yesterday's count are carried over.
            long start = dayStart(oldDay, zone);
            if (start >= 0 && start > now - 2 * WINDOW_MS) for (int i = 0; i < Math.min(oldToday, PER_DAY); i++) state.given.add(new Stamp(now, 0, "", false));
        }
        return state;
    }

    /** The text to keep. */
    String serialize() {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        out.append("total=").append(total).append('\n');
        out.append("done=").append(done).append('\n');
        out.append("given=");
        for (int i = 0; i < given.size(); i++) out.append(i == 0 ? "" : ",").append(given.get(i).format());
        out.append('\n');
        for (String urn : pending) out.append(urn).append('\n');
        // Written last: a file cut in the middle (a power loss) has no end, and is not read as a shorter one.
        out.append(END).append('\n');
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
     * How many likes the window of the 24 hours before {@code now} holds. Reads only: nothing is changed or dropped, so a
     * view of the queue on the main thread and the timer on another can share it. A stamp's age is the elapsed time while it
     * is the same start of the phone, so a wall clock that jumped (set right by the network, or by the user) opens nothing;
     * across starts it is the wall clock's, and a stamp ahead of it counts as just given until the clock has passed it.
     */
    int countIn(Moment now) {
        int count = 0;
        for (Stamp stamp : given) if (stamp.ageAt(now) < WINDOW_MS) count++;
        return count;
    }

    int countIn(long now) {
        return countIn(Moment.wall(now));
    }

    /** How many more likes the window lets through now. */
    int allowance(Moment now) {
        return Math.max(0, PER_DAY - countIn(now));
    }

    int allowance(long now) {
        return allowance(Moment.wall(now));
    }

    /** The most stamps kept: the window needs the last fifty, the rest is room for a clock that jumps. */
    static final int KEEP_STAMPS = 200;

    /**
     * Counts a like: before it is asked for, or for one the user gave by hand. On the same start of the phone as the one
     * before it the wall moment is never earlier than that one's (a clock that went back does not make the likes of that
     * time look old); on another start it is the wall clock's own. Stamps are never dropped by age, only the oldest beyond
     * {@link #KEEP_STAMPS}.
     *
     * @return The stamp given to the like.
     */
    Stamp count(Moment now) {
        lift(now);
        Stamp last = given.isEmpty() ? null : given.get(given.size() - 1);
        // Unknown starts (both empty) are taken as one: the conservative way, as before the start was told.
        boolean sameStart = last != null && last.boot.equals(now.boot);
        long wall = sameStart ? Math.max(now.wall, last.wall) : now.wall;
        Stamp stamp = new Stamp(wall, now.elapsed, now.boot, now.elapsedKnown);
        given.add(stamp);
        while (given.size() > KEEP_STAMPS) given.remove(0);
        return stamp;
    }

    /**
     * A clock set right raises what was recorded under a wrong one: every stamp of this same start gets the wall moment it
     * has by the elapsed time, {@code max(stamp.wall, now.wall - (now.elapsed - stamp.elapsed))}. Without it a stamp made
     * while the clock was days behind would, at the next start of the phone (where only the wall clock is left), look days
     * old and expire early. Done whenever a like is counted and whenever the state is saved.
     */
    boolean lift(Moment now) {
        boolean changed = false;
        for (int i = 0; i < given.size(); i++) {
            Stamp stamp = given.get(i);
            if (!stamp.sameStart(now)) continue;
            long wall = Math.max(stamp.wall, now.wall - Math.max(0, now.elapsed - stamp.elapsed));
            if (wall != stamp.wall) {
                given.set(i, new Stamp(wall, stamp.elapsed, stamp.boot, stamp.known));
                changed = true;
            }
        }
        return changed;
    }

    /**
     * What every look at the queue under the lock does first (the start of a run, the tick, the timer, a change of the
     * clock): lifts the stamps of this start to the clock as it is now, and has the state written if any changed. Without it
     * a clock set right while the window is full (nothing is counted, so nothing is saved) would leave the wrong wall
     * moments in the file for the next start of the phone.
     *
     * @return Whether anything changed (and the write was asked for).
     */
    boolean refreshAndKeep(Moment now, java.util.function.BooleanSupplier save) {
        if (!lift(now)) return false;
        save.getAsBoolean();
        return true;
    }

    /** As above for a moment known by the wall clock only; returns the wall moment given. */
    long count(long now) {
        return count(Moment.wall(now)).wall;
    }

    /** The latest wall moment counted, 0 when none. */
    long newestStamp() {
        long newest = 0;
        for (Stamp stamp : given) newest = Math.max(newest, stamp.wall);
        return newest;
    }

    /** Whether the clock looks wrong: the likes were counted at a moment more than an hour ahead of it. */
    boolean clockLooksWrong(Moment now) {
        return newestStamp() > now.wall + 3_600_000;
    }

    boolean clockLooksWrong(long now) {
        return clockLooksWrong(Moment.wall(now));
    }

    /**
     * Whether the user is offered the repair "the phone's clock was ahead: count from now": the window is full and the newest
     * stamp is more than an hour ahead of the clock.
     */
    boolean repairOffered(Moment now) {
        if (countIn(now) < PER_DAY) return false;
        // Only a stamp of ANOTHER start of the phone that counts and is ahead of the clock: on this start the age is the
        // elapsed time, exact whatever the clock was set to, and the wait is true; there is nothing to repair.
        for (Stamp stamp : given) {
            if (!stamp.sameStart(now) && stamp.ageAt(now) < WINDOW_MS && stamp.wall > now.wall + 3_600_000) return true;
        }
        return false;
    }

    /**
     * The repair (the user asked for it): every stamp ahead of the wall clock is moved to now, so it counts 24 hours from
     * now. Never fewer likes are counted; only the block that reaches far into the future (a clock set a year ahead for
     * one like) is taken away.
     *
     * @return How many stamps were moved.
     */
    int repairFuture(Moment now) {
        int moved = 0;
        for (int i = 0; i < given.size(); i++) {
            Stamp stamp = given.get(i);
            if (!stamp.sameStart(now) && stamp.wall > now.wall) {
                given.set(i, new Stamp(now.wall, now.elapsed, now.boot, now.elapsedKnown));
                moved++;
            }
        }
        return moved;
    }

    /** Whether an unreadable file is a new one to copy aside: not when the newest copy holds the same text already. */
    static boolean needsCopy(String newestCopy, String content) {
        return newestCopy == null || !newestCopy.equals(content);
    }

    /**
     * What stands in the place of a queue file that cannot be read (cut by a power loss, not ours): nothing is known of the
     * likes given lately, so the window counts as full for the next 24 hours, and no like goes beyond what it would have.
     */
    static LikeQueueState afterUnreadable(Moment now) {
        LikeQueueState state = new LikeQueueState();
        for (int i = 0; i < PER_DAY; i++) state.given.add(new Stamp(now.wall, now.elapsed, now.boot, now.elapsedKnown));
        return state;
    }

    static LikeQueueState afterUnreadable(long now) {
        return afterUnreadable(Moment.wall(now));
    }

    /**
     * {@link #afterUnreadable} that also keeps the tracks still waiting: the intact {@code soundcloud:tracks:} lines of the
     * file that could not be read. The window is full for 24 hours all the same; the queue is not lost.
     */
    static LikeQueueState salvage(String saved, Moment now) {
        LikeQueueState state = afterUnreadable(now);
        if (saved == null) return state;
        Set<String> seen = new HashSet<>();
        for (String line : saved.split("\n")) {
            String urn = line.trim();
            if (isTrackUrn(urn) && seen.add(urn) && state.pending.size() < MAX_QUEUE) state.pending.add(urn);
        }
        state.total = state.pending.size();
        return state;
    }

    /** A whole line {@code soundcloud:tracks:<digits>}: one cut in the middle is not taken. */
    private static boolean isTrackUrn(String line) {
        String prefix = "soundcloud:tracks:";
        if (!line.startsWith(prefix) || line.length() == prefix.length()) return false;
        for (int i = prefix.length(); i < line.length(); i++) if (!Character.isDigit(line.charAt(i))) return false;
        return true;
    }

    /**
     * Counts a like and has it written; when it is not written the count is taken back and false is returned: no like is
     * given then, so a process that dies after it cannot have forgotten one. The single step every like of Arsound's own
     * goes through (the queue, My Wave, the transfer).
     */
    boolean countAndKeep(Moment now, java.util.function.BooleanSupplier written) {
        Stamp stamp = count(now);
        if (written.getAsBoolean()) return true;
        removeCounted(stamp);
        return false;
    }

    boolean countAndKeep(long now, java.util.function.BooleanSupplier written) {
        return countAndKeep(Moment.wall(now), written);
    }

    /**
     * Takes back a stamp that was counted, found by what the lift never changes (its elapsed time and start), not by the
     * object: a write that failed may have lifted it into another object.
     */
    private void removeCounted(Stamp counted) {
        for (int i = given.size() - 1; i >= 0; i--) {
            Stamp stamp = given.get(i);
            if (stamp.elapsed == counted.elapsed && stamp.boot.equals(counted.boot) && stamp.known == counted.known) {
                given.remove(i);
                return;
            }
        }
    }

    /** What {@link #countIfAllowed} did. */
    enum Counted {
        COUNTED, LIMIT, NOT_WRITTEN
    }

    /**
     * The limit test and the count in one step, for the caller to run under one lock: a like of another kind counted
     * between a look at the allowance and the count would otherwise make this one the 51st.
     *
     * @return {@link Counted#LIMIT} when the window is full (nothing counted), {@link Counted#NOT_WRITTEN} when the count
     * could not be written (taken back), else {@link Counted#COUNTED}.
     */
    Counted countIfAllowed(Moment now, java.util.function.BooleanSupplier written) {
        if (countIn(now) >= PER_DAY) return Counted.LIMIT;
        return countAndKeep(now, written) ? Counted.COUNTED : Counted.NOT_WRITTEN;
    }

    Counted countIfAllowed(long now, java.util.function.BooleanSupplier written) {
        return countIfAllowed(Moment.wall(now), written);
    }

    /** Takes back a like counted at the wall moment {@code at}: the server said no to the track itself. */
    void giveBack(long at) {
        for (int i = given.size() - 1; i >= 0; i--) {
            if (given.get(i).wall == at) {
                given.remove(i);
                return;
            }
        }
    }

    /**
     * The wall moment the window lets the next like through: now when it does already, else when the oldest needed one
     * leaves (worked out from the age the same way as the count, so it is right whatever the wall clock was set to).
     */
    long nextSlotAt(Moment now) {
        List<Long> left = new ArrayList<>();
        for (Stamp stamp : given) {
            long age = stamp.ageAt(now);
            if (age < WINDOW_MS) left.add(WINDOW_MS - age);
        }
        if (left.size() < PER_DAY) return now.wall;
        java.util.Collections.sort(left);
        return now.wall + left.get(left.size() - PER_DAY);
    }

    long nextSlotAt(long now) {
        return nextSlotAt(Moment.wall(now));
    }

    /** True while tracks wait and the window is full. */
    boolean waitingForSlot(Moment now) {
        return !pending.isEmpty() && allowance(now) == 0;
    }

    boolean waitingForSlot(long now) {
        return waitingForSlot(Moment.wall(now));
    }

    /**
     * Whether the count of a failed like is given back: only for a failure that positively says the track itself was
     * refused (not found, not likeable). Nothing in SoundCloud's like call says so: it writes the like to its database
     * first and sends it to the server later, and what its answer carries is not told apart anywhere in this code. So no
     * failure gives the count back: every failed like stays counted, and the track stays queued for a later run.
     */
    static boolean givesCountBack(Throwable failure) {
        return false;
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
        // Never while any like is counted: a clock that jumped would find the file gone and the window empty.
        return !pending.isEmpty() || total > 0 || !given.isEmpty();
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
