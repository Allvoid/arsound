package app.revanced.extension.soundcloud.login;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

import app.revanced.extension.shared.Logger;

/**
 * The SoundCloud accounts kept for switching, and the switch that is under way. A preferences file of its own, private
 * to the app, outside the settings backup. Every write is committed at once: the app is restarted right after most of
 * them.
 */
final class AccountVault {
    static final String PREFERENCES = "arsound_accounts";
    private static final String LIST = "accounts";
    /** The switch under way: {@link #PENDING_ADD} or the name of the account to put in. */
    private static final String PENDING = "pending";
    private static final String PENDING_AT = "pending_at";
    /** The account that was signed in when the list was last looked at: SoundCloud's own sign-out removes it. */
    private static final String LAST_CURRENT = "last_current";
    /** The switch is to an empty sign-in screen, for one more account. */
    static final String PENDING_ADD = "\u0000add";
    /**
     * While this very run of the app has the switch going (its wipe is followed, the user may be reading the question
     * about it), the mark is kept this long: the wipe of a big account takes minutes, and a mark that went stale by then
     * made the end of the wipe look like SoundCloud's own sign-out, which dropped the saved copy of the account the user
     * was switching away from.
     */
    static final long PENDING_HERE_FOR_MS = 15 * 60_000;
    /** The switch under way was started by this run of the app (not left by one that was killed). */
    private static volatile boolean pendingHere;
    /** More are not kept: the oldest goes. */
    static final int MAX_ACCOUNTS = 10;

    private AccountVault() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    /** The text of a list that was damaged or lost entries, kept as it was (never lost silently): see {@link #losesEntries}. */
    static final String LIST_BACKUP = "accounts_backup";

    static synchronized List<SavedAccount> list(Context context) {
        String text = preferences(context).getString(LIST, null);
        if (damaged(text)) {
            // Dropped, so it is not read (and logged) again on every call; the next save starts a clean list. The text
            // stays in the backups, and the list is dropped only once it is there.
            if (backedUp(context, text)) preferences(context).edit().remove(LIST).commit();
            return new ArrayList<>();
        }
        return parse(text);
    }

    /**
     * Keeps the text of a list the code cannot read whole (damaged, or with an entry that is skipped) before anything
     * rewrites it. The first such text is kept: it is the one with the most in it.
     */
    private static boolean backedUp(Context context, String text) {
        if (!losesEntries(text)) return true;
        SharedPreferences preferences = preferences(context);
        String before = preferences.getString(LIST_BACKUP, null);
        String after = addBackup(before, text);
        if (after == null) {
            // The earlier backups are themselves unreadable: kept as they are under another key, not cleared.
            if (preferences.getString(LIST_BACKUP + "_unreadable", null) == null) {
                if (!preferences.edit().putString(LIST_BACKUP + "_unreadable", before).commit()) return false;
            }
            after = addBackup(null, text);
        }
        return after.equals(before) || preferences.edit().putString(LIST_BACKUP, after).commit();
    }

    /** How many different bad lists are kept, besides the largest. */
    static final int BACKUPS = 3;

    /** How many accounts a backed up text holds; what cannot be read counts as none. */
    static int entriesOf(String text) {
        try {
            return new JSONArray(text).length();
        } catch (JSONException ex) {
            // Cut or damaged: it may hold the most accounts of all, so it is sized by the accounts it still shows (their
            // names), and at least 1: never 0, never evicted first for being unreadable.
            int names = 0;
            for (int at = text.indexOf("\"name\""); at >= 0; at = text.indexOf("\"name\"", at + 1)) names++;
            return Math.max(1, names);
        }
    }

    /**
     * The backups with this text added: a JSON list of different texts, at most {@link #BACKUPS}. When it is too long the
     * oldest goes, but never the one that holds the most accounts (the first, largest list is the one worth most). A text
     * that is there already changes nothing.
     *
     * @return The new list; null when {@code backups} cannot be read (the caller keeps it aside, it is not cleared).
     */
    static String addBackup(String backups, String text) {
        List<String> kept = new ArrayList<>();
        try {
            JSONArray array = backups == null || backups.isEmpty() ? new JSONArray() : new JSONArray(backups);
            for (int i = 0; i < array.length(); i++) kept.add(array.getString(i));
        } catch (JSONException ex) {
            return null;
        }
        if (kept.contains(text)) return new JSONArray(kept).toString();
        kept.add(text);
        while (kept.size() > BACKUPS) {
            int largest = 0;
            for (int i = 1; i < kept.size(); i++) {
                // The newest of equal ones: the oldest is the one that goes.
                if (entriesOf(kept.get(i)) >= entriesOf(kept.get(largest))) largest = i;
            }
            // The oldest that is not the largest; the new text is never the one to go.
            int drop = largest == 0 ? 1 : 0;
            if (drop == kept.size() - 1) drop = 0;
            kept.remove(drop);
        }
        return new JSONArray(kept).toString();
    }

