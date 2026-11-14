package app.revanced.extension.soundcloud.login;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.settings.Settings;
import app.revanced.extension.soundcloud.shared.AppResources;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Dimens;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.update.UpdateChecker;

/**
 * Switching between SoundCloud accounts (bot ticket #64), the way other apps do it: the account signed in now is kept,
 * another kept one is put in its place, or the sign-in screen opens for one more.
 * <p>
 * SoundCloud keeps one account, in Android's account manager. A switch takes it out the way SoundCloud's own sign-out
 * does, but without telling the server: SoundCloud's sign-out ends the session there ({@code /sign_out}), and the kept
 * tokens would die with it. Taking the account out makes SoundCloud wipe the old user's data on the phone (likes,
 * playlists, history, its own offline tracks; {@code UserDataPurger}). Only when that is done
 * ({@link #subscribePurge}) is the next account put in, with the tokens and data SoundCloud wrote at its sign-in, and the
 * app started again, so nothing of one user is mixed with the other. Arsound's own downloads are files in the phone's
 * Music folder and stay.
 */
public final class AccountSwitch {
    /** SoundCloud's names in the account manager, as {@code SoundCloudTokenOperations} and {@code AccountOperations} use them. */
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String SCOPE = "scope";
    private static final String EXPIRES_IN = "expires_in";
    private static final String USER_ID = "currentUserId";
    private static final String EMAIL = "KEY_USER_EMAIL";
    /** How often Android syncs the account, as SoundCloud asks at its sign-in ({@code SyncConfig}). */
    private static final long SYNC_EVERY_SECONDS = 3600;
    /** If SoundCloud never says its wipe is over, the switch goes on after this. */
    private static final long PURGE_WAIT_MS = 25_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> starter = new WeakReference<>(null);
    private static Dialog progress;
    private static final Runnable PURGE_TIMEOUT = () -> {
        Logger.printInfo(() -> "Accounts: SoundCloud did not report the end of its wipe, going on");
        Utils.runOnBackgroundThread(() -> finishPending(Utils.getContext()));
    };

    private AccountSwitch() {
    }

    // region Account manager

    static String accountType(Context context) {
        int id = AppResources.identifier(context, "account_type", "string");
        return id != 0 ? context.getString(id) : context.getPackageName() + ".account";
    }

    /** SoundCloud's account on this phone, or null when nobody is signed in. */
    static Account current(Context context) {
        try {
            Account[] accounts = AccountManager.get(context).getAccountsByType(accountType(context));
            return accounts.length == 0 ? null : accounts[0];
        } catch (Exception ex) {
            Logger.printException(() -> "Accounts: could not read the account manager", ex);
            return null;
        }
    }

    /** The name of the signed-in account, or null. */
    static String currentName(Context context) {
        Account account = current(context);
        return account == null ? null : account.name;
    }

    /**
     * Keeps a copy of the signed-in account, so it can be come back to. Run off the main thread: the account manager is
     * another process. Its tokens change while it is used (SoundCloud renews them), so the copy is made again on every
     * return to the app once the list is in use.
     */
    static SavedAccount snapshotCurrent(Context context) {
        Account account = current(context);
        if (account == null) return null;
        try {
            AccountManager manager = AccountManager.get(context);
            SavedAccount saved = new SavedAccount(
                    account.name,
                    manager.getUserData(account, USER_ID),
                    manager.peekAuthToken(account, ACCESS_TOKEN),
                    manager.peekAuthToken(account, REFRESH_TOKEN),
                    manager.getUserData(account, SCOPE),
                    manager.getUserData(account, EXPIRES_IN),
                    manager.getUserData(account, EMAIL),
                    System.currentTimeMillis());
            AccountVault.setLastCurrent(context, account.name);
            if (!saved.usable()) {
                Logger.printInfo(() -> "Accounts: the signed-in account has no tokens yet, not kept");
                return null;
            }
            // Nothing to write when the copy at the top is the same: this runs on every return to the app.
            java.util.List<SavedAccount> kept = AccountVault.list(context);
            if (!kept.isEmpty() && kept.get(0).sameSession(saved)) return kept.get(0);
            AccountVault.save(context, saved);
            return saved;
        } catch (Exception ex) {
            Logger.printException(() -> "Accounts: could not keep the signed-in account", ex);
            return null;
        }
    }

