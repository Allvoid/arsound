package app.revanced.extension.soundcloud.shared;

import java.util.Locale;

/** Text in the language of the app: Russian when the app runs in Russian, English otherwise. */
public final class Lang {
    private Lang() {
    }

    /** Read each time: the app language can change while the app runs (the welcome switch). */
    public static boolean russian() {
        Boolean forced = FORCED.get();
        if (forced != null) return forced;
        return "ru".equals(Locale.getDefault().getLanguage());
    }

    /** The language the current thread reads texts in, set while the settings are read for the search in the other language. */
    private static final ThreadLocal<Boolean> FORCED = new ThreadLocal<>();

    /**
     * Runs the task with {@link #text} giving the Russian (or the English) string on this thread only, whatever the
     * app language is; the other threads and the app language are not touched.
     */
    public static void withLanguage(boolean russian, Runnable task) {
        Boolean before = FORCED.get();
        FORCED.set(russian);
        try {
            task.run();
        } finally {
            if (before == null) FORCED.remove();
            else FORCED.set(before);
        }
    }

    public static String text(String russian, String english) {
        return russian() ? russian : english;
    }
}
