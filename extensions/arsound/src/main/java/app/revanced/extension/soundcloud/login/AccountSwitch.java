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
    /** If SoundCloud does not say its wipe is over by then, the user is asked: wait more or go on. */
    private static final long PURGE_WAIT_MS = 25_000;
    /** The wait when the wipe could not be followed at all (SoundCloud changed its code): nothing will report its end. */
    private static final long UNCONFIRMED_WAIT_MS = 8_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The wipes SoundCloud runs now, of ours or of the user's own sign-out. */
    private static final PurgeGate PURGES = new PurgeGate();
    /** A switch ended while no screen was in front: the app is started anew at the next screen. */
    private static volatile boolean restartNeeded;
    private static WeakReference<Activity> starter = new WeakReference<>(null);
    // Weak: a recreated activity (a turned phone) must not be held by the dialog of its old window.
    private static WeakReference<Dialog> progress = new WeakReference<>(null);
    private static final Runnable PURGE_TIMEOUT = () -> {
        Logger.printInfo(() -> "Accounts: SoundCloud did not report the end of its wipe, asking the user");
        Utils.runOnBackgroundThread(() -> {
            Context context = Utils.getContext();
            // Not a switch of ours (SoundCloud's own sign-out): nothing to ask.
            if (context == null) return;
            if (AccountVault.pending(context) == null) {
                // The switch is gone while a spinner may still cover the screen (the mark expired during "Wait more").
                MAIN.post(AccountSwitch::switchGone);
                return;
            }
            askAboutWipe(false);
        });
    };

    /** What a try at keeping the signed-in account came to. */
    static final class Kept {
        /** The kept copy, or null when there is nothing to keep (nobody signed in, no tokens yet) or it failed. */
        final SavedAccount saved;
        /** True when there was something to keep and it did not reach the disk: a switch must not remove the account. */
        final boolean failed;
        /** True when it did not fit: ten other accounts are kept, and none is dropped for it. */
        final boolean full;

        Kept(SavedAccount saved, boolean failed) {
            this(saved, failed, false);
        }

        Kept(SavedAccount saved, boolean failed, boolean full) {
            this.saved = saved;
            this.failed = failed;
            this.full = full;
        }
    }

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
        return keepCurrent(context).saved;
    }

    /** As {@link #snapshotCurrent}, but tells "nothing to keep" from "could not keep it". */
    static Kept keepCurrent(Context context) {
        Account account = current(context);
        if (account == null) return new Kept(null, false);
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
                return new Kept(null, false);
            }
            // Nothing to write when the copy at the top is the same: this runs on every return to the app.
            java.util.List<SavedAccount> kept = AccountVault.list(context);
            if (!kept.isEmpty() && kept.get(0).sameSession(saved)) return new Kept(kept.get(0), false);
            if (AccountVault.isFullFor(context, saved.name)) {
                Logger.printInfo(() -> "Accounts: ten accounts are kept, the signed-in one is not added");
                return new Kept(null, true, true);
            }
            if (!AccountVault.save(context, saved)) {
                Logger.printInfo(() -> "Accounts: the copy of the signed-in account was not written");
                return new Kept(null, true);
            }
            return new Kept(saved, false);
        } catch (Exception ex) {
            Logger.printException(() -> "Accounts: could not keep the signed-in account", ex);
            return new Kept(null, true);
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
            AccountVault.SignInGate gate = AccountVault.signInGate(PURGES.busy(System.currentTimeMillis()),
                    AccountVault.pending(context) != null, AccountVault.wipeUnfinished(context));
            if (gate == AccountVault.SignInGate.WAIT) {
                // A wipe of the old account's data is running (a switch of ours, or the user's own sign-out): a restart now
                // would cut it off.
                MAIN.post(() -> {
                    hideProgress();
                    if (!screenAlive(activity)) return;
                    new ArsoundDialog(activity)
                            .setTitle(Lang.text("Данные прошлого аккаунта ещё убираются", "The previous account's data is still being cleared"))
                            .setMessage(Lang.text("Подождите немного и выберите аккаунт ещё раз.", "Wait a little and pick the account again."))
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
                return;
            }
            if (gate == AccountVault.SignInGate.WARN) {
                MAIN.post(() -> {
                    hideProgress();
                    if (!screenAlive(activity)) return;
                    new ArsoundDialog(activity)
                            .setTitle(Lang.text("Прошлый аккаунт убран не до конца", "The previous account was not cleared completely"))
                            .setMessage(Lang.text("Очистка данных прошлого аккаунта не закончилась: часть его лайков, плейлистов и истории "
                                            + "может показаться у этого аккаунта.",
                                    "The clearing of the previous account's data did not finish: some of its likes, playlists and "
                                            + "history may show up under this account."))
                            .setPositiveButton(Lang.text("Всё равно войти", "Sign in anyway"), (d, which) -> {
                                Utils.runOnBackgroundThread(() -> AccountVault.clearWipeOpen(context));
                                signInSaved(activity, target);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                });
                return;
            }
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

    private static void askToWait(Activity activity) {
        if (!screenAlive(activity)) return;
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Данные прошлого аккаунта ещё убираются", "The previous account's data is still being cleared"))
                .setMessage(Lang.text("Подождите немного и повторите.", "Wait a little and try again."))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void askAboutCutWipe(Activity activity, Runnable goOn) {
        if (!screenAlive(activity)) return;
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Прошлый аккаунт убран не до конца", "The previous account was not cleared completely"))
                .setMessage(Lang.text("Очистка данных прошлого аккаунта не закончилась: часть его лайков, плейлистов и истории "
                                + "может показаться у следующего.",
                        "The clearing of the previous account's data did not finish: some of its likes, playlists and "
                                + "history may show up under the next one."))
                .setPositiveButton(Lang.text("Всё равно продолжить", "Continue anyway"), (d, which) -> goOn.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void start(Activity activity, String what) {
        Context context = activity.getApplicationContext();
        starter = new WeakReference<>(activity);
        showProgress(activity);
        Utils.runOnBackgroundThread(() -> {
            // A wipe still running (ours or the user's own sign-out) or a cut one: the same wait and warning as at the sign-in.
            AccountVault.SignInGate gate = AccountVault.signInGate(PURGES.busy(System.currentTimeMillis()),
                    AccountVault.pending(context) != null, AccountVault.wipeUnfinished(context));
            if (gate == AccountVault.SignInGate.WAIT) {
                MAIN.post(() -> {
                    hideProgress();
                    askToWait(activity);
                });
                return;
            }
            if (gate == AccountVault.SignInGate.WARN) {
                MAIN.post(() -> {
                    hideProgress();
                    // Warned once: the mark is cleared first, so the start that follows goes on.
                    askAboutCutWipe(activity, () -> Utils.runOnBackgroundThread(() -> {
                        AccountVault.clearWipeOpen(context);
                        MAIN.post(() -> start(activity, what));
                    }));
                });
                return;
            }
            Kept kept = keepCurrent(context);
            Account account = current(context);
            if (account != null && account.name.equals(what)) {
                MAIN.post(AccountSwitch::hideProgress);
                return;
            }
            // Taking the account out wipes its data, and the only way back is the kept copy: no usable copy, no switch (a
            // copy that was not made because the tokens are not there yet leaves nothing to come back to).
            if (!kept.failed && !AccountVault.canLeave(account != null, account == null ? null : AccountVault.find(context, account.name))) {
                Logger.printInfo(() -> "Accounts: the switch is stopped, there is no usable copy of the signed-in account");
                MAIN.post(() -> noCopy(activity));
                return;
            }
            if (kept.failed || !AccountVault.setPending(context, what)) {
                Logger.printInfo(() -> "Accounts: the switch is stopped, the account could not be kept");
                final boolean full = kept.full;
                MAIN.post(() -> {
                    if (full) listFull(activity);
                    else couldNotKeep(activity);
                });
                return;
            }
            Settings.flush();
            if (account == null) {
                finishPending(context);
                return;
            }
            MAIN.postDelayed(PURGE_TIMEOUT, PURGE_WAIT_MS);
            AccountVault.markWipeOpen(context);
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
        Object onDone;
        Object onError;
        Method subscribe;
        try {
            ClassLoader loader = purge.getClass().getClassLoader();
            Class<?> action = Class.forName("io.reactivex.rxjava3.functions.Action", false, loader);
            Class<?> consumer = Class.forName("io.reactivex.rxjava3.functions.Consumer", false, loader);
            onDone = Proxy.newProxyInstance(loader, new Class<?>[]{action}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                onPurged(null);
                return null;
            });
            onError = Proxy.newProxyInstance(loader, new Class<?>[]{consumer}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                onPurged(args != null && args.length > 0 && args[0] instanceof Throwable ? (Throwable) args[0] : null);
                return null;
            });
            subscribe = purge.getClass().getMethod("subscribe", action, consumer);
        } catch (Throwable ex) {
            // Nothing was subscribed yet (only the proxies and the method were looked for), so the plain way is safe.
            Logger.printException(() -> "Accounts: could not follow SoundCloud's wipe, subscribing the plain way", ex);
            try {
                purge.getClass().getMethod("subscribe").invoke(purge);
            } catch (Throwable again) {
                Logger.printException(() -> "Accounts: SoundCloud's wipe did not start", again);
            }
            // Its end will not be reported: a short wait, then the user is asked, instead of the full one.
            Context context = Utils.getContext();
            if (context != null) {
                Utils.runOnBackgroundThread(() -> {
                    if (AccountVault.pending(context) == null) return;
                    MAIN.post(() -> {
                        MAIN.removeCallbacks(PURGE_TIMEOUT);
                        MAIN.postDelayed(PURGE_TIMEOUT, UNCONFIRMED_WAIT_MS);
                    });
                });
            }
            return;
        }
        PURGES.begin(System.currentTimeMillis());
        // Whoever started this wipe (a switch of ours, the user's own sign-out): a cut one is told at the next sign-in.
        Context purging = Utils.getContext();
        if (purging != null) AccountVault.markWipeOpen(purging);
        try {
            subscribe.invoke(purge, onDone, onError);
        } catch (Throwable ex) {
            PURGES.end();
            // Never a second subscribe after this one began: the wipe would run twice.
            Logger.printException(() -> "Accounts: SoundCloud's wipe could not be followed", ex);
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
        PURGES.end();
        // Only a wipe that ended well closes the mark: one that failed or was cut leaves it, and the next sign-in warns.
        if (error == null) AccountVault.clearWipeOpen(context);
        // Read before pending(): a stale mark is cleared there, and the flag with it.
        boolean gaveUp = AccountVault.isAbandoned();
        AccountVault.AfterPurge after = AccountVault.afterPurge(AccountVault.pending(context) != null, gaveUp, error != null,
                AccountVault.justFinished());
        if (after == AccountVault.AfterPurge.LEAVE) {
            Logger.printInfo(() -> "Accounts: the wipe ended after the switch was given up, nothing is changed");
            // The wipe is over, so nothing is waited for: a mark kept on would turn a later sign-out of SoundCloud's own
            // into this switch's end.
            Utils.runOnBackgroundThread(() -> AccountVault.clearPending(context));
            return;
        }
        if (after == AccountVault.AfterPurge.OWN_SIGN_OUT) {
            // RULE (20.11): a kept account is never deleted by itself. The user's own sign-out in SoundCloud leaves its copy
            // in "My accounts" (its tokens may be dead on the server; the user removes it there, or a sign-in with it that
            // the server refuses says so). Guessing which end of a wipe belongs to what lost copies four times.
            Logger.printInfo(() -> "Accounts: SoundCloud's wipe ended with no switch of ours, the kept accounts stay");
            return;
        }
        // A wipe that failed leaves the old user's data on the phone: the user decides, nothing goes on by itself.
        if (after == AccountVault.AfterPurge.ASK) {
            askAboutWipe(true);
            return;
        }
        Utils.runOnBackgroundThread(() -> finishPending(context));
    }

    /**
     * The wipe of the old account's data did not finish in time (or failed): keeps the switch waiting and asks. Not
     * "putting the next account in" by itself, since the process exits right after and would stop a wipe half-way.
     */
    private static void askAboutWipe(boolean failed) {
        MAIN.post(() -> {
            // The end of the wipe may have come while this waited for the main thread: the switch is not asked about then.
            Context now = Utils.getContext();
            if (!failed && now != null && AccountVault.pending(now) == null) {
                hideProgress();
                return;
            }
            Activity activity = UpdateChecker.resumedActivity();
            if (!screenAlive(activity)) activity = starter.get();
            if (!screenAlive(activity)) {
                // Nobody to ask: the switch stays pending (and goes stale on its own), the app is not restarted.
                Logger.printInfo(() -> "Accounts: the wipe did not finish, no screen to ask on");
                hideProgress();
                // Given up, but SoundCloud's wipe of the old account still runs: the mark stays (its end must not look like
                // a sign-out of SoundCloud's own, which would drop the kept copy of the account left), and the end of the
                // wipe neither removes anything nor restarts the app in the background. A wipe that already failed has no
                // end to wait for: the mark goes at once.
                Context left = Utils.getContext();
                if (failed && left != null) Utils.runOnBackgroundThread(() -> AccountVault.clearPending(left));
                else AccountVault.markAbandoned();
                return;
            }
            hideProgress();
            Activity screen = activity;
            Context context = screen.getApplicationContext();
            ArsoundDialog dialog = new ArsoundDialog(screen)
                    .setCancelable(false)
                    .setTitle(failed
                            ? Lang.text("Не удалось убрать данные прошлого аккаунта", "Could not clear the previous account's data")
                            : Lang.text("Данные прошлого аккаунта ещё убираются", "The previous account's data is still being cleared"))
                    .setMessage(Lang.text("Если продолжить сейчас, часть данных прошлого аккаунта (лайки, плейлисты, "
                                    + "история) может остаться на телефоне и показаться у нового.",
                            "If you go on now, some of the previous account's data (likes, playlists, history) may "
                                    + "stay on the phone and show up under the new one."));
            if (failed) {
                dialog.setPositiveButton(Lang.text("Всё равно продолжить", "Continue anyway"),
                        (d, which) -> continueSwitch(screen, context));
                dialog.setNegativeButton(Lang.text("Не сейчас", "Not now"),
                        (d, which) -> Utils.runOnBackgroundThread(() -> AccountVault.clearPending(context)));
            } else {
                dialog.setPositiveButton(Lang.text("Ждать ещё", "Wait more"), (d, which) -> {
                    showProgress(screen);
                    MAIN.postDelayed(PURGE_TIMEOUT, PURGE_WAIT_MS);
                    // The mark would look abandoned after two minutes of waiting.
                    Utils.runOnBackgroundThread(() -> AccountVault.touchPending(context));
                });
                dialog.setNegativeButton(Lang.text("Всё равно продолжить", "Continue anyway"),
                        (d, which) -> continueSwitch(screen, context));
            }
            progress = new WeakReference<>(dialog.show());
        });
    }

    private static void continueSwitch(Activity activity, Context context) {
        showProgress(activity);
        Utils.runOnBackgroundThread(() -> finishPending(context));
    }

    /** The wipe is over: puts the next account in (or none, for one more) and starts the app again. */
    private static synchronized void finishPending(Context context) {
        String what = AccountVault.pending(context);
        if (what == null) {
            // Nothing to go on with (the mark expired while the question waited): the screen must not stay covered.
            MAIN.post(AccountSwitch::switchGone);
            return;
        }
        if (!AccountVault.PENDING_ADD.equals(what)) {
            SavedAccount target = AccountVault.find(context, what);
            if (target == null || !putIn(context, target)) {
                Logger.printInfo(() -> "Accounts: could not sign in as " + SavedAccount.shownName(what) + ", the sign-in screen opens");
            }
        }
        // After the account is in: a wipe that ends before this point still finds the mark and waits for this method. The
        // end of a wipe in the moments after it is told from a sign-out by justFinished.
        AccountVault.markFinished();
        AccountVault.clearPending(context);
        MAIN.post(() -> restart(context));
    }

    /** The switch is gone (its mark expired): the progress goes and the user is told what to do. */
    private static void switchGone() {
        boolean covered = progress.get() != null;
        hideProgress();
        if (!covered) return;
        Activity activity = UpdateChecker.resumedActivity();
        if (activity == null || activity.isFinishing()) return;
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Смена аккаунта прервана", "The account switch was interrupted"))
                .setMessage(Lang.text("Слишком долго не было ответа. Войдите в нужный аккаунт из списка «Мои аккаунты» на экране входа.",
                        "There was no answer for too long. Sign in to the account you want from the My accounts list on the sign-in screen."))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Screen hook: a switch that ended in the background is finished now, with the app started anew. */
    static void performPendingRestart(Activity activity) {
        if (!restartNeeded) return;
        restartNeeded = false;
        restart(activity.getApplicationContext());
    }

    /** Whether a dialog can be shown on the screen: a finishing or destroyed one would throw. */
    static boolean screenAlive(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    private static void restart(Context context) {
        hideProgress();
        Settings.flush();
        // Only from a screen the user is looking at: from the background the system does not start an activity, and the exit
        // would only end the process (and the music). The new account is in; the next start shows it.
        Activity activity = UpdateChecker.resumedActivity();
        if (activity == null || activity.isFinishing()) {
            // Not ended here: the music would stop for nothing, and the new account is in already. The app starts anew at
            // its next screen (performPendingRestart), so it does not go on with the old account's state for long.
            Logger.printInfo(() -> "Accounts: the app is in the background, it restarts at its next screen");
            restartNeeded = true;
            return;
        }
        Context from = activity;
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
        if (!screenAlive(activity)) return;
        ProgressBar spinner = new ProgressBar(activity);
        int pad = Dimens.dp(activity, 12);
        spinner.setPadding(pad, pad, pad, pad);
        progress = new WeakReference<>(new ArsoundDialog(activity)
                .setTitle(Lang.text("Меняем аккаунт…", "Switching account…"))
                .setMessage(Lang.text("SoundCloud убирает данные прошлого аккаунта с телефона, потом приложение "
                                + "запустится заново. Скачанные треки остаются.",
                        "SoundCloud clears the previous account's data off the phone, then the app starts again. "
                                + "Downloaded tracks stay."))
                .setView(spinner)
                .setCancelable(false)
                .show());
    }

    private static void hideProgress() {
        Dialog dialog = progress.get();
        if (dialog == null) return;
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
            // Its window is gone with the activity.
        }
        progress = new WeakReference<>(null);
    }

    /** The account could not be kept on the phone, so nothing was changed. */
    private static void couldNotKeep(Activity activity) {
        hideProgress();
        if (!screenAlive(activity)) return;
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Не получилось сохранить аккаунт", "Could not keep the account"))
                .setMessage(Lang.text("Смена остановлена, ничего не изменилось. Проверьте свободное место на телефоне.",
                        "The switch was stopped, nothing was changed. Check the free space on the phone."))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void noCopy(Activity activity) {
        hideProgress();
        if (!screenAlive(activity)) return;
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Аккаунт пока нельзя сохранить", "The account cannot be kept yet"))
                .setMessage(Lang.text("У аккаунта ещё нет данных для входа. Смена остановлена, ничего не удалено: откройте "
                                + "SoundCloud на минуту и повторите.",
                        "The account has no sign-in data yet. The switch was stopped and nothing was removed: use SoundCloud "
                                + "for a minute and try again."))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** The account the full-list message was shown for last: not repeated at every return to the app. */
    private static volatile String toldFullFor;

    /**
     * On a return to the app: keeps a copy of the signed-in account; when the list is full and the account is not in it (a
     * fresh sign-in of another), says so once, so the user does not learn it at the next switch.
     */
    static void snapshotAndTell(Activity activity) {
        Kept kept = keepCurrent(activity);
        if (!kept.full) return;
        String name = currentName(activity);
        if (name == null || name.equals(toldFullFor)) return;
        toldFullFor = name;
        MAIN.post(() -> listFull(activity));
    }

    /** Ten accounts are kept and the one signed in is not among them: nothing is dropped, the user removes one. */
    static void listFull(Activity activity) {
        hideProgress();
        if (!screenAlive(activity)) return;
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Список аккаунтов полон", "The list of accounts is full"))
                .setMessage(Lang.text("Сохранено " + AccountVault.MAX_ACCOUNTS + " аккаунтов. Уберите один из сохранённых "
                                + "аккаунтов (долгое нажатие на строке в списке) и повторите: ничего не удаляется само.",
                        "Remove one of your saved accounts first (a long press on its row in the list), then try again: "
                                + AccountVault.MAX_ACCOUNTS + " are kept, and nothing is deleted by itself."))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private static void failed(Activity activity) {
        hideProgress();
        if (!screenAlive(activity)) return;
        new ArsoundDialog(activity)
                .setTitle(Lang.text("Не получилось войти", "Could not sign in"))
                .setMessage(Lang.text("Войдите в этот аккаунт обычным способом.", "Sign in to this account the usual way."))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // endregion
}