    /** Puts a kept account into the account manager, as SoundCloud's sign-in does ({@code AccountOperations}). */
    private static boolean putIn(Context context, SavedAccount saved) {
        try {
            AccountManager manager = AccountManager.get(context);
            String type = accountType(context);
            // Another account left there (a switch that broke off) would be the one SoundCloud reads.
            for (Account other : manager.getAccountsByType(type)) {
                if (!other.name.equals(saved.name)) manager.removeAccountExplicitly(other);
            }
            Account account = new Account(saved.name, type);
            if (!manager.addAccountExplicitly(account, null, null) && current(context) == null) {
                Logger.printInfo(() -> "Accounts: the account manager refused " + saved.shownName());
                return false;
            }
            manager.setUserData(account, USER_ID, saved.userId);
            if (saved.email != null) manager.setUserData(account, EMAIL, saved.email);
            if (saved.scope != null) manager.setUserData(account, SCOPE, saved.scope);
            if (saved.expiresIn != null) manager.setUserData(account, EXPIRES_IN, saved.expiresIn);
            if (saved.accessToken != null) manager.setAuthToken(account, ACCESS_TOKEN, saved.accessToken);
            if (saved.refreshToken != null) manager.setAuthToken(account, REFRESH_TOKEN, saved.refreshToken);
            int authority = AppResources.identifier(context, "account_authority", "string");
            if (authority != 0) {
                String name = context.getString(authority);
                ContentResolver.setIsSyncable(account, name, 1);
                ContentResolver.setSyncAutomatically(account, name, true);
                ContentResolver.addPeriodicSync(account, name, new Bundle(), SYNC_EVERY_SECONDS);
            }
            AccountVault.setLastCurrent(context, saved.name);
            Logger.printInfo(() -> "Accounts: signed in as " + saved.shownName());
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "Accounts: could not put the account in", ex);
            return false;
        }
    }

    // endregion

    // region Switching

    /** Signs in with another kept account in place of the one signed in now. */
    static void switchTo(Activity activity, SavedAccount target) {
        start(activity, target.name);
    }

    /** Keeps the account signed in now and opens SoundCloud's sign-in for one more. */
    static void addAccount(Activity activity) {
        start(activity, AccountVault.PENDING_ADD);
    }

    /** On the sign-in screen, with nobody signed in: puts a kept account in and starts the app with it. */
    static void signInSaved(Activity activity, SavedAccount target) {
        Context context = activity.getApplicationContext();
        showProgress(activity);
        Utils.runOnBackgroundThread(() -> {
            if (current(context) != null) {
                // Signed in after all (in another window): this is a switch.
                MAIN.post(() -> start(activity, target.name));
                return;
            }
            boolean done = putIn(context, target);
            MAIN.post(() -> {
                if (done) restart(context);
                else failed(activity);
            });
        });
    }

    private static void start(Activity activity, String what) {
        Context context = activity.getApplicationContext();
        starter = new WeakReference<>(activity);
        showProgress(activity);
        Utils.runOnBackgroundThread(() -> {
            snapshotCurrent(context);
            Account account = current(context);
            if (account != null && account.name.equals(what)) {
                MAIN.post(AccountSwitch::hideProgress);
                return;
            }
            AccountVault.setPending(context, what);
            Settings.flush();
            if (account == null) {
                finishPending(context);
                return;
            }
            MAIN.postDelayed(PURGE_TIMEOUT, PURGE_WAIT_MS);
            Logger.printInfo(() -> "Accounts: taking out " + SavedAccount.shownName(account.name));
            // Not SoundCloud's sign-out: that ends the session on the server too. This only takes the account off the
            // phone; SoundCloud's authenticator then wipes the user's data (subscribePurge hears when it is done).
            AccountManager.get(context).removeAccount(account, null, null);
        });
    }

    /**
     * Injection point: SoundCloud's authenticator, when an account is taken out, starts wiping the user's data and
     * subscribes to that work; this subscribes in its place, to hear when the wipe is over. Called with the wipe
     * ({@code io.reactivex.rxjava3.core.Completable}).
     */
    public static void subscribePurge(Object purge) {
        try {
            ClassLoader loader = purge.getClass().getClassLoader();
            Class<?> action = Class.forName("io.reactivex.rxjava3.functions.Action", false, loader);
            Class<?> consumer = Class.forName("io.reactivex.rxjava3.functions.Consumer", false, loader);
            Object onDone = Proxy.newProxyInstance(loader, new Class<?>[]{action}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                onPurged(null);
                return null;
            });
            Object onError = Proxy.newProxyInstance(loader, new Class<?>[]{consumer}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                onPurged(args != null && args.length > 0 && args[0] instanceof Throwable ? (Throwable) args[0] : null);
                return null;
            });
            Method subscribe = purge.getClass().getMethod("subscribe", action, consumer);
            subscribe.invoke(purge, onDone, onError);
        } catch (Throwable ex) {
            Logger.printException(() -> "Accounts: could not follow SoundCloud's wipe, subscribing the plain way", ex);
            try {
                purge.getClass().getMethod("subscribe").invoke(purge);
            } catch (Throwable again) {
                Logger.printException(() -> "Accounts: SoundCloud's wipe did not start", again);
            }
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            default:
                return "ArsoundAccountSwitch";
        }
    }

    private static void onPurged(Throwable error) {
        if (error != null) Logger.printException(() -> "Accounts: SoundCloud's wipe ended with an error", error);
        else Logger.printInfo(() -> "Accounts: SoundCloud's wipe is over");
        Context context = Utils.getContext();
        if (context == null) return;
        MAIN.removeCallbacks(PURGE_TIMEOUT);
        if (AccountVault.pending(context) == null) {
            // SoundCloud's own sign-out: it ended the session on the server, the kept tokens of that account are dead.
            String gone = AccountVault.lastCurrent(context);
            if (gone != null) {
                AccountVault.remove(context, gone);
                Logger.printInfo(() -> "Accounts: signed out in SoundCloud, " + SavedAccount.shownName(gone) + " dropped from the list");
            }
            return;
        }
        Utils.runOnBackgroundThread(() -> finishPending(context));
    }

    /** The wipe is over: puts the next account in (or none, for one more) and starts the app again. */
    private static synchronized void finishPending(Context context) {
        String what = AccountVault.pending(context);
        if (what == null) return;
        AccountVault.clearPending(context);
        if (!AccountVault.PENDING_ADD.equals(what)) {
            SavedAccount target = AccountVault.find(context, what);
            if (target == null || !putIn(context, target)) {
                Logger.printInfo(() -> "Accounts: could not sign in as " + SavedAccount.shownName(what) + ", the sign-in screen opens");
            }
        }
        MAIN.post(() -> restart(context));
    }

    private static void restart(Context context) {
        hideProgress();
        Settings.flush();
        Activity activity = starter.get();
        if (activity == null || activity.isFinishing()) activity = UpdateChecker.resumedActivity();
        Context from = activity != null ? activity : context;
        Intent launch = context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
        if (launch == null) return;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        MAIN.postDelayed(() -> {
            from.startActivity(launch);
            Runtime.getRuntime().exit(0);
        }, 150);
    }

    // endregion

    // region Progress

    private static void showProgress(Activity activity) {
        hideProgress();
        ProgressBar spinner = new ProgressBar(activity);
        int pad = Dimens.dp(activity, 12);
        spinner.setPadding(pad, pad, pad, pad);
        progress = new ArsoundDialog(activity)
                .setTitle(Lang.text("Меняем аккаунт…", "Switching account…"))
                .setMessage(Lang.text("SoundCloud убирает данные прошлого аккаунта с телефона, потом приложение "
                                + "запустится заново. Скачанные треки остаются.",
                        "SoundCloud clears the previous account's data off the phone, then the app starts again. "
                                + "Downloaded tracks stay."))
                .setView(spinner)
                .setCancelable(false)
                .show();
    }

    private static void hideProgress() {
        if (progress == null) return;
        try {
            progress.dismiss();
        } catch (Exception ignored) {
            // Its window is gone with the activity.
        }
        progress = null;
    }

    private static void failed(Activity activity) {
        hideProgress();
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Не получилось войти", "Could not sign in"))
                .setMessage(Lang.text("Войдите в этот аккаунт обычным способом.", "Sign in to this account the usual way."))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // endregion
}
