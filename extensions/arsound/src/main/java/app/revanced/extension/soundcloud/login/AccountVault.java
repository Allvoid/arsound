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
    /** A switch older than this is not finished any more: the app was killed in the middle of it. */
    private static final long PENDING_FOR_MS = 120_000;
    /** More are not kept: the oldest goes. */
    static final int MAX_ACCOUNTS = 10;

    private AccountVault() {
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static synchronized List<SavedAccount> list(Context context) {
        return parse(preferences(context).getString(LIST, null));
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

    /** Keeps the account, in place of an older copy of it, at the top of the list. */
    static synchronized void save(Context context, SavedAccount account) {
        write(context, upsert(list(context), account));
    }

    static synchronized void remove(Context context, String name) {
        List<SavedAccount> accounts = list(context);
        List<SavedAccount> left = new ArrayList<>();
        for (SavedAccount account : accounts) {
            if (!account.name.equals(name)) left.add(account);
        }
        if (left.size() != accounts.size()) write(context, left);
    }

    private static void write(Context context, List<SavedAccount> accounts) {
        preferences(context).edit().putString(LIST, serialize(accounts)).commit();
    }

    static void setPending(Context context, String what) {
        preferences(context).edit().putString(PENDING, what).putLong(PENDING_AT, System.currentTimeMillis()).commit();
    }

    /** The switch under way, or null; one left by an app that was killed long ago is dropped. */
    static String pending(Context context) {
        SharedPreferences preferences = preferences(context);
        String what = preferences.getString(PENDING, null);
        if (what == null) return null;
        long age = System.currentTimeMillis() - preferences.getLong(PENDING_AT, 0);
        if (age < 0 || age > PENDING_FOR_MS) {
            clearPending(context);
            return null;
        }
        return what;
    }

    static void clearPending(Context context) {
        preferences(context).edit().remove(PENDING).remove(PENDING_AT).commit();
    }

    static void setLastCurrent(Context context, String name) {
        if (name == null || name.equals(preferences(context).getString(LAST_CURRENT, null))) return;
        preferences(context).edit().putString(LAST_CURRENT, name).commit();
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
            Logger.printException(() -> "Accounts: the saved list is damaged, starting a new one", ex);
        }
        return accounts;
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
