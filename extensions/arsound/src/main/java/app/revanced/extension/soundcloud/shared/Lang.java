package app.revanced.extension.soundcloud.shared;

import java.util.Locale;

/** Text in the language of the app: Russian when the app runs in Russian, English otherwise. */
public final class Lang {
    private Lang() {
    }

    /** Read each time: the app language can change while the app runs (the welcome switch). */
    public static boolean russian() {
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    public static String text(String russian, String english) {
        return russian() ? russian : english;
    }
}
