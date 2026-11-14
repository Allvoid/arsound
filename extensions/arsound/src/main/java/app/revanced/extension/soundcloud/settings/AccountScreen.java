package app.revanced.extension.soundcloud.settings;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.widget.LinearLayout;
import android.widget.Toast;

import app.revanced.extension.soundcloud.login.AccountSheet;
import app.revanced.extension.soundcloud.search.YouTubeAccount;
import app.revanced.extension.soundcloud.search.YouTubeLoginActivity;
import app.revanced.extension.soundcloud.shared.HelpBadge;

/**
 * Accounts: switching between SoundCloud accounts ({@code login.AccountSheet}), and the YouTube Music account the Arsound
 * search signs in with, for the tracks YouTube gives only to adults.
 */
final class AccountScreen extends SectionScreen {
    static final String ID = "account";

    AccountScreen(SettingsUi ui) {
        super(ui);
    }

    @Override
    String id() {
        return ID;
    }

    @Override
    String title() {
        return text("Аккаунты", "Accounts");
    }

    @Override
    String description() {
        return text("Смена аккаунтов SoundCloud, аккаунт YouTube Music для поиска",
                "Switching SoundCloud accounts, YouTube Music account for the search");
    }

    @Override
    String summary() {
        return YouTubeAccount.isSignedIn() ? text("Вход выполнен", "Signed in") : text("Не указан", "Not set");
    }

    @Override
    String iconKey() {
        return "account";
    }

    @Override
    void fill(LinearLayout list) {
        boolean signedIn = YouTubeAccount.isSignedIn();
        shownSignedIn = signedIn;
        list.addView(ui.createSubHeading(text("SoundCloud", "SoundCloud")));
        list.addView(ui.createActionRow(
                text("Аккаунты SoundCloud", "SoundCloud accounts"),
                text("Добавить ещё один аккаунт или переключиться на другой. То же открывается долгим нажатием на "
                                + "аватарку в верхней панели.",
                        "Add another account or switch to one. The same opens on a long press on the avatar in the top bar."),
                null,
                v -> AccountSheet.show(activity)
        ).icon("account").chevron());

        list.addView(ui.createSubHeading(text("YouTube Music", "YouTube Music")));
        list.addView(ui.createActionRow(
                text("Аккаунт YouTube Music", "YouTube Music account"),
                signedIn
                        ? text("Вход выполнен. Нажмите, чтобы выйти.", "Signed in. Tap to sign out.")
                        : text("Не указан. Нажмите, чтобы войти.", "Not set. Tap to sign in."),
                HelpBadge.create(activity, ui.createText("H4.Primary", "").getCurrentTextColor(),
                        text("Зачем аккаунт YouTube Music", "What the YouTube Music account is for"),
                        text("Поиск Arsound берёт треки из YouTube Music. Часть треков YouTube отдаёт только "
                                        + "после входа в аккаунт: у них возрастное ограничение 18+.",
                                "The Arsound search takes tracks from YouTube Music. YouTube gives some tracks only "
                                        + "to signed-in listeners: they are age-restricted (18+)."),
                        text("Вход нужен только для них и указывается по желанию. Пароль вводится на странице "
                                        + "Google, Arsound его не видит. Сессия хранится только на этом телефоне "
                                        + "и отправляется только в YouTube.",
                                "Signing in is only for them, and optional. The password is typed into Google's page; "
                                        + "Arsound does not see it. The session stays on this phone and goes only to YouTube."),
                        text("Аккаунт должен быть совершеннолетним по данным Google.",
                                "Google must know the account as adult.")),
                v -> {
                    if (YouTubeAccount.isSignedIn()) {
                        YouTubeAccount.signOut();
                        Toast.makeText(activity, text("Вы вышли из YouTube Music", "Signed out of YouTube Music"),
                                Toast.LENGTH_SHORT).show();
                        activity.recreate();
                    } else {
                        YouTubeLoginActivity.start(activity);
                    }
                }
        ).icon("account").chevron());
    }

    /** The state the rows were made for. */
    private boolean shownSignedIn;

    @Override
    void onRestart() {
        // The screen is made again only when the sign-in page changed the state: a return from another app made the
        // whole settings screen again each time.
        if (YouTubeAccount.isSignedIn() != shownSignedIn) activity.recreate();
    }
}
