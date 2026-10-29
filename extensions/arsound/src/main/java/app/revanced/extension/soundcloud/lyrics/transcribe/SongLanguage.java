package app.revanced.extension.soundcloud.lyrics.transcribe;

import app.revanced.extension.soundcloud.shared.Lang;

/**
 * The language a song is sung in, as the user says before each recognition. It decides which network listens: the
 * Russian GigaAM hears nothing but Russian (an English song comes out as Russian letters that mean nothing), Whisper
 * hears many languages and is more accurate when told which one. Asking is cheaper than guessing: no model to tell the
 * languages apart is downloaded, and the answer is right.
 * <p>
 * Chosen in {@code lyrics.SongLanguageDialog}, carried by {@link TranscriptionManager#start} to the
 * {@link TranscriptionJob}.
 */
public enum SongLanguage {
    RUSSIAN("ru", AsrModel.GIGAAM, "Русский", "Russian"),
    ENGLISH("en", AsrModel.SMALL, "Английский", "English"),
    /** Whisper finds the language itself, and is believed whatever it finds. */
    OTHER("", AsrModel.SMALL, "Другой язык", "Another language");

    /** What Whisper is told; empty for "find it yourself". */
    final String code;
    private final AsrModel model;
    private final String russian;
    private final String english;

    SongLanguage(String code, AsrModel model, String russian, String english) {
        this.code = code;
        this.model = model;
        this.russian = russian;
        this.english = english;
    }

    public String label() {
        return Lang.text(russian, english);
    }

    /** The network that listens to a song in this language (a lighter one may stand in when memory is short). */
    public AsrModel model() {
        return model;
    }

    /** Whether a transcript heard in the language (its code) is one of a song in this language. */
    boolean matches(String heard) {
        if (heard == null || heard.isEmpty()) return false;
        if (this == OTHER) return !RUSSIAN.code.equals(heard) && !ENGLISH.code.equals(heard);
        return code.equals(heard);
    }

    /** The language a transcript was heard in, as one of the three; null when it does not say. */
    public static SongLanguage ofHeard(String heard) {
        if (heard == null || heard.isEmpty()) return null;
        if (RUSSIAN.code.equals(heard)) return RUSSIAN;
        if (ENGLISH.code.equals(heard)) return ENGLISH;
        return OTHER;
    }

    /** The answer kept under its name, or null for anything else. */
    static SongLanguage named(String name) {
        for (SongLanguage language : values()) {
            if (language.name().equals(name)) return language;
        }
        return null;
    }
}
