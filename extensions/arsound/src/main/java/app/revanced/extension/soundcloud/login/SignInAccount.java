package app.revanced.extension.soundcloud.login;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;

import java.lang.ref.WeakReference;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.shared.Utils;
import app.revanced.extension.soundcloud.debug.LogExport;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;
import app.revanced.extension.soundcloud.shared.Lang;
import app.revanced.extension.soundcloud.shared.Toasts;

/**
 * SoundCloud's sign-in puts the new account into Android's account manager ({@code AccountOperations}, patched by
 * {@code misc/account/SignInAccountPatch}). Android can refuse it with a bare {@code false}: then SoundCloud shows only
 * "Could not sign in. Try again", although the token was already received.
 * <p>
 * Android 14 refuses ({@code AccountManagerService.addAccountInternal}) when the user is locked, when a row with the
 * same name and type is already in its database but hidden from the app (left over from an earlier install), when the
 * phone holds more than 100 accounts of all apps, or when its database does not take the row. Only the second can be
 * worked around from here: the account is put in under another name. The rest are explained to the user.
 */
public final class SignInAccount {
    /** How many other names are tried after the usual one is refused. */
    static final int ALTERNATE_NAMES = 3;
    /** Android's own limit on the length of an account name. */
    static final int MAX_NAME_LENGTH = 200;
    /** SoundCloud shows its own error dialog after the refusal: Arsound's explanation comes above it. */
    private static final long EXPLAIN_DELAY_MS = 900;

    /** The sign-in screen on top, to show the explanation over; set by {@link LoginLogsButton}. */
    private static WeakReference<Activity> signInScreen = new WeakReference<>(null);

    private SignInAccount() {
    }

    static void onSignInScreen(Activity activity) {
        signInScreen = new WeakReference<>(activity);
    }

    /**
     * Injection point: replaces {@code AccountManager.addAccountExplicitly} in SoundCloud's sign-in. Returns the
     * account that went in (the given one, or one under another name), or null when Android refused every name.
     */
    public static Account add(AccountManager manager, Account account, String password, Bundle extras) {
        if (manager.addAccountExplicitly(account, password, extras)) return account;

        Logger.printInfo(() -> "Sign-in: Android refused to add the account; " + describe(manager, account));
        for (int attempt = 1; attempt <= ALTERNATE_NAMES; attempt++) {
            String name = alternateName(account.name, attempt);
            if (name == null) break;
            Account other = new Account(name, account.type);
            try {
                if (manager.addAccountExplicitly(other, password, extras)) {
                    Logger.printInfo(() -> "Sign-in: added under another name (" + name.length()
                            + " chars): Android keeps a hidden account under the usual one");
                    return other;
                }
            } catch (RuntimeException ex) {
                Logger.printException(() -> "Sign-in: adding under another name failed", ex);
                break;
            }
        }
        Logger.printInfo(() -> "Sign-in: Android refused every name too; " + describe(manager, account));
        explainLater();
        return null;
    }

    /** Injection point: SoundCloud's sign-in goes on as if {@code addAccountExplicitly} returned this. */
    public static boolean added(Object account) {
        return account != null;
    }

    /**
     * The name for the given retry: the usual one with " (2)", " (3)" and so on, cut so it stays within Android's
     * limit; null when nothing is left to add a number to.
     */
    static String alternateName(String name, int attempt) {
        if (name == null || name.isEmpty() || attempt < 1) return null;
        String suffix = " (" + (attempt + 1) + ")";
        int keep = Math.min(name.length(), MAX_NAME_LENGTH - suffix.length());
        if (keep <= 0) return null;
        return name.substring(0, keep) + suffix;
    }

    /** What the app can see of the refusal, for the logs (no account names: they are the user's). */
    private static String describe(AccountManager manager, Account account) {
        StringBuilder text = new StringBuilder();
        text.append("name length ").append(account.name == null ? -1 : account.name.length());
        try {
            text.append(", accounts of this type the app sees ").append(manager.getAccountsByType(account.type).length);
            text.append(", accounts of all apps the app sees ").append(manager.getAccounts().length);
        } catch (RuntimeException ex) {
            text.append(", accounts not readable: ").append(ex.getClass().getSimpleName());
        }
        try {
            Context context = Utils.getContext();
            UserManager users = context == null ? null : (UserManager) context.getSystemService(Context.USER_SERVICE);
            if (users != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                text.append(", user unlocked ").append(users.isUserUnlocked());
            }
        } catch (RuntimeException ignored) {
        }
        return text.toString();
    }

    private static void explainLater() {
        Utils.runOnMainThreadDelayed(SignInAccount::explain, EXPLAIN_DELAY_MS);
    }

    private static void explain() {
        String message = Lang.text(
                "SoundCloud пустил вас, но Android не дал сохранить аккаунт на телефоне. Чаще всего так бывает, когда "
                        + "на телефоне больше 100 аккаунтов всех приложений. Откройте Настройки → Аккаунты "
                        + "(на Xiaomi: «Аккаунты и синхронизация»), удалите ненужные и войдите снова. Не помогло — "
                        + "сохраните логи и пришлите их нам.",
                "SoundCloud let you in, but Android would not keep the account on the phone. Most often the phone "
                        + "holds more than 100 accounts of all apps. Open Settings → Accounts, remove the ones you "
                        + "do not need and sign in again. If that does not help, save the logs and send them to us.");
        Activity activity = signInScreen.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Context context = Utils.getContext();
            if (context != null) Toasts.showLong(context, message);
            return;
        }
        try {
            new ArsoundDialog(activity)
                    .setTitle(Lang.text("Android не сохранил аккаунт", "Android did not keep the account"))
                    .setMessage(message)
                    .setPositiveButton(Lang.text("Понятно", "OK"), null)
                    .setNegativeButton(Lang.text("Сохранить логи", "Save logs"),
                            (dialog, which) -> LogExport.save(activity, false))
                    .show();
        } catch (RuntimeException ex) {
            Logger.printException(() -> "Sign-in: the explanation was not shown", ex);
            Toasts.showLong(activity, message);
        }
    }
}
