package app.revanced.extension.soundcloud.permissions;

import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The language of the greeting while it is on screen. It starts in English and the switch on it picks the other
 * one; the texts of the greeting follow the pick at once, before the app language changes at all.
 */
final class WelcomeLanguage {
    /** The language picked on the greeting on screen; null when it is not shown. */
    private static Boolean russianShown;

    private WelcomeLanguage() {
    }

    /** The greeting is shown: it starts in English. */
    static void begin() {
        russianShown = false;
    }

    static void pick(boolean russian) {
        russianShown = russian;
    }

    /** The greeting is gone or could not be shown: the texts follow the app language again. */
    static void clear() {
        russianShown = null;
    }

    /** Ends the greeting. @return True if Russian was the last pick. */
    static boolean endWithRussian() {
        boolean russian = Boolean.TRUE.equals(russianShown);
        russianShown = null;
        return russian;
    }

    /** The text in the picked language while the greeting is shown, in the app language otherwise. */
    static String text(String russian, String english) {
        boolean inRussian = russianShown != null ? russianShown : Lang.russian();
        return inRussian ? russian : english;
    }
}