    /** Whether reading the text drops something: it is damaged, or it holds entries that do not read as accounts. */
    static boolean losesEntries(String text) {
        if (text == null || text.isEmpty()) return false;
        if (damaged(text)) return true;
        try {
            return new JSONArray(text).length() != parse(text).size();
        } catch (JSONException ex) {
            return true;
        }
    }

    static boolean isEmpty(Context context) {
        return list(context).isEmpty();
    }

    static synchronized SavedAccount find(Context context, String name) {
        for (SavedAccount account : list(context)) {
            if (account.name.equals(name)) return account;
        }
        return null;
    }

    /** Keeps the account, in place of an older copy of it, at the top of the list. False when it was not committed. */
    static synchronized boolean save(Context context, SavedAccount account) {
        // A list that loses entries when read is backed up before it is rewritten; no backup, no rewrite.
        String stored = preferences(context).getString(LIST, null);
        if (!mayRewrite(stored, () -> backedUp(context, stored))) return false;
        List<SavedAccount> updated = upsert(list(context), account);
        // The list is full of other accounts: nothing is dropped to make room, the user removes one first.
        return updated != null && write(context, updated);
    }

    /** Whether another account can be added to a list of this size without dropping any. */
    static boolean canAddAnother(int kept) {
        return kept < MAX_ACCOUNTS;
    }

    /**
     * Whether the signed-in account may be taken out: only when nothing is signed in, or a usable copy of it is kept (its
     * data is wiped with it, and the copy is the only way back).
     */
    static boolean canLeave(boolean signedIn, SavedAccount copy) {
        return !signedIn || (copy != null && copy.usable());
    }

    /** Whether the account cannot be kept because ten others are: adding it would drop one of them. */
    static synchronized boolean isFullFor(Context context, String name) {
        return upsert(list(context), new SavedAccount(name, "", "", "", null, null, null, 0)) == null;
    }

    /** Whether a list may be rewritten: it loses nothing when read, or a backup of it was made first. */
    static boolean mayRewrite(String stored, java.util.function.BooleanSupplier backupDone) {
        return !losesEntries(stored) || backupDone.getAsBoolean();
    }

    static synchronized void remove(Context context, String name) {
        // The rewrite below drops what is unreadable in the list: its text is backed up first, or nothing is rewritten.
        String stored = preferences(context).getString(LIST, null);
        if (!mayRewrite(stored, () -> backedUp(context, stored))) return;
        List<SavedAccount> accounts = list(context);
        List<SavedAccount> left = new ArrayList<>();
        for (SavedAccount account : accounts) {
            if (!account.name.equals(name)) left.add(account);
        }
        if (left.size() != accounts.size()) write(context, left);
    }

    private static boolean write(Context context, List<SavedAccount> accounts) {
        return preferences(context).edit().putString(LIST, serialize(accounts)).commit();
    }

    /** False when the mark did not reach the disk: the switch must not go on then. */
    static boolean setPending(Context context, String what) {
        boolean written = preferences(context).edit().putString(PENDING, what).putLong(PENDING_AT, System.currentTimeMillis()).commit();
        pendingHere = written;
        abandoned = false;
        newSwitch();
        return written;
    }

    /**
     * Whether a mark of this age is no longer a switch under way. A mark this run did not write is a leftover of a run
     * that was killed: its wipe is gone with it, so it is no switch at all (an own sign-out in SoundCloud meanwhile
     * must not be taken for its end).
     */
    static boolean isStale(long ageMs, boolean startedHere) {
        return ageMs < 0 || !startedHere || ageMs > PENDING_HERE_FOR_MS;
    }

    private static volatile long finishedAt;
    /** Counts the switches begun by this run; {@link #finishedId} is the one that has just put its account in. */
    private static volatile long switchId;
    private static volatile long finishedId = -1;
    private static final String WIPE_OPEN = "wipe_open";
    /** How long after a switch put the next account in the end of the old wipe is still part of that switch. */
    private static final long FINISHED_GRACE_MS = 5_000;

    /** The switch has put the next account in and the app is about to start again. */
    static void markFinished() {
        finishedAt = System.currentTimeMillis();
        finishedId = switchId;
    }

    /** A new switch begins: the grace of the one before it does not apply to it. */
    static void newSwitch() {
        switchId++;
    }

    /** Only for the switch that finished: a new one begun within the grace must hear the end of its own wipe. */
    static boolean justFinished() {
        long age = System.currentTimeMillis() - finishedAt;
        return finishedAt != 0 && finishedId == switchId && age >= 0 && age < FINISHED_GRACE_MS;
    }

    /** A wipe of ours is about to start: written before it, cleared when it ends, so a cut one is known afterwards. */
    static void markWipeOpen(Context context) {
        preferences(context).edit().putBoolean(WIPE_OPEN, true).commit();
    }

    static void clearWipeOpen(Context context) {
        preferences(context).edit().remove(WIPE_OPEN).commit();
    }

    /** Whether the last wipe of ours did not end (the app died in it, it failed, or the app was restarted over it). */
    static boolean wipeUnfinished(Context context) {
        return preferences(context).getBoolean(WIPE_OPEN, false);
    }

