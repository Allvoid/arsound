package app.revanced.extension.soundcloud.search;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.app.Activity;

import app.revanced.extension.shared.Logger;
import app.revanced.extension.soundcloud.permissions.WelcomePermissions;
import app.revanced.extension.soundcloud.shared.ArsoundDialog;

/** The question "Sign in to YouTube Music?" for a track that YouTube gives only to an account. */
final class SignInOffer {
    private static boolean shown;
    /** The dialog on screen, to ask whether it still is. */
    private static android.app.Dialog dialog;
    /** The user turned the offer down in this run of the app. */
    private static boolean declined;
    /** The user chose to sign in: the dismiss that follows is no refusal. */
    private static boolean signingIn;

    private SignInOffer() {
    }

    /**
     * Offers to sign in, once at a time.
     *
     * @param botCheck YouTube took the network for a bot; otherwise the track is age-restricted.
     */
    static void offer(boolean botCheck) {
        Activity current = SearchSourceSwitch.resumedActivity();
        // A dialog closed together with its screen never calls its dismiss listener: whether it is up is asked of it.
        if (shown && (dialog == null || !dialog.isShowing())) shown = false;
        if (current == null || current.isFinishing() || shown) return;
        // Turned down once: not asked again in this run of the app, so a batch of failures does not ask for each.
        if (declined) return;
        shown = true;
        try {
            ArsoundDialog built = new ArsoundDialog(current)
                    .setView(WelcomePermissions.createDialogContent(current,
                            botCheck
                                    ? text("YouTube принял запросы из этой сети за бота и просит войти в аккаунт. "
                                                    + "Так бывает с VPN и общими адресами; другая сеть тоже может помочь.",
                                            "YouTube took the requests of this network for a bot and asks to sign in. "
                                                    + "This happens with VPNs and shared addresses; another network may help too.")
                                    : text("У трека возрастное ограничение: YouTube отдаёт его только после входа в аккаунт.",
                                            "The track is age-restricted: YouTube gives it only to signed-in listeners."),
                            text("Войти в YouTube Music? Это по желанию: пароль вводится на странице Google, "
                                            + "а выйти можно в Настройках → Arsound → Аккаунт.",
                                    "Sign in to YouTube Music? It is optional: the password is typed into Google's page, "
                                            + "and you can sign out in Settings → Arsound → Account.")))
                    .setPositiveButton(text("Войти", "Sign in"), (shownDialog, which) -> {
                        signingIn = true;
                        YouTubeLoginActivity.start(current);
                    })
                    .setNegativeButton(text("Не сейчас", "Not now"), null)
                    .setOnDismissListener(shownDialog -> {
                        shown = false;
                        // Not kept: the dialog holds its screen.
                        dialog = null;
                        if (!signingIn) declined = true;
                        signingIn = false;
                    });
            dialog = built.show();
        } catch (Exception ex) {
            shown = false;
            Logger.printException(() -> "Could not offer the sign-in", ex);
        }
    }
}
