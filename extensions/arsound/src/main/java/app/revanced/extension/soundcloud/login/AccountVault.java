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
    private static final long PENDING_HERE_FOR_MS = 15 * 60_000;
    /** The switch under way was started by this run of the app (not left by one that was killed). */
    private static volatile boolean pendingHere;
    /** More are not kept: the oldest goes. */
    static final int MAX_ACCOUNTS = 10;

    private AccountVault() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static synchronized List<SavedAccount> list(Context context) {
        String text = preferences(context).getString(LIST, null);
        if (damaged(text)) {
            // Dropped, so it is not read (and logged) again on every call; the next save starts a clean list.
            preferences(context).edit().remove(LIST).commit();
            return new ArrayList<>();
        }
        return parse(text);
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
        return write(context, upsert(list(context), account));
    }

    static synchronized void remove(Context context, String name) {
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
    /** How long after a switch put the next account in the end of the old wipe is still part of that switch. */
    private static final long FINISHED_GRACE_MS = 5_000;

    /** The switch has put the next account in and the app is about to start again. */
    static void markFinished() {
        finishedAt = System.currentTimeMillis();
    }

    static boolean justFinished() {
        long age = System.currentTimeMillis() - finishedAt;
        return finishedAt != 0 && age >= 0 && age < FINISHED_GRACE_MS;
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

    static List<SavedAccount> upsert(List<SavedAccount> accounts, SavedAccount account) {
        List<SavedAccount> result = new ArrayList<>();
        result.add(account);
        for (SavedAccount other : accounts) {
            if (!other.name.equals(account.name) && result.size() < MAX_ACCOUNTS) result.add(other);
        }
        return result;
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
