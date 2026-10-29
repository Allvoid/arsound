package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Which languages the recognition trusts, and which letters belong to them. Whisper, shown a window of a song, may
 * "hear" Japanese in a Russian rap and then write Japanese words to the end of the song; so a language it names is only
 * believed when it is Russian, English or the language of the phone, and the letters of a window are checked against
 * the language of the song. No Android classes here: the rules are tested on the JVM.
 * <p>
 * Used by {@link SongListener} (what to fix), {@link TextCleaner} (which letters pass) and
 * {@link TranscriptionManager} (whether a title is in Russian letters).
 */
final class LanguagePolicy {
    /** The writing systems told apart: enough to see a Japanese window in a Russian song. */
    enum Script {
        CYRILLIC, LATIN, OTHER
    }

    private static final Set<String> CYRILLIC_LANGUAGES = words("ru uk be bg sr mk kk ky mn tg tt ba");
    private static final Set<String> OTHER_LANGUAGES = words("ja zh ko ar fa he yi hi bn ta te th lo my km el ka hy ur "
            + "ne mr gu pa kn ml si am ps sd");

    private LanguagePolicy() {
    }

    private static Set<String> words(String list) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(list.split(" "))));
    }

    /** Whether the language is one the recognition believes: Russian, English or the language of the phone. */
    static boolean isExpected(String code, String deviceLanguage) {
        if (code == null || code.isEmpty()) return false;
        return "ru".equals(code) || "en".equals(code) || code.equals(deviceLanguage);
    }

    /**
     * What to do with the language Whisper found in a window while the song's language is not fixed.
     *
     * @return "" if Whisper said nothing; the same code if it is an expected language; else Russian (in a Russian
     * app) or English, the language to fix instead.
     */
    static String pick(String heard, String deviceLanguage, boolean russianApp) {
        if (heard == null || heard.isEmpty()) return "";
        if (isExpected(heard, deviceLanguage)) return heard;
        return russianApp ? "ru" : "en";
    }

    /** The writing system the words of the language are in. */
    static Script scriptOfLanguage(String code) {
        if (CYRILLIC_LANGUAGES.contains(code)) return Script.CYRILLIC;
        if (OTHER_LANGUAGES.contains(code)) return Script.OTHER;
        return Script.LATIN;
    }

    /**
     * The scripts a window of the song may be in: that of the fixed language, or, while the language is not fixed,
     * those of Russian, English and the phone's language.
     */
    static Set<Script> scriptsFor(String language, String deviceLanguage) {
        Set<Script> scripts = EnumSet.noneOf(Script.class);
        if (language != null && !language.isEmpty()) {
            scripts.add(scriptOfLanguage(language));
        } else {
            scripts.add(Script.CYRILLIC);
            scripts.add(Script.LATIN);
            if (deviceLanguage != null && !deviceLanguage.isEmpty()) scripts.add(scriptOfLanguage(deviceLanguage));
        }
        return scripts;
    }

    /** The script of a letter; digits and marks are not asked about. */
    static Script scriptOf(char letter) {
        if (letter >= 0x0400 && letter <= 0x052F) return Script.CYRILLIC;
        if (letter < 0x0250 || (letter >= 0x1E00 && letter <= 0x1EFF)) return Script.LATIN;
        return Script.OTHER;
    }

    /** How many letters the text holds. */
    static int letters(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) count++;
        }
        return count;
    }

    /** The share (0 to 1) of the letters of the text that are in one of the scripts; 1 if there are no letters. */
    static double shareIn(String text, Set<Script> scripts) {
        int all = 0;
        int inside = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetter(c)) continue;
            all++;
            if (scripts.contains(scriptOf(c))) inside++;
        }
        return all == 0 ? 1.0 : inside / (double) all;
    }
}