    /** What signing in with a kept account does now. */
    enum SignInGate {
        /** A wipe is running (ours or SoundCloud's own): a restart would cut it off. */
        WAIT,
        /** The last wipe did not end: the user is warned that data of the earlier account may show. */
        WARN,
        GO
    }

    static SignInGate signInGate(boolean purgeRunning, boolean switchPending, boolean wipeUnfinished) {
        if (purgeRunning || switchPending) return SignInGate.WAIT;
        return wipeUnfinished ? SignInGate.WARN : SignInGate.GO;
    }

    /** Puts the switch under way back to a new time, so a wait that went on does not make it look abandoned. */
    static boolean touchPending(Context context) {
        SharedPreferences preferences = preferences(context);
        if (preferences.getString(PENDING, null) == null) return false;
        return preferences.edit().putLong(PENDING_AT, System.currentTimeMillis()).commit();
    }

    /** The switch under way, or null; one left by an app that was killed long ago is dropped. */
    static String pending(Context context) {
        SharedPreferences preferences = preferences(context);
        String what = preferences.getString(PENDING, null);
        if (what == null) return null;
        long age = System.currentTimeMillis() - preferences.getLong(PENDING_AT, 0);
        if (isStale(age, pendingHere)) {
            clearPending(context);
            return null;
        }
        return what;
    }

    /**
     * The switch was given up on this run (no screen to ask on) while SoundCloud's wipe may still be running. The mark
     * stays, with {@link #pendingHere}: the end of that wipe must not look like SoundCloud's own sign-out.
     */
    private static volatile boolean abandoned;

    static void markAbandoned() {
        abandoned = true;
    }

    static boolean isAbandoned() {
        return abandoned;
    }

    /** What the end of SoundCloud's wipe means for the switch. */
    enum AfterPurge {
        /** No switch of ours: SoundCloud's own sign-out; the kept copy of that account is dead. */
        OWN_SIGN_OUT,
        /** A switch given up on: nothing is removed and nothing starts in the background. */
        LEAVE,
        /** The wipe failed: the user decides. */
        ASK,
        /** The wipe is over: the next account goes in. */
        FINISH
    }

    static AfterPurge afterPurge(boolean pending, boolean abandoned, boolean failed, boolean justFinished) {
        // A given-up switch first: even when its mark went stale meanwhile, this was our removal, not a sign-out. The
        // same for a switch that has just put the next account in: the "current" account is already that one.
        if (abandoned || justFinished) return AfterPurge.LEAVE;
        if (!pending) return AfterPurge.OWN_SIGN_OUT;
        return failed ? AfterPurge.ASK : AfterPurge.FINISH;
    }

    static boolean clearPending(Context context) {
        pendingHere = false;
        abandoned = false;
        return preferences(context).edit().remove(PENDING).remove(PENDING_AT).commit();
    }

    /** True when the name is kept (it was already, or is written now). */
    static boolean setLastCurrent(Context context, String name) {
        if (name == null) return true;
        if (name.equals(preferences(context).getString(LAST_CURRENT, null))) return true;
        return preferences(context).edit().putString(LAST_CURRENT, name).commit();
    }

    static String lastCurrent(Context context) {
        return preferences(context).getString(LAST_CURRENT, null);
    }

    // region Pure list work (tested on the JVM)

    /**
     * The list with the account at the top in place of its older copy. RULE A: nothing kept is dropped to make room.
     *
     * @return The new list; null when the account is a new one and {@link #MAX_ACCOUNTS} others are kept already.
     */
    static List<SavedAccount> upsert(List<SavedAccount> accounts, SavedAccount account) {
        List<SavedAccount> result = new ArrayList<>();
        result.add(account);
        boolean known = false;
        for (SavedAccount other : accounts) {
            if (other.name.equals(account.name)) known = true;
            else result.add(other);
        }
        // Only a NEW account is refused: a list that is over the limit already (an older build, a restored backup) still
        // refreshes the copies it has, or every switch would fail.
        return !known && result.size() > MAX_ACCOUNTS ? null : result;
    }

    static List<SavedAccount> parse(String text) {
        List<SavedAccount> accounts = new ArrayList<>();
        if (text == null || text.isEmpty()) return accounts;
        try {
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                SavedAccount account = SavedAccount.fromJson(array.optJSONObject(i));
                if (account != null) accounts.add(account);
            }
        } catch (JSONException ex) {
            // Not the exception itself: its message holds the start of the text, and that text is tokens.
            Logger.printException(() -> "Accounts: the saved list is damaged (" + ex.getClass().getSimpleName()
                    + "), starting a new one");
        }
        return accounts;
    }

    /** True when the text is there but is not a JSON list at all. */
    static boolean damaged(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            new JSONArray(text);
            return false;
        } catch (JSONException ex) {
            return true;
        }
    }

    static String serialize(List<SavedAccount> accounts) {
        JSONArray array = new JSONArray();
        for (SavedAccount account : accounts) {
            try {
                array.put(account.toJson());
            } catch (JSONException ex) {
                Logger.printException(() -> "Accounts: could not write " + account.shownName(), ex);
            }
        }
        return array.toString();
    }

    // endregion
}
