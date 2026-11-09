package app.revanced.extension.soundcloud.transfer;

import static app.revanced.extension.soundcloud.shared.Lang.text;

import android.content.Context;

import java.util.List;

/** Yandex Music: its links, its API ({@link YandexMusic}) and the optional token ({@link YandexAccount}). */
final class YandexSource extends TransferSource {
    static final YandexSource INSTANCE = new YandexSource();

    private YandexSource() {
    }

    @Override
    public String id() {
        return "yandex";
    }

    @Override
    public String name() {
        return text("Яндекс Музыка", "Yandex Music");
    }

    @Override
    public String fromName() {
        return text("из Яндекс Музыки", "from Yandex Music");
    }

    @Override
    public TransferLink find(String text) {
        return YandexLink.find(text);
    }

    @Override
    TransferLink fromKey(String key) {
        return YandexLink.fromKey(key);
    }

    /** With the account when signed in; a token Yandex no longer takes is dropped for a public link. */
    @Override
    SourceCollection load(TransferLink link) throws Exception {
        YandexLink yandex = (YandexLink) link;
        String token = YandexAccount.token();
        try {
            return YandexMusic.load(yandex, token);
        } catch (SignInExpiredException ex) {
            if (token == null) throw ex;
            YandexAccount.forgetToken();
            return YandexMusic.load(yandex, null);
        }
    }

    @Override
    String notAvailable(TransferLink link) {
        boolean signedIn = YandexAccount.isSignedIn();
        if (((YandexLink) link).kind == YandexLink.Kind.LIKES && !signedIn) {
            return text("Лайки этого профиля закрыты. Свои можно перенести, войдя в Яндекс на этом экране.",
                    "The likes of this profile are private. To transfer your own, sign in to Yandex on this screen.");
        }
        return signedIn
                ? text("Яндекс Музыка не отдала этот список: он закрыт или удалён", "Yandex Music did not give this list: it is private or removed")
                : text("Яндекс Музыка не отдала этот список: он закрыт или удалён. Если он ваш, войдите в Яндекс на этом экране.",
                "Yandex Music did not give this list: it is private or removed. If it is yours, sign in to Yandex on this screen.");
    }

    @Override
    public boolean isSignedIn() {
        return YandexAccount.isSignedIn();
    }

    @Override
    public String accountName() {
        return YandexAccount.name();
    }

    @Override
    public void signIn(Context context) {
        YandexLoginActivity.start(context);
    }

    @Override
    public void signOut() {
        YandexAccount.signOut();
    }

    @Override
    public String signInTitle() {
        return text("Войти в Яндекс", "Sign in to Yandex");
    }

    @Override
    public String signInNote() {
        return text("Для закрытых лайков и своих плейлистов: после входа они появятся здесь списком. Пароль "
                        + "вводится на странице Яндекса, Arsound его не видит. Доступ хранится только на "
                        + "этом телефоне и отправляется только в Яндекс Музыку.",
                "For private likes and your own playlists: after signing in they are listed here. The "
                        + "password is typed into Yandex's page; Arsound does not see it. The access stays "
                        + "on this phone and goes only to Yandex Music.");
    }

    @Override
    public String signOutTitle() {
        return text("Выйти из Яндекса", "Sign out of Yandex");
    }

    @Override
    public List<SourceList> library() throws Exception {
        return YandexAccount.library();
    }
}
