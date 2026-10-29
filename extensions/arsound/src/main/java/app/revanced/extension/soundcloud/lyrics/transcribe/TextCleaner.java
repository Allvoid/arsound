package app.revanced.extension.soundcloud.lyrics.transcribe;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cleans what Whisper writes for a window of a song and cuts it into lines of a song's length.
 * <p>
 * Whisper, shown music without words, invents what it saw in its training videos ("Subtitles by …", "Thanks for
 * watching") and falls into loops that repeat one phrase to the end of the window. {@link #inspect} removes the
 * invented phrases and the sound notes, cuts the loops ({@link Loops}), drops a window that is mostly loop or mostly in
 * letters of another language, and tells why a window was dropped; {@link #splitLines} breaks the text at punctuation
 * and keeps the lines between about 15 and 42 letters. Used by {@link SongListener}.
 */
final class TextCleaner {
    /** Why a window gave no words. */
    enum Dropped {
        /** Nothing is left: silence, music notes, or empty text. */
        NOTHING,
        /** Phrases Whisper makes up for music. */
        INVENTED,
        /** Mostly a loop of the decoder: the words in it cannot be trusted. */
        LOOP,
        /** Mostly in letters of another language than the song's (Japanese in a Russian song). */
        FOREIGN
    }

    /** What is left of a window's text. */
    static final class Cleaned {
        /** The words, empty if the window is dropped. */
        final String text;
        /** Why it is dropped; null if it is not. */
        final Dropped dropped;

        Cleaned(String text, Dropped dropped) {
            this.text = text;
            this.dropped = dropped;
        }

        /** How many words the text holds. */
        int words() {
            return text.isEmpty() ? 0 : text.split("\\s+").length;
        }
    }

    /** The scripts that pass when the language is not known: Cyrillic and Latin. */
    private static final Set<LanguagePolicy.Script> COMMON_SCRIPTS =
            EnumSet.of(LanguagePolicy.Script.CYRILLIC, LanguagePolicy.Script.LATIN);
    /** A window needs this share of its letters in the scripts of the song's language. */
    private static final double MIN_SCRIPT_SHARE = 0.5;

    /** Phrases Whisper makes up for music and silence, compared in lower case and without punctuation. */
    private static final String[] INVENTED = {
            "субтитры сделал", "субтитры создавал", "субтитры подготовил", "субтитры добавил", "редактор субтитров",
            "продолжение следует", "спасибо за просмотр", "подписывайтесь на канал", "ставьте лайки", "dimatorzok",
            "корректор а", "thank you for watching", "thanks for watching", "subtitles by", "subtitles made by",
            "please subscribe", "like and subscribe", "amara org", "transcribed by", "translated by", "www",
            "для субтитров", "до новых встреч", "всем пока"};
    /** A window that holds only one of these is invented too. */
    private static final String[] INVENTED_WHOLE = {"thank you", "thanks", "you", "bye", "okay", "спасибо", "пока", "угу"};

    private static final int MAX_LINE = 42;
    /**
     * Where a sentence ends: after its punctuation, or, where Whisper wrote none, before a word with a capital letter
     * that follows a word (it starts each sung line with one: "…want me to be Feeling so faithless Lost under…").
     * "I" and "I'm", "I'll", … are capitals in the middle of a line too, so they start nothing.
     */
    private static final Pattern SENTENCE_END = Pattern.compile(
            "(?<=[.!?…;])\\s+|(?<=[\\p{L}\\p{N}])\\s+(?=\\p{Lu})(?!I(?:['’]\\p{L}+)?(?:\\s|$))");
    private static final int GOOD_LINE = 30;
    private static final int MIN_LINE = 15;

    private TextCleaner() {
    }

    /** The text of a window without made-up phrases, notes and loops; empty if nothing real is left. */
    static String clean(String raw) {
        return inspect(raw, COMMON_SCRIPTS).text;
    }

    /**
     * Cleans the text of a window.
     *
     * @param scripts The scripts the song's words may be in ({@link LanguagePolicy#scriptsFor}).
     * @return The words, or why the window is dropped: a window of which most is loop, or most letters are of another
     * script, gives nothing (its other words are made up too).
     */
    static Cleaned inspect(String raw, Set<LanguagePolicy.Script> scripts) {
        if (raw == null) return new Cleaned("", Dropped.NOTHING);
        String text = raw.replaceAll("\\[[^\\]]*\\]|\\([^)]*\\)|\\*[^*]*\\*|[♪♫♬♩]+", " ").replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return new Cleaned("", Dropped.NOTHING);
        String plain = plain(text);
        for (String phrase : INVENTED) {
            if (plain.contains(phrase)) return new Cleaned("", Dropped.INVENTED);
        }
        for (String phrase : INVENTED_WHOLE) {
            if (plain.equals(phrase)) return new Cleaned("", Dropped.INVENTED);
        }
        Loops.Result loops = Loops.analyse(text);
        if (loops.mostlyLoop) return new Cleaned("", Dropped.LOOP);
        if (LanguagePolicy.letters(loops.text) == 0) return new Cleaned("", Dropped.NOTHING);
        if (LanguagePolicy.shareIn(loops.text, scripts) < MIN_SCRIPT_SHARE) return new Cleaned("", Dropped.FOREIGN);
        return new Cleaned(loops.text, null);
    }

    /** Lower case, letters and digits only, single spaces: for comparing without punctuation getting in the way. */
    static String plain(String text) {
        StringBuilder out = new StringBuilder();
        boolean space = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
                space = false;
            } else if (!space) {
                out.append(' ');
                space = true;
            }
        }
        return out.toString().trim();
    }

    /**
     * A phrase of up to twelve words repeated five times in a row (a single word, six) is a loop of the decoder, not a
     * song: it is kept twice. Chains with dashes and runs of characters without spaces are cut too ({@link Loops}).
     */
    static String collapseLoops(String text) {
        return Loops.cut(text);
    }

    /** Whisper repeats the last line of the window before when the new one holds only music: such a repeat is dropped. */
    static List<String> withoutRepeatOfPrevious(List<String> windowLines, List<String> before) {
        if (before.isEmpty()) return windowLines;
        String last = TextCleaner.plain(before.get(before.size() - 1));
        List<String> kept = new ArrayList<>(windowLines);
        if (kept.size() == 1 && TextCleaner.plain(kept.get(0)).equals(last)) kept.clear();
        return kept;
    }

    /**
     * Breaks cleaned text into lines: at sentence ends (or capitals, {@link #SENTENCE_END}) and commas, short pieces
     * joined, long ones wrapped.
     */
    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        String normal = text.replaceAll("\\s+", " ").trim();
        if (normal.isEmpty()) return lines;
        for (String sentence : SENTENCE_END.split(normal)) {
            List<String> pieces = new ArrayList<>();
            for (String clause : sentence.split("(?<=[,:—–])\\s+")) {
                String current = pieces.isEmpty() ? null : pieces.get(pieces.size() - 1);
                if (current != null && (current.length() < MIN_LINE || current.length() + 1 + clause.length() <= GOOD_LINE)) {
                    pieces.set(pieces.size() - 1, current + " " + clause);
                } else {
                    pieces.add(clause);
                }
            }
            for (String piece : pieces) wrap(piece, lines);
        }
        // A short remainder joins the line before it when both fit.
        List<String> joined = new ArrayList<>();
        for (String line : lines) {
            int last = joined.size() - 1;
            if (last >= 0 && line.length() < MIN_LINE && joined.get(last).length() + 1 + line.length() <= MAX_LINE) {
                joined.set(last, joined.get(last) + " " + line);
            } else {
                joined.add(line);
            }
        }
        List<String> result = new ArrayList<>();
        for (String line : joined) {
            String trimmed = tidy(line);
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    /** Wraps a piece at spaces into lines of about equal length, none longer than the limit. */
    private static void wrap(String piece, List<String> out) {
        if (piece.length() <= MAX_LINE) {
            out.add(piece);
            return;
        }
        int parts = (piece.length() + MAX_LINE - 1) / MAX_LINE;
        int target = piece.length() / parts + 1;
        StringBuilder line = new StringBuilder();
        for (String word : piece.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > Math.max(target, 1) && line.length() >= MIN_LINE / 2) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(line.toString());
    }

    /** A line of a song has no comma or full stop at its end. */
    private static String tidy(String line) {
        String text = line.trim();
        while (!text.isEmpty() && ",.;:—–-".indexOf(text.charAt(text.length() - 1)) >= 0) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text;
    }
}
